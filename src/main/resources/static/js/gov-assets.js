/* 治理模块：资产目录（M2 元数据采集 + 资产清单）—— 现代套件重绘 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  window.GOV_RENDERERS.assets = function (c) {
    // R21 跨模块深链上下文: 接收 ctx.table({db,table}) 自动打开资产详情; ctx.subjectId 按主题域过滤
    var ctx = window.__govCtx || {};
    assetState.subjectId = (ctx.subjectId != null ? ctx.subjectId : '');

    // 顶部统计区
    var statBox = DN.h('div', { id: 'assetStats' });
    statBox.appendChild(DN.skeleton(2));
    c.appendChild(statBox);

    // 资产清单卡片
    var crawlBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '采集全部(源库+数仓)',
      onclick: function () {
        if (!window.confirm('将对全部源库与数仓发起元数据采集，可能耗时较久，确认开始？')) return;
        var done = lockBtn(crawlBtn);
        DN.post('/api/metadata-center/crawl/all').then(function (msg) {
          DN.toast(msg || '采集已启动', 'success'); setTimeout(function () { done(); loadAssets(); }, 1500);
        }).catch(function (e) { done(); DN.toast(e.message, 'error'); });
      } });
    var srcSel = DN.h('select', { class: 'iw-form-select', style: 'height:34px;',
      onchange: function () { assetState.src = srcSel.value; renderAssetTable(); } });
    srcSel.innerHTML = '<option value="">全部来源</option><option value="MYSQL">MYSQL</option><option value="DORIS">DORIS</option>';

    var histBox = DN.h('div', { id: 'assetCollectHistory' });
    c.appendChild(histBox);

    var coldBox = DN.h('div', { id: 'assetColdAdvice' });
    c.appendChild(coldBox);

    var listCard = DN.card({ title: '资产清单', icon: 'list' });
    c.appendChild(listCard.el);
    var listBody = listCard.body;
    listBody.appendChild(DN.h('div', { id: 'assetTbl' }, [DN.skeleton(4)]));

    // 快捷视图下拉
    var quickSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:130px' });
    [['', '全部资产'], ['nodesc', '缺业务描述'], ['bigsize', '体量Top'], ['bigrow', '行数Top']].forEach(function (o) { quickSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
    quickSel.onchange = function () { assetState.quick = quickSel.value; renderAssetTable(); };
    quickSelEl = quickSel;
    // 视图切换：表格 / 目录树(库→表层级)
    var viewSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:100px', title: '视图' });
    [['table', '表格视图'], ['tree', '目录树'], ['treemap', '体量图']].forEach(function (o) { viewSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
    viewSel.value = assetState.view;
    viewSel.onchange = function () { assetState.view = viewSel.value; try { localStorage.setItem('gov.assetView', viewSel.value); } catch (e) {} assetTbl = null; renderAssetTable(); };
    // 把工具条（采集按钮 + 来源筛选 + 快捷视图 + 视图切换）通过 DN.table 的 toolbar 注入，先缓存引用
    assetToolbar = [crawlBtn, srcSel, quickSel, viewSel];

    loadAssets();

    // 生命周期 / 无用表 / 成本 三小节
    renderLifecycle(c);
    renderUnused(c);
    renderCost(c);

    // 深链聚焦: 若带 ctx.table 则直接打开该表资产详情抽屉
    var tb = ctx.table;
    if (tb && (tb.db || tb.table)) {
      setTimeout(function () { openAssetDetail(tb.db || '', tb.table || ''); }, 0);
    }
  };

  // ===== 资产清单：统计 + 现代表格 =====
  var assetAll = [];
  var assetState = { src: '', quick: '', subjectId: '', view: (function () { try { return localStorage.getItem('gov.assetView') || 'table'; } catch (e) { return 'table'; } })() };
  var assetTbl = null;
  var assetToolbar = null;
  var quickSelEl = null;      // 快捷视图下拉（供统计磁贴联动同步）

  var _collectLogs = [];
  function loadAssets() {
    // 统计 + 最近采集状态 + 采集历史
    DN.get('/api/metadata-center/collect-logs').then(function (logs) {
      _collectLogs = Array.isArray(logs) ? logs : [];
      lastLog = _collectLogs[0] || null;
      renderStats();
      renderCollectHistory();
    }).catch(function () { _collectLogs = []; lastLog = null; renderStats(); renderCollectHistory(); });

    // R39 主题域深链: 带 subjectId 服务端筛选(assetState.subjectId 来自主题树下钻 ctx.subjectId)
    var tablesUrl = '/api/metadata-center/tables' + (assetState.subjectId ? ('?subjectId=' + encodeURIComponent(assetState.subjectId)) : '');
    DN.get(tablesUrl).then(function (tables) {
      assetAll = Array.isArray(tables) ? tables : [];
      renderStats();
      renderAssetTable();
      renderColdAdvice();
    }).catch(function (e) {
      assetAll = [];
      renderStats();
      var b = document.getElementById('assetTbl');
      if (b) { b.innerHTML = ''; b.appendChild(DN.errorBox('资产清单加载失败: ' + (e && e.message || e), function () { loadAssets(); })); }
      var cb = document.getElementById('assetColdAdvice'); if (cb) cb.innerHTML = '';
    });
  }

  var lastLog = null;

  function renderStats() {
    var box = document.getElementById('assetStats');
    if (!box) return;
    if (!Array.isArray(assetAll)) assetAll = [];
    var tableCount = assetAll.length;
    var dbSet = {}, colCount = 0, totalBytes = 0;
    assetAll.forEach(function (t) {
      if (t.databaseName) dbSet[t.databaseName] = 1;
      colCount += Number(t.columnCount) || 0;
      totalBytes += Number(t.sizeBytes) || 0;
    });
    var noDescN = assetAll.filter(function (t) { return !t.tableComment; }).length;
    var statusText = '尚无记录', statusTone = 'muted', statusSub = '';
    if (lastLog) {
      var st = (lastLog.status || '').toUpperCase();
      statusText = lastLog.status || '-';
      statusTone = st.indexOf('SUCC') >= 0 || st === 'OK' ? 'ok' : (st.indexOf('FAIL') >= 0 || st.indexOf('ERR') >= 0 ? 'err' : 'info');
      statusSub = (lastLog.dbType || '') + ' · ' + (lastLog.startedAt || '');
    }
    box.innerHTML = '';
    box.appendChild(DN.statRow([
      { icon: 'list', label: '表数', value: fmtInt(tableCount) },
      { icon: 'layers', label: '字段数', value: fmtInt(colCount) },
      { icon: 'db', label: '库数', value: fmtInt(Object.keys(dbSet).length) },
      { icon: 'chart', label: '总体量', value: DN.fmtBytes(totalBytes) },
      { icon: 'alert', label: '缺业务描述', value: fmtInt(noDescN), tone: noDescN ? 'warn' : 'ok', title: '点击只看缺业务描述的表', onClick: function () {
          assetState.quick = assetState.quick === 'nodesc' ? '' : 'nodesc';
          if (quickSelEl) quickSelEl.value = assetState.quick;
          renderAssetTable();
          DN.toast(assetState.quick === 'nodesc' ? '仅看缺业务描述' : '显示全部', 'info');
        } },
      { icon: 'clock', label: '最近采集', value: statusText, sub: statusSub, tone: statusTone }
    ]));
  }

  // 采集历史时间线(大功能): 近若干次元数据采集记录
  function renderCollectHistory() {
    var box = document.getElementById('assetCollectHistory'); if (!box) return;
    var logs = _collectLogs || [];
    box.innerHTML = '';
    if (!logs.length) return;
    var card = DN.card({ title: '采集历史（近 ' + Math.min(logs.length, 10) + ' 次）', icon: 'clock' });
    var wrap = DN.h('div', { style: 'position:relative;padding-left:16px' });
    wrap.appendChild(DN.h('div', { style: 'position:absolute;left:4px;top:4px;bottom:4px;width:2px;background:var(--divider,#eee)' }));
    logs.slice(0, 10).forEach(function (lg) {
      var st = (lg.status || '').toUpperCase();
      var color = (st.indexOf('SUCC') >= 0 || st === 'OK') ? '#52c41a' : (st.indexOf('FAIL') >= 0 || st.indexOf('ERR') >= 0) ? '#ff4d4f' : '#1890ff';
      var row = DN.h('div', { style: 'position:relative;padding:0 0 12px 14px;cursor:pointer', title: '点击查看本次采集明细' });
      row.innerHTML = '<div style="position:absolute;left:-16px;top:2px;width:9px;height:9px;border-radius:50%;background:' + color + ';border:2px solid var(--bg-card,#fff);box-shadow:0 0 0 1px ' + color + '"></div>'
        + '<div style="font-size:12px"><b style="color:' + color + '">' + DN.esc(lg.status || '-') + '</b> '
        + '<span style="color:var(--text-muted)">' + DN.esc(lg.dbType || '') + (lg.tableCount != null ? ' · ' + lg.tableCount + ' 表' : '') + '</span></div>'
        + '<div style="font-size:11px;color:var(--text-muted);margin-top:2px" title="' + DN.esc((lg.startedAt || lg.createdAt || '').toString().replace('T', ' ').slice(0, 19)) + '">' + DN.esc(DN.fmtAgo(lg.startedAt || lg.createdAt)) + (lg.message ? ' · ' + DN.esc(String(lg.message).slice(0, 60)) : '') + '</div>';
      row.addEventListener('click', function () { openCollectLogDetail(lg); });
      wrap.appendChild(row);
    });
    card.body.appendChild(wrap);
    box.appendChild(card.el);
  }

  // 采集历史下钻: 单次采集明细抽屉(无专用明细端点, 展示该次日志已有字段; 若带库名可一键跳资产清单按库筛查)
  function openCollectLogDetail(lg) {
    lg = lg || {};
    var body = DN.h('div', {});
    var title = '采集明细 · ' + (lg.dbType || '-') + ((lg.startedAt || lg.createdAt) ? ' · ' + String(lg.startedAt || lg.createdAt).replace('T', ' ').slice(0, 19) : '');
    var dr = DN.drawer(title, body);
    var st = (lg.status || '').toUpperCase();
    var tone = (st.indexOf('SUCC') >= 0 || st === 'OK') ? 'ok' : (st.indexOf('FAIL') >= 0 || st.indexOf('ERR') >= 0) ? 'err' : 'info';
    body.appendChild(DN.statRow([
      { icon: 'clock', label: '状态', value: lg.status || '-', tone: tone },
      { icon: 'db', label: '来源', value: lg.dbType || '-' },
      { icon: 'list', label: '采集表数', value: lg.tableCount != null ? fmtInt(lg.tableCount) : '-' }
    ]));
    // 该次日志全部已有字段, 逐项罗列(不臆造端点)
    var rows = Object.keys(lg).map(function (k) { return { _k: k, _v: lg[k] }; });
    body.appendChild(DN.table({
      columns: [
        { key: '_k', label: '字段' },
        { key: '_v', label: '值', render: function (r) {
            if (r._v == null) return '-';
            var s = (typeof r._v === 'object') ? (function () { try { return JSON.stringify(r._v); } catch (e) { return String(r._v); } })() : String(r._v);
            return clip(s, 120);
          } }
      ],
      rows: rows, pageSize: 50, search: false, empty: '该次采集无更多字段'
    }));
    if (lg.dbName || lg.databaseName) {
      var db = lg.dbName || lg.databaseName;
      body.appendChild(DN.h('div', { style: 'margin-top:10px' }, [
        DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '在资产清单按「' + db + '」筛查',
          onclick: function () {
            assetState.src = lg.dbType || assetState.src;
            assetTbl = null; renderAssetTable();
            dr.close();
            var b = document.getElementById('assetTbl'); if (b) { try { b.scrollIntoView({ behavior: 'smooth', block: 'start' }); } catch (e) {} }
            DN.toast('已按来源筛查资产清单, 可搜索库 ' + db, 'info');
          } })
      ]));
    }
  }

  // ===== 冷数据治理建议(大功能): 体量大但疑似冷(行数为0/空)的表, 给出归档/降冷建议 =====
  // 纯前端计算, 仅复用已加载的 assetAll, 不调用新 API, 不写后端, 仅展示建议
  function renderColdAdvice() {
    var box = document.getElementById('assetColdAdvice'); if (!box) return;
    box.innerHTML = '';
    // 筛选: 有体量(sizeBytes>0) 且 疑似冷(rowCount 为 0 或 空/未知)
    var cold = (assetAll || []).filter(function (t) {
      var sz = Number(t.sizeBytes) || 0;
      if (sz <= 0) return false;
      var rc = t.rowCount;
      return rc == null || (Number(rc) || 0) === 0;
    });
    if (!cold.length) return; // 无冷数据则不渲染该区块, 避免占位空卡片
    // 按体量降序取 Top20
    cold = cold.slice().sort(function (a, b) { return (Number(b.sizeBytes) || 0) - (Number(a.sizeBytes) || 0); }).slice(0, 20);
    var coldBytes = cold.reduce(function (s, t) { return s + (Number(t.sizeBytes) || 0); }, 0);
    var totalBytes = (assetAll || []).reduce(function (s, t) { return s + (Number(t.sizeBytes) || 0); }, 0) || 1;
    var ratio = Math.round(coldBytes * 100 / totalBytes);

    var card = DN.card({ title: '冷数据治理建议（体量大但疑似冷的表 · 归档/降冷参考）', icon: 'alert' });
    card.body.appendChild(DN.statRow([
      { icon: 'alert', label: '疑似冷表', value: fmtInt(cold.length), tone: cold.length ? 'warn' : 'ok', sub: '行数为0/未知' },
      { icon: 'db', label: '可治理体量', value: DN.fmtBytes(coldBytes), tone: 'info' },
      { icon: 'chart', label: '占总体量', value: ratio + '%', tone: ratio >= 30 ? 'warn' : 'info' }
    ]));
    // 建议生成: 体量越大优先级越高
    var maxCold = cold.reduce(function (m, t) { return Math.max(m, Number(t.sizeBytes) || 0); }, 1);
    function adviceOf(t) {
      var sz = Number(t.sizeBytes) || 0;
      var r = sz / maxCold;
      if (r > 0.5) return { text: '建议归档至冷存储', tone: 'err' };
      if (r > 0.2) return { text: '建议降冷(HOT_COLD)', tone: 'warn' };
      return { text: '建议核实后清理', tone: 'info' };
    }
    card.body.appendChild(DN.table({
      columns: [
        { key: 'dbType', label: '来源', exportValue: function (t) { return t.dbType || '-'; }, render: function (t) { var cm = { MYSQL: 'ok', DORIS: 'info', HIVE: 'warn', POSTGRESQL: 'info', ORACLE: 'err', SQLSERVER: 'warn' }; return DN.pill(t.dbType || '-', cm[t.dbType] || 'muted'); } },
        { key: 'databaseName', label: '库', copyable: true },
        { key: 'tableName', label: '表', copyable: true },
        { key: 'rowCount', label: '行数', align: 'right', exportValue: function (t) { return t.rowCount == null ? '未知' : (Number(t.rowCount) || 0); }, render: function (t) { return t.rowCount == null ? DN.pill('未知', 'muted') : DN.pill(fmtInt(t.rowCount), 'warn'); } },
        { key: 'sizeBytes', label: '体量', align: 'right', sortable: true, exportValue: function (t) { return Number(t.sizeBytes) || 0; }, render: function (t) { return DN.fmtBytes(t.sizeBytes); } },
        { key: '_advice', label: '治理建议', exportValue: function (t) { return adviceOf(t).text; }, render: function (t) { var a = adviceOf(t); return DN.pill(a.text, a.tone); } },
        { key: '_op', label: '操作', exportValue: function () { return ''; }, render: function (t) {
            var detail = DN.h('a', { class: 'btn', href: 'javascript:void(0)', title: '查看该表字段/画像/血缘',
              text: '资产详情', onclick: function () { openAssetDetail(t.databaseName || '', t.tableName || ''); } });
            var policy = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '建生命周期策略', style: 'margin-left:6px;', title: '为该表配置 TTL/降冷/归档以治理冷数据',
              onclick: function () { gotoLifecycle(t.databaseName || '', t.tableName || ''); } });
            return DN.h('span', {}, [detail, policy]);
          } }
      ],
      rows: cold,
      pageSize: 10,
      searchKeys: ['databaseName', 'tableName'],
      searchPlaceholder: '搜索库 / 表',
      exportName: '冷数据治理建议',
      empty: '暂无疑似冷数据，可先到上方“采集全部”补全行数/体量后再分析',
      emptyIcon: 'inbox'
    }));
    card.body.appendChild(DN.h('div', { style: 'font-size:11px;color:var(--text-muted);margin-top:8px',
      text: '判定: 占用体量 > 0 且 行数为 0 或未知(疑似空表/废弃表)。建议按体量优先归档至冷存储或配置 HOT_COLD 降冷策略, 点击行末「详情」可查看该表字段/画像/血缘后再决策, 此处仅为治理参考, 不执行任何操作。' }));
    box.appendChild(card.el);
  }

  var _maxRow = 0, _maxSize = 0;
  // 数值分档渐进色条：按占最大值比例渲染右对齐迷你条 + 数值
  function magBar(v, max, label) {
    var pct = max > 0 ? Math.max(2, Math.round(v * 100 / max)) : 0;
    var color = pct >= 75 ? '#ff4d4f' : pct >= 50 ? '#fa8c16' : pct >= 25 ? '#faad14' : '#52c41a';
    return '<div style="display:flex;align-items:center;gap:6px;justify-content:flex-end;">'
      + '<span style="font-variant-numeric:tabular-nums;">' + label + '</span>'
      + '<span style="display:inline-block;width:48px;height:6px;border-radius:3px;background:var(--bg-hover,#f0f1f3);overflow:hidden;"><span style="display:block;height:100%;width:' + pct + '%;background:' + color + ';"></span></span></div>';
  }
  function renderAssetTable() {
    var box = document.getElementById('assetTbl');
    if (!box) return;
    if (!Array.isArray(assetAll)) assetAll = [];
    _maxRow = assetAll.reduce(function (m, t) { return Math.max(m, Number(t.rowCount) || 0); }, 0);
    _maxSize = assetAll.reduce(function (m, t) { return Math.max(m, Number(t.sizeBytes) || 0); }, 0);
    var rows = assetAll.filter(function (t) {
      if (assetState.src && (t.dbType || '') !== assetState.src) return false;
      if (assetState.quick === 'nodesc' && t.tableComment) return false;
      // 深链主题域过滤(仅当资产数据带主题域字段时生效, 否则忽略)
      if (assetState.subjectId !== '' && assetState.subjectId != null) {
        var sid = t.subjectId != null ? t.subjectId : t.subjectArea;
        if (sid != null && String(sid) !== String(assetState.subjectId)) return false;
      }
      return true;
    });
    if (assetState.view === 'tree') { renderAssetTree(rows); return; }
    if (assetState.view === 'treemap') { renderAssetTreemap(rows); return; }
    if (assetState.quick === 'bigsize') rows = rows.slice().sort(function (a, b) { return (b.sizeBytes || 0) - (a.sizeBytes || 0); });
    else if (assetState.quick === 'bigrow') rows = rows.slice().sort(function (a, b) { return (b.rowCount || 0) - (a.rowCount || 0); });
    if (assetTbl) { assetTbl.reload(rows); return; }
    assetTbl = DN.table({
      columns: [
        { key: 'dbType', label: '来源', render: function (r) { var cm = { MYSQL: 'ok', DORIS: 'info', HIVE: 'warn', POSTGRESQL: 'info', ORACLE: 'err', SQLSERVER: 'warn' }; return DN.pill(r.dbType || '-', cm[r.dbType] || 'muted'); } },
        { key: 'databaseName', label: '库', copyable: true },
        { key: 'tableName', label: '表', copyable: true },
        { key: 'tableComment', label: '业务描述', render: function (r) { return r.tableComment ? clip(r.tableComment, 60) : '-'; } },
        { key: 'rowCount', label: '行数', align: 'right', sortable: true, html: true, render: function (r) { return r.rowCount == null ? '-' : magBar(Number(r.rowCount) || 0, _maxRow, fmtInt(r.rowCount)); } },
        { key: 'sizeBytes', label: '体量', align: 'right', sortable: true, html: true, render: function (r) { return r.sizeBytes == null ? '-' : magBar(Number(r.sizeBytes) || 0, _maxSize, DN.fmtBytes(r.sizeBytes)); } },
        { key: '_op', label: '操作', render: function (r) {
            return DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '详情',
              onclick: function () { openAssetDetail(r.databaseName || '', r.tableName || ''); } });
          } }
      ],
      rows: rows,
      pageSize: 20,
      searchKeys: ['databaseName', 'tableName', 'tableComment'],
      searchPlaceholder: '搜索库 / 表 / 描述',
      toolbar: assetToolbar,
      exportName: '资产清单',
      empty: '暂无资产，点击“采集全部”按钮',
      emptyIcon: 'inbox'
    });
    box.innerHTML = '';
    box.appendChild(assetTbl);
  }

  // ===== 资产体量矩形图(大功能): 库分带, 表按体量占比着色分块 =====
  function renderAssetTreemap(rows) {
    var box = document.getElementById('assetTbl'); if (!box) return;
    rows = Array.isArray(rows) ? rows : [];
    var bar = DN.h('div', { style: 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:10px' });
    (assetToolbar || []).forEach(function (el) { bar.appendChild(el); });
    box.innerHTML = ''; box.appendChild(bar);
    var withSize = rows.filter(function (t) { return (Number(t.sizeBytes) || 0) > 0; });
    if (!withSize.length) { box.appendChild(DN.empty('暂无体量数据(先采集), 或所有表体量为0', 'chart')); return; }
    // 按库分组并按总体量降序
    var byDb = {};
    withSize.forEach(function (t) { var k = t.databaseName || '(未命名库)'; (byDb[k] = byDb[k] || []).push(t); });
    var groups = Object.keys(byDb).map(function (k) {
      var ts = byDb[k]; var sum = ts.reduce(function (s, t) { return s + (Number(t.sizeBytes) || 0); }, 0);
      return { db: k, tables: ts, sum: sum };
    }).sort(function (a, b) { return b.sum - a.sum; });
    var grand = groups.reduce(function (s, g) { return s + g.sum; }, 0) || 1;
    var maxT = withSize.reduce(function (m, t) { return Math.max(m, Number(t.sizeBytes) || 0); }, 1);
    var colorOf = function (v) { var r = v / maxT; return r > 0.66 ? '#1677ff' : r > 0.33 ? '#4d94ff' : r > 0.1 ? '#85b8ff' : '#bcd7ff'; };
    var wrap = DN.h('div', { style: 'display:flex;flex-direction:column;gap:8px' });
    groups.forEach(function (g) {
      var bandH = Math.max(28, Math.round(g.sum / grand * 260)); // 带高按库占比
      var head = DN.h('div', { style: 'font-size:12px;color:var(--text-regular);margin-bottom:2px', html: '<b>' + DN.esc(g.db) + '</b> <span style="color:var(--text-muted)">' + g.tables.length + ' 表 · ' + DN.fmtBytes(g.sum) + '</span>' });
      var band = DN.h('div', { style: 'display:flex;width:100%;height:' + bandH + 'px;gap:2px;border-radius:6px;overflow:hidden' });
      g.tables.slice().sort(function (a, b) { return (b.sizeBytes || 0) - (a.sizeBytes || 0); }).forEach(function (t) {
        var w = (Number(t.sizeBytes) || 0) / (g.sum || 1) * 100;
        var cell = DN.h('div', { title: t.tableName + ' · ' + DN.fmtBytes(t.sizeBytes) + (t.rowCount != null ? ' · ' + fmtInt(t.rowCount) + ' 行' : ''),
          'aria-label': t.tableName + ' 体量 ' + DN.fmtBytes(t.sizeBytes) + ',点击查看详情', role: 'button',
          style: 'flex:0 0 ' + w.toFixed(2) + '%;background:' + colorOf(Number(t.sizeBytes) || 0) + ';display:flex;align-items:center;justify-content:center;color:#fff;font-size:10px;cursor:pointer;overflow:hidden;white-space:nowrap;' });
        cell.textContent = w > 8 ? t.tableName : '';
        cell.addEventListener('click', function () { openAssetDetail(t.databaseName || '', t.tableName || ''); });
        band.appendChild(cell);
      });
      var gb = DN.h('div', {}); gb.appendChild(head); gb.appendChild(band); wrap.appendChild(gb);
    });
    box.appendChild(wrap);
    box.appendChild(DN.h('div', { style: 'font-size:11px;color:var(--text-muted);margin-top:8px', text: '带高=库占总体量比例, 块宽=表占库体量比例, 颜色深浅=表体量; 点击块查看详情。' }));
  }

  // ===== 资产目录树视图(大功能): 库→表 层级导航 =====
  var _treeOpen = {}; // db -> bool 展开态
  var TREE_ROW_CAP = 200;     // 单库展开时最多渲染的表行数, 避免超长库一次性同步渲染卡顿
  function renderAssetTree(rows) {
    var box = document.getElementById('assetTbl'); if (!box) return;
    rows = Array.isArray(rows) ? rows : [];
    // toolbar 还原(树视图下 DN.table 不渲染, 手工补工具条)
    var bar = DN.h('div', { class: 'gov-tbl-tools', style: 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:10px' });
    (assetToolbar || []).forEach(function (el) { bar.appendChild(el); });
    // 按 库 分组
    var byDb = {};
    rows.forEach(function (t) { var k = (t.dbType || '') + '|' + (t.databaseName || '(未命名库)'); (byDb[k] = byDb[k] || []).push(t); });
    var keys = Object.keys(byDb).sort();
    box.innerHTML = '';
    box.appendChild(bar);
    if (!keys.length) { box.appendChild(DN.empty('暂无资产', 'inbox')); return; }
    var wrap = DN.h('div', { style: 'border:1px solid var(--border,#eceef1);border-radius:10px;overflow:hidden' });
    var manyDb = keys.length > 12;  // 库很多时默认折叠, 避免一次性展开全部库造成卡顿
    keys.forEach(function (k) {
      var parts = k.split('|'), dbType = parts[0], dbName = parts[1];
      var tables = byDb[k];
      var open = _treeOpen[k] != null ? _treeOpen[k] !== false : !manyDb; // 默认: 库少展开, 库多折叠; 用户手动态优先
      var totalBytes = tables.reduce(function (s, t) { return s + (Number(t.sizeBytes) || 0); }, 0);
      var head = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:9px 12px;cursor:pointer;background:var(--bg-hover,#f6f7f9);border-bottom:1px solid var(--divider,#eee)' });
      head.innerHTML = '<span style="font-size:11px;color:var(--text-muted);width:12px;display:inline-block;transition:transform .15s;transform:rotate(' + (open ? '90' : '0') + 'deg)">▶</span>'
        + '<span style="font-weight:600;font-size:13px">' + DN.esc(dbName) + '</span>'
        + '<span class="gov-pill" style="font-size:10px;padding:0 6px;border-radius:8px;background:#e6f0ff;color:#1890ff">' + DN.esc(dbType || '-') + '</span>'
        + '<span style="font-size:11px;color:var(--text-muted)">' + tables.length + ' 表 · ' + DN.fmtBytes(totalBytes) + '</span>';
      var bodyWrap = DN.h('div', { style: 'display:' + (open ? 'block' : 'none') });
      // 仅在展开时才构建该库的行(节省折叠库的渲染开销)
      if (open) {
        tables.sort(function (a, b) { return (a.tableName || '').localeCompare(b.tableName || ''); });
        var shown = tables.length > TREE_ROW_CAP ? tables.slice(0, TREE_ROW_CAP) : tables;
        shown.forEach(function (t) {
          var row = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:7px 12px 7px 34px;border-bottom:1px solid var(--divider,#f3f4f6);cursor:pointer;font-size:12.5px' });
          row.onmouseenter = function () { row.style.background = 'var(--bg-hover,#f6f7f9)'; };
          row.onmouseleave = function () { row.style.background = ''; };
          row.innerHTML = '<span style="color:#1890ff">▦</span>'
            + '<span style="font-weight:500">' + DN.esc(t.tableName || '-') + '</span>'
            + '<span style="color:var(--text-muted);flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis" title="' + DN.esc(t.tableComment || '') + '">' + DN.esc(t.tableComment || '') + '</span>'
            + '<span style="color:var(--text-muted);font-size:11px;white-space:nowrap">' + (t.rowCount == null ? '' : fmtInt(t.rowCount) + ' 行 · ') + DN.fmtBytes(t.sizeBytes) + '</span>';
          row.addEventListener('click', function () { openAssetDetail(t.databaseName || '', t.tableName || ''); });
          bodyWrap.appendChild(row);
        });
        if (tables.length > TREE_ROW_CAP) {
          bodyWrap.appendChild(DN.h('div', { style: 'padding:7px 12px 7px 34px;font-size:11px;color:var(--text-muted)',
            text: '仅显示前 ' + TREE_ROW_CAP + ' 张（共 ' + tables.length + ' 张），如需精确查找请切换「表格视图」搜索。' }));
        }
      }
      head.addEventListener('click', function () {
        _treeOpen[k] = !open;
        renderAssetTree(rows);
      });
      wrap.appendChild(head); wrap.appendChild(bodyWrap);
    });
    box.appendChild(wrap);
    box.appendChild(DN.h('div', { style: 'font-size:11px;color:var(--text-muted);margin-top:6px', text: '共 ' + keys.length + ' 个库 · ' + rows.length + ' 张表 · 点击表名查看详情' }));
  }

  // ===== 资产详情抽屉（字段元数据 + Profiler + 术语表 + 血缘） =====
  function openAssetDetail(db, table) {
    var body = DN.h('div', {});
    var dr = DN.drawer(db + '.' + table, body);
    renderGovLinks(body, db, table);   // R22 治理联动: 资产即枢纽, 交叉跳转质量/工单/血缘/分级/消费
    renderSubjectRow(body, db, table); // R35 所属主题域: 概要展示当前主题域并可设置/取消
    renderColumns(body, db, table);
    renderProfileSection(body, db, table);
    renderGlossarySection(body);
    renderLineageSection(body, db, table);
  }

  // R22 治理联动按钮排: 以当前资产(db.table)为枢纽, 跳转其它治理子模块并携带深链 ctx
  function renderGovLinks(panel, db, table) {
    if (!window.navigateTo) return;   // 独立页面(非工作台)无路由, 不渲染
    var fqn = (db || '') + '.' + (table || '');
    var links = [
      { text: '质量规则', tone: 'btn-primary', go: function () { navigateTo('governance', { gov: 'quality', table: { db: db, table: table } }); } },
      { text: '相关工单', go: function () { navigateTo('governance', { gov: 'health', issueFilter: { relTable: fqn } }); } },
      { text: '血缘图谱', go: function () { navigateTo('governance', { gov: 'lineage', table: { db: db, table: table } }); } },
      { text: '敏感分级', go: function () { navigateTo('governance', { gov: 'classification', table: { db: db, table: table } }); } },
      { text: '消费指标', go: function () { navigateTo('governance', { gov: 'consumption', table: { db: db, table: table } }); } }
    ];
    var bar = DN.h('div', { style: 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding:10px 12px;margin:0 0 4px;background:var(--bg-hover,#f6f7f9);border:1px solid var(--border,#eceef1);border-radius:8px' });
    bar.appendChild(DN.h('span', { text: '治理联动', style: 'font-size:12px;font-weight:600;color:var(--text-muted,#86909c);margin-right:2px' }));
    links.forEach(function (l) {
      bar.appendChild(DN.h('a', { class: 'btn ' + (l.tone || ''), href: 'javascript:void(0)', text: l.text, onclick: l.go }));
    });
    panel.appendChild(bar);
  }

  // R35 所属主题域: 概要区展示当前主题域名(或未设置)并可设置/取消, 独立 try/catch 不影响抽屉其它内容
  function renderSubjectRow(panel, db, table) {
    try {
      var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
      var subjects = [];        // [{id,name}]
      var curId = null;         // 当前主题域 id(数字或 null)
      var row = DN.h('div', { style: 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding:10px 12px;margin:0 0 4px;background:var(--bg-hover,#f6f7f9);border:1px solid var(--border,#eceef1);border-radius:8px' });
      row.appendChild(DN.h('span', { text: '所属主题域', style: 'font-size:12px;font-weight:600;color:var(--text-muted,#86909c);margin-right:2px' }));
      var nameSpan = DN.h('span', { text: '加载中…', style: 'font-size:13px;color:var(--text-primary,#1f2329)' });
      row.appendChild(nameSpan);
      panel.appendChild(row);

      function nameOf(id) {
        if (id == null) return null;
        for (var i = 0; i < subjects.length; i++) { if (String(subjects[i].id) === String(id)) return subjects[i].name; }
        return null;
      }
      function refresh() {
        var nm = nameOf(curId);
        nameSpan.textContent = nm || (curId == null ? '未设置' : '主题域#' + curId);
        nameSpan.style.color = nm ? 'var(--text-primary,#1f2329)' : 'var(--text-muted,#86909c)';
      }

      // 内联设置: 主题域下拉(含「未设置」空选项) + 保存/取消, 保存成功后 toast 并刷新本行
      function startEdit() {
        var sel = DN.h('select', { class: 'iw-form-select', style: 'min-width:160px;' });
        sel.innerHTML = '<option value="">（未设置 / 取消归属）</option>';
        subjects.forEach(function (s) {
          var o = document.createElement('option');
          o.value = String(s.id); o.textContent = s.name; o.selected = (String(s.id) === String(curId));
          sel.appendChild(o);
        });
        var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '保存' });
        var cancel = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '取消', onclick: function () { renderRow(); } });
        save.onclick = function () {
          var newId = sel.value === '' ? null : Number(sel.value);
          save.classList.add('is-loading');
          DN.post('/api/metadata/table/set-subject', { db: db, table: table, subjectId: newId }).then(function () {
            curId = newId;
            DN.toast('主题域已更新', 'success');
            renderRow();
          }).catch(function (e) {
            save.classList.remove('is-loading');
            DN.toast('设置失败: ' + (e && e.message || e), 'error');
          });
        };
        row.innerHTML = '';
        row.appendChild(DN.h('span', { text: '所属主题域', style: 'font-size:12px;font-weight:600;color:var(--text-muted,#86909c);margin-right:2px' }));
        row.appendChild(sel);
        row.appendChild(save);
        row.appendChild(cancel);
      }
      // 还原为只读行(主题域名 + 设置按钮)
      function renderRow() {
        row.innerHTML = '';
        row.appendChild(DN.h('span', { text: '所属主题域', style: 'font-size:12px;font-weight:600;color:var(--text-muted,#86909c);margin-right:2px' }));
        row.appendChild(nameSpan);
        row.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '设置', onclick: startEdit }));
        refresh();
      }

      Promise.all([
        DN.get('/api/metadata/table/subject' + qs).catch(function () { return null; }),
        DN.get('/api/gov/subject').catch(function () { return []; })
      ]).then(function (res) {
        var cur = res[0]; curId = cur && cur.subjectId != null ? cur.subjectId : null;
        subjects = Array.isArray(res[1]) ? res[1] : [];
        renderRow();
      }).catch(function () {
        nameSpan.textContent = '加载失败';
      });
    } catch (e) { /* 主题域行失败不影响抽屉其它内容 */ }
  }

  function subTitle(text) {
    return DN.h('div', { text: text, style: 'font-weight:600;font-size:13px;color:var(--text-primary,#1f2329);margin:18px 0 8px' });
  }

  // 字段级元数据表（含密级/敏感 pill）
  function renderColumns(panel, db, table) {
    panel.appendChild(subTitle('字段级元数据'));
    var box = DN.h('div', {}, [DN.skeleton(3)]);
    panel.appendChild(box);
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
    (function load() {
    box.innerHTML = ''; box.appendChild(DN.skeleton(3));
    DN.get('/api/gov/asset/detail' + qs).then(function (d) {
      var cols = (d && Array.isArray(d.columns)) ? d.columns : [];
      box.innerHTML = '';
      if (!cols.length) { box.appendChild(DN.empty('该表暂无字段元数据，可点上方「采集全部」重新采集该库后再查看', 'list')); return; }
      // 敏感/密级摘要徽标条
      var secN = cols.filter(function (c) { return c.securityLevel; }).length;
      var senN = cols.filter(function (c) { return c.sensitiveType; }).length;
      var noDescN = cols.filter(function (c) { return !c.businessDesc; }).length;
      box.appendChild(DN.statRow([
        { icon: 'list', label: '字段数', value: cols.length },
        { icon: 'lock', label: '已分级', value: secN, tone: secN ? 'warn' : 'ok' },
        { icon: 'tag', label: '敏感字段', value: senN, tone: senN ? 'err' : 'ok' },
        { icon: 'doc', label: '缺注释', value: noDescN, tone: noDescN ? 'warn' : 'ok' }
      ]));
      box.appendChild(DN.table({
        columns: [
          { key: 'columnName', label: '列名', sortable: true },
          { key: 'dataType', label: '类型', sortable: true },
          { key: 'columnKey', label: '键', render: function (r) { return r.columnKey || '-'; } },
          { key: 'isNullable', label: '可空', render: function (r) { return r.isNullable || '-'; } },
          { key: 'businessDesc', label: '注释', render: function (r) { return r.businessDesc ? clip(r.businessDesc, 60) : '-'; } },
          { key: 'securityLevel', label: '密级', sortable: true, render: function (r) { return r.securityLevel ? DN.pill(r.securityLevel, 'err') : '-'; } },
          { key: 'sensitiveType', label: '敏感', sortable: true, render: function (r) { return r.sensitiveType ? DN.pill(r.sensitiveType, 'warn') : '-'; } }
        ],
        rows: cols,
        pageSize: 50,
        searchKeys: ['columnName', 'dataType', 'businessDesc', 'sensitiveType'],
        searchPlaceholder: '搜索列名/类型/注释',
        exportName: db + '_' + table + '_columns',
        empty: '无字段元数据（请先采集）'
      }));
    }).catch(function (e) {
      box.innerHTML = ''; box.appendChild(DN.errorBox('加载失败: ' + e.message, function () { load(); }));
    });
    })();
  }

  // Profiler 探查（下推数仓）
  function renderProfileSection(panel, db, table) {
    panel.appendChild(subTitle('Profiler 探查'));
    var resultBox = DN.h('div', { class: 'gov-desc', style: 'margin-bottom:0' });
    var btn = DN.h('a', { class: 'btn', href: 'javascript:void(0)', style: 'margin-bottom:8px;', text: '执行探查（采样数仓）',
      onclick: function () {
        var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
        var done = lockBtn(btn);
        (function load() {
        resultBox.innerHTML = ''; resultBox.appendChild(DN.skeleton(3));
        DN.get('/api/gov/asset/profile' + qs).then(function (p) {
          done();
          p = p || {};
          var fields = Array.isArray(p.fields) ? p.fields : [];
          resultBox.innerHTML = '';
          resultBox.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-bottom:8px',
            text: '总行数: ' + (p.totalRows == null ? '-' : fmtInt(p.totalRows)) + '，字段数: ' + (p.columnCount == null ? '-' : p.columnCount) +
              '，已探查 ' + (p.profiledCount == null ? '-' : p.profiledCount) + ' 个' }));
          resultBox.appendChild(DN.table({
            columns: [
              { key: 'name', label: '字段' },
              { key: 'nullRate', label: '空值率', align: 'right', render: function (f) { return f.nullRate == null ? (f.error ? '错误' : '-') : f.nullRate; } },
              { key: 'distinctCount', label: 'distinct', align: 'right', render: function (f) { return f.distinctCount == null ? '-' : fmtInt(f.distinctCount); } }
            ],
            rows: fields, pageSize: 50, search: false, empty: '无探查结果（该表可能为空或不可采样）'
          }));
        }).catch(function (e) { done(); resultBox.innerHTML = ''; resultBox.appendChild(DN.errorBox('探查失败: ' + (e && e.message || e), function () { load(); })); });
        })();
      } });
    panel.appendChild(btn);
    panel.appendChild(resultBox);
  }

  // 业务术语表（查看 + 维护）
  function renderGlossarySection(panel) {
    panel.appendChild(subTitle('业务术语表'));
    var form = DN.h('div', { class: 'gov-form' });
    var term = DN.h('input', { class: 'iw-form-input', placeholder: '如：日活' });
    var cat = DN.h('input', { class: 'iw-form-input', placeholder: '如：指标' });
    var def = DN.h('input', { class: 'iw-form-input', placeholder: '术语定义' });
    function row(labelText, input) {
      return DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: labelText, style: 'display:block;margin-bottom:4px;font-size:12px;color:var(--text-muted,#86909c)' }), input]);
    }
    var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增术语',
      onclick: function () {
        var tv = term.value.trim(), cv = cat.value.trim(), dv = def.value.trim();
        if (!tv) { DN.toast('术语名必填', 'error'); term.focus(); return; }
        if (tv.length > 50) { DN.toast('术语名过长(请控制在 50 字以内)', 'error'); term.focus(); return; }
        if (cv.length > 30) { DN.toast('分类过长(请控制在 30 字以内)', 'error'); cat.focus(); return; }
        if (dv.length > 500) { DN.toast('定义过长(请控制在 500 字以内)', 'error'); def.focus(); return; }
        var done = lockBtn(addBtn);
        DN.post('/api/gov/asset/glossary', { term: tv, category: cv, definition: dv })
          .then(function () { done(); DN.toast('已新增', 'success'); term.value = ''; cat.value = ''; def.value = ''; loadGlossary(listBox); })
          .catch(function (e) { done(); DN.toast(e.message, 'error'); });
      } });
    form.appendChild(row('术语', term));
    form.appendChild(row('分类', cat));
    form.appendChild(row('定义', def));
    form.appendChild(DN.h('div', { style: 'margin-top:6px;' }, [addBtn]));
    DN.enterSubmit(form);
    panel.appendChild(form);
    var listBox = DN.h('div', {}, [DN.skeleton(2)]);
    panel.appendChild(listBox);
    loadGlossary(listBox);
  }

  function loadGlossary(box) {
    if (!box) return;
    DN.get('/api/gov/asset/glossary').then(function (list) {
      box.innerHTML = '';
      box.appendChild(DN.table({
        columns: [
          { key: 'term', label: '术语', render: function (g) { return clip(g.term, 40); } },
          { key: 'category', label: '分类', render: function (g) { return g.category || '-'; } },
          { key: 'definition', label: '定义', render: function (g) { return clip(g.definition, 80) || '-'; } },
          { key: '_op', label: '操作', render: function (g) {
              return DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '删',
                onclick: function () {
                  if (!window.confirm('确认删除术语「' + (g.term || '') + '」？此操作不可撤销。')) return;
                  DN.del('/api/gov/asset/glossary/' + g.id)
                    .then(function () { DN.toast('已删除', 'success'); loadGlossary(box); })
                    .catch(function (e) { DN.toast(e.message, 'error'); });
                } });
            } }
        ],
        rows: Array.isArray(list) ? list : [], pageSize: 10, search: false, empty: '暂无术语，可在上方表单新增'
      }));
    }).catch(function (e) {
      box.innerHTML = ''; box.appendChild(DN.errorBox('术语表加载失败: ' + (e && e.message || e), function () { loadGlossary(box); }));
    });
  }

  // 查看血缘（内联调用血缘端点，不跳转）
  function renderLineageSection(panel, db, table) {
    panel.appendChild(subTitle('查看血缘'));
    var box = DN.h('div', { class: 'gov-desc', style: 'margin-bottom:0' });
    var btn = DN.h('a', { class: 'btn', href: 'javascript:void(0)', style: 'margin-bottom:8px;', text: '查询上下游表',
      onclick: function () {
        var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
        var done = lockBtn(btn);
        (function load() {
        box.innerHTML = ''; box.appendChild(DN.skeleton(2));
        DN.get('/api/lineage/table-edges' + qs).then(function (nb) {
          done();
          nb = nb || {};
          var up = (Array.isArray(nb.upstream) ? nb.upstream : []).map(function (e) { return (e.srcDb || '') + '.' + (e.srcTable || ''); });
          var down = (Array.isArray(nb.downstream) ? nb.downstream : []).map(function (e) { return (e.dstDb || '') + '.' + (e.dstTable || ''); });
          box.innerHTML = '<div class="gov-desc" style="margin-bottom:4px"><b>上游表(' + up.length + '):</b> ' + (up.length ? up.map(DN.esc).join(', ') : '无') +
            '</div><div class="gov-desc" style="margin-bottom:0"><b>下游表(' + down.length + '):</b> ' + (down.length ? down.map(DN.esc).join(', ') : '无') + '</div>';
        }).catch(function (e) { done(); box.innerHTML = ''; box.appendChild(DN.errorBox('查询失败: ' + (e && e.message || e), function () { load(); })); });
        })();
      } });
    panel.appendChild(btn);
    panel.appendChild(box);
  }

  // ===== 生命周期策略（DN.card + DN.table） =====
  var policyTbl = null;
  var _lifecycleCard = null;     // 生命周期卡片根元素(供成本排行下钻滚动定位)
  var _lifecyclePicker = null;   // 生命周期库/表选择器(供下钻预填)
  function renderLifecycle(c) {
    var card = DN.card({ title: '生命周期策略（应用后自动下发 Doris DDL，失败降级 PENDING）', icon: 'clock' });
    c.appendChild(card.el);
    _lifecycleCard = card.el;
    var bd = card.body;

    var picker = DN.dbTablePicker({});
    _lifecyclePicker = picker;
    bd.appendChild(picker.el);
    var form = DN.h('div', { style: 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:14px;' });
    var type = DN.h('select', { class: 'iw-form-select', style: 'width:140px;' });
    ['TTL', 'HOT_COLD', 'ARCHIVE'].forEach(function (t) { type.appendChild(DN.h('option', { value: t, text: t })); });
    var days = DN.h('input', { class: 'iw-form-input', placeholder: 'TTL天/冷下沉天', style: 'width:160px;height:32px;' });
    var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增策略',
      onclick: function () {
        if (!picker.db() || !picker.table()) { DN.toast('请先选择库和表', 'error'); return; }
        var dv = days.value.trim();
        var d = parseInt(dv, 10);
        if (dv && (isNaN(d) || d <= 0)) { DN.toast('天数需为正整数', 'error'); days.focus(); return; }
        if (!isNaN(d) && d > 36500) { DN.toast('天数过大(请≤36500天/100年)', 'error'); days.focus(); return; }
        var body = { dbName: picker.db(), tableName: picker.table(), policyType: type.value, enabled: 1 };
        if (type.value === 'HOT_COLD') body.coldDays = isNaN(d) ? null : d; else body.ttlDays = isNaN(d) ? null : d;
        var done = lockBtn(addBtn);
        DN.post('/api/gov/lifecycle/policies', body).then(function () { done(); DN.toast('已新增', 'success'); days.value = ''; loadPolicies(); })
          .catch(function (e) { done(); DN.toast(e.message, 'error'); });
      } });
    form.appendChild(type); form.appendChild(days); form.appendChild(addBtn);
    bd.appendChild(form);
    bd.appendChild(DN.h('div', { id: 'policyList' }, [DN.skeleton(2)]));
    loadPolicies();
  }

  // 成本排行下钻: 滚动到生命周期策略卡片并预填库/表(选择器为异步加载, 库选完触发 onchange 拉表后再设表)
  function gotoLifecycle(db, table) {
    if (_lifecycleCard) {
      try { _lifecycleCard.scrollIntoView({ behavior: 'smooth', block: 'start' }); } catch (e) { _lifecycleCard.scrollIntoView(); }
    }
    if (!_lifecyclePicker || !db) { DN.toast('已定位生命周期策略', 'info'); return; }
    var sels = _lifecyclePicker.el.querySelectorAll('select');
    var dbSel = sels[0], tbSel = sels[1];
    if (!dbSel) return;
    dbSel.value = db;
    if (dbSel.value !== db) { DN.toast('请在生命周期手动选择 ' + db, 'info'); return; }
    if (dbSel.onchange) dbSel.onchange();
    if (table && tbSel) {
      // 表选项随 onchange 异步加载, 轮询设置
      var tries = 0;
      var timer = setInterval(function () {
        tries++;
        tbSel.value = table;
        if (tbSel.value === table || tries > 20) { clearInterval(timer); }
      }, 150);
    }
    DN.toast('已预填生命周期: ' + db + (table ? '.' + table : ''), 'info');
  }

  function policyStatusTone(s) {
    s = (s || '').toUpperCase();
    if (s.indexOf('SUCC') >= 0 || s === 'OK' || s === 'APPLIED') return 'ok';
    if (s.indexOf('PEND') >= 0) return 'warn';
    if (s.indexOf('FAIL') >= 0 || s.indexOf('ERR') >= 0) return 'err';
    return 'muted';
  }

  function loadPolicies() {
    DN.get('/api/gov/lifecycle/policies').then(function (list) {
      var box = document.getElementById('policyList');
      if (!box) return;
      var rows = list || [];
      if (policyTbl && box.contains(policyTbl)) { policyTbl.reload(rows); return; }
      policyTbl = DN.table({
        columns: [
          { key: '_t', label: '库.表', render: function (p) { return (p.dbName || '') + '.' + (p.tableName || ''); } },
          { key: 'policyType', label: '类型' },
          { key: '_param', label: '参数', render: function (p) { return p.policyType === 'HOT_COLD' ? ((p.coldDays == null ? '-' : p.coldDays) + '天冷') : ((p.ttlDays == null ? '-' : p.ttlDays) + '天TTL'); } },
          { key: 'status', label: '状态', render: function (p) { return p.status ? DN.pill(p.status, policyStatusTone(p.status)) : '-'; } },
          { key: 'lastMsg', label: '信息', render: function (p) { return p.lastMsg ? clip(p.lastMsg, 60) : '-'; } },
          { key: '_op', label: '操作', render: function (p) {
              var apply = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '应用',
                onclick: function () {
                  if (!window.confirm('将对 ' + (p.dbName || '') + '.' + (p.tableName || '') + ' 下发 ' + (p.policyType || '') + ' 策略(可能改动 Doris DDL)，确认应用？')) return;
                  var done = lockBtn(apply);
                  DN.post('/api/gov/lifecycle/policies/' + p.id + '/apply')
                    .then(function (r) { done(); DN.toast('应用结果: ' + (r && r.status || '完成'), 'success'); loadPolicies(); })
                    .catch(function (e) { done(); DN.toast(e.message, 'error'); });
                } });
              var del = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '删', style: 'margin-left:6px;',
                onclick: function () {
                  if (!window.confirm('确认删除该生命周期策略？此操作不可撤销。')) return;
                  var done = lockBtn(del);
                  DN.del('/api/gov/lifecycle/policies/' + p.id)
                    .then(function () { DN.toast('已删除', 'success'); loadPolicies(); })
                    .catch(function (e) { done(); DN.toast(e.message, 'error'); });
                } });
              return DN.h('span', {}, [apply, del]);
            } }
        ],
        rows: rows, pageSize: 10, search: false, empty: '暂无策略'
      });
      box.innerHTML = '';
      box.appendChild(policyTbl);
    }).catch(function (e) {
      var box = document.getElementById('policyList'); if (!box) return;
      policyTbl = null;
      box.innerHTML = ''; box.appendChild(DN.errorBox('生命周期策略加载失败: ' + (e && e.message || e), function () { loadPolicies(); }));
    });
  }

  // ===== 无用表识别（DN.card + DN.table，就近确认销毁） =====
  var unusedTbl = null;
  function renderUnused(c) {
    var collectBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '采集资产快照',
      onclick: function () {
        var done = lockBtn(collectBtn);
        DN.post('/api/gov/lifecycle/stats/collect').then(function (m) { done(); DN.toast(m || '已采集快照', 'success'); loadUnused(); loadCost(); })
          .catch(function (e) { done(); DN.toast(e.message, 'error'); });
      } });
    var dropBtn = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '执行到期销毁', style: 'margin-left:6px;',
      onclick: function () {
        if (!window.confirm('将对所有已过宽限期的表执行销毁，此操作不可逆，确认执行？')) return;
        var done = lockBtn(dropBtn);
        DN.post('/api/gov/lifecycle/drop/execute').then(function (r) {
          done(); DN.toast('到期销毁处理 ' + (Array.isArray(r) ? r.length : 0) + ' 张', 'success'); loadUnused();
        }).catch(function (e) { done(); DN.toast(e.message, 'error'); });
      } });
    var card = DN.card({ title: '无用表识别（久未访问 + 体量 + 无下游血缘 + 无任务引用）', icon: 'alert', actions: [collectBtn, dropBtn] });
    c.appendChild(card.el);
    card.body.appendChild(DN.h('div', { id: 'unusedList' }, [DN.skeleton(3)]));
    loadUnused();
  }

  function loadUnused() {
    DN.get('/api/gov/lifecycle/unused').then(function (list) {
      var box = document.getElementById('unusedList');
      if (!box) return;
      var rows = list || [];
      box.innerHTML = '';
      // 回收成本汇总
      var totalBytes = rows.reduce(function (a, u) { return a + (Number(u.sizeBytes) || 0); }, 0);
      var noLineage = rows.filter(function (u) { return !u.hasDownstreamLineage; }).length;
      var wrap = DN.h('div', { style: 'display:flex;gap:18px;align-items:center;flex-wrap:wrap;margin-bottom:6px' });
      var st = DN.statRow([
        { icon: 'alert', label: '无用表候选', value: rows.length, tone: rows.length > 0 ? 'warn' : 'ok' },
        { icon: 'db', label: '可回收体量', value: DN.fmtBytes(totalBytes), tone: 'info' },
        { icon: 'lineage', label: '无下游血缘', value: noLineage, sub: '可安全回收' }
      ]);
      st.style.flex = '1'; st.style.marginBottom = '0';
      wrap.appendChild(st);
      if (rows.length > 0) wrap.appendChild(DN.donut([
        { label: '无下游', value: noLineage, color: '#52c41a' },
        { label: '有下游', value: rows.length - noLineage, color: '#faad14' }
      ], { size: 96, stroke: 12, centerLabel: rows.length, centerSub: '候选' }));
      box.appendChild(wrap);
      unusedTbl = DN.table({
        columns: [
          { key: '_t', label: '库.表', render: function (u) { return (u.db || '') + '.' + (u.table || ''); } },
          { key: 'score', label: '分数', align: 'right' },
          { key: 'lastAccessDays', label: '未访问', align: 'right', render: function (u) { return u.lastAccessDays == null ? '-' : (u.lastAccessDays + '天'); } },
          { key: 'sizeBytes', label: '体量', align: 'right', render: function (u) { return DN.fmtBytes(u.sizeBytes); } },
          { key: '_down', label: '下游血缘', render: function (u) { return u.hasDownstreamLineage ? DN.pill('有', 'info') : DN.pill('无', 'muted'); } },
          { key: '_ref', label: '任务引用', render: function (u) { return u.hasTaskRef ? DN.pill('有', 'info') : DN.pill('无', 'muted'); } },
          { key: '_op', label: '操作', render: function (u) {
              var detail = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '资产详情', title: '查看该表字段/画像/血缘',
                onclick: function () { openAssetDetail(u.db || '', u.table || ''); } });
              var lineage = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '查血缘', style: 'margin-left:6px;', title: '画血缘图确认是否真无下游',
                onclick: function () {
                  if (window.navigateTo) navigateTo('governance', { gov: 'lineage', table: { db: u.db, table: u.table } });
                  else DN.toast('当前页面无路由,请到资产详情查看血缘', 'info');
                } });
              var drop = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '标记销毁', style: 'margin-left:6px;',
                onclick: function () { openDropConfirm(u); } });
              return DN.h('span', {}, [detail, lineage, drop]);
            } }
        ],
        rows: rows, pageSize: 15, searchKeys: ['db', 'table'], searchPlaceholder: '搜索库 / 表',
        empty: '暂无无用表候选（先采集资产快照）', emptyIcon: 'check'
      });
      box.appendChild(unusedTbl);
    }).catch(function (e) {
      var box = document.getElementById('unusedList'); if (!box) return;
      unusedTbl = null;
      box.innerHTML = ''; box.appendChild(DN.errorBox('无用表识别加载失败: ' + (e && e.message || e), function () { loadUnused(); }));
    });
  }

  // 就近确认（抽屉内）：审批人 + 原因 + 确认销毁
  function openDropConfirm(u) {
    var body = DN.h('div', {});
    var dr = DN.drawer('销毁确认', body);
    body.appendChild(DN.h('div', { style: 'color:var(--gov-err,#ff4d4f);font-size:14px;font-weight:600;margin-bottom:14px;',
      text: '确认销毁 ' + u.db + '.' + u.table + '？此操作进入宽限期后不可逆。' }));
    var form = DN.h('div', { class: 'gov-form' });
    var approver = DN.h('input', { class: 'iw-form-input', placeholder: '审批人(必填,留痕)', style: 'width:100%;height:34px;margin-bottom:10px;' });
    var reason = DN.h('input', { class: 'iw-form-input', placeholder: '销毁原因', style: 'width:100%;height:34px;' });
    form.appendChild(DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: '审批人', style: 'display:block;margin-bottom:4px;font-size:12px;color:var(--text-muted,#86909c)' }), approver]));
    form.appendChild(DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: '原因', style: 'display:block;margin-bottom:4px;font-size:12px;color:var(--text-muted,#86909c)' }), reason]));
    body.appendChild(form);
    var ok = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确认销毁',
      onclick: function () {
        var av = approver.value.trim(), rv = reason.value.trim();
        if (!av) { DN.toast('审批人必填', 'error'); approver.focus(); return; }
        if (rv.length > 200) { DN.toast('原因过长(请控制在 200 字以内)', 'error'); reason.focus(); return; }
        var done = lockBtn(ok);
        DN.post('/api/gov/lifecycle/drop', { db: u.db, table: u.table, approver: av, reason: rv })
          .then(function (p) { DN.toast('已进入宽限期，至 ' + (p && p.dropDueAt || '-'), 'success'); dr.close(); loadUnused(); loadPolicies(); })
          .catch(function (e) { done(); DN.toast(e.message, 'error'); });
      } });
    var cancel = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '取消', style: 'margin-left:8px;', onclick: function () { dr.close(); } });
    body.appendChild(DN.h('div', { style: 'margin-top:6px;' }, [ok, cancel]));
  }

  // ===== 成本排行（DN.card + DN.table） =====
  var costTbl = null;
  function renderCost(c) {
    var card = DN.card({ title: '成本排行（单价 × 体量，可在系统配置调单价）', icon: 'chart' });
    c.appendChild(card.el);
    card.body.appendChild(DN.h('div', { id: 'costList' }, [DN.skeleton(3)]));
    loadCost();
  }

  function loadCost() {
    DN.get('/api/gov/lifecycle/cost?limit=50').then(function (list) {
      var box = document.getElementById('costList');
      if (!box) return;
      var rows = list || [];
      box.innerHTML = '';
      // 成本总计/平均统计
      var totalCost = rows.reduce(function (a, s) { return a + (Number(s.costEstimate) || 0); }, 0);
      if (totalCost > 0) {
        box.appendChild(DN.statRow([
          { icon: 'chart', label: '总月成本', value: '¥' + fmtInt(Math.round(totalCost)), tone: 'warn' },
          { icon: 'chart', label: '平均/表', value: '¥' + (rows.length ? (totalCost / rows.length).toFixed(1) : 0), tone: 'info' },
          { icon: 'db', label: '统计表数', value: rows.length }
        ]));
      }
      // 成本 Top10 热力
      var top = rows.slice(0, 10).filter(function (s) { return (Number(s.costEstimate) || Number(s.sizeBytes) || 0) > 0; });
      if (top.length) {
        box.appendChild(DN.sectionTitle('成本 Top10（按月成本，无则按体量）'));
        box.appendChild(DN.heat(top.map(function (s) {
          var v = Number(s.costEstimate) || 0;
          return { label: (s.db || '') + '.' + (s.table || ''), value: v || (Number(s.sizeBytes) || 0), display: v ? ('¥' + v) : DN.fmtBytes(s.sizeBytes) };
        }), { rgb: [250, 173, 20] }));
        box.appendChild(DN.sectionTitle('全部资产成本明细'));
      }
      costTbl = DN.table({
        columns: [
          { key: '_t', label: '库.表', render: function (s) { return (s.db || '') + '.' + (s.table || ''); } },
          { key: 'sizeBytes', label: '体量', align: 'right', render: function (s) { return DN.fmtBytes(s.sizeBytes); } },
          { key: 'rowCount', label: '行数', align: 'right', render: function (s) { return s.rowCount == null ? '-' : fmtInt(s.rowCount); } },
          { key: 'costEstimate', label: '月成本(元)', align: 'right', render: function (s) { return s.costEstimate == null ? '-' : s.costEstimate; } },
          { key: '_op', label: '操作', exportValue: function () { return ''; }, render: function (s) {
              var detail = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '详情', title: '查看该表字段/画像/血缘',
                onclick: function () { openAssetDetail(s.db || '', s.table || ''); } });
              var policy = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '建生命周期策略', style: 'margin-left:6px;', title: '为该表配置 TTL/降冷/归档以降本',
                onclick: function () { gotoLifecycle(s.db || '', s.table || ''); } });
              return DN.h('span', {}, [detail, policy]);
            } }
        ],
        rows: rows, pageSize: 15, searchKeys: ['db', 'table'], searchPlaceholder: '搜索库 / 表', empty: '暂无成本数据（先采集资产快照）'
      });
      box.appendChild(costTbl);
    }).catch(function (e) {
      var box = document.getElementById('costList'); if (!box) return;
      costTbl = null;
      box.innerHTML = ''; box.appendChild(DN.errorBox('成本排行加载失败: ' + (e && e.message || e), function () { loadCost(); }));
    });
  }

  // ===== 工具 =====
  function fmtInt(v) {
    if (v == null) return '-';
    v = Number(v) || 0;
    return String(Math.round(v)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }
  // 超长文本截断 + title 悬浮看全文(返回 DOM 节点, 安全转义)
  function clip(s, max) {
    s = s == null ? '' : String(s);
    max = max || 60;
    if (s.length <= max) return s === '' ? '' : DN.h('span', { text: s });
    return DN.h('span', { text: s.slice(0, max) + '…', title: s });
  }
  // 异步操作期间锁定按钮防重复提交: 加 is-loading + disabled, 完成后还原。返回 done() 还原函数。
  function lockBtn(btn) {
    if (!btn) return function () {};
    var oldText = btn.textContent;
    btn.classList.add('is-loading');
    btn.style.pointerEvents = 'none';
    btn.style.opacity = '0.6';
    btn.setAttribute('aria-busy', 'true');
    var done = false;
    return function () {
      if (done) return; done = true;
      btn.classList.remove('is-loading');
      btn.style.pointerEvents = '';
      btn.style.opacity = '';
      btn.removeAttribute('aria-busy');
      if (btn.textContent !== oldText) btn.textContent = oldText;
    };
  }
})();
