/* DataNote 治理公共层 —— 被 governance.html 等治理页面复用。零框架依赖。 */
(function (global) {
  'use strict';
  var DN = {};

  /** 统一请求：解析 R<T> 信封（code===0 成功），失败抛 Error */
  DN.api = function (url, options) {
    return fetch(url, options || {}).then(function (resp) {
      var _bad = false;
      return resp.json().catch(function () { _bad = true; return {}; }).then(function (body) {
        if (body && typeof body.code !== 'undefined') {
          if (body.code === 0) return body.data;
          throw new Error(body.msg || ('请求失败(' + body.code + ')'));
        }
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        if (_bad) throw new Error('响应格式错误(非JSON)'); // 服务器返回HTML错误页等
        return body;
      });
    });
  };

  DN.get = function (url) { return DN.api(url); };

  DN.post = function (url, data) {
    return DN.api(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: data != null ? JSON.stringify(data) : undefined
    });
  };

  DN.del = function (url) { return DN.api(url, { method: 'DELETE' }); };

  /** 轻量 toast 提示 */
  DN.toast = function (msg, type) {
    var t = document.createElement('div');
    t.className = 'dn-toast dn-toast-' + (type || 'info');
    t.setAttribute('role', 'status'); t.setAttribute('aria-live', 'polite'); // 屏幕阅读器播报提示
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(function () { t.classList.add('dn-toast-show'); }, 10);
    setTimeout(function () {
      t.classList.remove('dn-toast-show');
      setTimeout(function () { if (t.parentNode && document.body.contains(t)) t.parentNode.removeChild(t); }, 300);
    }, 2600);
  };

  /** 复制文本到剪贴板：优先 navigator.clipboard，降级 textarea+execCommand */
  DN.copy = function (text) {
    text = String(text == null ? '' : text);
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { DN.toast('已复制', 'ok'); }, function () { DN.toast('复制失败', 'err'); });
        return;
      }
    } catch (e) {}
    try {
      var ta = document.createElement('textarea'); ta.value = text;
      ta.style.position = 'fixed'; ta.style.opacity = '0'; document.body.appendChild(ta); ta.select();
      document.execCommand('copy'); document.body.removeChild(ta); DN.toast('已复制', 'ok');
    } catch (e2) { DN.toast('复制失败', 'err'); }
  };

  /** DOM 简化器：DN.h('div', {class:'x', onclick:fn}, [child|text]) */
  DN.h = function (tag, attrs, children) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (k === 'class') el.className = v;
        else if (k === 'html') el.innerHTML = v;
        else if (k === 'text') el.textContent = (v == null ? '' : v);
        else if (k.indexOf('on') === 0 && typeof v === 'function') el.addEventListener(k.slice(2), v);
        else if (v != null) el.setAttribute(k, v);   // 跳过 null/undefined,避免写出字面 "null"/"undefined" 属性
      });
    }
    if (children != null) {
      if (!Array.isArray(children)) children = [children];
      children.forEach(function (c) {
        if (c == null) return;
        el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
      });
    }
    return el;
  };

  /** HTML 转义 */
  DN.esc = function (s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  };

  /** 字节可读化 */
  DN.fmtBytes = function (n) {
    if (n == null) return '-';
    var v = Number(n); if (isNaN(v) || v < 0) return '-';
    var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return v.toFixed(1) + ' ' + u[i];
  };

  // 友好相对时间："3分钟前"/"2小时前"/"昨天 14:30"，超 7 天回退绝对时间。无效/空返回 '-'。
  DN.fmtAgo = function (ts) {
    if (ts == null || ts === '') return '-';
    var s = String(ts).replace('T', ' ').trim();
    // 兼容 'YYYY-MM-DD HH:mm:ss' 与 ISO，统一按本地时间解析
    var d = new Date(s.indexOf('-') > 0 ? s.replace(/-/g, '/').split('.')[0] : s);
    if (isNaN(d.getTime())) return String(ts);
    var diff = (Date.now() - d.getTime()) / 1000;       // 秒
    if (diff < 0) return s.slice(0, 19);                 // 未来时间直接显示绝对
    if (diff < 60) return '刚刚';
    if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前';
    if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前';
    if (diff < 172800) return '昨天 ' + s.slice(11, 16);
    if (diff < 604800) return Math.floor(diff / 86400) + ' 天前';
    return s.slice(0, 10);                               // 超 7 天显示日期
  };

  // 相对时间 <span>，title 挂完整绝对时间（鼠标悬停可见，审计可溯源）
  DN.timeAgo = function (ts) {
    var full = ts == null ? '' : String(ts).replace('T', ' ').slice(0, 19);
    return DN.h('span', { text: DN.fmtAgo(ts), title: full, style: 'white-space:nowrap' });
  };

  // 表单回车提交：在容器内输入框按 Enter 触发提交（textarea/Shift+Enter 不触发，避免误提交）。
  // fn 省略时自动点击容器内首个 .btn-primary 按钮。
  DN.enterSubmit = function (container, fn) {
    if (!container || !container.addEventListener) return;
    container.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
      var t = e.target, tag = t && t.tagName;
      if (tag === 'TEXTAREA' || tag === 'BUTTON' || tag === 'A') return;
      if (typeof fn === 'function') { e.preventDefault(); fn(); return; }
      var btn = container.querySelector('.btn-primary');
      if (btn && !btn.disabled) { e.preventDefault(); btn.click(); }
    });
  };

  // ===== 元数据下拉数据源（数仓侧，复用工作台同款接口） =====
  DN.metaDatabases = function () { return DN.get('/api/metadata/databases'); };
  DN.metaTables = function (db) { return DN.get('/api/metadata/tables?db=' + encodeURIComponent(db)); };
  DN.metaColumns = function (db, table) {
    return DN.get('/api/metadata/columns?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table));
  };

  /**
   * 级联库/表(/列)下拉选择器，用 dn-form-select 表单体系，避免用户手输。
   * opts: { withColumn, defaultDb, onChange(db,table,column) }
   * 返回 { el, db(), table(), column() }
   */
  DN.dbTablePicker = function (opts) {
    opts = opts || {};
    var withColumn = !!opts.withColumn;
    function sel(ph) {
      // width:auto 覆盖表单类的 width:100%, 否则每个 select 占满整行致 flex 容器换行竖排(库/表错位)
      var s = DN.h('select', { class: 'dn-form-select', style: 'width:auto;min-width:150px;margin-right:4px;' });
      s.innerHTML = '<option value="">' + ph + '</option>';
      return s;
    }
    function lbl(t) { return DN.h('span', { text: t, style: 'color:var(--text-muted);font-size:13px;margin-right:4px;' }); }
    function addOpts(s, items, val, text) {
      (items || []).forEach(function (it) {
        var o = document.createElement('option');
        o.value = val(it); o.textContent = text(it);
        s.appendChild(o);
      });
    }
    var dbSel = sel('选择库'), tbSel = sel('选择表'), colSel = withColumn ? sel('选择字段') : null;
    var wrap = DN.h('div', { style: 'display:flex;gap:6px;align-items:center;flex-wrap:wrap;margin-bottom:14px;' },
      [lbl('库'), dbSel, lbl('表'), tbSel].concat(withColumn ? [lbl('字段'), colSel] : []));
    function fire() { if (opts.onChange) opts.onChange(dbSel.value, tbSel.value, colSel ? colSel.value : null); }
    dbSel.onchange = function () {
      tbSel.innerHTML = '<option value="">选择表</option>';
      if (colSel) colSel.innerHTML = '<option value="">选择字段</option>';
      if (dbSel.value) DN.metaTables(dbSel.value).then(function (ts) { addOpts(tbSel, ts, function (t) { return t; }, function (t) { return t; }); }).catch(function () {});
      fire();
    };
    tbSel.onchange = function () {
      if (colSel) {
        colSel.innerHTML = '<option value="">选择字段</option>';
        if (dbSel.value && tbSel.value) DN.metaColumns(dbSel.value, tbSel.value)
          .then(function (cs) { addOpts(colSel, cs, function (c) { return c.name; }, function (c) { return c.name + (c.type ? ' (' + c.type + ')' : ''); }); }).catch(function () {});
      }
      fire();
    };
    if (colSel) colSel.onchange = fire;
    DN.metaDatabases().then(function (dbs) {
      addOpts(dbSel, dbs, function (d) { return d; }, function (d) { return d; });
      if (opts.defaultDb) { dbSel.value = opts.defaultDb; dbSel.onchange(); }
    }).catch(function () {});
    return { el: wrap, db: function () { return dbSel.value; }, table: function () { return tbSel.value; }, column: function () { return colSel ? colSel.value : null; } };
  };

  // ===== 现代 UI 套件（配合 css/gov-modern.css，让各治理模块渲染一致的现代界面） =====
  var ICONS = {
    grid: '<rect x="3" y="3" width="6" height="6" rx="1"/><rect x="11" y="3" width="6" height="6" rx="1"/><rect x="3" y="11" width="6" height="6" rx="1"/><rect x="11" y="11" width="6" height="6" rx="1"/>',
    chart: '<path d="M3 16V8M8 16V4M13 16v-6M3 16h14"/>',
    db: '<ellipse cx="10" cy="5" rx="6.5" ry="2.5"/><path d="M3.5 5v10c0 1.4 2.9 2.5 6.5 2.5s6.5-1.1 6.5-2.5V5"/><path d="M3.5 10c0 1.4 2.9 2.5 6.5 2.5s6.5-1.1 6.5-2.5"/>',
    lineage: '<circle cx="5" cy="5" r="2.2"/><circle cx="15" cy="10" r="2.2"/><circle cx="5" cy="15" r="2.2"/><path d="M7 6l6 3M7 14l6-3"/>',
    shield: '<path d="M10 2l7 3v5c0 4-3 6.8-7 8-4-1.2-7-4-7-8V5l7-3z"/><path d="M7 9.5l2 2 4-4"/>',
    doc: '<rect x="4" y="2.5" width="12" height="15" rx="1.5"/><path d="M7 7h6M7 10h6M7 13h4"/>',
    tag: '<path d="M3 3h6l8 8-6 6-8-8V3z"/><circle cx="6.5" cy="6.5" r="1.2"/>',
    alert: '<path d="M10 3l8 14H2L10 3z"/><path d="M10 8v4M10 14.5h.01"/>',
    check: '<circle cx="10" cy="10" r="7.5"/><path d="M6.5 10l2.5 2.5 4.5-5"/>',
    search: '<circle cx="8.5" cy="8.5" r="5.5"/><path d="M13 13l4 4"/>',
    inbox: '<path d="M3 12l2.5-7h9L17 12v4H3v-4z"/><path d="M3 12h4l1 2h4l1-2h4"/>',
    clock: '<circle cx="10" cy="10" r="7.5"/><path d="M10 5.5V10l3 2"/>',
    user: '<circle cx="10" cy="6.5" r="3"/><path d="M4 17c0-3.3 2.7-5 6-5s6 1.7 6 5"/>',
    lock: '<rect x="4" y="9" width="12" height="8" rx="1.5"/><path d="M7 9V6.5a3 3 0 016 0V9"/>',
    layers: '<path d="M10 3l7 3.5-7 3.5-7-3.5L10 3z"/><path d="M3 10l7 3.5 7-3.5M3 13.5l7 3.5 7-3.5"/>',
    list: '<path d="M6 5h11M6 10h11M6 15h11"/><circle cx="3" cy="5" r="1"/><circle cx="3" cy="10" r="1"/><circle cx="3" cy="15" r="1"/>',
    pin: '<path d="M10 17v-4M6 13h8l-1-4 1.5-3h-9L7 9l-1 4z"/>',
    flag: '<path d="M5 3v14M5 4h9l-2 3 2 3H5"/>',
    bot: '<rect x="4" y="7" width="12" height="9" rx="2"/><path d="M10 4v3M7.5 11h.01M12.5 11h.01"/><circle cx="10" cy="3" r="1"/>'
  };
  DN.icon = function (name, attrs) {
    var p = ICONS[name] || ICONS.grid;
    // 默认 1em(= 容器 font-size)。SVG 不写 width/height 时按规范默认 100%, 在无尺寸容器里会撑成 300px 巨图;
    // 用 1em 让图标随文字大小走(永不撑大), 既有 .xxx svg{width:Npx} 之类 CSS 规则优先级更高仍可覆盖到精确尺寸。
    // 需要更大图标的容器: 给容器设 font-size(如 40px)即可, 或用 CSS 给该处 svg 设 width/height。
    return '<svg width="1em" height="1em" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" ' + (attrs || '') + '>' + p + '</svg>';
  };

  DN.statTile = function (o) {
    o = o || {};
    var meta = DN.h('div', {}, [
      DN.h('div', { class: 'v', text: (o.value == null ? '-' : String(o.value)) }),
      DN.h('div', { class: 'l', text: o.label || '' })
    ]);
    if (o.sub) meta.appendChild(DN.h('div', { class: 'sub', text: o.sub }));
    var tile = DN.h('div', { class: 'gov-stat' + (o.tone ? ' tone-' + o.tone : '') + (typeof o.onClick === 'function' ? ' clickable' : '') },
      [DN.h('div', { class: 'ic', html: DN.icon(o.icon || 'chart') }), meta]);
    if (typeof o.onClick === 'function') {
      tile.setAttribute('tabindex', '0'); if (o.title) tile.title = o.title;
      tile.addEventListener('click', o.onClick);
      tile.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); o.onClick(); } });
    }
    return tile;
  };
  DN.statRow = function (tiles) { return DN.h('div', { class: 'gov-stats' }, (tiles || []).map(function (t) { return DN.statTile(t); })); };

  /** 卡片：返回 {el, body}，把内容 append 到 body */
  DN.card = function (o) {
    o = o || {};
    var body = DN.h('div', { class: 'gov-card-bd' });
    var hd = DN.h('div', { class: 'gov-card-hd' }, [
      DN.h('span', { class: 'ic', html: DN.icon(o.icon || 'grid') }),
      DN.h('span', { text: o.title || '' }), DN.h('span', { class: 'sp' })
    ]);
    if (o.actions) (Array.isArray(o.actions) ? o.actions : [o.actions]).forEach(function (a) { if (a) hd.appendChild(a); });
    return { el: DN.h('div', { class: 'gov-card' }, [hd, body]), body: body };
  };

  DN.empty = function (text, icon) {
    return DN.h('div', { class: 'gov-empty' }, [DN.h('span', { html: DN.icon(icon || 'inbox') }), DN.h('div', { class: 'et', text: text || '暂无数据' })]);
  };
  // 错误态 + 重试按钮：加载失败时展示原因 + 可点重试(重新执行 retryFn)，替代纯空态/纯 toast，让用户无需刷新整页即可恢复
  DN.errorBox = function (text, retryFn) {
    var box = DN.h('div', { class: 'gov-empty' }, [DN.h('span', { html: DN.icon('alert') }), DN.h('div', { class: 'et', text: text || '加载失败' })]);
    if (typeof retryFn === 'function') {
      box.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', style: 'margin-top:10px', text: '重试', onclick: function () { retryFn(); } }));
    }
    return box;
  };
  DN.skeleton = function (rows) {
    var w = DN.h('div', {});
    for (var i = 0; i < (rows || 3); i++) w.appendChild(DN.h('div', { class: 'gov-skel', style: 'width:' + (100 - i * 7) + '%' }));
    return w;
  };
  DN.pill = function (text, tone) { return DN.h('span', { class: 'gov-pill is-' + (tone || 'muted'), text: text }); };

  function toneColor(t) { return t === 'ok' ? 'var(--success)' : t === 'warn' ? 'var(--warning)' : t === 'err' ? 'var(--error)' : 'var(--primary)'; }
  DN.bars = function (items) {
    items = (items || []).filter(function (i) { return i != null; });   // 剔除空项,防 i.max/i.value 取值崩
    var max = Math.max.apply(null, items.map(function (i) { return i.max || i.value || 0; }).concat([1]));
    var w = DN.h('div', {});
    items.forEach(function (i) {
      var pct = Math.round((i.value || 0) / (i.max || max || 1) * 100);
      var fill = DN.h('div', { class: 'bf', style: 'width:' + pct + '%;background:' + toneColor(i.tone) });
      var bar = DN.h('div', { class: 'gov-bar' }, [DN.h('span', { class: 'bl', text: i.label }), DN.h('div', { class: 'bt' }, [fill]), DN.h('span', { class: 'bv', text: (i.display != null ? i.display : i.value) })]);
      if (typeof i.onClick === 'function') { bar.style.cursor = 'pointer'; bar.title = '点击筛选'; bar.addEventListener('click', function () { i.onClick(i); }); }
      w.appendChild(bar);
    });
    return w;
  };

  /** 环形仪表：值 0-100，按分值着色(红<60/黄<80/绿) */
  DN.gauge = function (val, opts) {
    opts = opts || {}; var size = opts.size || 124, sw = 11, r = (size - sw) / 2, c = 2 * Math.PI * r;
    var v = Math.max(0, Math.min(100, Number(val) || 0)); var col = v < 60 ? 'var(--error)' : v < 80 ? 'var(--warning)' : 'var(--success)';
    var svg = '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '">'
      + '<circle cx="' + (size / 2) + '" cy="' + (size / 2) + '" r="' + r + '" fill="none" style="stroke:var(--bg-sunken)" stroke-width="' + sw + '"/>'
      + '<circle cx="' + (size / 2) + '" cy="' + (size / 2) + '" r="' + r + '" fill="none" stroke-width="' + sw + '" stroke-linecap="round" stroke-dasharray="' + c + '" stroke-dashoffset="' + (c * (1 - v / 100)) + '" transform="rotate(-90 ' + (size / 2) + ' ' + (size / 2) + ')" style="stroke:' + col + ';transition:stroke-dashoffset .6s var(--ease)"/></svg>';
    var ring = DN.h('div', { style: 'position:relative;width:' + size + 'px;height:' + size + 'px', html: svg });
    ring.appendChild(DN.h('div', { style: 'position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center' },
      [DN.h('div', { class: 'gv num', style: 'color:' + col, text: (opts.decimals ? v.toFixed(opts.decimals) : Math.round(v)) }), opts.label ? DN.h('div', { class: 'gl', text: opts.label }) : null]));
    return DN.h('div', { class: 'gov-gauge' }, [ring]);
  };

  /**
   * 现代表格(内置搜索+客户端分页+空态)。
   * o: { columns:[{key,label,align,render(row)->Node|string, html:bool}], rows, pageSize, search, searchKeys, searchPlaceholder, toolbar, empty, emptyIcon }
   * 返回 element，附 .reload(rows)
   */
  DN.table = function (o) {
    o = o || {}; var cols = o.columns || [], all = (o.rows || []).filter(function (r) { return r != null; }), pageSize = o.pageSize || 20, page = 1, q = '';
    var sortKey = null, sortDir = 1, inp = null, _t = null;
    var wrap = DN.h('div', {});
    if (o.search !== false) {
      inp = DN.h('input', { placeholder: o.searchPlaceholder || '搜索...', oninput: function () { clearTimeout(_t); inp.style.opacity = '0.6'; _t = setTimeout(function () { q = inp.value.trim().toLowerCase(); page = 1; draw(); inp.style.opacity = '1'; }, 220); } });
      var clr = DN.h('span', { class: 'gov-search-clr', text: '×', title: '清除', onclick: function () { inp.value = ''; q = ''; page = 1; draw(); inp.focus(); } });
      var bar = DN.h('div', { class: 'gov-toolbar' }, [DN.h('div', { class: 'gov-search' }, [DN.h('span', { html: DN.icon('search') }), inp, clr])]);
      if (o.exportName) bar.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '导出CSV', onclick: function () { exportCsv(); } }));
      if (o.toolbar) (Array.isArray(o.toolbar) ? o.toolbar : [o.toolbar]).forEach(function (t) { if (t) bar.appendChild(t); });
      wrap.appendChild(bar);
    } else if (o.toolbar || o.exportName) {
      var bar2 = DN.h('div', { class: 'gov-toolbar' });
      if (o.exportName) bar2.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '导出CSV', onclick: function () { exportCsv(); } }));
      if (o.toolbar) (Array.isArray(o.toolbar) ? o.toolbar : [o.toolbar]).forEach(function (t) { if (t) bar2.appendChild(t); }); wrap.appendChild(bar2);
    }
    var tw = DN.h('div', { class: 'gov-tbl-wrap' }), pager = DN.h('div', { class: 'gov-pager' });
    wrap.appendChild(tw); wrap.appendChild(pager);
    function filt() {
      var data = !q ? all.slice() : all.filter(function (r) {
        var s = (o.searchKeys || Object.keys(r)).map(function (k) { return r[k]; }).join(' ');
        return String(s).toLowerCase().indexOf(q) >= 0;
      });
      if (sortKey) {
        data.sort(function (a, b) {
          var va = a[sortKey], vb = b[sortKey];
          var na = Number(va), nb = Number(vb);
          if (!isNaN(na) && !isNaN(nb) && va !== '' && vb !== '') return (na - nb) * sortDir;
          return String(va == null ? '' : va).localeCompare(String(vb == null ? '' : vb)) * sortDir;
        });
      }
      return data;
    }
    function exportCsv() {
      var data = filt();
      var head = cols.filter(function (c) { return c.label; }).map(function (c) { return c.label; });
      var lines = [head.map(csvCell).join(',')];
      data.forEach(function (r) {
        lines.push(cols.filter(function (c) { return c.label; }).map(function (c) {
          var v = c.exportValue ? c.exportValue(r) : (r[c.key] == null ? '' : r[c.key]);
          return csvCell(v);
        }).join(','));
      });
      var blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
      var url = URL.createObjectURL(blob), a = document.createElement('a');
      a.href = url; a.download = (o.exportName || 'export') + '_' + new Date().toISOString().slice(0, 10) + '.csv';
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    }
    function csvCell(v) { var s = String(v == null ? '' : v).replace(/[\r\n]+/g, ' '); if (/^[=+\-@]/.test(s)) s = "'" + s; return '"' + s.replace(/"/g, '""') + '"'; }
    function draw() {
      var data = filt(), total = data.length, pages = Math.max(1, Math.ceil(total / pageSize));
      if (page > pages) page = pages;
      tw.innerHTML = '';
      if (!total) {
        if (q && all.length) {
          var em = DN.empty('未找到匹配「' + (inp ? inp.value : q) + '」的记录', 'search');
          em.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '清空搜索', style: 'color:var(--primary);font-size:12px', onclick: function () { if (inp) inp.value = ''; q = ''; page = 1; draw(); } }));
          tw.appendChild(em);
        } else tw.appendChild(DN.empty(o.empty || '暂无数据', o.emptyIcon));
        pager.innerHTML = ''; return;
      }
      var thead = '<thead><tr>' + cols.map(function (c) {
        var arrow = c.sortable ? (sortKey === c.key ? (sortDir > 0 ? ' ▲' : ' ▼') : ' ⇅') : '';
        return '<th scope="col"' + (c.align ? ' style="text-align:' + c.align + (c.sortable ? ';cursor:pointer' : '') + '"' : (c.sortable ? ' style="cursor:pointer"' : '')) + (c.sortable ? ' data-sk="' + DN.esc(c.key) + '" role="button" tabindex="0"' : '') + '>' + DN.esc(c.label) + arrow + '</th>';
      }).join('') + '</tr></thead>';
      var table = DN.h('table', { class: 'gov-tbl', html: thead }), tbody = document.createElement('tbody');
      Array.prototype.slice.call(table.querySelectorAll('th[data-sk]')).forEach(function (th) {
        var doSort = function () { var k = th.getAttribute('data-sk'); if (sortKey === k) sortDir = -sortDir; else { sortKey = k; sortDir = 1; } page = 1; draw(); };
        th.addEventListener('click', doSort);
        th.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); doSort(); } }); // 键盘可排序
      });
      data.slice((page - 1) * pageSize, page * pageSize).forEach(function (r) {
        var tr = document.createElement('tr');
        cols.forEach(function (c) {
          var td = document.createElement('td'); if (c.align) { td.style.textAlign = c.align; if (c.align === 'right') td.style.fontVariantNumeric = 'tabular-nums'; }
          var cell = c.render ? c.render(r) : (r[c.key] == null ? '' : r[c.key]);
          if (cell instanceof Node) td.appendChild(cell); else if (c.html) td.innerHTML = cell; else td.textContent = String(cell);
          if (c.copyable) {
            td.style.cursor = 'copy';
            var cv = c.exportValue ? c.exportValue(r) : (r[c.key] == null ? td.textContent : r[c.key]);
            td.title = '点击复制：' + String(cv);
            td.addEventListener('click', function (e) { e.stopPropagation(); DN.copy(cv); });
          }
          tr.appendChild(td);
        });
        tbody.appendChild(tr);
      });
      table.appendChild(tbody); tw.appendChild(table);
      pager.innerHTML = '';
      pager.appendChild(DN.h('span', { text: (q ? '匹配 ' + total + '/' + all.length : '共 ' + total) + ' 条' + (pages > 1 ? ' · ' + page + '/' + pages : '') }));
      if (pages > 1) {
        pager.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '上一页', onclick: function () { if (page > 1) { page--; draw(); } } }));
        pager.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '下一页', onclick: function () { if (page < pages) { page++; draw(); } } }));
      }
    }
    draw();
    wrap.reload = function (rows) { all = (rows || []).filter(function (r) { return r != null; }); page = 1; draw(); };
    return wrap;
  };

  /** 右侧抽屉：返回 {close, body}。footerNode 可选: 渲染为 sticky 底部操作栏(.df), 永远贴底不随内容滚走。 */
  DN.drawer = function (title, bodyNode, footerNode) {
    // 替换旧抽屉时先解绑其 keydown 监听,避免监听器泄漏累积
    if (DN._drawerKey) { document.removeEventListener('keydown', DN._drawerKey); DN._drawerKey = null; }
    var old = document.getElementById('govDrawerMask'); if (old) old.remove();
    var oldD = document.querySelector('.gov-drawer'); if (oldD) oldD.remove();
    var _prevFocus = document.activeElement; // 关闭后归还焦点(a11y)
    var mask = DN.h('div', { id: 'govDrawerMask' });
    var bd = DN.h('div', { class: 'db' }); if (bodyNode) bd.appendChild(bodyNode);
    var df = footerNode ? DN.h('div', { class: 'df' }, [footerNode]) : null;
    function onKey(e) {
      if (e.key === 'Escape') { close(); return; }
      if (e.key === 'Tab') { // 焦点陷阱:Tab 在抽屉内循环(规范模态 a11y)
        var fs = Array.prototype.filter.call(
          dr.querySelectorAll('a[href],button,input,select,textarea,[tabindex]:not([tabindex="-1"])'),
          function (el) { return !el.disabled && el.offsetParent !== null; });
        if (!fs.length) return;
        var first = fs[0], last = fs[fs.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    }
    var _closing = false;
    function close() { if (_closing) return; _closing = true; mask.onclick = null; document.removeEventListener('keydown', onKey); if (DN._drawerKey === onKey) DN._drawerKey = null; mask.classList.remove('show'); dr.classList.remove('show'); try { if (_prevFocus && _prevFocus.focus) _prevFocus.focus(); } catch (e) {} setTimeout(function () { if (mask.parentNode) mask.remove(); if (dr.parentNode) dr.remove(); }, 250); }
    var drKids = [DN.h('div', { class: 'dh' }, [DN.h('span', { text: title || '' }), DN.h('button', { class: 'x', text: '×', onclick: close, 'aria-label': '关闭' })]), bd];
    if (df) drKids.push(df);
    var dr = DN.h('div', { class: 'gov-drawer', role: 'dialog', 'aria-modal': 'true', 'aria-label': title || '详情' }, drKids);
    mask.onclick = close;
    document.body.appendChild(mask); document.body.appendChild(dr);
    DN._drawerKey = onKey; document.addEventListener('keydown', onKey);
    requestAnimationFrame(function () { mask.classList.add('show'); dr.classList.add('show'); var f = bd.querySelector('input,select,textarea,button'); if (f) try { f.focus(); } catch (e) {} });
    return { close: close, body: bd };
  };

  // ===== 表单组件(配合 app.css .dn-* 表单设计系统, 让抽屉/弹窗表单美观人性化) =====
  /** 输入框: opts {placeholder, type, value, disabled, textarea} */
  DN.formInput = function (opts) {
    opts = opts || {};
    var el = DN.h(opts.textarea ? 'textarea' : 'input', { class: 'dn-form-input' });
    if (opts.type && !opts.textarea) el.type = opts.type;
    if (opts.placeholder != null) el.placeholder = opts.placeholder;
    if (opts.value != null) el.value = String(opts.value);
    if (opts.disabled) el.disabled = true;
    return el;
  };
  /** 下拉: options 为 [val|[val,label],...], val 为当前值 */
  DN.formSelect = function (options, val) {
    var s = DN.h('select', { class: 'dn-form-select' });
    (options || []).forEach(function (o) {
      var ov = Array.isArray(o) ? o[0] : o, ol = Array.isArray(o) ? o[1] : o;
      var op = DN.h('option', { value: ov, text: ol });
      if (val != null && String(val) === String(ov)) op.selected = true;
      s.appendChild(op);
    });
    return s;
  };
  /** 字段: 标签(可必填红星) + 控件 + 提示。opts {hint, required} */
  DN.field = function (label, control, opts) {
    opts = opts || {};
    var lab = DN.h('label', { text: label || '' });
    if (opts.required) lab.appendChild(DN.h('span', { class: 'req', text: '*' }));
    var f = DN.h('div', { class: 'dn-field' + (opts.col2 ? ' dn-field-col2' : '') }, [lab, control]);
    if (opts.hint) f.appendChild(DN.h('div', { class: 'dn-hint', text: opts.hint }));
    return f;
  };
  /** 分组小节: 标题 + 容纳字段的容器。返回 {el, add(node)} */
  DN.formSection = function (title) {
    var box = DN.h('div', { class: 'dn-form-section' });
    if (title) box.appendChild(DN.h('div', { class: 'dn-sec-title', text: title }));
    return { el: box, add: function (n) { box.appendChild(n); return box; } };
  };
  /** 短字段两列容器: 把多个 dn-field 并成两列 */
  DN.formGrid2 = function (fields) {
    var g = DN.h('div', { class: 'dn-form-grid2' });
    (fields || []).forEach(function (f) { if (f) g.appendChild(f); });
    return g;
  };
  /** 抽屉底部操作栏: 取消(ghost) + 主按钮。opts {okText, onOk, onCancel(默认关闭), extra(左侧节点)}; 返回 {el, ok, busy(text), reset(text)} */
  DN.drawerFoot = function (opts) {
    opts = opts || {};
    var ok = DN.h('button', { class: 'btn btn-primary btn-sm', text: opts.okText || '保存', style: 'height:32px;padding:0 18px;' });
    var cancel = DN.h('button', { class: 'btn btn-sm', text: opts.cancelText || '取消', style: 'height:32px;padding:0 16px;' });
    var foot = DN.h('div', {});
    if (opts.extra) foot.appendChild(DN.h('span', { class: 'foot-grow' }, [opts.extra]));
    foot.appendChild(cancel); foot.appendChild(ok);
    if (typeof opts.onOk === 'function') ok.onclick = opts.onOk;
    cancel.onclick = function () { if (typeof opts.onCancel === 'function') opts.onCancel(); };
    return {
      el: foot, ok: ok, cancel: cancel,
      busy: function (t) { ok.disabled = true; cancel.disabled = true; ok.textContent = t || '保存中…'; },
      reset: function (t) { ok.disabled = false; cancel.disabled = false; ok.textContent = t || (opts.okText || '保存'); }
    };
  };

  /** 纯 SVG 雷达图。dims:[{label,value(0-100)}]，opts:{size,color} */
  DN.radar = function (dims, opts) {
    opts = opts || {}; dims = dims || [];
    var n = dims.length; if (!n) return DN.empty('暂无维度数据', 'chart');
    var size = opts.size || 230, cx = size / 2, cy = size / 2, r = size / 2 - 38, color = opts.color || 'var(--primary)';
    function pt(i, frac) { var a = -Math.PI / 2 + i * 2 * Math.PI / n; return [cx + r * frac * Math.cos(a), cy + r * frac * Math.sin(a)]; }
    var svg = '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '">';
    [0.25, 0.5, 0.75, 1].forEach(function (fr) {
      var ps = []; for (var i = 0; i < n; i++) { var p = pt(i, fr); ps.push(p[0].toFixed(1) + ',' + p[1].toFixed(1)); }
      svg += '<polygon points="' + ps.join(' ') + '" fill="none" style="stroke:var(--divider)" stroke-width="1"/>';
    });
    for (var i = 0; i < n; i++) {
      var pe = pt(i, 1); svg += '<line x1="' + cx + '" y1="' + cy + '" x2="' + pe[0].toFixed(1) + '" y2="' + pe[1].toFixed(1) + '" style="stroke:var(--divider)"/>';
      var lp = pt(i, 1.18); var anchor = Math.abs(lp[0] - cx) < 6 ? 'middle' : (lp[0] > cx ? 'start' : 'end');
      svg += '<text x="' + lp[0].toFixed(1) + '" y="' + lp[1].toFixed(1) + '" font-size="11" style="fill:var(--text-faint)" text-anchor="' + anchor + '" dominant-baseline="middle">' + DN.esc(dims[i].label) + '</text>';
    }
    var dp = []; for (var i = 0; i < n; i++) { var v = Math.max(0, Math.min(100, dims[i].value || 0)) / 100; var p = pt(i, v); dp.push(p[0].toFixed(1) + ',' + p[1].toFixed(1)); }
    svg += '<polygon points="' + dp.join(' ') + '" style="fill:rgba(var(--primary-rgb),.16);stroke:' + color + '" stroke-width="2"/>';
    for (var i = 0; i < n; i++) { var v = Math.max(0, Math.min(100, dims[i].value || 0)) / 100; var p = pt(i, v); svg += '<circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="3" style="fill:' + color + '"/>'; }
    return DN.h('div', { class: 'gov-radar', html: svg + '</svg>' });
  };

  /** 纯 SVG 折线/面积趋势图。values:[number]，opts:{height,color,max,min,area} */
  DN.line = function (values, opts) {
    opts = opts || {}; var data = (values || []).map(Number).filter(function (x) { return !isNaN(x); });
    if (data.length < 2) return DN.empty('趋势数据不足（需至少 2 个数据点）', 'chart');
    var h = opts.height || 72, pad = 6, vw = 300, n = data.length;
    var max = opts.max != null ? opts.max : Math.max.apply(null, data.concat([1])), min = opts.min != null ? opts.min : Math.min.apply(null, data.concat([0]));
    if (max === min) max = min + 1;
    var stepX = n > 1 ? (vw - pad * 2) / (n - 1) : 0, col = opts.color || 'var(--primary)';
    function X(i) { return pad + i * stepX; } function Y(v) { return (h - pad) - ((v - min) / (max - min)) * (h - pad * 2); }
    var pts = data.map(function (v, i) { return X(i).toFixed(1) + ',' + Y(v).toFixed(1); });
    var svg = '<svg width="100%" height="' + h + '" viewBox="0 0 ' + vw + ' ' + h + '" preserveAspectRatio="none">';
    svg += '<line x1="' + pad + '" y1="' + (h - pad) + '" x2="' + (vw - pad) + '" y2="' + (h - pad) + '" style="stroke:var(--divider)" vector-effect="non-scaling-stroke"/>';
    if (opts.area !== false) svg += '<path d="M' + pts.join(' L') + ' L' + X(n - 1).toFixed(1) + ',' + (h - pad) + ' L' + X(0).toFixed(1) + ',' + (h - pad) + ' Z" style="fill:' + col + '" opacity="0.10"/>';
    svg += '<polyline points="' + pts.join(' ') + '" fill="none" stroke-width="2" vector-effect="non-scaling-stroke" stroke-linejoin="round" style="stroke:' + col + '"/></svg>';
    return DN.h('div', { class: 'gov-line', html: svg });
  };

  /** 环形占比图。segments:[{label,value,color}]，opts:{size,stroke,centerLabel,centerSub} */
  DN.donut = function (segments, opts) {
    opts = opts || {}; var segs = (segments || []).filter(function (s) { return s && (s.value || 0) > 0; });
    var total = segs.reduce(function (a, s) { return a + (s.value || 0); }, 0);
    var size = opts.size || 120, sw = opts.stroke || 14, r = (size - sw) / 2, c = 2 * Math.PI * r, cx = size / 2;
    var svg = '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '"><circle cx="' + cx + '" cy="' + cx + '" r="' + r + '" fill="none" style="stroke:var(--bg-sunken)" stroke-width="' + sw + '"/>';
    var off = 0;
    segs.forEach(function (s) { var len = c * (total ? s.value / total : 0); svg += '<circle cx="' + cx + '" cy="' + cx + '" r="' + r + '" fill="none" stroke-width="' + sw + '" stroke-dasharray="' + len.toFixed(2) + ' ' + (c - len).toFixed(2) + '" stroke-dashoffset="' + (-off).toFixed(2) + '" transform="rotate(-90 ' + cx + ' ' + cx + ')" style="stroke:' + (s.color || 'var(--chart-1)') + ';transition:stroke-dasharray .5s"/>'; off += len; });
    svg += '</svg>';
    var ring = DN.h('div', { style: 'position:relative;width:' + size + 'px;height:' + size + 'px', html: svg });
    if (opts.centerLabel != null || opts.centerSub != null) ring.appendChild(DN.h('div', { style: 'position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center' },
      [opts.centerLabel != null ? DN.h('div', { style: 'font-size:20px;font-weight:750;line-height:1', text: String(opts.centerLabel) }) : null, opts.centerSub ? DN.h('div', { style: 'font-size:var(--fs-xs);color:var(--text-muted);margin-top:2px', text: opts.centerSub }) : null]));
    if (!opts.legend) return ring;
    // 可选内置图例：环 + 图例并排
    var lg = DN.h('div', { class: 'gov-legend' });
    segs.forEach(function (s) { lg.appendChild(DN.h('span', {}, [DN.h('i', { style: 'background:' + (s.color || 'var(--chart-1)') }), DN.h('span', { text: (s.label != null ? s.label : '') + ' ' + (s.value || 0) })])); });
    return DN.h('div', { style: 'display:flex;align-items:center;gap:18px;flex-wrap:wrap' }, [ring, lg]);
  };

  /** 热力清单：items:[{label,value,display?}] 按值映射蓝色深浅。opts:{rgb,onClick} */
  DN.heat = function (items, opts) {
    opts = opts || {}; items = (items || []).filter(function (i) { return i != null; }); if (!items.length) return DN.empty('暂无数据');
    var vals = items.map(function (i) { return Number(i.value) || 0; });
    var max = Math.max.apply(null, vals.concat([1]));
    var base = opts.rgb || [64, 81, 211];
    var w = DN.h('div', { class: 'gov-heat' });
    items.forEach(function (it) {
      var a = (0.1 + (it.value || 0) / max * 0.8).toFixed(2);
      var cell = DN.h('div', { class: 'gov-heat-cell', style: 'background:rgba(' + base.join(',') + ',' + a + ');', title: it.label + ': ' + (it.value || 0) },
        [DN.h('span', { class: 'hl', text: it.label }), DN.h('span', { class: 'hv', text: String(it.display != null ? it.display : it.value) })]);
      if (opts.onClick) { cell.style.cursor = 'pointer'; cell.addEventListener('click', function () { opts.onClick(it); }); }
      w.appendChild(cell);
    });
    return w;
  };

  /** 趋势增量标记 ↑↓→。opts:{lowerBetter,decimals,eps} */
  DN.delta = function (cur, prev, opts) {
    opts = opts || {}; var d = (Number(cur) || 0) - (Number(prev) || 0);
    var dir = Math.abs(d) < (opts.eps || 0.05) ? 'flat' : (d > 0 ? 'up' : 'down');
    var good = opts.lowerBetter ? dir === 'down' : dir === 'up';
    var tone = dir === 'flat' ? 'muted' : (good ? 'ok' : 'err');
    var txt = (d > 0 ? '+' : '') + (opts.decimals != null ? d.toFixed(opts.decimals) : Math.round(d));
    return DN.h('span', { class: 'gov-delta is-' + tone, text: (dir === 'flat' ? '→ ' : dir === 'up' ? '↑ ' : '↓ ') + txt });
  };

  /** 可关闭的持久告警条节点。tone: warn|err|ok|info */
  DN.alertNode = function (msg, tone) {
    var bar = DN.h('div', { class: 'gov-alert is-' + (tone || 'warn') });
    bar.appendChild(DN.h('span', { class: 'ic', html: DN.icon(tone === 'ok' ? 'check' : 'alert') }));
    bar.appendChild(DN.h('span', { class: 'm', text: msg }));
    bar.appendChild(DN.h('button', { class: 'x', text: '×', onclick: function () { bar.remove(); } }));
    return bar;
  };

  /** 小节标题 */
  DN.sectionTitle = function (text) { return DN.h('div', { class: 'gov-section-title', text: text }); };

  /**
   * v3 统一确认弹窗(取代 window.confirm)。返回 Promise<boolean>。
   * opts: { title, danger(危险操作红色主按钮), okText, cancelText }
   * 用法: DN.confirm('删除任务「xx」?', {danger:true}).then(ok => { if (ok) ... })
   */
  DN.confirm = function (message, opts) {
    opts = opts || {};
    return new Promise(function (resolve) {
      var prevFocus = document.activeElement;
      var ok = DN.h('button', { class: 'btn ' + (opts.danger ? 'btn-confirm-danger' : 'btn-primary'), text: opts.okText || '确认' });
      var cancel = DN.h('button', { class: 'btn', text: opts.cancelText || '取消' });
      var modal = DN.h('div', { class: 'dn-modal', role: 'alertdialog', 'aria-modal': 'true', 'aria-label': opts.title || '确认操作' }, [
        DN.h('div', { class: 'dm-title', text: opts.title || '确认操作' }),
        DN.h('div', { class: 'dm-body', text: message || '' }),
        DN.h('div', { class: 'dm-foot' }, [cancel, ok])
      ]);
      var mask = DN.h('div', { class: 'dn-modal-mask' }, [modal]);
      var done = false;
      function close(result) {
        if (done) return; done = true;
        document.removeEventListener('keydown', onKey);
        mask.classList.remove('show');
        try { if (prevFocus && prevFocus.focus) prevFocus.focus(); } catch (e) {}
        setTimeout(function () { if (mask.parentNode) mask.remove(); }, 200);
        resolve(result);
      }
      function onKey(e) {
        if (e.key === 'Escape') { close(false); return; }
        if (e.key === 'Tab') {  // 双按钮焦点循环
          e.preventDefault();
          (document.activeElement === ok ? cancel : ok).focus();
        }
      }
      ok.onclick = function () { close(true); };
      cancel.onclick = function () { close(false); };
      mask.onclick = function (e) { if (e.target === mask) close(false); };
      document.addEventListener('keydown', onKey);
      document.body.appendChild(mask);
      requestAnimationFrame(function () { mask.classList.add('show'); try { ok.focus(); } catch (e) {} });
    });
  };

  global.DN = DN;
  // 治理模块渲染器注册表：各 js/gov-<key>.js 注册 render 到此，governance.html 据此渲染
  global.GOV_RENDERERS = global.GOV_RENDERERS || {};
})(window);
