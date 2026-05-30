/* 治理模块：全局审计中心（M12）—— 检索(时间/类型/人/路径) + 分页 + CSV 导出。
   注册 GOV_RENDERERS.audit；并暴露 window.openGovAudit() 供其它模块跳转(导航项后补，governance.html 不改)。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var TYPES = ['', 'LOGIN', 'DATA_ACCESS', 'EXPORT', 'PERM_CHANGE', 'META_CHANGE', 'RULE_CHANGE', 'LABEL_CHANGE', 'OTHER'];
  var state = { page: 1, size: 20, total: 0 };

  window.GOV_RENDERERS.audit = function (c) {
    var ctl = 'width:auto;margin-right:8px';

    // 过滤条
    var bar = DN.h('div', { class: 'gov-desc', style: 'display:flex;gap:0;align-items:center;flex-wrap:wrap' });
    bar.appendChild(DN.h('input', { id: 'auFrom', type: 'date', title: '起始日期', class: 'iw-form-input', style: ctl }));
    bar.appendChild(DN.h('input', { id: 'auTo', type: 'date', title: '截止日期', class: 'iw-form-input', style: ctl }));
    var sel = DN.h('select', { id: 'auType', class: 'iw-form-select', style: ctl });
    TYPES.forEach(function (t) { sel.appendChild(DN.h('option', { value: t, text: t || '全部类型' })); });
    bar.appendChild(sel);
    bar.appendChild(DN.h('input', { id: 'auUser', placeholder: '操作人', class: 'iw-form-input', style: ctl }));
    bar.appendChild(DN.h('input', { id: 'auPath', placeholder: '路径含…', class: 'iw-form-input', style: ctl }));
    bar.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '检索',
      onclick: function () { state.page = 1; load(); } }));
    bar.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '导出CSV',
      onclick: exportCsv, style: 'margin-left:8px' }));
    c.appendChild(bar);

    c.appendChild(DN.h('div', { id: 'auTable' }));
    c.appendChild(DN.h('div', { id: 'auPager', class: 'gov-desc' }));

    load();
  };

  function qsParams() {
    var g = function (id) { return document.getElementById(id).value.trim(); };
    var p = [];
    // date 输入仅含日期，补足为当日起止时刻，保持后端 datetime 过滤语义
    if (g('auFrom')) p.push('from=' + encodeURIComponent(g('auFrom') + ' 00:00:00'));
    if (g('auTo')) p.push('to=' + encodeURIComponent(g('auTo') + ' 23:59:59'));
    if (g('auType')) p.push('actionType=' + encodeURIComponent(g('auType')));
    if (g('auUser')) p.push('userName=' + encodeURIComponent(g('auUser')));
    if (g('auPath')) p.push('path=' + encodeURIComponent(g('auPath')));
    return p;
  }

  function load() {
    var p = qsParams();
    p.push('page=' + state.page);
    p.push('size=' + state.size);
    DN.get('/api/gov/audit/search?' + p.join('&')).then(function (data) {
      state.total = data.total || 0;
      var rows = data.list || [];
      var box = document.getElementById('auTable');
      if (!rows.length) { box.innerHTML = '<div class="gov-placeholder">无审计记录</div>'; renderPager(); return; }
      var body = rows.map(function (r) {
        return '<tr><td>' + DN.esc(fmtTs(r.createdAt)) + '</td><td>' + DN.esc(r.userName) +
          '</td><td>' + DN.esc(r.actionType) + '</td><td>' + DN.esc(r.method || '') +
          '</td><td>' + DN.esc(r.path || '') + '</td><td>' + DN.esc(r.ip || '') +
          '</td><td>' + (r.status == null ? '' : r.status) + '</td><td>' + DN.esc(r.detail || '') + '</td></tr>';
      }).join('');
      box.innerHTML = '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
        '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        '<th style="padding:8px">时间</th><th style="padding:8px">操作人</th><th style="padding:8px">类型</th>' +
        '<th style="padding:8px">方法</th><th style="padding:8px">路径</th><th style="padding:8px">IP</th>' +
        '<th style="padding:8px">状态</th><th style="padding:8px">详情</th></tr></thead><tbody>' + body + '</tbody></table>';
      box.querySelectorAll('td').forEach(function (td) { td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5'; });
      renderPager();
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function renderPager() {
    var pages = Math.max(1, Math.ceil(state.total / state.size));
    var box = document.getElementById('auPager');
    box.innerHTML = '共 ' + state.total + ' 条，第 ' + state.page + '/' + pages + ' 页　';
    var prev = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '上一页' });
    prev.onclick = function () { if (state.page > 1) { state.page--; load(); } };
    var next = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '下一页', style: 'margin-left:8px' });
    next.onclick = function () { if (state.page < pages) { state.page++; load(); } };
    box.appendChild(prev);
    box.appendChild(next);
  }

  function exportCsv() {
    var p = qsParams();
    window.open('/api/gov/audit/export' + (p.length ? '?' + p.join('&') : ''), '_blank');
  }

  function fmtTs(s) {
    if (!s) return '';
    return String(s).replace('T', ' ').slice(0, 19);
  }

  // 供其它模块（健康分/安全）跳转的入口；导航项可后补，本期 governance.html 不改。
  // 直接渲染进 govContent 容器，不改 hash（audit 未登记进 GOV_MODULES，走路由会取不到模块而报错）。
  window.openGovAudit = function () {
    var box = document.getElementById('govContent') || document.body;
    box.innerHTML = '';
    box.appendChild(DN.h('div', { class: 'gov-h1', text: '全局审计中心' }));
    box.appendChild(DN.h('div', { class: 'gov-desc', text: '登录/数据访问/导出/权限·元数据·规则·打标变更的统一审计检索与导出（M12）' }));
    window.GOV_RENDERERS.audit(box);
  };
})();
