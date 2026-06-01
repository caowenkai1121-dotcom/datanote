/* 治理模块：数据血缘（M3 字段映射血缘查询 / M4 SQL 解析血缘扩展） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  // SVG 用具体色值（CSS 变量在 SVG 内解析不可靠），与系统令牌对齐
  var C = { primary: '#1890ff', up: '#ff4d4f', down: '#52c41a', node: '#f2f3f5', nodeBorder: '#d4d7de', text: '#1f2329' };

  // 三处库表选择器（替代自由文本框，级联真实元数据）
  var lnPicker = null, impPicker = null, grPicker = null;

  window.GOV_RENDERERS.lineage = function (c) {
    // 顶部操作条
    var bar = DN.h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px' });
    bar.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '从同步任务重建血缘',
      onclick: function () {
        DN.post('/api/lineage/rebuild-edges').then(function (r) {
          DN.toast('已重建 ' + (r && r.edgeCount != null ? r.edgeCount : 0) + ' 条血缘边');
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      } }));
    bar.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '解析脚本SQL血缘',
      onclick: function () {
        DN.post('/api/lineage/parse-scripts').then(function (r) {
          DN.toast('SQL血缘已解析 ' + (r && r.edgeCount != null ? r.edgeCount : 0) + ' 条边');
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      } }));
    c.appendChild(bar);

    // 1. 血缘查询（表级 + 字段级）
    var qCard = DN.card({ title: '血缘查询', icon: 'lineage' });
    c.appendChild(qCard.el);
    lnPicker = DN.dbTablePicker({});
    var q = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    q.appendChild(lnPicker.el);
    q.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '查询血缘', onclick: queryLineage }));
    qCard.body.appendChild(q);
    qCard.body.appendChild(DN.h('div', { id: 'lnResult' }));

    // 2. 影响 / 溯源（表级 BFS）
    var iCard = DN.card({ title: '影响溯源', icon: 'chart' });
    c.appendChild(iCard.el);
    impPicker = DN.dbTablePicker({});
    var iq = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    iq.appendChild(impPicker.el);
    iq.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '下游影响',
      onclick: function () { queryFlow('impact'); } }));
    iq.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '上游溯源',
      onclick: function () { queryFlow('trace'); } }));
    iCard.body.appendChild(iq);
    iCard.body.appendChild(DN.h('div', { id: 'impResult' }));

    // 3. 血缘图谱（交互式 SVG 有向图）
    var gCard = DN.card({ title: '血缘图谱', icon: 'grid' });
    c.appendChild(gCard.el);
    grPicker = DN.dbTablePicker({});
    var gq = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    gq.appendChild(grPicker.el);
    gq.appendChild(DN.h('span', { text: '跳数', style: 'color:var(--text-muted,#86909c);font-size:13px' }));
    var gDep = DN.h('input', { id: 'grDepth', type: 'number', value: '2', min: '1', max: '6',
      class: 'iw-form-input', title: '跳数', style: 'width:60px' });
    gq.appendChild(gDep);
    gq.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '画血缘图', onclick: queryGraph }));
    gCard.body.appendChild(gq);
    // 图例
    gCard.body.appendChild(DN.h('div', { style: 'color:var(--text-muted,#86909c);font-size:12px;margin-bottom:8px',
      html: '<span style="display:inline-block;width:12px;height:12px;background:' + C.primary + ';border-radius:3px;vertical-align:middle;margin-right:4px"></span>中心表 ' +
        '<span style="display:inline-block;width:18px;border-top:2px solid ' + C.up + ';vertical-align:middle;margin:0 4px 0 12px"></span>上游边 ' +
        '<span style="display:inline-block;width:18px;border-top:2px solid ' + C.down + ';vertical-align:middle;margin:0 4px 0 12px"></span>下游边 · 点节点高亮相邻，悬停边看来源/层级' }));
    gCard.body.appendChild(DN.h('div', { id: 'graphResult' }));

    // 4. 孤儿表检测（创新功能）：基于已有血缘边数据，识别某库中“既无上游也无下游血缘边”的表（疑似废弃/未接入），供治理清理参考
    var oCard = DN.card({ title: '孤儿表检测', icon: 'alert' });
    c.appendChild(oCard.el);
    oCard.body.appendChild(DN.h('div', { class: 'gov-desc',
      style: 'color:var(--text-muted,#86909c);font-size:12px;margin-bottom:10px',
      text: '扫描所选库下所有表的表级血缘，列出既无上游来源、也无下游去向的表（疑似废弃或未接入血缘），供治理清理参考。' }));
    var oSel = DN.h('select', { id: 'orphanDb', class: 'iw-form-select', style: 'min-width:160px' });
    oSel.innerHTML = '<option value="">选择库</option>';
    DN.metaDatabases().then(function (dbs) {
      (dbs || []).forEach(function (d) {
        var o = document.createElement('option'); o.value = d; o.textContent = d; oSel.appendChild(o);
      });
    }).catch(function () {});
    var oq = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    oq.appendChild(DN.h('span', { text: '库', style: 'color:var(--text-muted,#86909c);font-size:13px' }));
    oq.appendChild(oSel);
    oq.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '检测孤儿表', onclick: detectOrphans }));
    oCard.body.appendChild(oq);
    oCard.body.appendChild(DN.h('div', { id: 'orphanResult' }));
  };

  /** 并发受限地对一组表查询表级血缘，避免请求风暴。fn 返回 Promise，limit 为最大并发。 */
  function mapLimit(items, limit, fn) {
    return new Promise(function (resolve) {
      var results = new Array(items.length), idx = 0, done = 0;
      if (!items.length) { resolve(results); return; }
      function next() {
        if (idx >= items.length) return;
        var cur = idx++;
        fn(items[cur], cur).then(function (r) { results[cur] = r; }, function () { results[cur] = null; })
          .then(function () { if (++done === items.length) resolve(results); else next(); });
      }
      for (var i = 0; i < Math.min(limit, items.length); i++) next();
    });
  }

  // 孤儿表检测：取所选库全部表，逐表查表级上下游边，聚合出上下游皆空者
  function detectOrphans() {
    var db = document.getElementById('orphanDb').value;
    if (!db) { DN.toast('请先选择库', 'error'); return; }
    var box = document.getElementById('orphanResult');
    box.innerHTML = '';
    box.appendChild(DN.skeleton(4));
    DN.metaTables(db).then(function (tables) {
      tables = tables || [];
      if (!tables.length) { box.innerHTML = ''; box.appendChild(DN.empty('该库下暂无表', 'db')); return; }
      return mapLimit(tables, 6, function (t) {
        var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(t);
        return DN.get('/api/lineage/table-edges' + qs).then(function (nb) {
          nb = nb || {};
          var up = (nb.upstream || []).length, down = (nb.downstream || []).length;
          return { table: t, up: up, down: down };
        });
      }).then(function (stats) {
        renderOrphans(box, db, tables.length, stats.filter(function (s) { return s; }));
      });
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.empty('检测失败: ' + (e && e.message ? e.message : e), 'alert')); });
  }

  function renderOrphans(box, db, total, stats) {
    box.innerHTML = '';
    var orphans = stats.filter(function (s) { return s.up === 0 && s.down === 0; });
    var connected = total - orphans.length;
    var pct = Math.round(connected * 100 / (total || 1));
    box.appendChild(DN.statRow([
      { icon: 'db', label: '库内表数', value: total },
      { icon: 'alert', label: '孤儿表数', value: orphans.length, tone: orphans.length > 0 ? 'warn' : 'ok',
        sub: orphans.length > 0 ? '疑似废弃/未接入' : '全部已接入血缘' },
      { icon: 'check', label: '血缘覆盖率', value: pct + '%', tone: pct >= 80 ? 'ok' : 'info' }
    ]));
    if (!orphans.length) {
      box.appendChild(DN.empty('未发现孤儿表，' + DN.esc(db) + ' 库内表血缘覆盖良好', 'check'));
      return;
    }
    box.appendChild(DN.table({
      columns: [
        { label: '孤儿表', copyable: true, render: function (s) { return DN.esc(db) + '.' + DN.esc(s.table); },
          exportValue: function (s) { return db + '.' + s.table; } },
        { label: '上游边', align: 'center', render: function () { return DN.pill('0', 'muted'); }, exportValue: function () { return '0'; } },
        { label: '下游边', align: 'center', render: function () { return DN.pill('0', 'muted'); }, exportValue: function () { return '0'; } },
        { label: '判定', align: 'center', render: function () { return DN.pill('孤儿表', 'err'); }, exportValue: function () { return '孤儿表'; } }
      ],
      rows: orphans,
      searchKeys: ['table'],
      searchPlaceholder: '搜索孤儿表...',
      exportName: '孤儿表清单_' + db,
      empty: '未发现孤儿表', emptyIcon: 'check'
    }));
  }

  var SVG_NS = 'http://www.w3.org/2000/svg';
  function svg(tag, attrs) {
    var el = document.createElementNS(SVG_NS, tag);
    if (attrs) Object.keys(attrs).forEach(function (k) { el.setAttribute(k, attrs[k]); });
    return el;
  }

  function queryGraph() {
    var db = grPicker.db();
    var table = grPicker.table();
    var depth = parseInt(document.getElementById('grDepth').value, 10) || 2;
    if (depth < 1 || depth > 6) { DN.toast('跳数需在 1-6 之间', 'error'); return; }
    if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
    var box = document.getElementById('graphResult');
    box.innerHTML = '';
    box.appendChild(DN.skeleton(4));
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table) + '&depth=' + depth;
    DN.get('/api/lineage/graph' + qs).then(function (g) {
      g = g || {};
      var nodes = g.nodes || [], edges = g.edges || [];
      box.innerHTML = '';
      if (nodes.length <= 1 && !edges.length) {
        box.appendChild(DN.empty('该表暂无血缘边（先点上方重建/解析）', 'lineage')); return;
      }
      box.appendChild(renderGraph(nodes, edges, db + '.' + table));
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.empty('查询失败: ' + e.message, 'alert')); });
  }

  /** 分层布局：以中心表为第 0 列，下游为正列(右)、上游为负列(左)，纯 SVG 绘制有向图。 */
  function renderGraph(nodes, edges, centerId) {
    // 邻接：out(下游) / in(上游)
    var out = {}, inc = {};
    edges.forEach(function (e) {
      (out[e.src] = out[e.src] || []).push(e.dst);
      (inc[e.dst] = inc[e.dst] || []).push(e.src);
    });
    // BFS 计算每个节点的列(相对中心)：下游 +1，上游 -1
    var col = {}; col[centerId] = 0;
    var queue = [centerId];
    while (queue.length) {
      var cur = queue.shift();
      (out[cur] || []).forEach(function (n) {
        if (col[n] == null) { col[n] = col[cur] + 1; queue.push(n); }
      });
      (inc[cur] || []).forEach(function (n) {
        if (col[n] == null) { col[n] = col[cur] - 1; queue.push(n); }
      });
    }
    // 未连通到中心的孤立节点放第 0 列
    nodes.forEach(function (n) { if (col[n.id] == null) col[n.id] = 0; });

    // 按列分组、纵向排布
    var cols = {};
    nodes.forEach(function (n) { (cols[col[n.id]] = cols[col[n.id]] || []).push(n.id); });
    var colKeys = Object.keys(cols).map(Number).sort(function (a, b) { return a - b; });

    var NW = 150, NH = 36, GAPX = 90, GAPY = 22, PAD = 20;
    var pos = {}; // id -> {x,y}
    var maxRows = 0;
    colKeys.forEach(function (ck) { if (cols[ck].length > maxRows) maxRows = cols[ck].length; });
    colKeys.forEach(function (ck, ci) {
      var list = cols[ck];
      var colH = list.length * NH + (list.length - 1) * GAPY;
      var totalH = maxRows * NH + (maxRows - 1) * GAPY;
      var y0 = PAD + (totalH - colH) / 2;
      list.forEach(function (id, ri) {
        pos[id] = { x: PAD + ci * (NW + GAPX), y: y0 + ri * (NH + GAPY) };
      });
    });
    var width = PAD * 2 + colKeys.length * NW + (colKeys.length - 1) * GAPX;
    var height = PAD * 2 + maxRows * NH + (maxRows - 1) * GAPY;
    if (width < 320) width = 320;

    var s = svg('svg', { width: String(width), height: String(height),
      viewBox: '0 0 ' + width + ' ' + height,
      style: 'background:#fff;border:1px solid var(--border,#eceef1);border-radius:10px;max-width:100%' });
    // 箭头标记（上游红 / 下游绿）
    var defs = svg('defs');
    [['arrU', C.up], ['arrD', C.down]].forEach(function (a) {
      var m = svg('marker', { id: a[0], viewBox: '0 0 10 10', refX: '9', refY: '5',
        markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse' });
      m.appendChild(svg('path', { d: 'M0,0 L10,5 L0,10 z', fill: a[1] }));
      defs.appendChild(m);
    });
    s.appendChild(defs);

    var edgeEls = []; // {el, src, dst}
    var gEdges = svg('g');
    edges.forEach(function (e) {
      var a = pos[e.src], b = pos[e.dst];
      if (!a || !b) return;
      var downstream = (col[e.dst] || 0) >= (col[e.src] || 0);
      var color = downstream ? C.down : C.up;
      var marker = downstream ? 'url(#arrD)' : 'url(#arrU)';
      var x1 = a.x + NW, y1 = a.y + NH / 2, x2 = b.x, y2 = b.y + NH / 2;
      // 若目标在左侧（上游边方向反向），从左边出、右边入
      if (b.x < a.x) { x1 = a.x; x2 = b.x + NW; }
      var line = svg('line', { x1: x1, y1: y1, x2: x2, y2: y2,
        stroke: color, 'stroke-width': '1.6', 'marker-end': marker });
      var tip = (downstream ? '下游' : '上游') + ' · 来源:' + (e.source || '-') + ' · ' + (e.level || 'TABLE');
      var title = svg('title'); title.textContent = tip; line.appendChild(title);
      gEdges.appendChild(line);
      edgeEls.push({ el: line, src: e.src, dst: e.dst, color: color });
    });
    s.appendChild(gEdges);

    var nodeEls = {}; // id -> rect
    var selected = null;
    nodes.forEach(function (n) {
      var p = pos[n.id]; if (!p) return;
      var g = svg('g', { style: 'cursor:pointer' });
      var isCenter = n.id === centerId;
      var rect = svg('rect', { x: p.x, y: p.y, width: NW, height: NH, rx: '8',
        fill: isCenter ? C.primary : C.node,
        stroke: isCenter ? C.primary : C.nodeBorder, 'stroke-width': '1' });
      var label = n.id.length > 22 ? n.id.slice(0, 21) + '…' : n.id;
      var txt = svg('text', { x: p.x + NW / 2, y: p.y + NH / 2 + 4, 'text-anchor': 'middle',
        'font-size': '12', fill: isCenter ? '#fff' : C.text });
      txt.textContent = label;
      var ttl = svg('title'); ttl.textContent = n.id; rect.appendChild(ttl);
      g.appendChild(rect); g.appendChild(txt);
      g.addEventListener('click', function () {
        selected = (selected === n.id) ? null : n.id;
        highlight(selected);
      });
      s.appendChild(g);
      nodeEls[n.id] = { rect: rect, center: isCenter };
    });

    function highlight(id) {
      // 复位
      Object.keys(nodeEls).forEach(function (k) {
        var ne = nodeEls[k];
        ne.rect.setAttribute('opacity', id ? '0.35' : '1');
      });
      edgeEls.forEach(function (ee) {
        ee.el.setAttribute('opacity', id ? '0.15' : '1');
        ee.el.setAttribute('stroke-width', '1.6');
      });
      if (!id) return;
      var keep = {}; keep[id] = 1;
      edgeEls.forEach(function (ee) {
        if (ee.src === id || ee.dst === id) {
          ee.el.setAttribute('opacity', '1');
          ee.el.setAttribute('stroke-width', '2.6');
          keep[ee.src] = 1; keep[ee.dst] = 1;
        }
      });
      Object.keys(keep).forEach(function (k) {
        if (nodeEls[k]) nodeEls[k].rect.setAttribute('opacity', '1');
      });
    }

    // ---- 缩放 / 平移 / 导出 ----
    var scale = 1;
    function applyScale() { s.setAttribute('width', (width * scale).toFixed(0)); s.setAttribute('height', (height * scale).toFixed(0)); }
    function zoom(d) { scale = Math.min(3, Math.max(0.4, Math.round((scale + d) * 10) / 10)); applyScale(); }
    function exportSvg() {
      try {
        var xml = new XMLSerializer().serializeToString(s);
        var blob = new Blob(['<?xml version="1.0" encoding="UTF-8"?>\n' + xml], { type: 'image/svg+xml' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a'); a.href = url; a.download = 'lineage_' + String(centerId || '').replace(/[^a-zA-Z0-9]/g, '_') + '.svg';
        document.body.appendChild(a); a.click(); document.body.removeChild(a);
        setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
      } catch (e) { DN.toast('导出失败', 'error'); }
    }
    var scroll = DN.h('div', { style: 'overflow:auto;max-width:100%;max-height:600px;border:1px solid var(--border,#eceef1);border-radius:10px' }, [s]);
    scroll.addEventListener('wheel', function (e) {
      if (!e.ctrlKey) return; e.preventDefault(); zoom(e.deltaY < 0 ? 0.1 : -0.1);
    }, { passive: false });
    var tb = DN.h('div', { style: 'display:flex;gap:6px;align-items:center;margin-bottom:8px' }, [
      DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '＋', title: '放大', onclick: function () { zoom(0.2); } }),
      DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '－', title: '缩小', onclick: function () { zoom(-0.2); } }),
      DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '复位', onclick: function () { scale = 1; applyScale(); } }),
      DN.h('a', { class: 'btn btn-sm btn-ghost', href: 'javascript:void(0)', text: '导出 SVG', onclick: exportSvg }),
      DN.h('span', { style: 'font-size:11px;color:var(--text-muted)', text: 'Ctrl+滚轮缩放 · 拖动滚动条平移' })
    ]);
    return DN.h('div', {}, [tb, scroll]);
  }

  // 影响面板(大功能): 受影响表数/库数/最大层级 统计 + 按层级分组芯片
  function buildImpactPanel(list, kind, centerId) {
    var dbs = {}, maxDepth = 0, byDepth = {};
    list.forEach(function (n) {
      dbs[n.db] = 1;
      var d = Number(n.depth) || 1; if (d > maxDepth) maxDepth = d;
      (byDepth[d] = byDepth[d] || []).push(n);
    });
    var wrap = DN.h('div', { style: 'margin-bottom:12px' });
    var verb = kind === 'impact' ? '下游受影响' : '上游依赖';
    wrap.appendChild(DN.statRow([
      { icon: 'db', label: verb + '表数', value: list.length, tone: list.length > 10 ? 'warn' : 'info' },
      { icon: 'layers', label: '涉及库数', value: Object.keys(dbs).length },
      { icon: 'chart', label: '最大层级', value: '第 ' + maxDepth + ' 层' }
    ]));
    // 按层级分组芯片
    var lvWrap = DN.h('div', { style: 'margin-top:8px' });
    Object.keys(byDepth).map(Number).sort(function (a, b) { return a - b; }).forEach(function (d) {
      var row = DN.h('div', { style: 'display:flex;align-items:flex-start;gap:8px;padding:5px 0;border-bottom:1px solid var(--divider,#f0f1f3)' });
      row.appendChild(DN.h('span', { style: 'flex-shrink:0;font-size:11px;color:var(--text-muted);width:54px;padding-top:2px', text: '第 ' + d + ' 层' }));
      var chips = DN.h('div', { style: 'display:flex;flex-wrap:wrap;gap:4px' });
      byDepth[d].forEach(function (n) { chips.appendChild(DN.h('span', { style: 'font-size:11px;padding:1px 8px;border-radius:10px;background:var(--bg-hover,#f0f1f3);color:var(--text-regular)', text: n.db + '.' + n.table })); });
      row.appendChild(chips);
      lvWrap.appendChild(row);
    });
    var card = DN.card({ title: '影响面分析 · ' + centerId, icon: 'lineage' });
    card.body.appendChild(wrap); card.body.appendChild(lvWrap);
    if (kind === 'impact' && list.length > 10) card.body.appendChild(DN.alertNode ? DN.alertNode('该表变更将波及 ' + list.length + ' 张下游表, 建议变更前充分评估与通知。', 'warn') : DN.h('div', { class: 'gov-desc', style: 'color:#d48806;margin-top:8px', text: '⚠ 该表变更将波及 ' + list.length + ' 张下游表, 建议充分评估。' }));
    return card.el;
  }

  function queryFlow(kind) {
    var db = impPicker.db();
    var table = impPicker.table();
    if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
    var box = document.getElementById('impResult');
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
    DN.get('/api/lineage/' + kind + qs).then(function (list) {
      list = list || [];
      var title = kind === 'impact' ? '下游影响' : '上游溯源';
      box.innerHTML = '';
      if (!list.length) { box.appendChild(DN.empty('无' + title + '（先解析脚本SQL血缘或重建）', 'lineage')); return; }
      box.appendChild(DN.h('div', { style: 'font-size:13px;font-weight:600;margin-bottom:10px',
        text: title + '（共 ' + list.length + ' 个表）' }));
      // 影响面统计 + 按层分组(大功能)
      box.appendChild(buildImpactPanel(list, kind, db + '.' + table));
      box.appendChild(DN.table({
        columns: [
          { label: '表', copyable: true, render: function (n) { return n.db + '.' + n.table; }, exportValue: function (n) { return n.db + '.' + n.table; } },
          { label: '层级', align: 'center', render: function (n) { return DN.pill('第 ' + n.depth + ' 层', 'info'); }, exportValue: function (n) { return '第 ' + n.depth + ' 层'; } },
          { label: '来源', render: function (n) { return n.source ? DN.pill(n.source, 'muted') : '-'; }, exportValue: function (n) { return n.source || ''; } }
        ],
        rows: list,
        searchKeys: ['db', 'table', 'source'],
        searchPlaceholder: '搜索表/来源...',
        exportName: title,
        empty: '无' + title, emptyIcon: 'lineage'
      }));
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.empty('查询失败: ' + e.message, 'alert')); });
  }

  function queryLineage() {
    var db = lnPicker.db();
    var table = lnPicker.table();
    if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
    var box = document.getElementById('lnResult');
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
    Promise.all([
      DN.get('/api/lineage/table-edges' + qs),
      DN.get('/api/lineage/column-edges' + qs)
    ]).then(function (res) {
      var nb = res[0] || {}, cols = res[1] || [];
      var up = (nb.upstream || []).map(function (e) { return e.srcDb + '.' + e.srcTable; });
      var down = (nb.downstream || []).map(function (e) { return e.dstDb + '.' + e.dstTable; });
      box.innerHTML = '';

      // 上下游表：用药丸罗列
      var tblSummary = DN.h('div', { style: 'margin-bottom:14px' });
      tblSummary.appendChild(neighborLine('上游表', up, 'up'));
      tblSummary.appendChild(neighborLine('下游表', down, 'down'));
      box.appendChild(tblSummary);

      // 字段级血缘入边：用 DN.table
      if (cols.length) {
        box.appendChild(DN.table({
          columns: [
            { key: 'dstColumn', label: '目标列', copyable: true, exportValue: function (e) { return e.dstColumn || ''; } },
            { label: '来源', copyable: true, render: function (e) { return e.srcDb + '.' + e.srcTable + '.' + e.srcColumn; }, exportValue: function (e) { return e.srcDb + '.' + e.srcTable + '.' + e.srcColumn; } },
            { label: '变换', render: function (e) { return e.transformType ? DN.pill(e.transformType, 'info') : '-'; }, exportValue: function (e) { return e.transformType || ''; } }
          ],
          rows: cols,
          searchKeys: ['dstColumn', 'srcDb', 'srcTable', 'srcColumn', 'transformType'],
          searchPlaceholder: '搜索字段...',
          exportName: '字段级血缘_' + db + '.' + table,
          empty: '无字段级血缘', emptyIcon: 'lineage'
        }));
      } else {
        box.appendChild(DN.empty('无字段级血缘（先点上方重建，或该表非同步目标）', 'lineage'));
      }
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.empty('查询失败: ' + e.message, 'alert')); });
  }

  // 上/下游表的一行药丸罗列
  function neighborLine(label, arr, dir) {
    var tone = dir === 'up' ? 'err' : 'ok';
    var row = DN.h('div', { style: 'display:flex;align-items:baseline;gap:8px;flex-wrap:wrap;margin-bottom:8px' });
    row.appendChild(DN.h('b', { text: label + '：', style: 'font-size:13px;flex:none' }));
    if (!arr.length) { row.appendChild(DN.h('span', { text: '无', style: 'color:var(--text-muted,#86909c);font-size:13px' })); return row; }
    arr.forEach(function (t) { row.appendChild(DN.pill(t, tone)); });
    return row;
  }
})();
