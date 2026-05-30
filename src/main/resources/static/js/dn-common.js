/* DataNote 治理公共层 —— 被 governance.html 等治理页面复用。零框架依赖。 */
(function (global) {
  'use strict';
  var DN = {};

  /** 统一请求：解析 R<T> 信封（code===0 成功），失败抛 Error */
  DN.api = function (url, options) {
    return fetch(url, options || {}).then(function (resp) {
      return resp.json().catch(function () { return {}; }).then(function (body) {
        if (body && typeof body.code !== 'undefined') {
          if (body.code === 0) return body.data;
          throw new Error(body.msg || ('请求失败(' + body.code + ')'));
        }
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
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
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(function () { t.classList.add('dn-toast-show'); }, 10);
    setTimeout(function () {
      t.classList.remove('dn-toast-show');
      setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 300);
    }, 2600);
  };

  /** DOM 简化器：DN.h('div', {class:'x', onclick:fn}, [child|text]) */
  DN.h = function (tag, attrs, children) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (k === 'class') el.className = v;
        else if (k === 'html') el.innerHTML = v;
        else if (k === 'text') el.textContent = v;
        else if (k.indexOf('on') === 0 && typeof v === 'function') el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v);
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
    var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0, v = Number(n);
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return v.toFixed(1) + ' ' + u[i];
  };

  // ===== 元数据下拉数据源（数仓侧，复用工作台同款接口） =====
  DN.metaDatabases = function () { return DN.get('/api/metadata/databases'); };
  DN.metaTables = function (db) { return DN.get('/api/metadata/tables?db=' + encodeURIComponent(db)); };
  DN.metaColumns = function (db, table) {
    return DN.get('/api/metadata/columns?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table));
  };

  /**
   * 级联库/表(/列)下拉选择器，用系统原生 .iw-form-select 样式，避免用户手输。
   * opts: { withColumn, defaultDb, onChange(db,table,column) }
   * 返回 { el, db(), table(), column() }
   */
  DN.dbTablePicker = function (opts) {
    opts = opts || {};
    var withColumn = !!opts.withColumn;
    function sel(ph) {
      var s = DN.h('select', { class: 'iw-form-select', style: 'min-width:140px;margin-right:4px;' });
      s.innerHTML = '<option value="">' + ph + '</option>';
      return s;
    }
    function lbl(t) { return DN.h('span', { text: t, style: 'color:var(--text-muted,#86909c);font-size:13px;margin-right:4px;' }); }
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

  global.DN = DN;
  // 治理模块渲染器注册表：各 js/gov-<key>.js 注册 render 到此，governance.html 据此渲染
  global.GOV_RENDERERS = global.GOV_RENDERERS || {};
})(window);
