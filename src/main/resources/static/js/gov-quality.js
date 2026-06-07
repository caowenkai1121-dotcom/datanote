/* 治理模块：数据质量（M5 阈值 + 调度 + 趋势评分） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var allRules = [];          // 全量规则
  var filterStatus = '';      // ''=全部 / '1'=启用 / '0'=停用
  var filterDim = '', filterType = '', filterBlock = '', filterScheduled = '';
  var ctxTableFilter = null;  // R21 深链: {db,table} 跨模块跳来时按库表过滤
  var tableEl = null;         // DN.table 实例（供筛选 reload）
  var statusSelEl = null;     // 状态下拉（供磁贴联动同步）
  var trendBody = null;       // 趋势卡片 body

  window.GOV_RENDERERS.quality = function (c) {
    // R21 深链: 读取上下文(读完即用, 本次渲染据此聚焦/预填/过滤)
    var ctx = window.__govCtx || {};
    ctxTableFilter = (ctx.table && ctx.table.db && ctx.table.table)
      ? { db: ctx.table.db, table: ctx.table.table } : null;
    // 顶部：质量分 gauge + 趋势 bars 两张卡片
    var grid = DN.h('div', { class: 'gov-grid' });
    var scoreCard = DN.card({ title: '整体质量分', icon: 'check' });
    scoreCard.el.classList.add('primary');
    var trendCard = DN.card({ title: '规则趋势 · 根因分析', icon: 'chart' });
    trendBody = trendCard.body;
    scoreCard.body.appendChild(DN.skeleton(2));
    trendBody.appendChild(DN.empty('点击下方规则查看其通过率趋势与失败根因', 'chart'));
    grid.appendChild(scoreCard.el);
    grid.appendChild(trendCard.el);
    c.appendChild(grid);

    // 24 小时执行概览卡（复用 /overview，原前端未用）
    var ovCard = DN.card({ title: '近 24 小时执行概览', icon: 'clock' });
    ovCard.body.appendChild(DN.skeleton(2));
    c.appendChild(ovCard.el);

    // 规则列表卡片
    var runAllBtn = DN.h('button', { class: 'btn', type: 'button', text: '一键全量复跑' });
    var gotoBtn = DN.h('a', { class: 'btn btn-primary', href: 'workspace.html#/quality', text: '前往工作台质量', onclick: function () { if (window.navigateTo) navigateTo('quality'); } });
    var rulesCard = DN.card({ title: '质量规则', icon: 'list', actions: [runAllBtn, gotoBtn] });
    var rulesBody = rulesCard.body;
    rulesBody.appendChild(DN.skeleton(4));
    c.appendChild(rulesCard.el);

    runAllBtn.onclick = function () {
      var enabledN = allRules.filter(function (r) { return r.status === 1; }).length;
      if (enabledN === 0) { DN.toast('暂无已启用规则可复跑', 'warn'); return; }
      if (!window.confirm('将执行全部 ' + enabledN + ' 条已启用规则的质量检查，可能耗时较久，确认继续？')) return;
      runAllBtn.disabled = true; runAllBtn.textContent = '复跑中…';
      DN.post('/api/quality/run-all').then(function (msg) {
        DN.toast(typeof msg === 'string' ? msg : '批量复跑完成', 'ok');
        runAllBtn.disabled = false; runAllBtn.textContent = '一键全量复跑';
        loadScore(scoreCard.body);            // 刷新质量分
        loadOverview(ovCard.body);            // 刷新 24h 概览
        loadRules(rulesBody);                 // 刷新规则与失败聚焦
      }).catch(function (e) {
        DN.toast('批量复跑失败: ' + (e && e.message ? e.message : '未知错误'), 'err');
        runAllBtn.disabled = false; runAllBtn.textContent = '一键全量复跑';
      });
    };

    loadScore(scoreCard.body);
    loadOverview(ovCard.body);
    // R21 深链: 携带预填上下文, 规则加载完后自动弹"新建质量规则"并预填
    loadRules(rulesBody, ctx.prefillRule);
  };

  function loadScore(box) {
    DN.get('/api/quality/score').then(function (d) {
      var score = (d && d.score != null) ? Number(d.score) : 100;
      var n = (d && d.sampleRuns != null) ? d.sampleRuns : 0;
      box.innerHTML = '';
      var wrap = DN.h('div', { style: 'display:flex;align-items:center;gap:18px;flex-wrap:wrap' });
      wrap.appendChild(DN.gauge(score, { label: '满分 100', decimals: 1 }));
      wrap.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0',
        text: '近 7 天 ' + n + ' 次检查均值' }));
      box.appendChild(wrap);
    }).catch(function () {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('质量分加载失败', function () { loadScore(box); }));
    });
  }

  // 24 小时执行概览(创新功能): 复用 /overview, 环图展示成功/失败/异常占比 + 健康解读
  function loadOverview(box) {
    DN.get('/api/quality/overview').then(function (d) {
      d = d || {};
      box.innerHTML = '';
      var total = Number(d.totalRuns) || 0;
      var ok = Number(d.successRuns) || 0;
      var failed = Number(d.failedRuns) || 0;
      var err = Number(d.errorRuns) || 0;
      if (total === 0) {
        box.appendChild(DN.empty('近 24 小时暂无质量检查执行记录，可点上方“一键全量复跑”立即触发', 'clock'));
        return;
      }
      var wrap = DN.h('div', { style: 'display:flex;align-items:center;gap:20px;flex-wrap:wrap' });
      var segs = [
        { label: '成功', value: ok, color: '#52c41a' },
        { label: '失败', value: failed, color: '#faad14' },
        { label: '异常', value: err, color: '#ff4d4f' }
      ].filter(function (s) { return s.value > 0; });
      wrap.appendChild(DN.donut(segs, { size: 104, stroke: 14, centerLabel: total, centerSub: '次', legend: true }));
      // 成功率健康解读
      var okRate = total ? Math.round(ok / total * 1000) / 10 : 0;
      var band = okRate >= 95 ? ['健康', 'ok'] : okRate >= 80 ? ['关注', 'warn'] : ['告警', 'err'];
      var advice = okRate >= 95 ? '执行成功率良好，质量管控稳定。'
        : okRate >= 80 ? '存在少量失败/异常，建议关注下方“失败规则聚焦”。'
        : '失败/异常占比偏高，建议尽快排查异常规则与数据源连通性。';
      var info = DN.h('div', { style: 'min-width:180px' }, [
        DN.h('div', { style: 'display:flex;align-items:center;gap:8px;margin-bottom:6px' }, [
          DN.h('span', { style: 'font-size:22px;font-weight:600', text: okRate + '%' }),
          DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '成功率' }),
          DN.pill(band[0], band[1])
        ]),
        DN.h('div', { class: 'gov-desc', style: 'margin:0', text: advice })
      ]);
      wrap.appendChild(info);
      box.appendChild(wrap);
    }).catch(function () {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('执行概览加载失败', function () { loadOverview(box); }));
    });
  }

  // 规则覆盖矩阵(大功能): 维度 × 规则类型 计数热力, 点格下钻筛选
  function buildCoverageMatrix() {
    if (!allRules.length) return null;
    var dims = uniq(allRules.map(function (r) { return r.dimension || '未分类'; }));
    var types = uniq(allRules.map(function (r) { return r.ruleType || '未分类'; }));
    if (dims.length < 1 || types.length < 1) return null;
    var grid = {}, max = 0;
    allRules.forEach(function (r) { var k = (r.dimension || '未分类') + '||' + (r.ruleType || '未分类'); grid[k] = (grid[k] || 0) + 1; if (grid[k] > max) max = grid[k]; });
    var card = DN.card({ title: '规则覆盖矩阵（维度 × 类型）', icon: 'grid' });
    var colorOf = function (n) { if (!n) return 'var(--bg-hover,#f0f1f3)'; var rr = n / (max || 1); return rr > 0.66 ? '#1890ff' : rr > 0.33 ? '#69a9ff' : '#bcd7ff'; };
    var tbl = DN.h('table', { class: 'gov-tbl', style: 'width:auto' });
    var thead = '<thead><tr><th></th>' + types.map(function (t) { return '<th style="font-size:11px;text-align:center">' + DN.esc(t) + '</th>'; }).join('') + '</tr></thead>';
    var tb = '<tbody>';
    dims.forEach(function (d) {
      tb += '<tr><td style="font-size:12px;font-weight:500;white-space:nowrap">' + DN.esc(d) + '</td>';
      types.forEach(function (t) {
        var n = grid[d + '||' + t] || 0;
        tb += '<td style="text-align:center;padding:2px"><div data-dim="' + DN.esc(d) + '" data-type="' + DN.esc(t) + '" title="' + DN.esc(d) + ' · ' + DN.esc(t) + ' · ' + n + ' 条规则' + (n ? ' (点击筛选)' : '') + '" style="width:42px;height:28px;line-height:28px;border-radius:4px;margin:0 auto;background:' + colorOf(n) + ';color:' + (n ? '#fff' : 'var(--text-muted)') + ';font-size:12px;cursor:' + (n ? 'pointer' : 'default') + '">' + (n || '') + '</div></td>';
      });
      tb += '</tr>';
    });
    tbl.innerHTML = thead + tb + '</tbody>';
    // 点格联动筛选
    tbl.addEventListener('click', function (e) {
      var cell = e.target.closest('[data-dim]'); if (!cell) return;
      filterDim = cell.getAttribute('data-dim') === '未分类' ? '' : cell.getAttribute('data-dim');
      filterType = cell.getAttribute('data-type') === '未分类' ? '' : cell.getAttribute('data-type');
      if (tableEl) tableEl.reload(filtered());
      DN.toast('已筛选: ' + cell.getAttribute('data-dim') + ' · ' + cell.getAttribute('data-type'), 'info');
    });
    var wrap = DN.h('div', { style: 'overflow-x:auto' }); wrap.appendChild(tbl);
    card.body.appendChild(wrap);
    card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:8px 0 0', text: '色块深浅=该维度该类型规则数, 点击格子下钻筛选下方规则表' }));
    return card.el;
  }

  // 失败规则聚焦(创新功能): 扫描各规则最近一次执行, 聚合"未通过"清单(passRate<100 或非success), 提供立即复跑
  function buildFailFocus() {
    var card = DN.card({ title: '失败规则聚焦', icon: 'alert' });
    var body = card.body;
    var btn = DN.h('button', { class: 'btn btn-primary', type: 'button', text: '扫描最近执行' });
    var resultBox = DN.h('div', { style: 'margin-top:10px' });
    body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0',
      text: '逐条读取规则最近一次执行结果, 高亮通过率不足或失败/异常的规则, 支持一键复跑' }));
    body.appendChild(btn);
    body.appendChild(resultBox);

    btn.onclick = function () {
      // 仅扫描已启用规则, 上限 30 条以控制请求量
      var targets = allRules.filter(function (r) { return r.status === 1 && r.id != null; }).slice(0, 30);
      if (!targets.length) {
        resultBox.innerHTML = '';
        var emp = DN.empty('暂无已启用的规则可扫描，请在下方规则表启用规则或前往工作台创建', 'check');
        emp.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', style: 'margin-top:10px', text: '前往工作台质量', onclick: function () { if (window.navigateTo) navigateTo('quality'); } }));
        resultBox.appendChild(emp);
        return;
      }
      btn.disabled = true; btn.textContent = '扫描中…';
      resultBox.innerHTML = ''; resultBox.appendChild(DN.skeleton(3));
      // 顺序拉取每条规则最近执行(复用已有 /rule/{id}/runs 端点), 取首条(时间倒序)
      var fails = [];
      var chain = Promise.resolve();
      targets.forEach(function (r) {
        chain = chain.then(function () {
          return DN.get('/api/quality/rule/' + encodeURIComponent(r.id) + '/runs').then(function (runs) {
            var last = (runs && runs.length) ? runs[0] : null;
            if (!last) return;
            var rate = last.passRate == null ? null : Number(last.passRate);
            var notPass = (last.runStatus && last.runStatus !== 'success') || (rate != null && rate < 100);
            if (notPass) fails.push({ rule: r, run: last, rate: rate });
          }).catch(function () { /* 单条失败忽略, 不阻断整体扫描 */ });
        });
      });
      chain.then(function () {
        btn.disabled = false; btn.textContent = '重新扫描';
        renderFailFocus(resultBox, fails);
      });
    };
    return card.el;
  }

  function renderFailFocus(resultBox, fails) {
    resultBox.innerHTML = '';
    if (!fails.length) {
      var emp = DN.empty('所有已启用规则最近一次执行均通过，数据质量良好', 'check');
      emp.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', style: 'margin-top:10px',
        text: '查看全部规则趋势', onclick: function () { var first = allRules.filter(function (r) { return r.status === 1 && r.id != null; })[0]; if (first) loadRuleDetail(first.id, first.ruleName); } }));
      resultBox.appendChild(emp);
      return;
    }
    // 强阻断失败优先(阻断下游=最高风险)置顶, 其次按通过率升序(最差在前), null(异常无率)视为最差
    fails.sort(function (a, b) {
      var ab = a.rule.blockDownstream === 1 ? 1 : 0, bb = b.rule.blockDownstream === 1 ? 1 : 0;
      if (ab !== bb) return bb - ab;
      return (a.rate == null ? -1 : a.rate) - (b.rate == null ? -1 : b.rate);
    });
    var blockedN = fails.filter(function (f) { return f.rule.blockDownstream === 1; }).length;
    var summary = blockedN
      ? '共 ' + fails.length + ' 条规则未通过，其中 ' + blockedN + ' 条为强阻断（已置顶，会阻断下游流程，请优先处理），点规则名查看趋势根因'
      : '共 ' + fails.length + ' 条规则最近一次未通过, 按通过率从低到高展示, 点规则名查看趋势根因';
    resultBox.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 6px' + (blockedN ? ';color:#ff4d4f;font-weight:500' : ''), text: summary }));
    // 扁平化为行数据(供 DN.table 渲染 + CSV 导出取 key)
    var rows = fails.map(function (f) {
      var run = f.run, st = run.runStatus || '';
      return {
        _f: f,
        ruleName: f.rule.ruleName || ('规则 #' + f.rule.id),
        ruleType: f.rule.ruleType || '-',
        dimension: f.rule.dimension || '-',
        status: st === 'error' ? '异常' : '未达标',
        rateText: f.rate == null ? '-' : (Math.round(f.rate * 100) / 100) + '%',
        failCount: run.failCount == null ? '-' : run.failCount,
        startedAt: String(run.startedAt || '').replace('T', ' ').slice(0, 19)
      };
    });
    var tbl = DN.table({
      columns: [
        { key: 'status', label: '判定', render: function (r) {
            return DN.pill(r.status, r.status === '异常' ? 'err' : 'warn');
          } },
        { key: 'ruleName', label: '规则', render: function (r) {
            var link = DN.h('a', { href: 'javascript:void(0)', text: r.ruleName,
              style: 'color:var(--primary,#1890ff)', title: '点击查看该规则趋势与失败根因',
              onclick: function () { loadRuleDetail(r._f.rule.id, r._f.rule.ruleName); } });
            if (r._f.rule.blockDownstream !== 1) return link;
            var wrap = DN.h('span', { style: 'display:inline-flex;align-items:center;gap:6px' });
            wrap.appendChild(link);
            wrap.appendChild(DN.pill('阻断下游', 'err'));
            return wrap;
          } },
        { key: 'dimension', label: '维度', render: function (r) { return r.dimension; } },
        { key: 'rateText', label: '通过率', align: 'right', render: function (r) { return r.rateText; } },
        { key: 'failCount', label: '失败数', align: 'right', render: function (r) { return String(r.failCount); } },
        { key: 'startedAt', label: '执行时间', render: function (r) { return r.startedAt ? DN.timeAgo(r.startedAt) : '-'; } },
        { key: '_op', label: '操作', render: function (r) {
            var f = r._f;
            var rerunBtn = DN.h('button', { class: 'btn', type: 'button', style: 'font-size:12px;padding:2px 10px', text: '立即复跑' });
            rerunBtn.onclick = function () {
              rerunBtn.disabled = true; rerunBtn.textContent = '复跑中…';
              DN.post('/api/quality/rule/' + encodeURIComponent(f.rule.id) + '/run').then(function (nr) {
                var nrate = (nr && nr.passRate != null) ? Number(nr.passRate) : null;
                var ok = nr && nr.runStatus === 'success';
                DN.toast('复跑完成: ' + (f.rule.ruleName || ('#' + f.rule.id)) + ' 通过率 ' + (nrate == null ? '-' : nrate + '%') + (ok ? '' : '，已自动生成治理工单'), ok ? 'ok' : 'warn');
                rerunBtn.disabled = false; rerunBtn.textContent = ok ? '已通过' : '重新复跑';
              }).catch(function (e) {
                DN.toast('复跑失败: ' + (e && e.message ? e.message : '未知错误'), 'err');
                rerunBtn.disabled = false; rerunBtn.textContent = '立即复跑';
              });
            };
            var db = f.rule.databaseName || f.rule.dbName, tbl = f.rule.tableName;
            if (!db || !tbl) return rerunBtn;
            // 影响面(创新功能): 按需查该失败规则目标表的下游受影响范围, 助按影响排序修复优先级(质量↔血缘联动)
            var wrap = DN.h('span', { style: 'display:inline-flex;gap:8px;align-items:center' });
            wrap.appendChild(rerunBtn);
            var impBtn = DN.h('a', { href: 'javascript:void(0)', style: 'font-size:12px;color:var(--primary,#1890ff)', title: '查看该表下游受影响范围(评估修复优先级)', text: '影响面' });
            impBtn.onclick = function () {
              impBtn.textContent = '查询中…';
              DN.get('/api/lineage/impact?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(tbl)).then(function (list) {
                var n = (list || []).length;
                impBtn.textContent = n > 0 ? ('下游 ' + n + ' 表') : '无下游';
                impBtn.style.color = n > 5 ? '#cf1322' : n > 0 ? '#d48806' : 'var(--text-muted)';
                impBtn.title = n > 0 ? ('该表异常将波及 ' + n + ' 张下游表，建议优先修复') : '该表无下游血缘';
                impBtn.onclick = null; impBtn.style.cursor = 'default';
              }).catch(function () { impBtn.textContent = '影响面'; DN.toast('影响面查询失败(可先在血缘模块解析脚本血缘)', 'warn'); });
            };
            wrap.appendChild(impBtn);
            return wrap;
          } }
      ],
      rows: rows,
      pageSize: 8,
      searchKeys: ['ruleName', 'ruleType', 'dimension'],
      searchPlaceholder: '搜索规则名/类型/维度',
      exportName: '失败规则聚焦',
      empty: '无未通过规则',
      emptyIcon: 'check'
    });
    resultBox.appendChild(tbl);
  }

  function loadRules(box, prefillRule) {
    DN.get('/api/quality/rules').then(function (rules) {
      allRules = rules || [];
      box.innerHTML = '';
      // R21 深链: 按库表过滤时, 顶部提示 + 可清除
      if (ctxTableFilter) {
        var hint = DN.h('div', { class: 'gov-desc', style: 'margin:0 0 8px;color:var(--primary,#1890ff)' }, [
          DN.h('span', { text: '按 ' + ctxTableFilter.db + '.' + ctxTableFilter.table + ' 过滤' }),
          DN.h('a', { href: 'javascript:void(0)', style: 'margin-left:10px', text: '清除过滤',
            onclick: function () { ctxTableFilter = null; hint.remove(); if (tableEl) tableEl.reload(filtered()); DN.toast('已清除库表过滤', 'info'); } })
        ]);
        box.appendChild(hint);
      }
      // 统计卡: 总数/启用/强阻断/已调度
      var enabled = allRules.filter(function (r) { return r.status === 1; }).length;
      var blocked = allRules.filter(function (r) { return r.blockDownstream === 1; }).length;
      var scheduled = allRules.filter(function (r) { return r.scheduleCron; }).length;
      box.appendChild(DN.statRow([
        { icon: 'list', label: '规则总数', value: allRules.length, title: '点击清空筛选', onClick: function () { filterStatus = ''; filterDim = ''; filterType = ''; filterBlock = ''; filterScheduled = ''; loadRules(box); DN.toast('已清空筛选', 'info'); } },
        { icon: 'check', label: '已启用', value: enabled, tone: 'ok', title: '点击仅看已启用', onClick: function () { filterStatus = filterStatus === '1' ? '' : '1'; if (statusSelEl) statusSelEl.value = filterStatus; if (tableEl) tableEl.reload(filtered()); DN.toast(filterStatus === '1' ? '仅看已启用' : '显示全部', 'info'); } },
        { icon: 'shield', label: '强阻断', value: blocked, tone: blocked ? 'warn' : 'ok', title: '点击仅看强阻断', onClick: function () { filterBlock = filterBlock ? '' : '1'; if (tableEl) tableEl.reload(filtered()); DN.toast(filterBlock ? '仅看强阻断' : '显示全部', 'info'); } },
        { icon: 'clock', label: '已配调度', value: scheduled, title: '点击仅看已配调度', onClick: function () { filterScheduled = filterScheduled ? '' : '1'; if (tableEl) tableEl.reload(filtered()); DN.toast(filterScheduled ? '仅看已配调度' : '显示全部', 'info'); } }
      ]));
      var cov = buildCoverageMatrix(); if (cov) box.appendChild(cov);
      box.appendChild(buildFailFocus());
      box.appendChild(buildToolbarAndTable());
      // R21 深链: 携带 prefillRule 上下文时, 自动弹"新建质量规则"并预填库/表/列/维度/阈值
      if (prefillRule) openPrefillRuleDrawer(prefillRule, box);
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('加载失败: ' + e.message, function () { loadRules(box); }));
    });
  }

  function filtered() {
    return allRules.filter(function (r) {
      if (ctxTableFilter) { // R21 深链: 按跨模块跳来的库表过滤
        if ((r.databaseName || r.dbName || '') !== ctxTableFilter.db) return false;
        if ((r.tableName || '') !== ctxTableFilter.table) return false;
      }
      if (filterStatus !== '' && String(r.status == null ? 1 : r.status) !== filterStatus) return false;
      if (filterDim && (r.dimension || '') !== filterDim) return false;
      if (filterType && (r.ruleType || '') !== filterType) return false;
      if (filterBlock && r.blockDownstream !== 1) return false;
      if (filterScheduled && !r.scheduleCron) return false;
      return true;
    });
  }
  function uniq(arr) { var s = {}, o = []; arr.forEach(function (x) { if (x && !s[x]) { s[x] = 1; o.push(x); } }); return o; }

  // R21 深链: 接收 ctx.prefillRule({db,table,column,dimension,threshold}) 自动弹"新建质量规则"并预填
  // 后端 /api/quality/rule/save 接收 DnQualityRule(库/表/列/维度/阈值/类型/数据源/严重级), 与工作台同源
  function openPrefillRuleDrawer(pf, rulesBox) {
    pf = pf || {};
    function formRow(label, input) {
      var row = DN.h('div', { class: 'ds-form-row', style: 'margin-bottom:10px' });
      row.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);margin-bottom:4px', text: label }));
      row.appendChild(input);
      return row;
    }
    var nameIn = DN.h('input', { class: 'iw-form-input', placeholder: '如：订单表主键非空检查',
      value: pf.db && pf.table ? ((pf.column ? pf.column + ' ' : '') + (pf.dimension || '') + '检查').trim() : '' });
    var typeSel = DN.h('select', { class: 'iw-form-select' });
    [['null_check', '空值检查'], ['unique_check', '唯一性检查'], ['value_range', '值域检查'], ['regex_check', '正则检查'], ['custom_sql', '自定义SQL']]
      .forEach(function (o) { typeSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
    var dsSel = DN.h('select', { class: 'iw-form-select' });
    dsSel.appendChild(DN.h('option', { value: '0', text: 'Doris 数仓' }));
    var dbIn = DN.h('input', { class: 'iw-form-input', value: pf.db || '', placeholder: '数据库名' });
    var tblIn = DN.h('input', { class: 'iw-form-input', value: pf.table || '', placeholder: '表名' });
    var colIn = DN.h('input', { class: 'iw-form-input', value: pf.column || '', placeholder: '字段名(可空)' });
    var dimIn = DN.h('input', { class: 'iw-form-input', value: pf.dimension || '', placeholder: '如：完整性/唯一性/准确性' });
    var thrIn = DN.h('input', { class: 'iw-form-input', type: 'number', value: pf.threshold != null ? pf.threshold : 100, placeholder: '通过率阈值 0-100' });
    var sevSel = DN.h('select', { class: 'iw-form-select' });
    [['info', '信息'], ['warning', '警告'], ['error', '错误']].forEach(function (o) { sevSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
    sevSel.value = 'warning';

    var body = DN.h('div');
    body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 10px',
      text: '已自动预填来自来源模块的库/表/列/维度/阈值, 选择数据源后即可创建' }));
    body.appendChild(formRow('规则名称', nameIn));
    body.appendChild(formRow('规则类型', typeSel));
    body.appendChild(formRow('数据源', dsSel));
    body.appendChild(formRow('数据库', dbIn));
    body.appendChild(formRow('表名', tblIn));
    body.appendChild(formRow('字段', colIn));
    body.appendChild(formRow('质量维度', dimIn));
    body.appendChild(formRow('通过率阈值(%)', thrIn));
    body.appendChild(formRow('严重级别', sevSel));
    var okBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '创建规则', style: 'margin-top:10px' });
    body.appendChild(okBtn);

    var dr = DN.drawer('新建质量规则', body);
    // 拉取数据源列表填充下拉(失败则仅保留 Doris 数仓选项, 不阻断)
    DN.get('/api/datasource/list').then(function (list) {
      (list || []).forEach(function (d) { dsSel.appendChild(DN.h('option', { value: String(d.id), text: (d.name || ('数据源#' + d.id)) + (d.type ? ' (' + d.type + ')' : '') })); });
    }).catch(function () {});

    okBtn.onclick = function () {
      var name = (nameIn.value || '').trim();
      if (!name) { DN.toast('请填写规则名称', 'warn'); return; }
      if (!(dbIn.value || '').trim() || !(tblIn.value || '').trim()) { DN.toast('请填写数据库与表名', 'warn'); return; }
      var thrVal = thrIn.value === '' ? null : Number(thrIn.value);
      var payload = {
        ruleName: name,
        ruleType: typeSel.value,
        datasourceId: dsSel.value === '0' ? 0 : parseInt(dsSel.value, 10),
        databaseName: (dbIn.value || '').trim(),
        tableName: (tblIn.value || '').trim(),
        columnName: (colIn.value || '').trim() || null,
        dimension: (dimIn.value || '').trim() || null,
        passThreshold: thrVal,
        severity: sevSel.value
      };
      okBtn.style.pointerEvents = 'none'; okBtn.textContent = '创建中…';
      DN.post('/api/quality/rule/save', payload).then(function () {
        DN.toast('质量规则已创建', 'ok');
        if (dr && dr.close) dr.close();
        loadRules(rulesBox);   // 刷新规则列表(新规则即时可见)
      }).catch(function (e) {
        DN.toast('创建失败: ' + (e && e.message ? e.message : '未知错误'), 'err');
        okBtn.style.pointerEvents = ''; okBtn.textContent = '创建规则';
      });
    };
  }

  function buildToolbarAndTable() {
    // 状态筛选下拉（放进 DN.table 工具条）
    var statusSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:120px' });
    [['', '全部状态'], ['1', '启用'], ['0', '停用']].forEach(function (o) {
      statusSel.appendChild(DN.h('option', { value: o[0], text: o[1] }));
    });
    statusSel.value = filterStatus;
    statusSelEl = statusSel;
    statusSel.onchange = function () { filterStatus = statusSel.value; if (tableEl) tableEl.reload(filtered()); };
    var dimSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:120px' });
    dimSel.appendChild(DN.h('option', { value: '', text: '全部维度' }));
    uniq(allRules.map(function (r) { return r.dimension; })).forEach(function (d) { dimSel.appendChild(DN.h('option', { value: d, text: d })); });
    dimSel.value = filterDim;
    dimSel.onchange = function () { filterDim = dimSel.value; if (tableEl) tableEl.reload(filtered()); };
    var typeSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:120px' });
    typeSel.appendChild(DN.h('option', { value: '', text: '全部类型' }));
    uniq(allRules.map(function (r) { return r.ruleType; })).forEach(function (t) { typeSel.appendChild(DN.h('option', { value: t, text: t })); });
    typeSel.value = filterType;
    typeSel.onchange = function () { filterType = typeSel.value; if (tableEl) tableEl.reload(filtered()); };

    tableEl = DN.table({
      columns: [
        { key: 'ruleName', label: '规则', render: function (r) {
            return DN.h('a', { href: 'javascript:void(0)', text: r.ruleName || '-',
              style: 'color:var(--primary,#1890ff)', onclick: function () { loadRuleDetail(r.id, r.ruleName); } });
          } },
        { key: 'ruleType', label: '类型', render: function (r) { return r.ruleType || '-'; } },
        { key: 'dimension', label: '维度', render: function (r) { return r.dimension || '-'; } },
        { key: 'target', label: '目标库表', render: function (r) {
            var t = [(r.databaseName || r.dbName), r.tableName].filter(Boolean).join('.');
            if (r.columnName) t += '.' + r.columnName;
            return t || '-';
          } },
        { key: 'severity', label: '严重级', render: function (r) {
            var on = (r.blockDownstream === 1);
            return DN.pill(on ? '强阻断' : '弱提醒', on ? 'err' : 'info');
          } },
        { key: 'passThreshold', label: '阈值', align: 'right', render: function (r) {
            return (r.passThreshold == null ? 100 : r.passThreshold) + '%';
          } },
        { key: 'scheduleCron', label: '调度', render: function (r) { return r.scheduleCron || '-'; } },
        { key: 'status', label: '状态', render: function (r) {
            return r.status === 1 ? DN.pill('启用', 'ok') : DN.pill('停用', 'muted');
          } },
        { key: '_op', label: '操作', render: function (r) {
            // R21 深链下钻: 跳健康/工单, 按该规则 id 过滤其触发的治理工单(质量↔工单联动)
            return DN.h('a', { href: 'javascript:void(0)', text: '查看相关工单',
              style: 'color:var(--primary,#1890ff)', title: '查看该规则触发生成的治理工单',
              onclick: function () { if (window.navigateTo) navigateTo('governance', { gov: 'health', issueFilter: { ruleId: r.id } }); } });
          } }
      ],
      rows: filtered(),
      pageSize: 20,
      searchKeys: ['ruleName', 'ruleType', 'dimension'],
      searchPlaceholder: '搜索规则名/类型/维度',
      toolbar: [statusSel, dimSel, typeSel],
      exportName: '质量规则',
      empty: '暂无质量规则，请前往工作台创建',
      emptyIcon: 'check'
    });

    return tableEl;
  }

  function loadRuleDetail(ruleId, ruleName) {
    if (!trendBody) return;
    trendBody.innerHTML = '';
    trendBody.appendChild(DN.skeleton(3));
    Promise.all([
      DN.get('/api/quality/trend?ruleId=' + encodeURIComponent(ruleId)),
      DN.get('/api/quality/failure-analysis?ruleId=' + encodeURIComponent(ruleId)).catch(function () { return {}; })
    ]).then(function (r) {
      var points = r[0] || [], fa = r[1] || {};
      trendBody.innerHTML = '';
      trendBody.appendChild(DN.h('div', { style: 'font-weight:600;font-size:13px;margin-bottom:8px', text: (ruleName || ('规则 #' + ruleId)) }));
      if (!points.length) { trendBody.appendChild(DN.empty('该规则暂无执行记录', 'clock')); return; }
      // 通过率折线 + 有效性
      var rates = points.map(function (p) { return p.passRate == null ? 0 : Number(p.passRate); });
      var avg = rates.reduce(function (a, b) { return a + b; }, 0) / rates.length;
      var eff = avg >= 90 ? ['有效性高', 'ok'] : avg >= 70 ? ['有效性中', 'warn'] : ['有效性低', 'err'];
      var head = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;margin-bottom:6px' }, [
        DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '近 ' + points.length + ' 次通过率均值 ' + (Math.round(avg * 10) / 10) + '%' }),
        DN.pill(eff[0], eff[1])
      ]);
      trendBody.appendChild(head);
      trendBody.appendChild(DN.line(rates, { height: 76, max: 100, min: 0, color: avg >= 80 ? '#52c41a' : avg >= 60 ? '#faad14' : '#ff4d4f' }));
      // 状态分布环 + 失败根因样本
      var sc = (fa.statusCounts || []);
      if (sc.length) {
        trendBody.appendChild(DN.sectionTitle('执行状态分布（近100次）'));
        var colorOf = function (s) { return s === 'PASS' ? '#52c41a' : s === 'FAIL' ? '#faad14' : s === 'ERROR' ? '#ff4d4f' : '#8c8c8c'; };
        var segs = sc.map(function (x) { return { label: x.status, value: Number(x.cnt) || 0, color: colorOf(x.status) }; });
        trendBody.appendChild(DN.donut(segs, { size: 96, stroke: 13, centerLabel: fa.totalRuns || '', centerSub: '次', legend: true }));
      }
      var fails = (fa.recentFailures || []);
      if (fails.length) {
        trendBody.appendChild(DN.sectionTitle('最近失败/错误样本'));
        var ul = DN.h('div', { style: 'font-size:12px;max-height:160px;overflow:auto' });
        fails.slice(0, 8).forEach(function (f) {
          var ts = String(f.startedAt || '').replace('T', ' ').slice(0, 19);
          var reason = f.errorMsg || (f.errorSample ? ('样本: ' + String(f.errorSample).slice(0, 80)) : ('通过率 ' + (f.passRate == null ? '-' : f.passRate + '%')));
          ul.appendChild(DN.h('div', { style: 'padding:5px 0;border-bottom:1px solid var(--divider,#f0f1f3)' }, [
            DN.pill(f.runStatus, f.runStatus === 'ERROR' ? 'err' : 'warn'),
            DN.h('span', { style: 'color:var(--text-muted);margin:0 6px', text: DN.fmtAgo(f.startedAt), title: ts }),
            DN.h('span', { text: reason })
          ]));
        });
        trendBody.appendChild(ul);
      }
    }).catch(function () {
      trendBody.innerHTML = '';
      trendBody.appendChild(DN.errorBox('趋势加载失败', function () { loadRuleDetail(ruleId, ruleName); }));
    });
  }
})();
