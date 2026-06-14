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
    var calcAll = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '一键计算全部指标', 'data-perm': 'metrics:edit' });
    var boardCard = DN.card({ title: '指标驾驶舱（最新值 / 新鲜度）', icon: 'chart', actions: [calcAll] });
    boardCard.el.id = 'consBoardCard';
    boardCard.body.appendChild(DN.h('div', { id: 'consBoard' }, DN.skeleton(4)));
    c.appendChild(boardCard.el);
    // I-1: 排行+热度合并为单卡双窗口(同一真实消费口径, days=30/全部切换)
    var winBtns = [
      DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '近30天', onclick: function () { _rankDays = 30; markRankWin(); loadRanking(); } }),
      DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '全部', onclick: function () { _rankDays = null; markRankWin(); loadRanking(); } })
    ];
    _rankWinBtns = winBtns;
    var rankCard = DN.card({ title: '消费排行 Top20（查询/趋势/导出真实消费计数）', icon: 'bars', actions: winBtns });
    rankCard.el.id = 'consHeatCard';   // 概览磁贴"近7天消费"锚点沿用
    rankCard.body.appendChild(DN.h('div', { id: 'consRank' }, DN.skeleton(2)));
    c.appendChild(rankCard.el);
    markRankWin();
    var zCard = DN.card({ title: '僵尸指标（启用但从未被消费取值）', icon: 'shield' });
    zCard.el.id = 'consZombieCard';
    zCard.body.appendChild(DN.h('div', { id: 'consZombie' }, DN.skeleton(2)));
    c.appendChild(zCard.el);

    // 数据集卡: 指标取值签默认隐藏(消费产物非指标主线); 深链 newDataset 时显示
    var newDs = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建数据集', 'data-perm': 'metrics:edit', onclick: function () { addDataset(); } });
    var dsCard = DN.card({ title: '数据集 / 数据产品（受治理可复用查询）', icon: 'list', actions: [newDs] });
    dsCard.body.appendChild(DN.h('div', { id: 'consDataset' }, DN.skeleton(3)));
    if (!ctx.newDataset) dsCard.el.style.display = 'none';
    c.appendChild(dsCard.el);

    calcAll.onclick = function () {
      // 注意：<a> 元素的 disabled 不阻止点击，必须用 dataset.busy 标志位防重复提交
      if (calcAll.dataset.busy) return;
      DN.confirm('将计算全部启用指标的当前值，可能耗时，确认？', { title: '计算确认' }).then(function (ok) {
        if (!ok) return;
        setLinkBusy(calcAll, true, '计算中…');
        DN.post('/api/consumption/metric/calc-all?operator=ui').then(function (r) {
          r = r || {};
          DN.toast('计算完成：成功 ' + (r.success || 0) + ' / 失败 ' + (r.failed || 0) + ' / 共 ' + (r.total || 0), 'ok');
          setLinkBusy(calcAll, false, '一键计算全部指标');
          // 计算后多张卡片数据均变化：概览/看板/排行/僵尸全部刷新保持联动
          loadOverview(); loadBoard(); loadRanking(); loadZombies();
        }).catch(function (e) {
          DN.toast('计算失败：' + errMsg(e), 'err');
          setLinkBusy(calcAll, false, '一键计算全部指标');
        });
      });
    };

    loadOverview(); loadBoard(); loadRanking(); loadZombies();
    if (ctx.newDataset) loadDatasets();

    // R21 深链：从其他模块带 newDataset 进来 → 自动开「新建数据集」抽屉并预填 SQL
    if (ctx.newDataset) {
      var nd = ctx.newDataset;
      var sql = nd.sql || (nd.db && nd.table ? 'SELECT * FROM ' + nd.db + '.' + nd.table + ' LIMIT 100' : '');
      setTimeout(function () { addDataset({ defaultDb: nd.db || '', querySql: sql }); }, 60);
    }
  };

  var _rankDays = 30;        // 默认近30天(原热度卡语义); 可切全部
  var _rankWinBtns = null;
  function markRankWin() {
    if (!_rankWinBtns) return;
    _rankWinBtns[0].classList.toggle('btn-primary', _rankDays === 30);
    _rankWinBtns[1].classList.toggle('btn-primary', _rankDays == null);
  }
  function loadRanking() {
    var box = document.getElementById('consRank'); if (box) { box.innerHTML = ''; box.appendChild(DN.skeleton(2)); }
    DN.get('/api/consumption/metric-ranking' + (_rankDays ? '?days=' + _rankDays : '')).then(function (rows) {
      var box = document.getElementById('consRank'); if (!box) return; box.innerHTML = '';
      if (!Array.isArray(rows) || !rows.length) { box.appendChild(DN.empty('暂无消费排行，指标被查询/导出后这里按调用次数排序展示 Top20', 'bars')); return; }
      // 边界：仅取前 20 条，且过滤掉非正计数，避免空条形与超长列表卡顿
      var items = rows.slice(0, 20).map(function (x) {
        x = x || {};
        var n = Number(x.cnt || x.CNT || 0); if (isNaN(n) || n < 0) n = 0;
        var code = x.target_code || x.targetCode || x.metricCode || '';
        var label = x.metricName || code || '-';
        return { label: clip(label, 28), fullLabel: label, value: n, tone: 'primary', display: String(n), code: code };
      }).filter(function (it) { return it.value > 0; });
      if (!items.length) { box.appendChild(DN.empty('暂无有效消费计数', 'bars')); return; }
      box.appendChild(DN.bars(items.map(function (it) {
        return { label: it.label, value: it.value, tone: it.tone, display: it.display,
          onClick: function () { focusBoardRow(it.code); } };
      })));
    }).catch(function (e) {
      var box = document.getElementById('consRank'); if (box) { box.innerHTML = ''; box.appendChild(DN.errorBox('消费排行加载失败：' + errMsg(e), loadRanking)); }
    });
  }

  function loadDatasets() {
    var box0 = document.getElementById('consDataset'); if (box0) { box0.innerHTML = ''; box0.appendChild(DN.skeleton(3)); }
    DN.get('/api/consumption/dataset/list').then(function (rows) {
      var box = document.getElementById('consDataset'); if (!box) return; box.innerHTML = '';
      if (!Array.isArray(rows) || !rows.length) { box.appendChild(DN.empty('暂无数据集，点「新建数据集」把精选 SQL 注册为可复用查询', 'list')); return; }
      box.appendChild(DN.table({
        rows: rows, pageSize: 10, searchKeys: ['datasetName', 'datasetCode', 'owner'], searchPlaceholder: '搜索数据集',
        empty: '暂无数据集', emptyIcon: 'list',
        columns: [
          // 超长名称截断 + title 悬浮显示全称
          { key: 'datasetName', label: '名称', render: function (r) { var t = r.datasetName || r.datasetCode || '-'; return DN.h('span', { title: t, text: clip(t, 30) }); } },
          { key: 'datasetCode', label: '编码', render: function (r) { var t = r.datasetCode || '-'; return DN.h('span', { title: t, text: clip(t, 24) }); } },
          { key: 'owner', label: '负责人', render: function (r) { return r.owner || '-'; } },
          { key: 'status', label: '状态', render: function (r) { return r.status === 1 ? DN.pill('启用', 'ok') : DN.pill('下线', 'muted'); } },
          { key: '_op', label: '操作', render: function (r) { return dsOps(r); } }
        ]
      }));
    }).catch(function (e) {
      var box = document.getElementById('consDataset'); if (box) { box.innerHTML = ''; box.appendChild(DN.errorBox('数据集加载失败：' + errMsg(e), loadDatasets)); }
    });
  }

  function dsOps(r) {
    r = r || {};
    var wrap = DN.h('span', { style: 'display:inline-flex;gap:8px;align-items:center' });
    var run = DN.h('a', { href: 'javascript:void(0)', text: '运行', 'data-perm': 'metrics:edit' });
    run.onclick = function () {
      if (r.id == null) { DN.toast('数据集缺少 ID，无法运行', 'err'); return; }
      if (run.dataset.busy) return; setLinkBusy(run, true, '运行中…'); // 防重复提交
      DN.post('/api/consumption/dataset/' + r.id + '/query?consumer=ui').then(function (res) {
        setLinkBusy(run, false, '运行');
        showDatasetResult(r, res || {});
      }).catch(function (e) { setLinkBusy(run, false, '运行'); DN.toast('运行失败：' + errMsg(e), 'err'); });
    };
    wrap.appendChild(run);
    var del = DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error)', 'data-perm': 'metrics:edit' });
    del.onclick = function () {
      if (r.id == null) { DN.toast('数据集缺少 ID，无法删除', 'err'); return; }
      if (del.dataset.busy) return;
      DN.confirm('确认删除数据集「' + (r.datasetName || r.datasetCode || r.id) + '」？删除后不可恢复。', { title: '删除确认', danger: true }).then(function (ok) {
        if (!ok) return;
        setLinkBusy(del, true, '删除中…');
        DN.del('/api/consumption/dataset/' + r.id).then(function () { DN.toast('已删除', 'ok'); loadDatasets(); }).catch(function (e) { setLinkBusy(del, false, '删除'); DN.toast('删除失败：' + errMsg(e), 'err'); });
      });
    };
    wrap.appendChild(del);
    return wrap;
  }

  function showDatasetResult(r, res) {
    res = res || {}; r = r || {};
    var cols = Array.isArray(res.columns) ? res.columns : [], rows = Array.isArray(res.rows) ? res.rows : [];
    var rowsObj = rows.map(function (rowArr) {
      var o = {}; cols.forEach(function (c, i) { o[c] = Array.isArray(rowArr) ? rowArr[i] : (rowArr ? rowArr[c] : null); }); return o;
    });
    var body = DN.h('div');
    body.appendChild(DN.h('div', { class: 'gov-desc', text: '行数 ' + (res.rowCount != null ? res.rowCount : rowsObj.length) + (res.truncated ? '（已截断）' : '') + ' · 受治理脱敏后结果' }));
    body.appendChild(DN.table({
      rows: rowsObj, pageSize: 10, empty: '查询无返回数据', emptyIcon: 'inbox',
      // 单元格超长截断 + title 显示全文，避免宽表撑破抽屉
      columns: (cols.length ? cols : ['(空)']).map(function (cn) { return { key: cn, label: cn, render: function (x) { var v = x[cn]; if (v == null) return 'NULL'; var s = String(v); return DN.h('span', { title: s, text: clip(s, 60) }); } }; })
    }));
    DN.drawer('数据集结果 · ' + (r.datasetName || r.datasetCode || '查询'), body);
  }

  function addDataset(prefill) {
    prefill = prefill || {};
    var nameI = DN.h('input', { class: 'dn-form-input', placeholder: '数据集名称' });
    var codeI = DN.h('input', { class: 'dn-form-input', placeholder: '编码(唯一,如 daily_gmv)' });
    var dbI = DN.h('input', { class: 'dn-form-input', placeholder: '默认库(如 ods,可空)', value: prefill.defaultDb || '' });
    var sqlI = DN.h('textarea', { class: 'dn-form-input', rows: '4', placeholder: '精选 SELECT 查询' });
    if (prefill.querySql) sqlI.value = prefill.querySql; // R21 深链预填 SQL
    var ownerI = DN.h('input', { class: 'dn-form-input', placeholder: '负责人(可空)' });

    var body = DN.h('div');
    var secBase = DN.formSection('基本信息');
    secBase.add(DN.field('名称', nameI, { required: true }));
    secBase.add(DN.formGrid2([
      DN.field('编码', codeI, { required: true, hint: '字母开头，字母/数字/下划线，长度2-50' }),
      DN.field('负责人', ownerI)
    ]));
    body.appendChild(secBase.el);

    var secQuery = DN.formSection('查询配置');
    secQuery.add(DN.field('默认库', dbI, { hint: '如 ods，可空' }));
    secQuery.add(DN.field('查询SQL', sqlI, { required: true, hint: '只允许以 SELECT 开头（消费层只读）' }));
    body.appendChild(secQuery.el);

    var dr, foot;
    var doSave = function () {
      if (foot.ok.disabled) return;
      var name = nameI.value.trim(), code = codeI.value.trim(), sql = sqlI.value.trim();
      // 输入校验：必填 / 编码格式 / SQL 仅允许 SELECT（消费层只读安全）
      if (!name) { DN.toast('请填写数据集名称', 'err'); nameI.focus(); return; }
      if (!code) { DN.toast('请填写数据集编码', 'err'); codeI.focus(); return; }
      if (!/^[A-Za-z][A-Za-z0-9_]{1,49}$/.test(code)) { DN.toast('编码须以字母开头，仅含字母/数字/下划线，长度2-50', 'err'); codeI.focus(); return; }
      if (!sql) { DN.toast('请填写查询 SQL', 'err'); sqlI.focus(); return; }
      if (!/^\s*select\b/i.test(sql)) { DN.toast('查询 SQL 只能以 SELECT 开头（消费层仅支持只读查询）', 'err'); sqlI.focus(); return; }
      // 与行过滤口径一致：拦截分号/注释符，仅允许单条 SELECT（服务端仍强制只读单语句）
      if (/;|--|\/\*|\*\//.test(sql)) { DN.toast('查询 SQL 不能包含分号或注释符（仅支持单条 SELECT）', 'err'); sqlI.focus(); return; }
      foot.busy();
      DN.post('/api/consumption/dataset/save', { datasetName: name, datasetCode: code, defaultDb: dbI.value.trim(), querySql: sql, owner: ownerI.value.trim(), status: 1 })
        .then(function () { DN.toast('已保存', 'ok'); dr.close(); loadDatasets(); })
        .catch(function (e) { foot.reset(); DN.toast('保存失败：' + errMsg(e), 'err'); });
    };
    foot = DN.drawerFoot({ okText: '保存', onOk: doSave, onCancel: function () { dr.close(); } });
    dr = DN.drawer('新建数据集', body, foot.el);
    DN.enterSubmit(body, doSave);
  }

  function loadOverview() {
    var box0 = document.getElementById('consOverview'); if (box0) { box0.innerHTML = ''; box0.appendChild(DN.skeleton(1)); }
    DN.get('/api/consumption/overview').then(function (o) {
      o = o || {};
      var box = document.getElementById('consOverview'); if (!box) return; box.innerHTML = '';
      box.appendChild(DN.statRow([
        { icon: 'list', label: '启用指标', value: o.enabledMetrics || 0, title: '查看指标驾驶舱', onClick: function () { scrollToCard('consBoardCard'); } },
        { icon: 'check', label: '指标值快照', value: o.totalValues || 0, tone: 'ok', title: '查看指标驾驶舱', onClick: function () { scrollToCard('consBoardCard'); } },
        { icon: 'clock', label: '陈旧指标', value: o.staleMetrics || 0, tone: (o.staleMetrics ? 'warn' : 'ok'), title: '定位新鲜度看板', onClick: function () { scrollToCard('consBoardCard'); } },
        { icon: 'shield', label: '僵尸指标', value: o.zombieMetrics || 0, tone: (o.zombieMetrics ? 'err' : 'ok'), title: '定位僵尸指标卡', onClick: function () { scrollToCard('consZombieCard'); } },
        { icon: 'chart', label: '近7天消费', value: o.consume7d || 0, title: '定位消费热度卡', onClick: function () { scrollToCard('consHeatCard'); } }
      ]));
    }).catch(function (e) {
      var box = document.getElementById('consOverview'); if (box) { box.innerHTML = ''; box.appendChild(DN.errorBox('概览加载失败：' + errMsg(e), loadOverview)); }
    });
  }

  function loadBoard() {
    var box0 = document.getElementById('consBoard'); if (box0) { box0.innerHTML = ''; box0.appendChild(DN.skeleton(4)); }
    DN.get('/api/consumption/metric/freshness').then(function (rows) {
      var box = document.getElementById('consBoard'); if (!box) return; box.innerHTML = '';
      if (!Array.isArray(rows) || !rows.length) { box.appendChild(DN.empty('暂无启用指标，请先在指标管理中定义并启用', 'chart')); return; }
      box.appendChild(DN.table({
        rows: rows, pageSize: 10, searchKeys: ['metricCode', 'metricName'], searchPlaceholder: '搜索指标',
        empty: '暂无指标', emptyIcon: 'chart',
        columns: [
          // 名称单元标记 data-mcode，供 R21 深链/条形图下钻高亮定位本行；超长名称截断+title
          { key: 'metricName', label: '指标', render: function (r) { var t = r.metricName || r.metricCode || '-'; return DN.h('span', { 'data-mcode': r.metricCode || '', title: t, text: clip(t, 30) }); } },
          { key: 'metricCode', label: '编码', render: function (r) { var t = r.metricCode || '-'; return DN.h('span', { title: t, text: clip(t, 24), style: 'display:inline-block;max-width:160px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;vertical-align:middle;' }); } },
          { key: 'lastValue', label: '最新值', align: 'right', render: function (r) { return fmtNum(r.lastValue); } },
          { key: 'lastValueAt', label: '取值时间', render: function (r) { return r.lastValueAt ? DN.timeAgo(r.lastValueAt) : '从未'; } },
          { key: 'stale', label: '新鲜度', render: function (r) { return r.stale ? DN.pill('陈旧', 'warn') : DN.pill('新鲜', 'ok'); } },
          { key: '_op', label: '操作', render: function (r) { return opCell(r); } }
        ]
      }));
      // R21 深链：携带 focusMetric 进入 → 高亮并滚动定位该指标行
      if (ctx.focusMetric) { setTimeout(function () { focusBoardRow(ctx.focusMetric); }, 80); }
    }).catch(function (e) {
      var box = document.getElementById('consBoard'); if (box) { box.innerHTML = ''; box.appendChild(DN.errorBox('看板加载失败：' + errMsg(e), loadBoard)); }
    });
  }

  function opCell(r) {
    r = r || {};
    var wrap = DN.h('span', { style: 'display:inline-flex;gap:8px;align-items:center' });
    var calc = DN.h('a', { href: 'javascript:void(0)', text: '计算', 'data-perm': 'metrics:edit' });
    calc.onclick = function () {
      if (r.metricId == null) { DN.toast('指标缺少 ID，无法计算', 'err'); return; }
      if (calc.dataset.busy) return; setLinkBusy(calc, true, '计算中…'); // 防重复提交
      DN.post('/api/consumption/metric/' + r.metricId + '/calc?operator=ui').then(function (v) {
        setLinkBusy(calc, false, '计算');
        DN.toast((r.metricName || r.metricCode || '指标') + ' = ' + (v && v.metricValue != null ? fmtNum(v.metricValue) : (v && v.runStatus === 'error' ? '计算异常' : '—')), v && v.runStatus === 'success' ? 'ok' : 'warn');
        loadBoard(); loadOverview();
      }).catch(function (e) { setLinkBusy(calc, false, '计算'); DN.toast('计算失败：' + errMsg(e), 'err'); });
    };
    wrap.appendChild(calc);
    if (r.metricId != null) {
      var exp = DN.h('a', { href: '/api/consumption/metric/' + r.metricId + '/export?format=csv', text: '导出', style: 'color:var(--primary)', title: '导出该指标历史值 CSV' });
      wrap.appendChild(exp);
    }
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
    r = r || {};
    if (r.metricId == null) { DN.toast('指标缺少 ID，无法查看趋势', 'err'); return; }
    var title = '指标历史趋势 · ' + (r.metricName || r.metricCode || '');
    // 先开抽屉显示骨架，再异步加载（错误态落在抽屉内，不靠纯 toast）
    var body = DN.h('div');
    body.appendChild(DN.h('div', { class: 'gov-desc', text: '指标「' + (r.metricName || r.metricCode || '') + '」历史取值变化' }));
    var box = DN.h('div', {}, DN.skeleton(3)); body.appendChild(box);
    DN.drawer(title, body);
    DN.get('/api/consumption/metric/' + r.metricId + '/history').then(function (rows) {
      box.innerHTML = '';
      var list = Array.isArray(rows) ? rows.slice() : [];
      // 兼容不同字段命名取值与时间，按时间升序
      function valOf(x) { x = x || {}; var v = (x.metricValue != null ? x.metricValue : x.value); return Number(v); }
      function timeOf(x) { x = x || {}; return x.createdAt || x.created_at || ''; }
      list.sort(function (a, b) { return String(timeOf(a)).localeCompare(String(timeOf(b))); });
      var pts = list.filter(function (x) { return !isNaN(valOf(x)); });
      if (!pts.length) { box.appendChild(DN.empty('暂无历史值，先计算几次该指标即可生成趋势', 'chart')); return; }
      // 边界：折线最多取最近 200 个点，避免超长历史渲染卡顿
      var trend = pts.slice(-200);
      box.appendChild(DN.line(trend.map(function (x) { return valOf(x); }), { height: 120 }));
      // 最近 N 条明细（倒序，取最新在前）
      var recent = pts.slice(-12).reverse();
      box.appendChild(DN.h('div', { class: 'gov-section-title', text: '最近取值', style: 'margin-top:12px' }));
      box.appendChild(DN.table({
        rows: recent, pageSize: 12, empty: '无', search: false,
        columns: [
          { key: '_t', label: '时间', render: function (x) { var t = timeOf(x); return t ? DN.timeAgo(t) : '-'; } },
          { key: '_v', label: '值', align: 'right', render: function (x) { return fmtNum(valOf(x)); } }
        ]
      }));
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.errorBox('趋势加载失败：' + errMsg(e), function () { metricTrendDrawer(r); })); });
  }

  // 指标输入质量联动(R18) —— 来源表质量规则 + 最新通过率 + 可信度信号
  function inputQualityDrawer(r) {
    r = r || {};
    if (r.metricId == null) { DN.toast('指标缺少 ID，无法查看输入质量', 'err'); return; }
    var SIG = { HEALTHY: ['输入质量健康', 'ok'], AT_RISK: ['输入存在失败规则', 'err'],
                NO_RESULT: ['规则未执行', 'warn'], NO_RULES: ['来源表无质量规则', 'info'] };
    var body = DN.h('div');
    body.appendChild(DN.h('div', { class: 'gov-desc', text: '指标「' + (r.metricName || r.metricCode || '') + '」来源表的数据质量，评估指标可信度' }));
    var box = DN.h('div', { id: 'iqBox' }, DN.skeleton(2));
    body.appendChild(box);
    DN.drawer('指标输入质量 · ' + (r.metricName || r.metricCode || ''), body);
    DN.get('/api/consumption/metric/' + r.metricId + '/input-quality').then(function (d) {
      d = d || {};
      box.innerHTML = '';
      var sig = SIG[d.signal] || ['未知', 'info'];
      var head = DN.h('div', { style: 'margin-bottom:10px' });
      head.appendChild(DN.pill(sig[0], sig[1]));
      head.appendChild(DN.h('span', { style: 'margin-left:8px;font-size:12px;color:var(--text-muted)', text: '规则 ' + (d.ruleTotal || 0) + ' · 失败 ' + (d.ruleFail || 0) }));
      box.appendChild(head);
      var tables = Array.isArray(d.tables) ? d.tables : [];
      if (!tables.length) { box.appendChild(DN.empty('该指标无登记的来源表（先在治理→指标关联资产）', 'shield')); return; }
      tables.forEach(function (t) {
        t = t || {}; var rules = Array.isArray(t.rules) ? t.rules : [];
        // R22 下钻：点来源表标题 → 跳质量模块并按该表过滤，输入质量从只读变为可处理
        var title = DN.h('div', { class: 'gov-section-title', style: 'cursor:pointer', title: '查看该表质量规则（点击跳质量模块）',
          text: (t.db || '?') + '.' + (t.table || '?') + ' （' + (t.ruleCount != null ? t.ruleCount : rules.length) + ' 条规则）→' });
        title.onclick = function () { gotoQualityTable(t.db, t.table); };
        box.appendChild(title);
        if (!rules.length) { box.appendChild(DN.h('div', { class: 'gov-desc', text: '无启用质量规则' })); return; }
        rules.forEach(function (ru) {
          ru = ru || {};
          var line = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid var(--divider)' });
          // 未跑(passRate 为空)按待执行 warn 显示，避免与"未跑"文案矛盾地标红 err
          var tone = ru.runStatus === 'no_result' ? 'info' : (ru.passRate == null ? 'warn' : (Number(ru.passRate) >= 100 ? 'ok' : 'err'));
          line.appendChild(DN.pill(ru.severity || 'warning', ru.severity === 'error' ? 'err' : (ru.severity === 'info' ? 'info' : 'warn')));
          var ruLabel = (ru.ruleName || ru.ruleType || '规则') + (ru.dimension ? ' · ' + ru.dimension : '');
          line.appendChild(DN.h('span', { style: 'flex:1;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap', title: ruLabel, text: clip(ruLabel, 40) }));
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
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.errorBox('输入质量加载失败：' + errMsg(e), function () { inputQualityDrawer(r); })); });
  }

  // 指标预警规则管理(复用 R12 端点) —— 列表 + 新增 + 删除
  function alertRulesDrawer(r) {
    r = r || {};
    if (r.metricId == null) { DN.toast('指标缺少 ID，无法配置预警', 'err'); return; }
    var body = DN.h('div');
    var listBox = DN.h('div', { id: 'alRules' }, DN.skeleton(2));
    body.appendChild(DN.h('div', { class: 'gov-desc', text: '指标「' + (r.metricName || r.metricCode || '') + '」越界自动生成治理工单' }));
    body.appendChild(listBox);
    // 新增表单
    var opSel = selectOf(['GT', 'LT', 'GE', 'LE', 'NE', 'OUT', 'IN'], 'GT');
    var minI = DN.h('input', { class: 'dn-form-input', placeholder: '阈值/区间下界' });
    var maxI = DN.h('input', { class: 'dn-form-input', placeholder: '区间上界(OUT/IN用,可空)' });
    var sevSel = selectOf(['HIGH', 'MEDIUM', 'LOW'], 'MEDIUM');
    var secAdd = DN.formSection('新增规则');
    secAdd.add(DN.formGrid2([
      DN.field('比较符', opSel, { required: true }),
      DN.field('严重度', sevSel, { required: true })
    ]));
    secAdd.add(DN.formGrid2([
      DN.field('阈值下界', minI, { required: true }),
      DN.field('区间上界', maxI, { hint: 'OUT/IN 区间比较时填写' })
    ]));
    body.appendChild(secAdd.el);

    var dr, foot;
    var doAdd = function () {
      if (foot.ok.disabled) return;
      var op = opSel.value, minS = minI.value.trim(), maxS = maxI.value.trim();
      var needsRange = (op === 'OUT' || op === 'IN');
      // 输入校验：单值比较符必填下界且为数值；区间比较符必填上下界且下界<上界
      if (minS === '') { DN.toast(needsRange ? '区间比较符需填写下界' : '请填写阈值', 'err'); minI.focus(); return; }
      var minN = Number(minS); if (isNaN(minN)) { DN.toast('阈值下界必须是数值', 'err'); minI.focus(); return; }
      var body2 = { metricId: r.metricId, op: op, severity: sevSel.value, thresholdMin: minN };
      if (needsRange) {
        if (maxS === '') { DN.toast(op + ' 需要填写区间上界', 'err'); maxI.focus(); return; }
        var maxN = Number(maxS); if (isNaN(maxN)) { DN.toast('区间上界必须是数值', 'err'); maxI.focus(); return; }
        if (maxN <= minN) { DN.toast('区间上界必须大于下界', 'err'); maxI.focus(); return; }
        body2.thresholdMax = maxN;
      } else if (maxS !== '') {
        var maxN2 = Number(maxS); if (!isNaN(maxN2)) body2.thresholdMax = maxN2;
      }
      foot.busy();
      DN.post('/api/consumption/metric/alert-rule/save', body2).then(function () {
        foot.reset();
        DN.toast('规则已保存', 'ok'); minI.value = ''; maxI.value = ''; reload();
      }).catch(function (e) { foot.reset(); DN.toast('保存失败：' + errMsg(e), 'err'); });
    };
    foot = DN.drawerFoot({ okText: '新增规则', onOk: doAdd, onCancel: function () { dr.close(); } });
    dr = DN.drawer('指标预警规则 · ' + (r.metricName || r.metricCode || ''), body, foot.el);
    DN.enterSubmit(body, doAdd);
    function reload() {
      listBox.innerHTML = ''; listBox.appendChild(DN.skeleton(2));
      DN.get('/api/consumption/metric/' + r.metricId + '/alert-rules').then(function (rows) {
        listBox.innerHTML = '';
        if (!Array.isArray(rows) || !rows.length) { listBox.appendChild(DN.empty('暂无预警规则，可在下方为该指标设置越界阈值', 'shield')); return; }
        rows.forEach(function (rule) {
          rule = rule || {};
          var line = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid var(--divider)' });
          line.appendChild(DN.pill(rule.severity || 'MEDIUM', rule.severity === 'HIGH' ? 'err' : (rule.severity === 'LOW' ? 'info' : 'warn')));
          line.appendChild(DN.h('span', { style: 'flex:1;font-size:13px', text: (rule.op || '?') + ' ' + (rule.thresholdMin != null ? rule.thresholdMin : '') + (rule.thresholdMax != null ? ('~' + rule.thresholdMax) : '') }));
          var del = DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error)', 'data-perm': 'metrics:edit' });
          del.onclick = function () {
            if (rule.id == null) { DN.toast('规则缺少 ID，无法删除', 'err'); return; }
            if (del.dataset.busy) return;
            DN.confirm('确认删除该预警规则？', { title: '删除确认', danger: true }).then(function (ok) { // 破坏性操作二次确认
              if (!ok) return;
              setLinkBusy(del, true, '删除中…');
              DN.del('/api/consumption/metric/alert-rule/' + rule.id).then(function () { DN.toast('已删除', 'ok'); reload(); }).catch(function (e) { setLinkBusy(del, false, '删除'); DN.toast('删除失败：' + errMsg(e), 'err'); });
            });
          };
          line.appendChild(del);
          listBox.appendChild(line);
        });
      }).catch(function (e) { listBox.innerHTML = ''; listBox.appendChild(DN.errorBox('预警规则加载失败：' + errMsg(e), reload)); });
    }
    reload();
  }

  function selectOf(opts, def) {
    var s = DN.h('select', { class: 'dn-form-select' });
    opts.forEach(function (o) { var op = DN.h('option', { value: o, text: o }); if (o === def) op.selected = true; s.appendChild(op); });
    return s;
  }

  // (原 loadHeat 已并入 loadRanking 单卡双窗口: 同一真实消费口径)

  function loadZombies() {
    var box0 = document.getElementById('consZombie'); if (box0) { box0.innerHTML = ''; box0.appendChild(DN.skeleton(2)); }
    DN.get('/api/consumption/metric/zombies').then(function (rows) {
      var box = document.getElementById('consZombie'); if (!box) return; box.innerHTML = '';
      if (!Array.isArray(rows) || !rows.length) { box.appendChild(DN.empty('无僵尸指标，全部已被消费 👍', 'check')); return; }
      box.appendChild(DN.table({
        rows: rows, pageSize: 8, empty: '无僵尸指标', emptyIcon: 'check',
        searchKeys: ['metricName', 'metricCode', 'owner'], searchPlaceholder: '搜索僵尸指标',
        columns: [
          { key: 'metricName', label: '指标', render: function (r) { var t = r.metricName || r.metricCode || '-'; return DN.h('span', { title: t, text: clip(t, 28) }); } },
          { key: 'metricCode', label: '编码', render: function (r) { var t = r.metricCode || '-'; return DN.h('span', { title: t, text: clip(t, 24), style: 'display:inline-block;max-width:160px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;vertical-align:middle;' }); } },
          { key: 'category', label: '分类', render: function (r) { return r.category || '-'; } },
          { key: 'owner', label: '负责人', render: function (r) { return r.owner || '-'; } },
          { key: '_op', label: '操作', render: function (r) { return zombieOps(r); } }
        ]
      }));
    }).catch(function (e) {
      var box = document.getElementById('consZombie'); if (box) { box.innerHTML = ''; box.appendChild(DN.errorBox('僵尸指标加载失败：' + errMsg(e), loadZombies)); }
    });
  }

  // 僵尸指标行操作：立即计算（激活取值）/ 编辑（深链至指标管理）
  function zombieOps(r) {
    r = r || {};
    var wrap = DN.h('span', { style: 'display:inline-flex;gap:8px;align-items:center' });
    var calc = DN.h('a', { href: 'javascript:void(0)', text: '立即计算', 'data-perm': 'metrics:edit' });
    calc.onclick = function () {
      if (r.id == null) { DN.toast('指标缺少 ID，无法计算', 'err'); return; }
      if (calc.dataset.busy) return; setLinkBusy(calc, true, '计算中…'); // 防重复提交
      DN.post('/api/consumption/metric/' + r.id + '/calc?operator=ui').then(function (v) {
        setLinkBusy(calc, false, '立即计算');
        DN.toast((r.metricName || r.metricCode || '指标') + ' = ' + (v && v.metricValue != null ? fmtNum(v.metricValue) : '—'), v && v.runStatus === 'success' ? 'ok' : 'warn');
        loadZombies(); loadBoard(); loadOverview();
      }).catch(function (e) { setLinkBusy(calc, false, '立即计算'); DN.toast('计算失败：' + errMsg(e), 'err'); });
    };
    wrap.appendChild(calc);
    var edit = DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary)', 'data-perm': 'metrics:edit' });
    edit.onclick = function () {
      if (r.id == null) { DN.toast('指标缺少 ID，无法编辑', 'err'); return; }
      if (typeof navigateTo === 'function') navigateTo('metrics', { editId: r.id });
      else DN.toast('当前环境不支持跳转指标管理', 'warn');
    };
    wrap.appendChild(edit);
    // I-1: 僵尸处置闭环——确实不用的指标可就地停用(退出取值/新鲜度监控, 历史保留)
    var off = DN.h('a', { href: 'javascript:void(0)', text: '停用', style: 'color:var(--error)', 'data-perm': 'metrics:edit' });
    off.onclick = function () {
      if (r.id == null) { DN.toast('指标缺少 ID，无法停用', 'err'); return; }
      if (off.dataset.busy) return;
      DN.confirm('停用后将停止定时取值、退出消费看板与新鲜度监控(历史值与导出保留)。确认停用「' + (r.metricName || r.metricCode || r.id) + '」？', { title: '停用指标' }).then(function (ok) {
        if (!ok) return;
        setLinkBusy(off, true, '停用中…');
        DN.post('/api/metric/save', { id: r.id, status: 0 }).then(function () {
          DN.toast('已停用', 'ok');
          loadZombies(); loadOverview(); loadBoard();
        }).catch(function (e) { setLinkBusy(off, false, '停用'); DN.toast('停用失败：' + errMsg(e), 'err'); });
      });
    };
    wrap.appendChild(off);
    // 数据权限: 设置该指标可见范围(默认公开)
    var acl = DN.h('a', { href: 'javascript:void(0)', text: '授权', style: 'color:var(--primary)', 'data-perm': 'data:grant' });
    acl.onclick = function () {
      if (r.id == null) { DN.toast('指标缺少 ID', 'err'); return; }
      if (window.openDataAclModal) window.openDataAclModal('METRIC', r.id, r.metricName || r.metricCode || ('指标#' + r.id));
      else DN.toast('当前环境不支持数据授权', 'warn');
    };
    wrap.appendChild(acl);
    return wrap;
  }

  // 高亮闪烁定位元素：与 ensureFlashStyle 注入的 1.5s 动画时长保持一致（去重两处定位逻辑）
  function flash(el) {
    if (!el) return;
    el.classList.add('cons-flash');
    setTimeout(function () { el.classList.remove('cons-flash'); }, 1500);
  }

  // 滚动定位某张卡片（消费层概览统计卡下钻用）
  function scrollToCard(id) {
    var el = document.getElementById(id); if (!el) return;
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    flash(el);
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
        flash(target);
        return;
      }
    }
    DN.toast('指标「' + code + '」可能在其他页（已在驾驶舱搜索框输入编码可定位）', 'info');
  }

  // R22 下钻：从输入质量跳质量模块并按来源表过滤（gov-quality 收 ctx.table 过滤）
  function gotoQualityTable(db, table) {
    if (!db || !table) { DN.toast('缺少来源表信息，无法跳转质量模块', 'warn'); return; }
    if (typeof navigateTo === 'function') navigateTo('governance', { gov: 'quality', table: { db: db, table: table } });
    else DN.toast('当前环境不支持跳转质量模块', 'warn');
  }

  // —— 通用小工具（深优共用，函数声明会被提升到 IIFE 顶部，上方各处可直接调用） ——
  // 超长文本截断 + 省略号（配合 title 显示全文）；非字符串安全转换
  function clip(s, max) {
    s = (s == null ? '' : String(s)); max = max || 30;
    return s.length > max ? s.slice(0, max - 1) + '…' : s;
  }
  // III-1: 数值格式化——千分位 + 最多4位小数(0.3333333… 不再撑表)
  function fmtNum(v) {
    if (v == null || v === '') return '—';
    var n = Number(v);
    if (!isFinite(n)) return String(v);
    return n.toLocaleString('zh-CN', { maximumFractionDigits: 4 });
  }
  // 统一从异常对象提取可读信息，避免 “undefined”
  function errMsg(e) { return (e && e.message) ? e.message : '请稍后重试'; }
  // 链接型按钮的“忙碌”态：置灰 + 改文案 + dataset.busy 标志位防重复提交（<a> 的 disabled 无效）
  function setLinkBusy(el, busy, text) {
    if (!el) return;
    if (busy) {
      if (el.dataset.label == null) el.dataset.label = el.textContent;
      el.dataset.busy = '1';
      el.style.pointerEvents = 'none'; el.style.opacity = '0.6';
      if (text != null) el.textContent = text;
    } else {
      delete el.dataset.busy;
      el.style.pointerEvents = ''; el.style.opacity = '';
      el.textContent = (text != null ? text : (el.dataset.label != null ? el.dataset.label : el.textContent));
      delete el.dataset.label;
    }
  }

  // 一次性注入深链高亮动画样式（不污染全局 css 文件，模块自包含）
  function ensureFlashStyle() {
    if (document.getElementById('consFlashStyle')) return;
    var st = document.createElement('style'); st.id = 'consFlashStyle';
    st.textContent = '@keyframes consFlash{0%{background:rgba(var(--primary-rgb),.22)}100%{background:transparent}}'
      + '.cons-flash{animation:consFlash 1.5s ease-out;border-radius:var(--radius)}';
    document.head.appendChild(st);
  }
})();
