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
      // 异常活跃检测（基于已加载的用户/路径统计，前端计算 均值+2σ 离群点）
      box.appendChild(buildAnomalyCard(users, paths));
    }).catch(function () { box.innerHTML = ''; });
  }

  // 异常活跃检测（大功能）: 对操作人/路径的操作量做统计，标记显著高于均值
  // (阈值 = 均值 + 2倍标准差；样本不足时降级为 均值×2) 的离群项作为异常活跃提示。
  function buildAnomalyCard(users, paths) {
    var card = DN.card({ title: '异常活跃检测', icon: 'shield' });
    card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 8px',
      text: '基于近期操作量统计，自动标记操作量显著高于均值的操作人/路径（阈值 = 均值 + 2σ），作为异常活跃提示' }));

    // 计算一组样本中超过 (均值 + 2σ) 的离群项；样本不足时回退到 均值×2。
    var detect = function (list, getName, getVal) {
      var vals = list.map(function (x) { return Number(getVal(x)) || 0; });
      var n = vals.length;
      if (!n) return { items: [], mean: 0, threshold: 0 };
      var sum = vals.reduce(function (a, b) { return a + b; }, 0);
      var mean = sum / (n || 1);
      var variance = vals.reduce(function (a, v) { return a + (v - mean) * (v - mean); }, 0) / (n || 1);
      var std = Math.sqrt(variance);
      // 样本量过小时标准差不稳定，降级为简单的 均值×2 判定。
      var threshold = n >= 3 ? (mean + 2 * std) : (mean * 2);
      var items = [];
      list.forEach(function (x) {
        var v = Number(getVal(x)) || 0;
        if (v > threshold && v > mean) {
          var ratio = (v / (mean || 1));
          items.push({ name: getName(x) || '-', value: v, ratio: ratio });
        }
      });
      items.sort(function (a, b) { return b.value - a.value; });
      return { items: items, mean: mean, threshold: threshold };
    };

    var uRes = detect(users || [], function (u) { return u.userName; }, function (u) { return u.cnt; });
    var pRes = detect(paths || [], function (p) { return p.path; }, function (p) { return p.cnt; });

    var hasUserData = (users || []).length > 0;
    var hasPathData = (paths || []).length > 0;
    if (!hasUserData && !hasPathData) {
      card.body.appendChild(DN.empty('暂无可分析的统计数据', 'shield'));
      return card.el;
    }
    if (!uRes.items.length && !pRes.items.length) {
      card.body.appendChild(DN.h('div', { class: 'gov-desc',
        text: '未检测到异常活跃项：当前各操作人/路径的操作量均在正常区间内' }));
      return card.el;
    }

    // 异常操作人：超均值越多色调越重（>3倍 err，否则 warn），点击可联动筛选。
    if (uRes.items.length) {
      card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:4px 0 4px;font-weight:600',
        text: '异常活跃操作人（均值 ' + uRes.mean.toFixed(1) + ' · 阈值 ' + uRes.threshold.toFixed(1) + '）' }));
      card.body.appendChild(DN.bars(uRes.items.map(function (it) {
        return { label: it.name, value: it.value, tone: it.ratio >= 3 ? 'err' : 'warn',
          display: it.value + ' 次 (≈' + it.ratio.toFixed(1) + '×均值)',
          onClick: function () { if (els.user) { els.user.value = it.name === '-' ? '' : it.name; state.page = 1; load(); DN.toast('已按异常操作人 ' + it.name + ' 筛选'); } } };
      })));
    }
    // 异常高频路径
    if (pRes.items.length) {
      card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:12px 0 4px;font-weight:600',
        text: '异常高频路径（均值 ' + pRes.mean.toFixed(1) + ' · 阈值 ' + pRes.threshold.toFixed(1) + '）' }));
      card.body.appendChild(DN.bars(pRes.items.map(function (it) {
        return { label: it.name, value: it.value, tone: it.ratio >= 3 ? 'err' : 'warn',
          display: it.value + ' 次 (≈' + it.ratio.toFixed(1) + '×均值)' };
      })));
    }
    return card.el;
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
    // HTTP 状态码分布(2xx/3xx/4xx/5xx)
    box.appendChild(buildStatusDistCard(rows));
    // 行为时段热力(时×星期)
    box.appendChild(buildHourHeatCard(rows));
  }

  // HTTP 状态码分布(大功能): 成功/重定向/客户端错/服务端错 占比
  function buildStatusDistCard(rows) {
    var grp = { '2xx 成功': 0, '3xx 重定向': 0, '4xx 客户端错误': 0, '5xx 服务端错误': 0, '其它': 0 };
    rows.forEach(function (r) {
      var n = Number(r.status);
      if (isNaN(n)) grp['其它']++;
      else if (n >= 200 && n < 300) grp['2xx 成功']++;
      else if (n >= 300 && n < 400) grp['3xx 重定向']++;
      else if (n >= 400 && n < 500) grp['4xx 客户端错误']++;
      else if (n >= 500) grp['5xx 服务端错误']++;
      else grp['其它']++;
    });
    var toneMap = { '2xx 成功': 'ok', '3xx 重定向': 'info', '4xx 客户端错误': 'warn', '5xx 服务端错误': 'err', '其它': 'muted' };
    var items = Object.keys(grp).filter(function (k) { return grp[k] > 0; }).map(function (k) { return { label: k, value: grp[k], tone: toneMap[k] }; });
    var card = DN.card({ title: '本页 HTTP 状态码分布', icon: 'shield' });
    if (!items.length) card.body.appendChild(DN.empty('无状态码数据', 'shield'));
    else card.body.appendChild(DN.bars(items));
    return card.el;
  }

  // 行为时段热力图(大功能): 7星期 × 24小时 操作密度
  function buildHourHeatCard(rows) {
    var card = DN.card({ title: '操作时段热力（星期 × 小时）', icon: 'clock' });
    var grid = {}; var max = 0;
    rows.forEach(function (r) {
      var s = r.createdAt; if (!s) return;
      var d = new Date(String(s).replace(' ', 'T'));
      if (isNaN(d.getTime())) return;
      var key = d.getDay() + '_' + d.getHours();
      grid[key] = (grid[key] || 0) + 1; if (grid[key] > max) max = grid[key];
    });
    var dows = ['日', '一', '二', '三', '四', '五', '六'];
    var colorOf = function (n) { if (!n) return '#f0f1f3'; var r = n / (max || 1); return r > 0.66 ? '#1677ff' : r > 0.33 ? '#69a9ff' : '#bcd7ff'; };
    var wrap = DN.h('div', { style: 'overflow-x:auto' });
    var tbl = DN.h('div', { style: 'display:inline-block;min-width:100%' });
    // 表头(小时)
    var head = DN.h('div', { style: 'display:flex;gap:2px;margin-bottom:2px;padding-left:22px' });
    for (var hH = 0; hH < 24; hH += 2) head.appendChild(DN.h('div', { style: 'width:26px;font-size:9px;color:#999;text-align:left', text: hH }));
    tbl.appendChild(head);
    for (var w = 0; w < 7; w++) {
      var row = DN.h('div', { style: 'display:flex;gap:2px;align-items:center;margin-bottom:2px' });
      row.appendChild(DN.h('div', { style: 'width:20px;font-size:10px;color:#999', text: dows[w] }));
      for (var hr = 0; hr < 24; hr++) {
        var n = grid[w + '_' + hr] || 0;
        row.appendChild(DN.h('div', { title: dows[w] + ' ' + hr + ':00 · ' + n + ' 次', style: 'width:11px;height:11px;border-radius:2px;background:' + colorOf(n) }));
      }
      tbl.appendChild(row);
    }
    wrap.appendChild(tbl);
    card.body.appendChild(wrap);
    card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:8px 0 0', text: '基于本页 ' + rows.length + ' 条审计记录, 颜色越深该时段操作越密集(峰值 ' + max + ' 次)' }));
    return card.el;
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
