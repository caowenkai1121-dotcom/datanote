/* 治理模块：数据血缘（M3 字段映射血缘查询 / M4 SQL 解析血缘扩展） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  // 三处库表选择器（替代自由文本框，级联真实元数据）
  var lnPicker = null, impPicker = null, grPicker = null;

  window.GOV_RENDERERS.lineage = function (c) {
    var bar = DN.h('div', { class: 'gov-desc' });
    bar.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '从同步任务重建血缘',
      onclick: function () {
        DN.post('/api/lineage/rebuild-edges').then(function (r) {
          DN.toast('已重建 ' + (r && r.edgeCount != null ? r.edgeCount : 0) + ' 条血缘边');
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      } }));
    bar.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '解析脚本SQL血缘',
      style: 'margin-left:8px',
      onclick: function () {
        DN.post('/api/lineage/parse-scripts').then(function (r) {
          DN.toast('SQL血缘已解析 ' + (r && r.edgeCount != null ? r.edgeCount : 0) + ' 条边');
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      } }));
    c.appendChild(bar);

    var q = DN.h('div', { class: 'gov-desc', style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    lnPicker = DN.dbTablePicker({});
    q.appendChild(lnPicker.el);
    q.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '查询血缘',
      onclick: queryLineage }));
    c.appendChild(q);

    c.appendChild(DN.h('div', { id: 'lnResult' }));

    // 影响/溯源查询区（基于 dn_lineage_edge 表级 BFS）
    var iq = DN.h('div', { class: 'gov-desc', style: 'margin-top:16px;border-top:1px solid #e5e6eb;padding-top:12px;display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    impPicker = DN.dbTablePicker({});
    iq.appendChild(impPicker.el);
    iq.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '下游影响',
      onclick: function () { queryFlow('impact'); } }));
    iq.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '上游溯源',
      onclick: function () { queryFlow('trace'); } }));
    c.appendChild(iq);
    c.appendChild(DN.h('div', { id: 'impResult' }));

    // 血缘图谱（交互式 SVG 有向图）
    var gq = DN.h('div', { class: 'gov-desc', style: 'margin-top:16px;border-top:1px solid #e5e6eb;padding-top:12px;display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    gq.appendChild(DN.h('b', { text: '血缘图谱' }));
    grPicker = DN.dbTablePicker({});
    gq.appendChild(grPicker.el);
    gq.appendChild(DN.h('span', { text: '跳数', style: 'color:#86909c;font-size:13px' }));
    var gDep = DN.h('input', { id: 'grDepth', type: 'number', value: '2', min: '1', max: '6',
      class: 'iw-form-input', title: '跳数', style: 'width:60px' });
    gq.appendChild(gDep);
    gq.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '画血缘图',
      onclick: queryGraph }));
    c.appendChild(gq);
    c.appendChild(DN.h('div', { class: 'gov-desc', style: 'color:#86909c;font-size:12px',
      html: '<span style="display:inline-block;width:12px;height:12px;background:#165dff;vertical-align:middle;margin-right:4px"></span>中心表 ' +
        '<span style="display:inline-block;width:18px;border-top:2px solid #f53f3f;vertical-align:middle;margin:0 4px 0 12px"></span>上游边 ' +
        '<span style="display:inline-block;width:18px;border-top:2px solid #00b42a;vertical-align:middle;margin:0 4px 0 12px"></span>下游边 · 点节点高亮相邻，悬停边看来源/层级' }));
    c.appendChild(DN.h('div', { id: 'graphResult' }));
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
    box.innerHTML = '加载中...';
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table) + '&depth=' + depth;
    DN.get('/api/lineage/graph' + qs).then(function (g) {
      g = g || {};
      var nodes = g.nodes || [], edges = g.edges || [];
      if (nodes.length <= 1 && !edges.length) {
        box.innerHTML = '<div class="gov-placeholder">该表暂无血缘边（先点上方重建/解析）</div>'; return;
      }
      box.innerHTML = '';
      box.appendChild(renderGraph(nodes, edges, db + '.' + table));
    }).catch(function (e) { box.innerHTML = '<div class="gov-placeholder">查询失败: ' + DN.esc(e.message) + '</div>'; });
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

    var NW = 150, NH = 34, GAPX = 90, GAPY = 22, PAD = 20;
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
      style: 'background:#fff;border:1px solid #e5e6eb;border-radius:6px;max-width:100%' });
    // 箭头标记（上游红 / 下游绿）
    var defs = svg('defs');
    [['arrU', '#f53f3f'], ['arrD', '#00b42a']].forEach(function (a) {
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
      var color = downstream ? '#00b42a' : '#f53f3f';
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
      var rect = svg('rect', { x: p.x, y: p.y, width: NW, height: NH, rx: '6',
        fill: isCenter ? '#165dff' : '#f2f3f5',
        stroke: isCenter ? '#165dff' : '#c9cdd4', 'stroke-width': '1' });
      var label = n.id.length > 22 ? n.id.slice(0, 21) + '…' : n.id;
      var txt = svg('text', { x: p.x + NW / 2, y: p.y + NH / 2 + 4, 'text-anchor': 'middle',
        'font-size': '12', fill: isCenter ? '#fff' : '#1d2129' });
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

    return s;
  }

  function queryFlow(kind) {
    var db = impPicker.db();
    var table = impPicker.table();
    if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
    var box = document.getElementById('impResult');
    box.innerHTML = '加载中...';
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
    DN.get('/api/lineage/' + kind + qs).then(function (list) {
      list = list || [];
      var title = kind === 'impact' ? '下游影响' : '上游溯源';
      if (!list.length) { box.innerHTML = '<div class="gov-placeholder">无' + title + '（先解析脚本SQL血缘或重建）</div>'; return; }
      box.innerHTML = '<div class="gov-desc"><b>' + title + '（共 ' + list.length + ' 个表）:</b></div>' +
        '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px"><thead>' +
        '<tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        '<th style="padding:8px">表</th><th style="padding:8px">层级</th><th style="padding:8px">来源</th></tr></thead><tbody>' +
        list.map(function (n) {
          return '<tr><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(n.db + '.' + n.table) +
            '</td><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(String(n.depth)) +
            '</td><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(n.source || '') + '</td></tr>';
        }).join('') + '</tbody></table>';
    }).catch(function (e) { box.innerHTML = '<div class="gov-placeholder">查询失败: ' + DN.esc(e.message) + '</div>'; });
  }

  function queryLineage() {
    var db = lnPicker.db();
    var table = lnPicker.table();
    if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
    var box = document.getElementById('lnResult');
    box.innerHTML = '加载中...';
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
    Promise.all([
      DN.get('/api/lineage/table-edges' + qs),
      DN.get('/api/lineage/column-edges' + qs)
    ]).then(function (res) {
      var nb = res[0] || {}, cols = res[1] || [];
      var up = (nb.upstream || []).map(function (e) { return e.srcDb + '.' + e.srcTable; });
      var down = (nb.downstream || []).map(function (e) { return e.dstDb + '.' + e.dstTable; });
      var html = '<div class="gov-desc"><b>上游表:</b> ' + (up.length ? up.map(DN.esc).join(', ') : '无') + '</div>' +
        '<div class="gov-desc"><b>下游表:</b> ' + (down.length ? down.map(DN.esc).join(', ') : '无') + '</div>';
      if (cols.length) {
        html += '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px"><thead>' +
          '<tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
          '<th style="padding:8px">目标列</th><th style="padding:8px">来源</th><th style="padding:8px">变换</th></tr></thead><tbody>' +
          cols.map(function (e) {
            return '<tr><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(e.dstColumn) +
              '</td><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(e.srcDb + '.' + e.srcTable + '.' + e.srcColumn) +
              '</td><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(e.transformType || '') + '</td></tr>';
          }).join('') + '</tbody></table>';
      } else {
        html += '<div class="gov-placeholder">无字段级血缘（先点上方重建，或该表非同步目标）</div>';
      }
      box.innerHTML = html;
    }).catch(function (e) { box.innerHTML = '<div class="gov-placeholder">查询失败: ' + DN.esc(e.message) + '</div>'; });
  }
})();
