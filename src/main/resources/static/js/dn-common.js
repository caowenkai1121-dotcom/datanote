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

  global.DN = DN;
  // 治理模块渲染器注册表：各 js/gov-<key>.js 注册 render 到此，governance.html 据此渲染
  global.GOV_RENDERERS = global.GOV_RENDERERS || {};
})(window);
