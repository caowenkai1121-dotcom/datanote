/* 治理模块：资产目录（M2 元数据采集 + 资产清单）—— 现代套件重绘 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  window.GOV_RENDERERS.assets = function (c) {
    // 顶部统计区
    var statBox = DN.h('div', { id: 'assetStats' });
    statBox.appendChild(DN.skeleton(2));
    c.appendChild(statBox);

    // 资产清单卡片
    var crawlBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '采集全部(源库+数仓)',
      onclick: function () {
        DN.post('/api/metadata-center/crawl/all').then(function (msg) {
          DN.toast(msg || '采集已启动'); setTimeout(loadAssets, 1500);
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      } });
    var srcSel = DN.h('select', { class: 'iw-form-select', style: 'height:34px;',
      onchange: function () { assetState.src = srcSel.value; renderAssetTable(); } });
    srcSel.innerHTML = '<option value="">全部来源</option><option value="MYSQL">MYSQL</option><option value="DORIS">DORIS</option>';

    var histBox = DN.h('div', { id: 'assetCollectHistory' });
    c.appendChild(histBox);

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
  };

  // ===== 资产清单：统计 + 现代表格 =====
  var assetAll = [];
  var assetState = { src: '', quick: '', view: (function () { try { return localStorage.getItem('gov.assetView') || 'table'; } catch (e) { return 'table'; } })() };
  var assetTbl = null;
  var assetToolbar = null;
  var quickSelEl = null;      // 快捷视图下拉（供统计磁贴联动同步）

  var _collectLogs = [];
  function loadAssets() {
    // 统计 + 最近采集状态 + 采集历史
    DN.get('/api/metadata-center/collect-logs').then(function (logs) {
      _collectLogs = logs || [];
      lastLog = _collectLogs[0] || null;
      renderStats();
      renderCollectHistory();
    }).catch(function () { lastLog = null; });

    DN.get('/api/metadata-center/tables').then(function (tables) {
      assetAll = tables || [];
      renderStats();
      renderAssetTable();
    }).catch(function (e) {
      var b = document.getElementById('assetTbl');
      if (b) { b.innerHTML = ''; b.appendChild(DN.empty('加载失败: ' + e.message, 'alert')); }
    });
  }

  var lastLog = null;

  function renderStats() {
    var box = document.getElementById('assetStats');
    if (!box) return;
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
      var row = DN.h('div', { style: 'position:relative;padding:0 0 12px 14px' });
      row.innerHTML = '<div style="position:absolute;left:-16px;top:2px;width:9px;height:9px;border-radius:50%;background:' + color + ';border:2px solid var(--bg-card,#fff);box-shadow:0 0 0 1px ' + color + '"></div>'
        + '<div style="font-size:12px"><b style="color:' + color + '">' + DN.esc(lg.status || '-') + '</b> '
        + '<span style="color:var(--text-muted)">' + DN.esc(lg.dbType || '') + (lg.tableCount != null ? ' · ' + lg.tableCount + ' 表' : '') + '</span></div>'
        + '<div style="font-size:11px;color:var(--text-muted);margin-top:2px">' + DN.esc((lg.startedAt || lg.createdAt || '').toString().replace('T', ' ').slice(0, 19)) + (lg.message ? ' · ' + DN.esc(String(lg.message).slice(0, 60)) : '') + '</div>';
      wrap.appendChild(row);
    });
    card.body.appendChild(wrap);
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
    _maxRow = assetAll.reduce(function (m, t) { return Math.max(m, Number(t.rowCount) || 0); }, 0);
    _maxSize = assetAll.reduce(function (m, t) { return Math.max(m, Number(t.sizeBytes) || 0); }, 0);
    var rows = assetAll.filter(function (t) {
      if (assetState.src && (t.dbType || '') !== assetState.src) return false;
      if (assetState.quick === 'nodesc' && t.tableComment) return false;
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
        { key: 'tableComment', label: '业务描述', render: function (r) { return r.tableComment || '-'; } },
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
        var w = (Number(t.sizeBytes) || 0) / g.sum * 100;
        var cell = DN.h('div', { title: t.tableName + ' · ' + DN.fmtBytes(t.sizeBytes) + (t.rowCount != null ? ' · ' + fmtInt(t.rowCount) + ' 行' : ''),
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
  function renderAssetTree(rows) {
    var box = document.getElementById('assetTbl'); if (!box) return;
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
    keys.forEach(function (k) {
      var parts = k.split('|'), dbType = parts[0], dbName = parts[1];
      var tables = byDb[k];
      var open = _treeOpen[k] !== false; // 默认展开
      var totalBytes = tables.reduce(function (s, t) { return s + (Number(t.sizeBytes) || 0); }, 0);
      var head = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:9px 12px;cursor:pointer;background:var(--bg-hover,#f6f7f9);border-bottom:1px solid var(--divider,#eee)' });
      head.innerHTML = '<span style="font-size:11px;color:var(--text-muted);width:12px;display:inline-block;transition:transform .15s;transform:rotate(' + (open ? '90' : '0') + 'deg)">▶</span>'
        + '<span style="font-weight:600;font-size:13px">' + DN.esc(dbName) + '</span>'
        + '<span class="gov-pill" style="font-size:10px;padding:0 6px;border-radius:8px;background:#e6f0ff;color:#1890ff">' + DN.esc(dbType || '-') + '</span>'
        + '<span style="font-size:11px;color:var(--text-muted)">' + tables.length + ' 表 · ' + DN.fmtBytes(totalBytes) + '</span>';
      var bodyWrap = DN.h('div', { style: 'display:' + (open ? 'block' : 'none') });
      tables.sort(function (a, b) { return (a.tableName || '').localeCompare(b.tableName || ''); });
      tables.forEach(function (t) {
        var row = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:7px 12px 7px 34px;border-bottom:1px solid var(--divider,#f3f4f6);cursor:pointer;font-size:12.5px' });
        row.onmouseenter = function () { row.style.background = 'var(--bg-hover,#f6f7f9)'; };
        row.onmouseleave = function () { row.style.background = ''; };
        row.innerHTML = '<span style="color:#1890ff">▦</span>'
          + '<span style="font-weight:500">' + DN.esc(t.tableName || '-') + '</span>'
          + '<span style="color:var(--text-muted);flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">' + DN.esc(t.tableComment || '') + '</span>'
          + '<span style="color:var(--text-muted);font-size:11px;white-space:nowrap">' + (t.rowCount == null ? '' : fmtInt(t.rowCount) + ' 行 · ') + DN.fmtBytes(t.sizeBytes) + '</span>';
        row.addEventListener('click', function () { openAssetDetail(t.databaseName || '', t.tableName || ''); });
        bodyWrap.appendChild(row);
      });
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
    renderColumns(body, db, table);
    renderProfileSection(body, db, table);
    renderGlossarySection(body);
    renderLineageSection(body, db, table);
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
    DN.get('/api/gov/asset/detail' + qs).then(function (d) {
      var cols = (d && d.columns) || [];
      box.innerHTML = '';
      if (!cols.length) { box.appendChild(DN.empty('该表暂无字段元数据(可重新采集该库)', 'list')); return; }
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
          { key: 'businessDesc', label: '注释', render: function (r) { return r.businessDesc || '-'; } },
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
      box.innerHTML = ''; box.appendChild(DN.empty('加载失败: ' + e.message, 'alert'));
    });
  }

  // Profiler 探查（下推数仓）
  function renderProfileSection(panel, db, table) {
    panel.appendChild(subTitle('Profiler 探查'));
    var resultBox = DN.h('div', { class: 'gov-desc', style: 'margin-bottom:0' });
    var btn = DN.h('a', { class: 'btn', href: 'javascript:void(0)', style: 'margin-bottom:8px;', text: '执行探查（采样数仓）',
      onclick: function () {
        resultBox.innerHTML = ''; resultBox.appendChild(DN.skeleton(3));
        var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
        DN.get('/api/gov/asset/profile' + qs).then(function (p) {
          p = p || {};
          var fields = p.fields || [];
          resultBox.innerHTML = '';
          resultBox.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-bottom:8px',
            text: '总行数: ' + (p.totalRows == null ? '-' : p.totalRows) + '，字段数: ' + (p.columnCount == null ? '-' : p.columnCount) +
              '，已探查 ' + (p.profiledCount == null ? '-' : p.profiledCount) + ' 个' }));
          resultBox.appendChild(DN.table({
            columns: [
              { key: 'name', label: '字段' },
              { key: 'nullRate', label: '空值率', align: 'right', render: function (f) { return f.nullRate == null ? (f.error ? '错误' : '-') : f.nullRate; } },
              { key: 'distinctCount', label: 'distinct', align: 'right', render: function (f) { return f.distinctCount == null ? '-' : f.distinctCount; } }
            ],
            rows: fields, pageSize: 50, search: false, empty: '无探查结果'
          }));
        }).catch(function (e) { resultBox.innerHTML = ''; resultBox.appendChild(DN.empty(e.message, 'alert')); });
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
        if (!term.value.trim()) { DN.toast('术语名必填', 'error'); return; }
        DN.post('/api/gov/asset/glossary', { term: term.value.trim(), category: cat.value.trim(), definition: def.value.trim() })
          .then(function () { DN.toast('已新增'); term.value = ''; cat.value = ''; def.value = ''; loadGlossary(listBox); })
          .catch(function (e) { DN.toast(e.message, 'error'); });
      } });
    form.appendChild(row('术语', term));
    form.appendChild(row('分类', cat));
    form.appendChild(row('定义', def));
    form.appendChild(DN.h('div', { style: 'margin-top:6px;' }, [addBtn]));
    panel.appendChild(form);
    var listBox = DN.h('div', {}, [DN.skeleton(2)]);
    panel.appendChild(listBox);
    loadGlossary(listBox);
  }

  function loadGlossary(box) {
    DN.get('/api/gov/asset/glossary').then(function (list) {
      box.innerHTML = '';
      box.appendChild(DN.table({
        columns: [
          { key: 'term', label: '术语' },
          { key: 'category', label: '分类', render: function (g) { return g.category || '-'; } },
          { key: 'definition', label: '定义', render: function (g) { return g.definition || '-'; } },
          { key: '_op', label: '操作', render: function (g) {
              return DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '删',
                onclick: function () {
                  DN.del('/api/gov/asset/glossary/' + g.id)
                    .then(function () { DN.toast('已删除'); loadGlossary(box); })
                    .catch(function (e) { DN.toast(e.message, 'error'); });
                } });
            } }
        ],
        rows: list || [], pageSize: 10, search: false, empty: '暂无术语'
      }));
    }).catch(function () {});
  }

  // 查看血缘（内联调用血缘端点，不跳转）
  function renderLineageSection(panel, db, table) {
    panel.appendChild(subTitle('查看血缘'));
    var box = DN.h('div', { class: 'gov-desc', style: 'margin-bottom:0' });
    var btn = DN.h('a', { class: 'btn', href: 'javascript:void(0)', style: 'margin-bottom:8px;', text: '查询上下游表',
      onclick: function () {
        box.innerHTML = ''; box.appendChild(DN.skeleton(2));
        var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
        DN.get('/api/lineage/table-edges' + qs).then(function (nb) {
          nb = nb || {};
          var up = (nb.upstream || []).map(function (e) { return e.srcDb + '.' + e.srcTable; });
          var down = (nb.downstream || []).map(function (e) { return e.dstDb + '.' + e.dstTable; });
          box.innerHTML = '<div class="gov-desc" style="margin-bottom:4px"><b>上游表:</b> ' + (up.length ? up.map(DN.esc).join(', ') : '无') +
            '</div><div class="gov-desc" style="margin-bottom:0"><b>下游表:</b> ' + (down.length ? down.map(DN.esc).join(', ') : '无') + '</div>';
        }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.empty('查询失败: ' + e.message, 'alert')); });
      } });
    panel.appendChild(btn);
    panel.appendChild(box);
  }

  // ===== 生命周期策略（DN.card + DN.table） =====
  var policyTbl = null;
  function renderLifecycle(c) {
    var card = DN.card({ title: '生命周期策略（应用后自动下发 Doris DDL，失败降级 PENDING）', icon: 'clock' });
    c.appendChild(card.el);
    var bd = card.body;

    var picker = DN.dbTablePicker({});
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
        if (dv && (isNaN(d) || d <= 0)) { DN.toast('天数需为正整数', 'error'); return; }
        var body = { dbName: picker.db(), tableName: picker.table(), policyType: type.value, enabled: 1 };
        if (type.value === 'HOT_COLD') body.coldDays = isNaN(d) ? null : d; else body.ttlDays = isNaN(d) ? null : d;
        DN.post('/api/gov/lifecycle/policies', body).then(function () { DN.toast('已新增'); loadPolicies(); })
          .catch(function (e) { DN.toast(e.message, 'error'); });
      } });
    form.appendChild(type); form.appendChild(days); form.appendChild(addBtn);
    bd.appendChild(form);
    bd.appendChild(DN.h('div', { id: 'policyList' }, [DN.skeleton(2)]));
    loadPolicies();
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
          { key: '_param', label: '参数', render: function (p) { return p.policyType === 'HOT_COLD' ? (p.coldDays || '-') + '天冷' : (p.ttlDays || '-') + '天TTL'; } },
          { key: 'status', label: '状态', render: function (p) { return p.status ? DN.pill(p.status, policyStatusTone(p.status)) : '-'; } },
          { key: 'lastMsg', label: '信息', render: function (p) { return p.lastMsg || '-'; } },
          { key: '_op', label: '操作', render: function (p) {
              var apply = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '应用',
                onclick: function () {
                  DN.post('/api/gov/lifecycle/policies/' + p.id + '/apply')
                    .then(function (r) { DN.toast('应用结果: ' + (r && r.status)); loadPolicies(); })
                    .catch(function (e) { DN.toast(e.message, 'error'); });
                } });
              var del = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '删', style: 'margin-left:6px;',
                onclick: function () {
                  DN.del('/api/gov/lifecycle/policies/' + p.id)
                    .then(function () { DN.toast('已删除'); loadPolicies(); })
                    .catch(function (e) { DN.toast(e.message, 'error'); });
                } });
              return DN.h('span', {}, [apply, del]);
            } }
        ],
        rows: rows, pageSize: 10, search: false, empty: '暂无策略'
      });
      box.innerHTML = '';
      box.appendChild(policyTbl);
    }).catch(function () {});
  }

  // ===== 无用表识别（DN.card + DN.table，就近确认销毁） =====
  var unusedTbl = null;
  function renderUnused(c) {
    var collectBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '采集资产快照',
      onclick: function () {
        DN.post('/api/gov/lifecycle/stats/collect').then(function (m) { DN.toast(m); loadUnused(); loadCost(); })
          .catch(function (e) { DN.toast(e.message, 'error'); });
      } });
    var dropBtn = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '执行到期销毁', style: 'margin-left:6px;',
      onclick: function () {
        DN.post('/api/gov/lifecycle/drop/execute').then(function (r) {
          DN.toast('到期销毁处理 ' + (r ? r.length : 0) + ' 张'); loadUnused();
        }).catch(function (e) { DN.toast(e.message, 'error'); });
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
          { key: 'lastAccessDays', label: '未访问', align: 'right', render: function (u) { return u.lastAccessDays + '天'; } },
          { key: 'sizeBytes', label: '体量', align: 'right', render: function (u) { return DN.fmtBytes(u.sizeBytes); } },
          { key: '_down', label: '下游血缘', render: function (u) { return u.hasDownstreamLineage ? DN.pill('有', 'info') : DN.pill('无', 'muted'); } },
          { key: '_ref', label: '任务引用', render: function (u) { return u.hasTaskRef ? DN.pill('有', 'info') : DN.pill('无', 'muted'); } },
          { key: '_op', label: '操作', render: function (u) {
              return DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '标记销毁',
                onclick: function () { openDropConfirm(u); } });
            } }
        ],
        rows: rows, pageSize: 15, searchKeys: ['db', 'table'], searchPlaceholder: '搜索库 / 表',
        empty: '暂无无用表候选（先采集资产快照）', emptyIcon: 'check'
      });
      box.appendChild(unusedTbl);
    }).catch(function () {});
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
        if (!approver.value.trim()) { DN.toast('审批人必填', 'error'); return; }
        DN.post('/api/gov/lifecycle/drop', { db: u.db, table: u.table, approver: approver.value.trim(), reason: reason.value.trim() })
          .then(function (p) { DN.toast('已进入宽限期，至 ' + (p && p.dropDueAt)); dr.close(); loadUnused(); loadPolicies(); })
          .catch(function (e) { DN.toast(e.message, 'error'); });
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
          { icon: 'chart', label: '总月成本', value: '¥' + totalCost.toFixed(0), tone: 'warn' },
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
          { key: 'costEstimate', label: '月成本(元)', align: 'right', render: function (s) { return s.costEstimate == null ? '-' : s.costEstimate; } }
        ],
        rows: rows, pageSize: 15, searchKeys: ['db', 'table'], searchPlaceholder: '搜索库 / 表', empty: '暂无成本数据（先采集资产快照）'
      });
      box.appendChild(costTbl);
    }).catch(function () {});
  }

  // ===== 工具 =====
  function fmtInt(v) {
    if (v == null) return '-';
    v = Number(v) || 0;
    return String(Math.round(v)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }
})();
