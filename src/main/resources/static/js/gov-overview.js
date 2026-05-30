/* 治理模块：治理总览大屏（F2）
   一屏看板：健康总分+五维雷达、资产卡片、质量分、工单状态、敏感分布。SVG/div 自绘，零图表库依赖。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var DIMS = ['规范', '质量', '安全', '生命周期', '血缘'];
  // 配色统一走系统令牌，CSS 变量取不到时回落到原值
  var C = {
    primary: 'var(--primary,#1890ff)', success: 'var(--success,#52c41a)',
    warning: 'var(--warning,#faad14)', error: 'var(--error,#ff4d4f)', accent: '#722ed1',
    card: 'var(--bg-card,#fff)', border: 'var(--border,#e0e0e6)', track: 'var(--divider,#efeff5)',
    title: 'var(--text-primary,#1f2329)', muted: 'var(--text-muted,#86909c)', regular: 'var(--text-regular,#4e5969)'
  };
  var ISSUE_COLOR = { open: C.error, fixing: C.warning, closed: C.muted };
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
    grid.appendChild(sensitiveCard('敏感等级分布', sensitive.byLevel, C.primary));
    grid.appendChild(sensitiveCard('敏感类型分布', sensitive.byType, C.accent));

    box.appendChild(grid);
  }

  // ========== 卡片 ==========

  function card(title) {
    var el = DN.h('div', {
      style: 'background:' + C.card + ';border:1px solid ' + C.border + ';border-radius:8px;padding:16px;display:flex;flex-direction:column'
    });
    el.appendChild(DN.h('div', {
      text: title,
      style: 'font-size:14px;font-weight:600;color:' + C.title + ';margin-bottom:12px'
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
      '<div style="color:' + C.muted + ';font-size:12px;margin-top:4px">满分 100</div></div>' +
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
      var cell = DN.h('div', { style: 'background:' + C.track + ';border-radius:6px;padding:12px;text-align:center' });
      cell.innerHTML =
        '<div style="font-size:24px;font-weight:700;color:' + C.title + ';line-height:1.2">' + DN.esc(it[1]) + '</div>' +
        '<div style="color:' + C.muted + ';font-size:12px;margin-top:4px">' + DN.esc(it[0]) + '</div>';
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
      '<div style="color:' + C.muted + ';font-size:12px;margin-top:4px">近期检查通过率</div></div>' +
      '<div style="text-align:center;min-width:90px">' +
      '<div style="font-size:36px;font-weight:700;color:' + C.title + ';line-height:1">' + fmtInt(quality.runs24h) + '</div>' +
      '<div style="color:' + C.muted + ';font-size:12px;margin-top:4px">近 24h 运行数</div></div>';
    // 通过率进度条
    var barWrap = DN.h('div', { style: 'margin-top:12px;width:100%' });
    barWrap.innerHTML =
      '<div style="background:' + C.track + ';border-radius:4px;height:8px;overflow:hidden">' +
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
      '<div style="width:80px;color:' + C.regular + ';flex:none;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="' + DN.esc(label) + '">' + DN.esc(label) + '</div>' +
      '<div style="flex:1;background:' + C.track + ';border-radius:4px;height:14px;overflow:hidden">' +
      '<div style="height:14px;border-radius:4px;background:' + color + ';width:' + w.toFixed(1) + '%;min-width:2px"></div></div>' +
      '<div style="width:48px;text-align:right;color:' + C.title + ';font-weight:600;flex:none">' + fmtInt(value) + '</div>';
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
      parts.push('<polygon points="' + pts.join(' ') + '" fill="none" stroke="#e0e0e6" stroke-width="1"/>');
    }
    for (var j = 0; j < n; j++) {
      var a = angle(j, n);
      var edge = polar(cx, cy, R, a);
      parts.push('<line x1="' + cx + '" y1="' + cy + '" x2="' + edge.x.toFixed(1) + '" y2="' + edge.y.toFixed(1) + '" stroke="#e0e0e6" stroke-width="1"/>');
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
    parts.push('<polygon points="' + dpts.join(' ') + '" fill="' + color + '" fill-opacity="0.2" stroke="' + color + '" stroke-width="2"/>');
    for (var m = 0; m < n; m++) {
      var co = dpts[m].split(',');
      parts.push('<circle cx="' + co[0] + '" cy="' + co[1] + '" r="3" fill="' + color + '"/>');
    }
    return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '">' + parts.join('') + '</svg>';
  }

  function angle(i, n) { return -Math.PI / 2 + 2 * Math.PI * i / n; }
  function polar(cx, cy, r, a) { return { x: cx + r * Math.cos(a), y: cy + r * Math.sin(a) }; }

  // ========== 工具 ==========

  // 供 SVG fill 使用，返回与系统令牌一致的具体色值（SVG 不可靠解析 CSS 变量）
  function scoreColor(v) {
    return v >= 85 ? '#52c41a' : (v >= 70 ? '#1890ff' : (v >= 60 ? '#faad14' : '#ff4d4f'));
  }
  function round1(v) { return Math.round((Number(v) || 0) * 10) / 10; }
  function fmtInt(v) {
    v = Number(v) || 0;
    return String(Math.round(v)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }
})();
