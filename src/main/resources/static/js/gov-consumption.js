/* 治理模块：数据消费层（R11）— 指标驾驶舱：概览/指标值看板/新鲜度/僵尸指标/消费热度/一键计算/导出。
   填补断点④治理成果无消费出口。全量走 DN 现代套件 + /api/consumption/*。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  // R21 跨模块深链上下文：读取本次渲染的 ctx（focusMetric 高亮定位 / newDataset 预填）
  var ctx = window.__govCtx || {};
  ensureFlashStyle();

  window.GOV_RENDERERS.consumption = function (c) {
    // 每次进入重新取 ctx（switchGovModule 会在调用渲染器前设好 window.__govCtx）
    ctx = window.__govCtx || {};
    var ov = DN.h('div', { id: 'consOverview' }); c.appendChild(ov);
    var calcAll = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '一键计算全部指标' });
    var boardCard = DN.card({ title: '指标驾驶舱（最新值 / 新鲜度）', icon: 'chart', actions: [calcAll] });
    boardCard.el.id = 'consBoardCard';
    boardCard.body.appendChild(DN.h('div', { id: 'consBoard' }, DN.skeleton(4)));
    c.appendChild(boardCard.el);
    var heatCard = DN.card({ title: '消费热度 Top（近30天调用）', icon: 'bars' });
    heatCard.el.id = 'consHeatCard';
    heatCard.body.appendChild(DN.h('div', { id: 'consHeat' }, DN.skeleton(2)));
    c.appendChild(heatCard.el);
    var rankCard = DN.card({ title: '指标消费排行（按消费日志计数 Top20）', icon: 'bars' });
    rankCard.body.appendChild(DN.h('div', { id: 'consRank' }, DN.skeleton(2)));
    c.appendChild(rankCard.el);
    var zCard = DN.card({ title: '僵尸指标（启用但从未被消费取值）', icon: 'shield' });
    zCard.el.id = 'consZombieCard';
    zCard.body.appendChild(DN.h('div', { id: 'consZombie' }, DN.skeleton(2)));
    c.appendChild(zCard.el);

    var newDs = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建数据集', onclick: function () { addDataset(); } });
    var dsCard = DN.card({ title: '数据集 / 数据产品（受治理可复用查询）', icon: 'list', actions: [newDs] });
    dsCard.body.appendChild(DN.h('div', { id: 'consDataset' }, DN.skeleton(3)));
    c.appendChild(dsCard.el);

    calcAll.onclick = function () {
      if (!window.confirm('将计算全部启用指标的当前值，可能耗时，确认？')) return;
      calcAll.disabled = true; calcAll.textContent = '计算中…';
      DN.post('/api/consumption/metric/calc-all?operator=ui').then(function (r) {
        DN.toast('计算完成：成功 ' + (r.success || 0) + ' / 失败 ' + (r.failed || 0) + ' / 共 ' + (r.total || 0), 'ok');
        calcAll.disabled = false; calcAll.textContent = '一键计算全部指标';
        loadOverview(); loadBoard(); loadHeat();
      }).catch(function (e) {
        DN.toast('计算失败：' + (e && e.message || ''), 'err');
        calcAll.disabled = false; calcAll.textContent = '一键计算全部指标';
      });
    };

    loadOverview(); loadBoard(); loadHeat(); loadRanking(); loadZombies(); loadDatasets();

    // R21 深链：从其他模块带 newDataset 进来 → 自动开「新建数据集」抽屉并预填 SQL
    if (ctx.newDataset) {
      var nd = ctx.newDataset;
      var sql = nd.sql || (nd.db && nd.table ? 'SELECT * FROM ' + nd.db + '.' + nd.table + ' LIMIT 100' : '');
      setTimeout(function () { addDataset({ defaultDb: nd.db || '', querySql: sql }); }, 60);
    }
  };

  function loadRanking() {
    DN.get('/api/consumption/metric-ranking').then(function (rows) {
      var box = document.getElementById('consRank'); if (!box) return; box.innerHTML = '';
      if (!rows || !rows.length) { box.appendChild(DN.empty('暂无消费排行', 'bars')); return; }
      box.appendChild(DN.bars(rows.map(function (x) {
        var n = Number(x.cnt || x.CNT || 0);
        var code = x.target_code || x.targetCode || x.metricCode || '';
        return { label: x.metricName || code || '-', value: n, tone: 'primary', display: String(n),
          onClick: function () { focusBoardRow(code); } };
      })));
    }).catch(function () {});
  }

  function loadDatasets() {
    DN.get('/api/consumption/dataset/list').then(function (rows) {
      var box = document.getElementById('consDataset'); if (!box) return; box.innerHTML = '';
      if (!rows || !rows.length) { box.appendChild(DN.empty('暂无数据集，点「新建数据集」把精选 SQL 注册为可复用查询', 'list')); return; }
      box.appendChild(DN.table({
        rows: rows, pageSize: 10, searchKeys: ['datasetName', 'datasetCode', 'owner'], searchPlaceholder: '搜索数据集',
        columns: [
          { key: 'datasetName', label: '名称', render: function (r) { return r.datasetName || r.datasetCode; } },
          { key: 'datasetCode', label: '编码' },
          { key: 'owner', label: '负责人', render: function (r) { return r.owner || '-'; } },
          { key: 'status', label: '状态', render: function (r) { return r.status === 1 ? DN.pill('启用', 'ok') : DN.pill('下线', 'muted'); } },
          { key: '_op', label: '操作', render: function (r) { return dsOps(r); } }
        ]
      }));
    }).catch(function (e) {
      var box = document.getElementById('consDataset'); if (box) { box.innerHTML = ''; box.appendChild(DN.errorBox('数据集加载失败：' + (e && e.message || ''), loadDatasets)); }
    });
  }

  function dsOps(r) {
    var wrap = DN.h('span', { style: 'display:inline-flex;gap:8px;align-items:center' });
    var run = DN.h('a', { href: 'javascript:void(0)', text: '运行' });
    run.onclick = function () {
      run.textContent = '运行中…';
      DN.post('/api/consumption/dataset/' + r.id + '/query?consumer=ui').then(function (res) {
        run.textContent = '运行';
        showDatasetResult(r, res || {});
      }).catch(function (e) { run.textContent = '运行'; DN.toast('运行失败：' + (e && e.message || ''), 'err'); });
    };
    wrap.appendChild(run);
    var del = DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error,#f53f3f)' });
    del.onclick = function () {
      if (!window.confirm('确认删除数据集「' + (r.datasetName || r.datasetCode) + '」？')) return;
      DN.del('/api/consumption/dataset/' + r.id).then(function () { DN.toast('已删除'); loadDatasets(); }).catch(function (e) { DN.toast(e && e.message || '删除失败', 'err'); });
    };
    wrap.appendChild(del);
    return wrap;
  }

  function showDatasetResult(r, res) {
    var cols = res.columns || [], rows = res.rows || [];
    var rowsObj = rows.map(function (rowArr) {
      var o = {}; (cols || []).forEach(function (c, i) { o[c] = Array.isArray(rowArr) ? rowArr[i] : rowArr[c]; }); return o;
    });
    var body = DN.h('div');
    body.appendChild(DN.h('div', { class: 'gov-desc', text: '行数 ' + (res.rowCount != null ? res.rowCount : rowsObj.length) + (res.truncated ? '（已截断）' : '') + ' · 受治理脱敏后结果' }));
    body.appendChild(DN.table({
      rows: rowsObj, pageSize: 10, empty: '无数据',
      columns: (cols.length ? cols : ['(空)']).map(function (cn) { return { key: cn, label: cn, render: function (x) { var v = x[cn]; return v == null ? 'NULL' : String(v); } }; })
    }));
    DN.drawer('数据集结果 · ' + (r.datasetName || r.datasetCode), body);
  }

  function addDataset(prefill) {
    prefill = prefill || {};
    var nameI = DN.h('input', { class: 'iw-form-input', placeholder: '数据集名称' });
    var codeI = DN.h('input', { class: 'iw-form-input', placeholder: '编码(唯一,如 daily_gmv)' });
    var dbI = DN.h('input', { class: 'iw-form-input', placeholder: '默认库(如 ods,可空)', value: prefill.defaultDb || '' });
    var sqlI = DN.h('textarea', { class: 'iw-form-input', rows: '4', placeholder: '精选 SELECT 查询' });
    if (prefill.querySql) sqlI.value = prefill.querySql; // R21 深链预填 SQL
    var ownerI = DN.h('input', { class: 'iw-form-input', placeholder: '负责人(可空)' });
    drawerForm('新建数据集', [
      formRow('名称', nameI), formRow('编码', codeI), formRow('默认库', dbI), formRow('查询SQL', sqlI), formRow('负责人', ownerI)
    ], function (close) {
      var name = nameI.value.trim(), code = codeI.value.trim(), sql = sqlI.value.trim();
      if (!name || !code || !sql) { DN.toast('名称/编码/SQL 必填', 'err'); return; }
      DN.post('/api/consumption/dataset/save', { datasetName: name, datasetCode: code, defaultDb: dbI.value.trim(), querySql: sql, owner: ownerI.value.trim(), status: 1 })
        .then(function () { DN.toast('已保存'); close(); loadDatasets(); })
        .catch(function (e) { DN.toast('保存失败：' + (e && e.message || ''), 'err'); });
    });
  }

  // 抽屉表单:基于 DN.drawer(title, bodyNode) 构建表单 + 确定按钮
  function drawerForm(title, rows, onOk) {
    var body = DN.h('div'); rows.forEach(function (r) { body.appendChild(r); });
    var ok = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确定', style: 'margin-top:10px' });
    body.appendChild(ok);
    var d = DN.drawer(title, body);
    ok.onclick = function () { onOk(function () { if (d && d.close) d.close(); }); };
  }
  function formRow(label, input) {
    var row = DN.h('div', { class: 'ds-form-row', style: 'margin-bottom:10px' });
    row.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);margin-bottom:4px', text: label }));
    row.appendChild(input);
    return row;
  }

  function loadOverview() {
    DN.get('/api/consumption/overview').then(function (o) {
      var box = document.getElementById('consOverview'); if (!box) return; box.innerHTML = '';
      box.appendChild(DN.statRow([
        { icon: 'list', label: '启用指标', value: o.enabledMetrics || 0, title: '查看指标驾驶舱', onClick: function () { scrollToCard('consBoardCard'); } },
        { icon: 'check', label: '指标值快照', value: o.totalValues || 0, tone: 'ok', title: '查看指标驾驶舱', onClick: function () { scrollToCard('consBoardCard'); } },
        { icon: 'clock', label: '陈旧指标', value: o.staleMetrics || 0, tone: (o.staleMetrics ? 'warn' : 'ok'), title: '定位新鲜度看板', onClick: function () { scrollToCard('consBoardCard'); } },
        { icon: 'shield', label: '僵尸指标', value: o.zombieMetrics || 0, tone: (o.zombieMetrics ? 'err' : 'ok'), title: '定位僵尸指标卡', onClick: function () { scrollToCard('consZombieCard'); } },
        { icon: 'chart', label: '近7天消费', value: o.consume7d || 0, title: '定位消费热度卡', onClick: function () { scrollToCard('consHeatCard'); } }
      ]));
    }).catch(function () {});
  }

  function loadBoard() {
    DN.get('/api/consumption/metric/freshness').then(function (rows) {
      var box = document.getElementById('consBoard'); if (!box) return; box.innerHTML = '';
      if (!rows || !rows.length) { box.appendChild(DN.empty('暂无启用指标，请先在指标管理中定义并启用', 'chart')); return; }
      box.appendChild(DN.table({
        rows: rows, pageSize: 10, searchKeys: ['metricCode', 'metricName'], searchPlaceholder: '搜索指标',
        empty: '暂无指标', emptyIcon: 'chart',
        columns: [
          // 名称单元标记 data-mcode，供 R21 深链/条形图下钻高亮定位本行
          { key: 'metricName', label: '指标', render: function (r) { return DN.h('span', { 'data-mcode': r.metricCode || '', text: r.metricName || r.metricCode }); } },
          { key: 'metricCode', label: '编码', render: function (r) { return r.metricCode || '-'; } },
          { key: 'lastValue', label: '最新值', align: 'right', render: function (r) { return r.lastValue == null ? '—' : String(r.lastValue); } },
          { key: 'lastValueAt', label: '取值时间', render: function (r) { return r.lastValueAt ? DN.timeAgo(r.lastValueAt) : '从未'; } },
          { key: 'stale', label: '新鲜度', render: function (r) { return r.stale ? DN.pill('陈旧', 'warn') : DN.pill('新鲜', 'ok'); } },
          { key: '_op', label: '操作', render: function (r) { return opCell(r); } }
        ]
      }));
      // R21 深链：携带 focusMetric 进入 → 高亮并滚动定位该指标行
      if (ctx.focusMetric) { setTimeout(function () { focusBoardRow(ctx.focusMetric); }, 80); }
    }).catch(function (e) {
      var box = document.getElementById('consBoard'); if (box) { box.innerHTML = ''; box.appendChild(DN.errorBox('看板加载失败：' + (e && e.message || ''), loadBoard)); }
    });
  }

  function opCell(r) {
    var wrap = DN.h('span', { style: 'display:inline-flex;gap:8px;align-items:center' });
    var calc = DN.h('a', { href: 'javascript:void(0)', text: '计算' });
    calc.onclick = function () {
      calc.textContent = '…';
      DN.post('/api/consumption/metric/' + r.metricId + '/calc?operator=ui').then(function (v) {
        DN.toast(r.metricName + ' = ' + (v && v.metricValue != null ? v.metricValue : (v && v.runStatus === 'error' ? '计算异常' : '—')), v && v.runStatus === 'success' ? 'ok' : 'warn');
        loadBoard(); loadOverview();
      }).catch(function (e) { DN.toast('计算失败：' + (e && e.message || ''), 'err'); calc.textContent = '计算'; });
    };
    wrap.appendChild(calc);
    var exp = DN.h('a', { href: '/api/consumption/metric/' + r.metricId + '/export?format=csv', text: '导出', style: 'color:var(--primary,#1890ff)' });
    wrap.appendChild(exp);
    var al = DN.h('a', { href: 'javascript:void(0)', text: '预警', onclick: function () { alertRulesDrawer(r); } });
    wrap.appendChild(al);
    var iq = DN.h('a', { href: 'javascript:void(0)', text: '输入质量', onclick: function () { inputQualityDrawer(r); } });
    wrap.appendChild(iq);
    var tr = DN.h('a', { href: 'javascript:void(0)', text: '趋势', onclick: function () { metricTrendDrawer(r); } });
    wrap.appendChild(tr);
    return wrap;
  }

  // 指标历史趋势(本轮) —— 折线图展示历史值 + 最近 N 条明细表
  function metricTrendDrawer(r) {
    try {
      DN.get('/api/consumption/metric/' + r.metricId + '/history').then(function (rows) {
        var list = (rows || []).slice();
        // 兼容不同字段命名取值与时间，按时间升序
        function valOf(x) { var v = (x.metricValue != null ? x.metricValue : x.value); return Number(v); }
        function timeOf(x) { return x.createdAt || x.created_at || ''; }
        list.sort(function (a, b) { return String(timeOf(a)).localeCompare(String(timeOf(b))); });
        var pts = list.filter(function (x) { return !isNaN(valOf(x)); });
        var body = DN.h('div');
        body.appendChild(DN.h('div', { class: 'gov-desc', text: '指标「' + (r.metricName || r.metricCode) + '」历史取值变化' }));
        if (!pts.length) { body.appendChild(DN.empty('暂无历史值，先计算几次该指标', 'chart')); DN.drawer('指标历史趋势 · ' + (r.metricName || r.metricCode), body); return; }
        var vals = pts.map(function (x) { return valOf(x); });
        body.appendChild(DN.line(vals, { height: 120 }));
        // 最近 N 条明细（倒序，取最新在前）
        var recent = pts.slice(-12).reverse();
        body.appendChild(DN.h('div', { class: 'gov-section-title', text: '最近取值', style: 'margin-top:12px' }));
        body.appendChild(DN.table({
          rows: recent, pageSize: 12, empty: '无',
          columns: [
            { key: '_t', label: '时间', render: function (x) { var t = timeOf(x); return t ? DN.timeAgo(t) : '-'; } },
            { key: '_v', label: '值', align: 'right', render: function (x) { return String(valOf(x)); } }
          ]
        }));
        DN.drawer('指标历史趋势 · ' + (r.metricName || r.metricCode), body);
      }).catch(function (e) { DN.toast('趋势加载失败：' + (e && e.message || ''), 'err'); });
    } catch (e) { DN.toast('趋势加载失败：' + (e && e.message || ''), 'err'); }
  }

  // 指标输入质量联动(R18) —— 来源表质量规则 + 最新通过率 + 可信度信号
  function inputQualityDrawer(r) {
    var SIG = { HEALTHY: ['输入质量健康', 'ok'], AT_RISK: ['输入存在失败规则', 'err'],
                NO_RESULT: ['规则未执行', 'warn'], NO_RULES: ['来源表无质量规则', 'info'] };
    var body = DN.h('div');
    body.appendChild(DN.h('div', { class: 'gov-desc', text: '指标「' + (r.metricName || r.metricCode) + '」来源表的数据质量，评估指标可信度' }));
    var box = DN.h('div', { id: 'iqBox' }, DN.skeleton(2));
    body.appendChild(box);
    DN.drawer('指标输入质量 · ' + (r.metricName || r.metricCode), body);
    DN.get('/api/consumption/metric/' + r.metricId + '/input-quality').then(function (d) {
      box.innerHTML = '';
      var sig = SIG[d.signal] || ['未知', 'info'];
      var head = DN.h('div', { style: 'margin-bottom:10px' });
      head.appendChild(DN.pill(sig[0], sig[1]));
      head.appendChild(DN.h('span', { style: 'margin-left:8px;font-size:12px;color:var(--text-muted)', text: '规则 ' + (d.ruleTotal || 0) + ' · 失败 ' + (d.ruleFail || 0) }));
      box.appendChild(head);
      var tables = d.tables || [];
      if (!tables.length) { box.appendChild(DN.empty('该指标无登记的来源表（先在治理→指标关联资产）', 'shield')); return; }
      tables.forEach(function (t) {
        // R22 下钻：点来源表标题 → 跳质量模块并按该表过滤，输入质量从只读变为可处理
        var title = DN.h('div', { class: 'gov-section-title', style: 'cursor:pointer', title: '查看该表质量规则（点击跳质量模块）',
          text: t.db + '.' + t.table + ' （' + t.ruleCount + ' 条规则）→' });
        title.onclick = function () { gotoQualityTable(t.db, t.table); };
        box.appendChild(title);
        if (!t.rules.length) { box.appendChild(DN.h('div', { class: 'gov-desc', text: '无启用质量规则' })); return; }
        t.rules.forEach(function (ru) {
          var line = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid var(--divider)' });
          var tone = ru.runStatus === 'no_result' ? 'info' : (ru.passRate != null && Number(ru.passRate) >= 100 ? 'ok' : 'err');
          line.appendChild(DN.pill(ru.severity || 'warning', ru.severity === 'error' ? 'err' : (ru.severity === 'info' ? 'info' : 'warn')));
          line.appendChild(DN.h('span', { style: 'flex:1;font-size:13px', text: (ru.ruleName || ru.ruleType) + (ru.dimension ? ' · ' + ru.dimension : '') }));
          line.appendChild(DN.pill(ru.passRate != null ? (ru.passRate + '%') : '未跑', tone));
          // R22 下钻：规则行能拿到 db/table（来自所属来源表 t）即可点击跳质量
          if (t.db && t.table) {
            line.style.cursor = 'pointer';
            line.title = '到质量模块处理该表规则';
            line.onclick = function () { gotoQualityTable(t.db, t.table); };
          }
          box.appendChild(line);
        });
      });
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.empty('加载失败：' + (e && e.message || ''), 'shield')); });
  }

  // 指标预警规则管理(复用 R12 端点) —— 列表 + 新增 + 删除
  function alertRulesDrawer(r) {
    var body = DN.h('div');
    var listBox = DN.h('div', { id: 'alRules' }, DN.skeleton(2));
    body.appendChild(DN.h('div', { class: 'gov-desc', text: '指标「' + (r.metricName || r.metricCode) + '」越界自动生成治理工单' }));
    body.appendChild(listBox);
    // 新增表单
    var opSel = selectOf(['GT', 'LT', 'GE', 'LE', 'NE', 'OUT', 'IN'], 'GT');
    var minI = DN.h('input', { class: 'iw-form-input', placeholder: '阈值/区间下界' });
    var maxI = DN.h('input', { class: 'iw-form-input', placeholder: '区间上界(OUT/IN用,可空)' });
    var sevSel = selectOf(['HIGH', 'MEDIUM', 'LOW'], 'MEDIUM');
    var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增规则', style: 'margin-top:8px' });
    body.appendChild(DN.h('div', { class: 'gov-section-title', text: '新增规则' }));
    body.appendChild(formRow('比较符', opSel));
    body.appendChild(formRow('阈值下界', minI));
    body.appendChild(formRow('区间上界', maxI));
    body.appendChild(formRow('严重度', sevSel));
    body.appendChild(addBtn);
    DN.drawer('指标预警规则 · ' + (r.metricName || r.metricCode), body);
    function reload() {
      DN.get('/api/consumption/metric/' + r.metricId + '/alert-rules').then(function (rows) {
        listBox.innerHTML = '';
        if (!rows || !rows.length) { listBox.appendChild(DN.empty('暂无预警规则', 'shield')); return; }
        rows.forEach(function (rule) {
          var line = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid var(--divider)' });
          line.appendChild(DN.pill(rule.severity || 'MEDIUM', rule.severity === 'HIGH' ? 'err' : (rule.severity === 'LOW' ? 'info' : 'warn')));
          line.appendChild(DN.h('span', { style: 'flex:1;font-size:13px', text: rule.op + ' ' + (rule.thresholdMin != null ? rule.thresholdMin : '') + (rule.thresholdMax != null ? ('~' + rule.thresholdMax) : '') }));
          var del = DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error,#f53f3f)' });
          del.onclick = function () { DN.del('/api/consumption/metric/alert-rule/' + rule.id).then(function () { DN.toast('已删除'); reload(); }); };
          line.appendChild(del);
          listBox.appendChild(line);
        });
      }).catch(function () { listBox.innerHTML = ''; listBox.appendChild(DN.empty('加载失败', 'shield')); });
    }
    addBtn.onclick = function () {
      var body2 = { metricId: r.metricId, op: opSel.value, severity: sevSel.value };
      if (minI.value.trim() !== '') body2.thresholdMin = Number(minI.value.trim());
      if (maxI.value.trim() !== '') body2.thresholdMax = Number(maxI.value.trim());
      DN.post('/api/consumption/metric/alert-rule/save', body2).then(function () {
        DN.toast('规则已保存'); minI.value = ''; maxI.value = ''; reload();
      }).catch(function (e) { DN.toast('保存失败：' + (e && e.message || ''), 'err'); });
    };
    reload();
  }

  function selectOf(opts, def) {
    var s = DN.h('select', { class: 'iw-form-select' });
    opts.forEach(function (o) { var op = DN.h('option', { value: o, text: o }); if (o === def) op.selected = true; s.appendChild(op); });
    return s;
  }

  function loadHeat() {
    DN.get('/api/consumption/log/heat').then(function (rows) {
      var box = document.getElementById('consHeat'); if (!box) return; box.innerHTML = '';
      if (!rows || !rows.length) { box.appendChild(DN.empty('暂无消费记录', 'bars')); return; }
      box.appendChild(DN.bars(rows.map(function (x) {
        var code = x.target_code || x.targetCode || '';
        return { label: code || '-', value: Number(x.cnt || x.CNT || 0), tone: 'info', display: String(x.cnt || x.CNT || 0),
          onClick: function () { focusBoardRow(code); } };
      })));
    }).catch(function () {});
  }

  function loadZombies() {
    DN.get('/api/consumption/metric/zombies').then(function (rows) {
      var box = document.getElementById('consZombie'); if (!box) return; box.innerHTML = '';
      if (!rows || !rows.length) { box.appendChild(DN.empty('无僵尸指标，全部已被消费 👍', 'check')); return; }
      box.appendChild(DN.table({
        rows: rows, pageSize: 8, empty: '无',
        columns: [
          { key: 'metricName', label: '指标', render: function (r) { return r.metricName || r.metricCode; } },
          { key: 'metricCode', label: '编码' },
          { key: 'category', label: '分类', render: function (r) { return r.category || '-'; } },
          { key: 'owner', label: '负责人', render: function (r) { return r.owner || '-'; } },
          { key: '_op', label: '操作', render: function (r) { return zombieOps(r); } }
        ]
      }));
    }).catch(function () {});
  }

  // 僵尸指标行操作：立即计算（激活取值）/ 编辑（深链至指标管理）
  function zombieOps(r) {
    var wrap = DN.h('span', { style: 'display:inline-flex;gap:8px;align-items:center' });
    var calc = DN.h('a', { href: 'javascript:void(0)', text: '立即计算' });
    calc.onclick = function () {
      calc.textContent = '…';
      DN.post('/api/consumption/metric/' + r.id + '/calc?operator=ui').then(function (v) {
        DN.toast((r.metricName || r.metricCode) + ' = ' + (v && v.metricValue != null ? v.metricValue : '—'), v && v.runStatus === 'success' ? 'ok' : 'warn');
        loadZombies(); loadBoard(); loadOverview();
      }).catch(function (e) { calc.textContent = '立即计算'; DN.toast('计算失败：' + (e && e.message || ''), 'err'); });
    };
    wrap.appendChild(calc);
    var edit = DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#1890ff)' });
    edit.onclick = function () { if (typeof navigateTo === 'function') navigateTo('metrics', { editId: r.id }); };
    wrap.appendChild(edit);
    return wrap;
  }

  // 滚动定位某张卡片（消费层概览统计卡下钻用）
  function scrollToCard(id) {
    var el = document.getElementById(id); if (!el) return;
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    el.classList.add('cons-flash'); setTimeout(function () { el.classList.remove('cons-flash'); }, 1500);
  }

  // 高亮并滚动定位驾驶舱中指定指标编码所在行（深链 focusMetric / 条形图下钻共用）
  function focusBoardRow(code) {
    if (!code) return;
    var board = document.getElementById('consBoard'); if (!board) return;
    var marks = board.querySelectorAll('[data-mcode]');
    for (var i = 0; i < marks.length; i++) {
      if (marks[i].getAttribute('data-mcode') === code) {
        var tr = marks[i].closest ? marks[i].closest('tr') : marks[i].parentNode;
        var target = tr || marks[i];
        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
        target.classList.add('cons-flash'); (function (t) { setTimeout(function () { t.classList.remove('cons-flash'); }, 1500); })(target);
        return;
      }
    }
    DN.toast('指标「' + code + '」可能在其他页（已在驾驶舱搜索框输入编码可定位）', 'info');
  }

  // R22 下钻：从输入质量跳质量模块并按来源表过滤（gov-quality 收 ctx.table 过滤）
  function gotoQualityTable(db, table) {
    if (!db || !table) return;
    if (typeof navigateTo === 'function') navigateTo('governance', { gov: 'quality', table: { db: db, table: table } });
  }

  // 一次性注入深链高亮动画样式（不污染全局 css 文件，模块自包含）
  function ensureFlashStyle() {
    if (document.getElementById('consFlashStyle')) return;
    var st = document.createElement('style'); st.id = 'consFlashStyle';
    st.textContent = '@keyframes consFlash{0%{background:rgba(24,144,255,.22)}100%{background:transparent}}'
      + '.cons-flash{animation:consFlash 1.5s ease-out;border-radius:6px}';
    document.head.appendChild(st);
  }
})();
