/* 治理模块：数据质量（M5 阈值 + 调度 + 趋势评分） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var allRules = [];          // 全量规则
  var filterStatus = '';      // ''=全部 / '1'=启用 / '0'=停用
  var filterDim = '', filterType = '', filterBlock = '', filterScheduled = '';
  var tableEl = null;         // DN.table 实例（供筛选 reload）
  var statusSelEl = null;     // 状态下拉（供磁贴联动同步）
  var trendBody = null;       // 趋势卡片 body

  window.GOV_RENDERERS.quality = function (c) {
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

    // 规则列表卡片
    var actions = DN.h('a', { class: 'btn btn-primary', href: 'workspace.html#/quality', text: '前往工作台质量' });
    var rulesCard = DN.card({ title: '质量规则', icon: 'list', actions: actions });
    var rulesBody = rulesCard.body;
    rulesBody.appendChild(DN.skeleton(4));
    c.appendChild(rulesCard.el);

    loadScore(scoreCard.body);
    loadRules(rulesBody);
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
      box.appendChild(DN.empty('质量分加载失败', 'alert'));
    });
  }

  function loadRules(box) {
    DN.get('/api/quality/rules').then(function (rules) {
      allRules = rules || [];
      box.innerHTML = '';
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
      box.appendChild(buildToolbarAndTable());
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.empty('加载失败: ' + e.message, 'alert'));
    });
  }

  function filtered() {
    return allRules.filter(function (r) {
      if (filterStatus !== '' && String(r.status == null ? 1 : r.status) !== filterStatus) return false;
      if (filterDim && (r.dimension || '') !== filterDim) return false;
      if (filterType && (r.ruleType || '') !== filterType) return false;
      if (filterBlock && r.blockDownstream !== 1) return false;
      if (filterScheduled && !r.scheduleCron) return false;
      return true;
    });
  }
  function uniq(arr) { var s = {}, o = []; arr.forEach(function (x) { if (x && !s[x]) { s[x] = 1; o.push(x); } }); return o; }

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
            var t = [r.dbName, r.tableName].filter(Boolean).join('.');
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
        var dwrap = DN.h('div', { style: 'display:flex;align-items:center;gap:18px;flex-wrap:wrap' });
        dwrap.appendChild(DN.donut(segs, { size: 96, stroke: 13, centerLabel: fa.totalRuns || '', centerSub: '次' }));
        var lg = DN.h('div', { class: 'gov-legend' });
        segs.forEach(function (s) { lg.appendChild(DN.h('span', {}, [DN.h('i', { style: 'background:' + s.color }), DN.h('span', { text: s.label + ' ' + s.value })])); });
        dwrap.appendChild(lg);
        trendBody.appendChild(dwrap);
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
            DN.h('span', { style: 'color:var(--text-muted);margin:0 6px', text: ts }),
            DN.h('span', { text: reason })
          ]));
        });
        trendBody.appendChild(ul);
      }
    }).catch(function () {
      trendBody.innerHTML = '';
      trendBody.appendChild(DN.empty('趋势加载失败', 'alert'));
    });
  }
})();
