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
        { key: 'createdAt', label: '时间', render: function (r) { return DN.timeAgo(r.createdAt); } },
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
      var ok = DN.h('div', { class: 'gov-desc',
        text: '未检测到异常活跃项：当前各操作人/路径的操作量均在正常区间内' });
      // 空态可操作：一键清空筛选回到全量审计，便于继续排查。
      ok.appendChild(DN.h('a', { href: 'javascript:void(0)', style: 'margin-left:8px;color:var(--primary);font-size:12px',
        text: '查看全部审计 →', onclick: resetAndLoad }));
      card.body.appendChild(ok);
      return card.el;
    }

    // 倍数文案：mean=0（全部样本同值/无均值）时不显示倍数，避免误导；并对极端值封顶展示。
    var ratioText = function (it) {
      if (uRes.mean <= 0 && pRes.mean <= 0) return it.value + ' 次';
      var rd = it.ratio >= 99.5 ? '99+' : it.ratio.toFixed(1);
      return it.value + ' 次 (≈' + rd + '×均值)';
    };

    // 异常操作人：超均值越多色调越重（>3倍 err，否则 warn），点击可联动筛选。
    if (uRes.items.length) {
      card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:4px 0 4px;font-weight:600',
        text: '异常活跃操作人（均值 ' + uRes.mean.toFixed(1) + ' · 阈值 ' + uRes.threshold.toFixed(1) + '）' }));
      card.body.appendChild(DN.bars(uRes.items.map(function (it) {
        return { label: it.name, value: it.value, tone: it.ratio >= 3 ? 'err' : 'warn',
          // 非色严重度提示(WCAG 1.4.1)：高=! 中=· ，避免仅靠 err红/warn黄 区分
          display: (it.ratio >= 3 ? '! ' : '· ') + ratioText(it),
          onClick: function () { filterByUser(it.name); } };
      })));
    }
    // 异常高频路径（点击下钻：复制该路径，便于在检索框/外部排查时直接粘贴）
    if (pRes.items.length) {
      card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:12px 0 4px;font-weight:600',
        text: '异常高频路径（均值 ' + pRes.mean.toFixed(1) + ' · 阈值 ' + pRes.threshold.toFixed(1) + '）' }));
      card.body.appendChild(DN.bars(pRes.items.map(function (it) {
        return { label: it.name, value: it.value, tone: it.ratio >= 3 ? 'err' : 'warn',
          // 非色严重度提示(WCAG 1.4.1)：高=! 中=· ，避免仅靠 err红/warn黄 区分
          display: (it.ratio >= 3 ? '! ' : '· ') + ratioText(it),
          onClick: function () { if (it.name && it.name !== '-') DN.copy(it.name); } };
      })));
    }

    // 异常明细汇总表：合并操作人/路径离群项，支持 CSV 导出 + 行下钻（操作人→筛选，路径→复制）。
    var detailRows = uRes.items.map(function (it) {
      return { dim: '操作人', name: it.name, cnt: it.value, ratio: it.ratio,
        level: it.ratio >= 3 ? '高' : '中', mean: uRes.mean, threshold: uRes.threshold };
    }).concat(pRes.items.map(function (it) {
      return { dim: '路径', name: it.name, cnt: it.value, ratio: it.ratio,
        level: it.ratio >= 3 ? '高' : '中', mean: pRes.mean, threshold: pRes.threshold };
    }));
    var anomalyTbl = DN.table({
      columns: [
        { key: 'dim', label: '维度', render: function (r) { return DN.pill(r.dim, r.dim === '操作人' ? 'info' : 'muted'); } },
        { key: 'name', label: '名称', copyable: true, exportValue: function (r) { return r.name || ''; },
          render: function (r) { return r.name || '-'; } },
        { key: 'cnt', label: '操作量', align: 'right', sortable: true,
          render: function (r) { return String(r.cnt); } },
        { key: 'ratio', label: '×均值', align: 'right', sortable: true,
          exportValue: function (r) { return r.ratio.toFixed(2); },
          render: function (r) { return (uRes.mean <= 0 && pRes.mean <= 0) ? '-' : (r.ratio >= 99.5 ? '99+' : r.ratio.toFixed(1)) + '×'; } },
        { key: 'level', label: '严重度', render: function (r) { return DN.pill(r.level, r.level === '高' ? 'err' : 'warn'); } }
      ],
      rows: detailRows,
      search: false,
      pageSize: 10,
      exportName: 'gov_audit_anomaly',
      empty: '无异常明细', emptyIcon: 'shield'
    });
    // 行点击下钻：操作人维度联动筛选，路径维度复制路径（名称列本身已支持点击复制）。
    anomalyTbl.addEventListener('click', function (e) {
      var tr = e.target && e.target.closest ? e.target.closest('tbody tr') : null;
      if (!tr || (e.target.closest('td') && e.target.closest('td').style.cursor === 'copy')) return;
      var idx = Array.prototype.indexOf.call(tr.parentNode.children, tr);
      var row = detailRows[idx];
      if (!row) return;
      if (row.dim === '操作人') filterByUser(row.name);
      else if (row.name && row.name !== '-') DN.copy(row.name);
    });
    card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:12px 0 4px;font-weight:600', text: '异常明细（可导出 / 点击下钻）' }));
    card.body.appendChild(anomalyTbl);
    return card.el;
  }

  // 按操作人联动筛选并刷新（异常卡片下钻复用）；空名回退为清空筛选。
  function filterByUser(name) {
    if (!els.user) return;
    var v = (name && name !== '-') ? name : '';
    els.user.value = v;
    state.page = 1;
    load();
    DN.toast(v ? ('已按异常操作人 ' + v + ' 筛选') : '已清空操作人筛选');
  }

  // 清空所有筛选条件并重新加载（异常卡片空态可操作链接复用）。
  function resetAndLoad() {
    if (els.user) els.user.value = '';
    if (els.type) els.type.value = '';
    if (els.from) els.from.value = '';
    if (els.to) els.to.value = '';
    state.page = 1;
    load();
  }

  // 下钻（消除"纯展示死胡同"）：状态码分布/操作时段热力是基于当前页 lastRows 计算的，
  // 点击后用同一份数据在前端过滤并复用审计表格展示具体记录，给出下钻横幅 + 一键返回全部。
  function applyDrill(predicate, label) {
    if (!auditTbl) return;
    var rows = (lastRows || []).filter(predicate);
    auditTbl.reload(rows);
    renderStats(rows);                 // 统计区随下钻子集刷新，保持联动
    renderDrillBanner(label, rows.length);
    DN.toast('已下钻：' + label + '（' + rows.length + ' 条）');
  }

  // 用下钻横幅替换服务端分页条；提供"返回全部"链接，调用 load() 恢复完整服务端结果与正常分页。
  function renderDrillBanner(label, count) {
    var existing = document.getElementById('auSrvPager');
    if (existing) existing.remove();
    var box = DN.h('div', { id: 'auSrvPager', class: 'gov-pager' });
    box.appendChild(DN.h('span', { text: '下钻：' + label + ' · 当前页命中 ' + count + ' 条' }));
    box.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '返回全部',
      onclick: function () { state.page = 1; load(); } }));
    if (auditTbl && auditTbl.parentNode) auditTbl.parentNode.appendChild(box);
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
    // 各状态码区间对应的命中判定（下钻用），与上面分组口径一致。
    var matchOf = {
      '2xx 成功': function (n) { return !isNaN(n) && n >= 200 && n < 300; },
      '3xx 重定向': function (n) { return !isNaN(n) && n >= 300 && n < 400; },
      '4xx 客户端错误': function (n) { return !isNaN(n) && n >= 400 && n < 500; },
      '5xx 服务端错误': function (n) { return !isNaN(n) && n >= 500; },
      '其它': function (n) { return isNaN(n); }
    };
    var items = Object.keys(grp).filter(function (k) { return grp[k] > 0; }).map(function (k) {
      return { label: k, value: grp[k], tone: toneMap[k],
        // 点击下钻：按该状态码区间过滤当前页审计记录并刷新表格
        onClick: function () { applyDrill(function (r) { return matchOf[k](Number(r.status)); }, '状态码 ' + k); } };
    });
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
        var cell = DN.h('div', { title: dows[w] + ' ' + hr + ':00 · ' + n + ' 次' + (n ? '（点击下钻）' : ''), style: 'width:11px;height:11px;border-radius:2px;background:' + colorOf(n) });
        // 点击下钻：按该时段(星期×小时)过滤当前页审计记录并刷新表格；空格不可点。
        if (n) {
          (function (dow, hour) {
            cell.style.cursor = 'pointer';
            cell.addEventListener('click', function () {
              applyDrill(function (r) {
                var s = r.createdAt; if (!s) return false;
                var d = new Date(String(s).replace(' ', 'T'));
                if (isNaN(d.getTime())) return false;
                return d.getDay() === dow && d.getHours() === hour;
              }, '周' + dows[dow] + ' ' + hour + ':00 时段');
            });
          })(w, hr);
        }
        row.appendChild(cell);
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

  // 供其它模块跳转的入口；直接渲染进 govContent 容器（保持原行为）。
  window.openGovAudit = function () {
    var box = document.getElementById('govContent') || document.body;
    box.innerHTML = '';
    box.appendChild(DN.h('div', { class: 'gov-h1', text: '全局审计中心' }));
    box.appendChild(DN.h('div', { class: 'gov-desc', text: '登录/数据访问/导出/权限·元数据·规则·打标变更的统一审计检索与导出（M12）' }));
    window.GOV_RENDERERS.audit(box);
  };
})();
