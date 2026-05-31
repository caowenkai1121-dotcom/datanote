/* 治理模块：全局审计中心（M12）—— 现代套件重绘。
   检索(时间/类型/人/路径) + 现代表格(内置分页) + CSV 导出。
   注册 GOV_RENDERERS.audit；并暴露 window.openGovAudit() 供其它模块跳转(governance.html 不改)。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var TYPES = ['', 'LOGIN', 'DATA_ACCESS', 'EXPORT', 'PERM_CHANGE', 'META_CHANGE', 'RULE_CHANGE', 'LABEL_CHANGE', 'OTHER'];
  // actionType -> 药丸色调
  var TYPE_TONE = {
    LOGIN: 'info', DATA_ACCESS: 'ok', EXPORT: 'warn',
    PERM_CHANGE: 'err', META_CHANGE: 'info', RULE_CHANGE: 'info', LABEL_CHANGE: 'info', OTHER: 'muted'
  };
  var state = { page: 1, size: 50, total: 0 };
  var els = {};
  var auditTbl = null;
  var toolbar = null;
  var lastRows = [];

  function buildTable() {
    return DN.table({
      columns: [
        { key: 'createdAt', label: '时间', render: function (r) { return fmtTs(r.createdAt); } },
        { key: 'actionType', label: '类型', render: function (r) { return r.actionType ? DN.pill(r.actionType, TYPE_TONE[r.actionType] || 'muted') : '-'; } },
        { key: 'userName', label: '操作人', render: function (r) { return r.userName || '-'; } },
        { key: 'method', label: '方法', render: function (r) { return r.method || '-'; } },
        { key: 'path', label: '路径', copyable: true, exportValue: function (r) { return r.path || ''; }, render: function (r) { return r.path || '-'; } },
        { key: 'ip', label: 'IP', render: function (r) { return r.ip || '-'; } },
        { key: 'status', label: '状态', render: function (r) { return r.status == null ? '-' : DN.pill(String(r.status), statusTone(r.status)); } },
        { key: 'detail', label: '详情', render: function (r) { return r.detail || '-'; } }
      ],
      rows: lastRows,
      pageSize: state.size,
      searchKeys: ['path', 'userName', 'detail'],
      searchPlaceholder: '过滤当前结果(路径/人/详情)',
      toolbar: toolbar,
      empty: '无审计记录', emptyIcon: 'doc'
    });
  }
  function rebuildTable() {
    if (!auditTbl || !auditTbl.parentNode) return;
    var fresh = buildTable();
    auditTbl.parentNode.replaceChild(fresh, auditTbl);
    auditTbl = fresh;
  }

  window.GOV_RENDERERS.audit = function (c) {
    state.page = 1;

    // 审计概览（7天时序 + 用户/路径行为分析）
    var ovBox = DN.h('div', { id: 'auOverview' });
    c.appendChild(ovBox);
    renderOverview(ovBox);

    // 统计区（按类型分布）
    var statBox = DN.h('div', { id: 'auStats' });
    c.appendChild(statBox);

    // 过滤工具条
    els.from = DN.h('input', { type: 'date', title: '起始日期', class: 'iw-form-select', style: 'height:34px;' });
    els.to = DN.h('input', { type: 'date', title: '截止日期', class: 'iw-form-select', style: 'height:34px;' });
    els.type = DN.h('select', { class: 'iw-form-select', style: 'height:34px;' });
    TYPES.forEach(function (t) { els.type.appendChild(DN.h('option', { value: t, text: t || '全部类型' })); });
    els.user = DN.h('input', { placeholder: '操作人', class: 'iw-form-select', style: 'height:34px;width:120px;' });
    var searchBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '检索',
      onclick: function () { state.page = 1; load(); } });
    var exportBtn = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '导出CSV', onclick: exportCsv });
    toolbar = [els.from, els.to, els.type, els.user, searchBtn, exportBtn];
    lastRows = [];

    // 现代表格（toolbar 内置过滤条 + 路径搜索框；客户端搜索覆盖当前页路径）
    auditTbl = buildTable();
    c.appendChild(auditTbl);

    load();
  };

  function statusTone(s) {
    var n = Number(s);
    if (isNaN(n)) return 'muted';
    if (n >= 500) return 'err';
    if (n >= 400) return 'warn';
    if (n >= 200 && n < 300) return 'ok';
    return 'info';
  }

  function qsParams() {
    var g = function (el) { return (el && el.value || '').trim(); };
    var p = [];
    if (g(els.from)) p.push('from=' + encodeURIComponent(g(els.from) + ' 00:00:00'));
    if (g(els.to)) p.push('to=' + encodeURIComponent(g(els.to) + ' 23:59:59'));
    if (g(els.type)) p.push('actionType=' + encodeURIComponent(g(els.type)));
    if (g(els.user)) p.push('userName=' + encodeURIComponent(g(els.user)));
    return p;
  }

  function load() {
    var p = qsParams();
    p.push('page=' + state.page);
    p.push('size=' + state.size);
    DN.get('/api/gov/audit/search?' + p.join('&')).then(function (data) {
      data = data || {};
      state.total = data.total || 0;
      var rows = data.list || [];
      lastRows = rows;
      if (auditTbl) auditTbl.reload(rows);
      renderStats(rows);
      renderServerPager(rows.length);
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  // 审计概览：近7天审计量折线 + Top操作人 + Top路径
  function renderOverview(box) {
    box.appendChild(DN.skeleton(3));
    Promise.all([
      DN.get('/api/gov/audit/trend?days=7').catch(function () { return []; }),
      DN.get('/api/gov/audit/stat/user').catch(function () { return []; }),
      DN.get('/api/gov/audit/stat/path').catch(function () { return []; })
    ]).then(function (r) {
      var trend = r[0] || [], users = r[1] || [], paths = r[2] || [];
      box.innerHTML = '';
      var grid = DN.h('div', { class: 'gov-grid' });
      // 7天时序
      var tc = DN.card({ title: '近 7 天审计量', icon: 'clock' });
      tc.el.classList.add('primary');
      if (trend.length) {
        var vals = trend.map(function (x) { return Number(x.cnt) || 0; });
        tc.body.appendChild(DN.line(vals, { height: 80, color: '#1890ff' }));
        tc.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:8px 0 0;text-align:center', text: trend[0].day + ' ~ ' + trend[trend.length - 1].day + ' · 合计 ' + vals.reduce(function (a, b) { return a + b; }, 0) + ' 条' }));
      } else { tc.body.appendChild(DN.empty('暂无审计数据', 'clock')); }
      grid.appendChild(tc.el);
      // Top 操作人
      var uc = DN.card({ title: 'Top 活跃操作人', icon: 'user' });
      if (users.length) uc.body.appendChild(DN.bars(users.slice(0, 8).map(function (u) {
        return { label: u.userName || '-', value: Number(u.cnt) || 0, tone: 'info', display: String(u.cnt),
          onClick: function () { if (els.user) { els.user.value = u.userName || ''; state.page = 1; load(); DN.toast('已按操作人 ' + (u.userName || '') + ' 筛选'); } } };
      })));
      else uc.body.appendChild(DN.empty('暂无数据', 'user'));
      grid.appendChild(uc.el);
      // Top 路径
      var pc = DN.card({ title: 'Top 高频路径', icon: 'list' });
      if (paths.length) pc.body.appendChild(DN.heat(paths.slice(0, 9).map(function (p) { return { label: p.path || '-', value: Number(p.cnt) || 0 }; })));
      else pc.body.appendChild(DN.empty('暂无数据', 'list'));
      grid.appendChild(pc.el);
      box.appendChild(grid);
    }).catch(function () { box.innerHTML = ''; });
  }

  // 按类型统计（本页数据）用 DN.bars
  function renderStats(rows) {
    var box = document.getElementById('auStats');
    if (!box) return;
    box.innerHTML = '';
    if (!rows.length) return;
    var byType = {};
    rows.forEach(function (r) { var t = r.actionType || 'OTHER'; byType[t] = (byType[t] || 0) + 1; });
    var items = Object.keys(byType).sort(function (a, b) { return byType[b] - byType[a]; }).map(function (t) {
      var tone = TYPE_TONE[t] === 'ok' ? 'ok' : TYPE_TONE[t] === 'warn' ? 'warn' : TYPE_TONE[t] === 'err' ? 'err' : 'info';
      return { label: t, value: byType[t], tone: tone };
    });
    var card = DN.card({ title: '本页操作类型分布', icon: 'chart' });
    card.body.appendChild(DN.bars(items));
    box.appendChild(card.el);
  }

  // 服务端分页（总数由后端给出，单独追加在表格下方）
  function renderServerPager(pageCount) {
    var existing = document.getElementById('auSrvPager');
    if (existing) existing.remove();
    var pages = Math.max(1, Math.ceil(state.total / state.size));
    var box = DN.h('div', { id: 'auSrvPager', class: 'gov-pager' });
    box.appendChild(DN.h('span', { text: '服务端共 ' + state.total + ' 条 · 第 ' + state.page + '/' + pages + ' 页' }));
    // 每页条数选择器
    var sizeSel = DN.h('select', { class: 'iw-form-select', style: 'height:30px;width:auto;' });
    [20, 50, 100, 200].forEach(function (n) { sizeSel.appendChild(DN.h('option', { value: String(n), text: n + ' 条/页' })); });
    sizeSel.value = String(state.size);
    sizeSel.onchange = function () { state.size = Number(sizeSel.value) || 50; state.page = 1; rebuildTable(); load(); };
    box.appendChild(sizeSel);
    box.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '上一页',
      onclick: function () { if (state.page > 1) { state.page--; load(); } } }));
    box.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '下一页',
      onclick: function () { if (state.page < pages) { state.page++; load(); } } }));
    if (auditTbl && auditTbl.parentNode) auditTbl.parentNode.appendChild(box);
  }

  function exportCsv() {
    var p = qsParams();
    window.open('/api/gov/audit/export' + (p.length ? '?' + p.join('&') : ''), '_blank');
  }

  function fmtTs(s) {
    if (!s) return '';
    return String(s).replace('T', ' ').slice(0, 19);
  }

  // 供其它模块跳转的入口；直接渲染进 govContent 容器（保持原行为）。
  window.openGovAudit = function () {
    var box = document.getElementById('govContent') || document.body;
    box.innerHTML = '';
    box.appendChild(DN.h('div', { class: 'gov-h1', text: '全局审计中心' }));
    box.appendChild(DN.h('div', { class: 'gov-desc', text: '登录/数据访问/导出/权限·元数据·规则·打标变更的统一审计检索与导出（M12）' }));
    window.GOV_RENDERERS.audit(box);
  };
})();
