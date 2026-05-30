/* 治理模块：治理总览大屏（F2）
   一屏看板：健康总分+五维雷达、资产卡片、质量分、工单状态、敏感分布。SVG/div 自绘，零图表库依赖。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var DIMS = ['规范', '质量', '安全', '生命周期', '血缘'];
  var ISSUE_COLOR = { open: '#f53f3f', fixing: '#ff7d00', closed: '#86909c' };
  var ISSUE_LABEL = { open: '待处理', fixing: '处理中', closed: '已关闭' };

  window.GOV_RENDERERS.overview = function (c) {
    var box = DN.h('div', { id: 'ovBox', class: 'gov-desc' }, '加载中...');
    c.appendChild(box);
    DN.get('/api/gov/overview').then(function (d) {
      render(box, d || {});
    }).catch(function (e) {
      box.innerHTML = '<div class="gov-placeholder">加载失败: ' + DN.esc(e.message) + '</div>';
    });
  };

  function render(box, d) {
    var health = d.health || {};
    var assets = d.assets || {};
    var quality = d.quality || {};
    var issues = d.issues || {};
    var sensitive = d.sensitive || {};

    box.innerHTML = '';
    var grid = DN.h('div', {
      style: 'display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:16px;align-items:stretch'
    });

    grid.appendChild(healthCard(health));
    grid.appendChild(assetsCard(assets));
    grid.appendChild(qualityCard(quality));
    grid.appendChild(issuesCard(issues));
    grid.appendChild(sensitiveCard('敏感等级分布', sensitive.byLevel, '#165dff'));
    grid.appendChild(sensitiveCard('敏感类型分布', sensitive.byType, '#722ed1'));

    box.appendChild(grid);
  }

  // ========== 卡片 ==========

  function card(title) {
    var el = DN.h('div', {
      style: 'background:#fff;border:1px solid #e5e6eb;border-radius:8px;padding:16px;display:flex;flex-direction:column'
    });
    el.appendChild(DN.h('div', {
      text: title,
      style: 'font-size:14px;font-weight:600;color:#1f2329;margin-bottom:12px'
    }));
    return el;
  }

  function healthCard(health) {
    var el = card('治理健康总分');
    var total = Number(health.total) || 0;
    var dims = health.dims || {};
    var color = scoreColor(total);
    var inner = DN.h('div', { style: 'display:flex;gap:12px;align-items:center;flex-wrap:wrap' });
    inner.innerHTML =
      '<div style="text-align:center;min-width:96px">' +
      '<div style="font-size:42px;font-weight:700;color:' + color + ';line-height:1">' + round1(total) + '</div>' +
      '<div style="color:#86909c;font-size:12px;margin-top:4px">满分 100</div></div>' +
      '<div>' + drawRadar(DIMS, DIMS.map(function (k) { return Number(dims[k]) || 0; }), 200, color) + '</div>';
    el.appendChild(inner);
    return el;
  }

  function assetsCard(assets) {
    var el = card('数据资产');
    var items = [
      ['表数', fmtInt(assets.tableCount)],
      ['字段数', fmtInt(assets.columnCount)],
      ['库数', fmtInt(assets.dbCount)],
      ['总体量', DN.fmtBytes(assets.totalSizeBytes)]
    ];
    var g = DN.h('div', { style: 'display:grid;grid-template-columns:1fr 1fr;gap:12px' });
    items.forEach(function (it) {
      var cell = DN.h('div', { style: 'background:#f7f8fa;border-radius:6px;padding:12px;text-align:center' });
      cell.innerHTML =
        '<div style="font-size:24px;font-weight:700;color:#1f2329;line-height:1.2">' + DN.esc(it[1]) + '</div>' +
        '<div style="color:#86909c;font-size:12px;margin-top:4px">' + DN.esc(it[0]) + '</div>';
      g.appendChild(cell);
    });
    el.appendChild(g);
    return el;
  }

  function qualityCard(quality) {
    var el = card('数据质量');
    var rate = Number(quality.recentPassRate) || 0;
    var color = scoreColor(rate);
    var inner = DN.h('div', { style: 'display:flex;gap:24px;align-items:center;flex-wrap:wrap' });
    inner.innerHTML =
      '<div style="text-align:center;min-width:110px">' +
      '<div style="font-size:36px;font-weight:700;color:' + color + ';line-height:1">' + round1(rate) + '%</div>' +
      '<div style="color:#86909c;font-size:12px;margin-top:4px">近期检查通过率</div></div>' +
      '<div style="text-align:center;min-width:90px">' +
      '<div style="font-size:36px;font-weight:700;color:#1f2329;line-height:1">' + fmtInt(quality.runs24h) + '</div>' +
      '<div style="color:#86909c;font-size:12px;margin-top:4px">近 24h 运行数</div></div>';
    // 通过率进度条
    var barWrap = DN.h('div', { style: 'margin-top:12px;width:100%' });
    barWrap.innerHTML =
      '<div style="background:#f2f3f5;border-radius:4px;height:8px;overflow:hidden">' +
      '<div style="height:8px;border-radius:4px;background:' + color + ';width:' + Math.max(0, Math.min(100, rate)) + '%"></div></div>';
    el.appendChild(inner);
    el.appendChild(barWrap);
    return el;
  }

  function issuesCard(issues) {
    var el = card('治理工单');
    var keys = ['open', 'fixing', 'closed'];
    var max = Math.max(1, Number(issues.open) || 0, Number(issues.fixing) || 0, Number(issues.closed) || 0);
    keys.forEach(function (k) {
      var v = Number(issues[k]) || 0;
      el.appendChild(barRow(ISSUE_LABEL[k], v, max, ISSUE_COLOR[k]));
    });
    return el;
  }

  function sensitiveCard(title, map, color) {
    var el = card(title);
    map = map || {};
    var keys = Object.keys(map);
    if (!keys.length) {
      el.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无数据' }));
      return el;
    }
    var max = 1;
    keys.forEach(function (k) { max = Math.max(max, Number(map[k]) || 0); });
    keys.forEach(function (k) {
      el.appendChild(barRow(k, Number(map[k]) || 0, max, color));
    });
    return el;
  }

  // ========== 通用条形行 ==========

  function barRow(label, value, max, color) {
    var row = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;margin-bottom:8px;font-size:13px' });
    var w = max > 0 ? (Number(value) || 0) * 100 / max : 0;
    row.innerHTML =
      '<div style="width:80px;color:#4e5969;flex:none;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="' + DN.esc(label) + '">' + DN.esc(label) + '</div>' +
      '<div style="flex:1;background:#f2f3f5;border-radius:4px;height:14px;overflow:hidden">' +
      '<div style="height:14px;border-radius:4px;background:' + color + ';width:' + w.toFixed(1) + '%;min-width:2px"></div></div>' +
      '<div style="width:48px;text-align:right;color:#1f2329;font-weight:600;flex:none">' + fmtInt(value) + '</div>';
    return row;
  }

  // ========== SVG 雷达自绘（精简版） ==========

  function drawRadar(axes, vals, size, color) {
    var n = axes.length;
    if (!n) return '';
    var cx = size / 2, cy = size / 2, R = size / 2 - 34;
    var parts = [];
    for (var g = 1; g <= 4; g++) {
      var rr = R * g / 4, pts = [];
      for (var i = 0; i < n; i++) {
        var p = polar(cx, cy, rr, angle(i, n));
        pts.push(p.x.toFixed(1) + ',' + p.y.toFixed(1));
      }
      parts.push('<polygon points="' + pts.join(' ') + '" fill="none" stroke="#e5e6eb" stroke-width="1"/>');
    }
    for (var j = 0; j < n; j++) {
      var a = angle(j, n);
      var edge = polar(cx, cy, R, a);
      parts.push('<line x1="' + cx + '" y1="' + cy + '" x2="' + edge.x.toFixed(1) + '" y2="' + edge.y.toFixed(1) + '" stroke="#e5e6eb" stroke-width="1"/>');
      var lp = polar(cx, cy, R + 14, a);
      var anchor = Math.abs(lp.x - cx) < 4 ? 'middle' : (lp.x > cx ? 'start' : 'end');
      parts.push('<text x="' + lp.x.toFixed(1) + '" y="' + (lp.y + 4).toFixed(1) + '" font-size="10" fill="#4e5969" text-anchor="' + anchor + '">' + DN.esc(axes[j]) + '</text>');
    }
    var dpts = [];
    for (var k = 0; k < n; k++) {
      var v = Math.max(0, Math.min(100, vals[k] || 0));
      var pp = polar(cx, cy, R * v / 100, angle(k, n));
      dpts.push(pp.x.toFixed(1) + ',' + pp.y.toFixed(1));
    }
    parts.push('<polygon points="' + dpts.join(' ') + '" fill="' + color + '33" stroke="' + color + '" stroke-width="2"/>');
    for (var m = 0; m < n; m++) {
      var co = dpts[m].split(',');
      parts.push('<circle cx="' + co[0] + '" cy="' + co[1] + '" r="3" fill="' + color + '"/>');
    }
    return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '">' + parts.join('') + '</svg>';
  }

  function angle(i, n) { return -Math.PI / 2 + 2 * Math.PI * i / n; }
  function polar(cx, cy, r, a) { return { x: cx + r * Math.cos(a), y: cy + r * Math.sin(a) }; }

  // ========== 工具 ==========

  function scoreColor(v) {
    return v >= 85 ? '#00b42a' : (v >= 70 ? '#165dff' : (v >= 60 ? '#ff7d00' : '#f53f3f'));
  }
  function round1(v) { return Math.round((Number(v) || 0) * 10) / 10; }
  function fmtInt(v) {
    v = Number(v) || 0;
    return String(Math.round(v)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }
})();
