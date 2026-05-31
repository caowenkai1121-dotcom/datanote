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
  };

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
        var a = document.createElement('a'); a.href = url; a.download = 'lineage_' + centerId.replace(/[^\w]/g, '_') + '.svg';
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
      box.appendChild(DN.table({
        columns: [
          { label: '表', render: function (n) { return n.db + '.' + n.table; }, exportValue: function (n) { return n.db + '.' + n.table; } },
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
            { key: 'dstColumn', label: '目标列' },
            { label: '来源', render: function (e) { return e.srcDb + '.' + e.srcTable + '.' + e.srcColumn; } },
            { label: '变换', render: function (e) { return e.transformType ? DN.pill(e.transformType, 'info') : '-'; } }
          ],
          rows: cols,
          searchKeys: ['dstColumn', 'srcDb', 'srcTable', 'srcColumn', 'transformType'],
          searchPlaceholder: '搜索字段...',
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
