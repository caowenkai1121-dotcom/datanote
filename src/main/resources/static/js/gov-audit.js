/* 治理模块：全局审计中心（M12）—— 现代套件重绘。
   检索(时间/类型/人/路径) + 现代表格(内置分页) + CSV 导出。
   注册 GOV_RENDERERS.audit；并暴露 window.openGovAudit() 供其它模块跳转(governance.html 不改)。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  // 批4: 类型下拉动态化——默认枚举兜底, 渲染时拉 /types 合并实际出现过的类型
  var TYPES = ['', 'LOGIN', 'DATA_ACCESS', 'DATA_PREVIEW', 'EXPORT', 'PERM_CHANGE', 'META_CHANGE', 'RULE_CHANGE', 'LABEL_CHANGE', 'OTHER'];
  // actionType -> 药丸色调
  var TYPE_TONE = {
    LOGIN: 'info', DATA_ACCESS: 'ok', DATA_PREVIEW: 'warn', EXPORT: 'warn',
    PERM_CHANGE: 'err', META_CHANGE: 'info', RULE_CHANGE: 'info', LABEL_CHANGE: 'info', OTHER: 'muted'
  };
  var state = { page: 1, size: 50, total: 0 };
  var els = {};
  var auditTbl = null;
  var toolbar = null;
  var lastRows = [];
  var loadSeq = 0;        // 请求序号：丢弃过期响应，避免慢响应覆盖新结果(去重/竞态守卫)
  var loading = false;    // 加载中标志：禁用检索/翻页按钮，防重复提交
  var searchBtn = null, exportBtn = null;  // 缓存按钮引用，用于加载中禁用
  var TEXT_MAX = 60;      // 超长文本(路径/详情)截断阈值，悬停 title 看全文

  function buildTable() {
    return DN.table({
      columns: [
        { key: 'createdAt', label: '时间', render: function (r) { return DN.timeAgo(r && r.createdAt); } },
        { key: 'actionType', label: '类型', render: function (r) { return r.actionType ? DN.pill(r.actionType, TYPE_TONE[r.actionType] || 'muted') : '-'; } },
        { key: 'userName', label: '操作人', render: function (r) { return r.userName || '-'; } },
        { key: 'method', label: '方法', render: function (r) { return r.method || '-'; } },
        { key: 'path', label: '路径', copyable: true, exportValue: function (r) { return r.path || ''; }, render: function (r) { return truncSpan(r.path); } },
        { key: 'ip', label: 'IP', render: function (r) { return r.ip || '-'; } },
        { key: 'status', label: '状态', render: function (r) { return r.status == null ? '-' : DN.pill(String(r.status), statusTone(r.status)); } },
        { key: 'detail', label: '详情', exportValue: function (r) { return r.detail || ''; }, render: function (r) { return truncSpan(r.detail); } }
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

  // 超长文本截断为节点：超过阈值显示省略号，title 挂完整原文(鼠标悬停可见完整路径/详情)；空值显示 '-'。
  function truncSpan(s) {
    var v = (s == null ? '' : String(s));
    if (!v) return '-';
    if (v.length <= TEXT_MAX) return v;
    return DN.h('span', { title: v, style: 'cursor:help', text: v.slice(0, TEXT_MAX) + '…' });
  }

  window.GOV_RENDERERS.audit = function (c) {
    state.page = 1;
    loading = false;          // 重入时清除可能残留的加载锁，避免翻页/换页大小被误禁
    loadSeq++;                // 作废上一次渲染遗留的在途请求(其响应将被丢弃)

    // 审计概览（7天时序 + 用户/路径行为分析）
    var ovBox = DN.h('div', { id: 'auOverview' });
    c.appendChild(ovBox);
    renderOverview(ovBox);

    // 统计区（按类型分布）
    var statBox = DN.h('div', { id: 'auStats' });
    c.appendChild(statBox);

    // 过滤工具条
    els.from = DN.h('input', { type: 'date', title: '起始日期', class: 'dn-form-input', style: 'height:34px;' });
    els.to = DN.h('input', { type: 'date', title: '截止日期', class: 'dn-form-input', style: 'height:34px;' });
    els.type = DN.h('select', { class: 'dn-form-select', style: 'height:34px;' });
    TYPES.forEach(function (t) { els.type.appendChild(DN.h('option', { value: t, text: t || '全部类型' })); });
    // 批4: 合并库里实际出现过的类型(动态化), 新类型无需改前端
    DN.get('/api/gov/audit/types').then(function (list) {
      (Array.isArray(list) ? list : []).forEach(function (t) {
        if (t && TYPES.indexOf(t) < 0) {
          TYPES.push(t);
          els.type.appendChild(DN.h('option', { value: t, text: t }));
        }
      });
    }).catch(function () { /* 动态加载失败保持默认枚举 */ });
    els.user = DN.h('input', { placeholder: '操作人', class: 'dn-form-input', style: 'height:34px;width:120px;' });
    // 操作人输入框回车即检索；类型/日期变更不自动触发，由「检索」按钮统一提交，避免误触发请求
    els.user.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); runSearch(); }
    });
    searchBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '检索',
      onclick: function () { runSearch(); } });
    exportBtn = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '导出CSV', onclick: exportCsv });
    toolbar = [els.from, els.to, els.type, els.user, searchBtn, exportBtn];
    lastRows = [];

    // 现代表格（toolbar 内置过滤条 + 路径搜索框；客户端搜索覆盖当前页路径）
    auditTbl = buildTable();
    c.appendChild(auditTbl);

    load();
  };

  // 统一检索入口：先做日期区间校验，校验通过再回到第 1 页加载。
  function runSearch() {
    var f = (els.from && els.from.value || '').trim();
    var t = (els.to && els.to.value || '').trim();
    if (f && t && f > t) { DN.toast('起始日期不能晚于截止日期', 'warn'); return; }
    state.page = 1;
    load();
  }

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

  // 加载中：禁用检索/导出按钮与翻页按钮，避免重复提交导致并发请求
  function setLoading(on) {
    loading = !!on;
    [searchBtn, exportBtn].forEach(function (b) {
      if (!b) return;
      b.style.pointerEvents = on ? 'none' : '';
      b.style.opacity = on ? '0.6' : '';
      if (on) b.setAttribute('aria-busy', 'true'); else b.removeAttribute('aria-busy');
    });
    var pager = document.getElementById('auSrvPager');
    if (pager) pager.querySelectorAll('a.btn').forEach(function (a) {
      a.style.pointerEvents = on ? 'none' : ''; a.style.opacity = on ? '0.6' : '';
    });
  }

  function load() {
    var seq = ++loadSeq;          // 本次请求序号，响应回来时若已非最新则丢弃(竞态/去重守卫)
    var p = qsParams();
    p.push('page=' + state.page);
    p.push('size=' + state.size);
    setLoading(true);
    // 加载骨架：清空统计区并显示占位，避免空白闪烁；表格区保留旧数据直到新数据到达
    var statBox = document.getElementById('auStats');
    if (statBox) { statBox.innerHTML = ''; statBox.appendChild(DN.skeleton(3)); }
    DN.get('/api/gov/audit/search?' + p.join('&')).then(function (data) {
      if (seq !== loadSeq) return;   // 已有更新的请求发出，丢弃这条过期响应
      setLoading(false);
      data = data || {};
      state.total = Number(data.total) || 0;
      var rows = Array.isArray(data.list) ? data.list : [];
      lastRows = rows;
      if (auditTbl) auditTbl.reload(rows);
      renderStats(rows);
      renderServerPager(rows.length);
    }).catch(function (e) {
      if (seq !== loadSeq) return;
      setLoading(false);
      // 错误态 + 重试：替换统计区为错误框(可重试)，并以 toast 提示具体原因
      var box = document.getElementById('auStats');
      if (box) { box.innerHTML = ''; box.appendChild(DN.errorBox('审计记录加载失败：' + ((e && e.message) || '未知错误'), load)); }
      DN.toast((e && e.message) || '加载失败', 'err');
    });
  }

  // 审计概览：近7天审计量折线 + Top操作人 + Top路径
  function renderOverview(box) {
    box.innerHTML = '';                 // 重渲染前先清空，避免骨架/旧内容叠加
    box.appendChild(DN.skeleton(3));
    var failed = 0;                      // 统计失败的子请求数：三者全失败时给整体重试入口
    var fail = function () { failed++; return []; };
    Promise.all([
      DN.get('/api/gov/audit/trend?days=7').catch(fail),
      DN.get('/api/gov/audit/stat/user').catch(fail),
      DN.get('/api/gov/audit/stat/path').catch(fail)
    ]).then(function (r) {
      var trend = Array.isArray(r[0]) ? r[0] : [], users = Array.isArray(r[1]) ? r[1] : [], paths = Array.isArray(r[2]) ? r[2] : [];
      box.innerHTML = '';
      // 三个概览接口全部失败：展示错误框 + 重试，而不是留空白
      if (failed >= 3) { box.appendChild(DN.errorBox('审计概览加载失败', function () { renderOverview(box); })); return; }
      var grid = DN.h('div', { class: 'gov-grid' });
      // 7天时序
      var tc = DN.card({ title: '近 7 天审计量', icon: 'clock' });
      tc.el.classList.add('primary');
      if (trend.length >= 2) {
        var vals = trend.map(function (x) { return Number(x && x.cnt) || 0; });
        tc.body.appendChild(DN.line(vals, { height: 80, color: 'var(--primary)' }));
        var d0 = (trend[0] && trend[0].day) || '', dn = (trend[trend.length - 1] && trend[trend.length - 1].day) || '';
        var sum = vals.reduce(function (a, b) { return a + b; }, 0);
        tc.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:8px 0 0;text-align:center', text: (d0 && dn ? d0 + ' ~ ' + dn + ' · ' : '') + '合计 ' + sum + ' 条' }));
      } else { tc.body.appendChild(DN.empty('暂无审计数据', 'clock')); }
      grid.appendChild(tc.el);
      // Top 操作人
      var uc = DN.card({ title: 'Top 活跃操作人', icon: 'user' });
      if (users.length) uc.body.appendChild(DN.bars(users.slice(0, 8).map(function (u) {
        return { label: (u && u.userName) || '-', value: Number(u && u.cnt) || 0, tone: 'info', display: String((u && u.cnt) || 0),
          onClick: function () { filterByUser(u && u.userName); } };
      })));
      else uc.body.appendChild(DN.empty('暂无数据', 'user'));
      grid.appendChild(uc.el);
      // Top 路径
      var pc = DN.card({ title: 'Top 高频路径', icon: 'list' });
      if (paths.length) pc.body.appendChild(DN.heat(paths.slice(0, 9).map(function (p) { return { label: (p && p.path) || '-', value: Number(p && p.cnt) || 0 }; })));
      else pc.body.appendChild(DN.empty('暂无数据', 'list'));
      grid.appendChild(pc.el);
      box.appendChild(grid);
      // 异常活跃检测（基于已加载的用户/路径统计，前端计算 均值+2σ 离群点）
      box.appendChild(buildAnomalyCard(users, paths));
    }).catch(function () {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('审计概览渲染异常', function () { renderOverview(box); }));
    });
  }

  // 异常活跃检测（大功能）: 对操作人/路径的操作量做统计，标记显著高于均值
  // (阈值 = 均值 + 2倍标准差；样本不足时降级为 均值×2) 的离群项作为异常活跃提示。
  function buildAnomalyCard(users, paths) {
    var card = DN.card({ title: '异常活跃检测', icon: 'shield' });
    card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 8px',
      text: '基于近期操作量统计，自动标记操作量显著高于均值的操作人/路径（阈值 = 均值 + 2σ），作为异常活跃提示' }));

    // 计算一组样本中超过 (均值 + 2σ) 的离群项；样本不足时回退到 均值×2。
    var detect = function (list, getName, getVal) {
      var safe = (Array.isArray(list) ? list : []).filter(function (x) { return x != null; });  // 过滤空项
      var vals = safe.map(function (x) { return Number(getVal(x)) || 0; });
      var n = vals.length;
      if (!n) return { items: [], mean: 0, threshold: 0 };
      var sum = vals.reduce(function (a, b) { return a + b; }, 0);
      var mean = sum / (n || 1);
      var variance = vals.reduce(function (a, v) { return a + (v - mean) * (v - mean); }, 0) / (n || 1);
      var std = Math.sqrt(variance);
      // 样本量过小时标准差不稳定，降级为简单的 均值×2 判定。
      var threshold = n >= 3 ? (mean + 2 * std) : (mean * 2);
      var items = [];
      safe.forEach(function (x) {
        var v = Number(getVal(x)) || 0;
        if (v > threshold && v > mean) {
          var ratio = (v / (mean || 1));
          items.push({ name: getName(x) || '-', value: v, ratio: ratio });
        }
      });
      items.sort(function (a, b) { return b.value - a.value; });
      return { items: items, mean: mean, threshold: threshold };
    };

    var uRes = detect(users || [], function (u) { return u && u.userName; }, function (u) { return u && u.cnt; });
    var pRes = detect(paths || [], function (p) { return p && p.path; }, function (p) { return p && p.cnt; });

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
          onClick: function () { filterByUser(it.name, '异常操作人'); } };
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
    // 注意：表格内置分页(pageSize 10)，不能用 tr 的 DOM 下标直接索引 detailRows——翻页后下标会错位。
    // 改为读取该行「维度」「名称」单元格文本回查 detailRows，保证分页下下钻仍命中正确行。
    anomalyTbl.addEventListener('click', function (e) {
      var td = e.target && e.target.closest ? e.target.closest('td') : null;
      var tr = e.target && e.target.closest ? e.target.closest('tbody tr') : null;
      if (!tr || (td && td.style.cursor === 'copy')) return;   // 名称列点击=复制，交给单元格自身处理
      var cells = tr.children;
      if (!cells || cells.length < 2) return;
      var dim = (cells[0].textContent || '').trim();
      var name = (cells[1].textContent || '').trim();
      var row = detailRows.filter(function (x) { return x.dim === dim && (x.name || '-') === name; })[0];
      if (!row) return;
      if (row.dim === '操作人') filterByUser(row.name, '异常操作人');
      else if (row.name && row.name !== '-') DN.copy(row.name);
    });
    card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:12px 0 4px;font-weight:600', text: '异常明细（可导出 / 点击下钻）' }));
    card.body.appendChild(anomalyTbl);
    return card.el;
  }

  // 按操作人联动筛选并刷新（Top 列表/异常卡片下钻共用）；空名回退为清空筛选。
  // noun 用于区分文案：异常卡片传 '异常操作人'，普通 Top 列表用默认 '操作人'。
  function filterByUser(name, noun) {
    if (!els.user) return;
    var v = (name && name !== '-') ? name : '';
    els.user.value = v;
    state.page = 1;
    load();
    DN.toast(v ? ('已按' + (noun || '操作人') + ' ' + v + ' 筛选') : '已清空操作人筛选');
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

  // 按类型统计（本页数据）用 DN.bars
  function renderStats(rows) {
    var box = document.getElementById('auStats');
    if (!box) return;
    rows = Array.isArray(rows) ? rows : [];
    box.innerHTML = '';
    if (!rows.length) { box.appendChild(DN.empty('当前条件下无审计记录可统计', 'chart')); return; }
    var byType = {};
    rows.forEach(function (r) { if (!r) return; var t = r.actionType || 'OTHER'; byType[t] = (byType[t] || 0) + 1; });
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
    var total = Math.max(0, Number(state.total) || 0);   // 防 0/负/空总数
    var pages = Math.max(1, Math.ceil(total / state.size));
    if (state.page > pages) state.page = pages;          // 越界页码回收
    var box = DN.h('div', { id: 'auSrvPager', class: 'gov-pager' });
    box.appendChild(DN.h('span', { text: total ? ('服务端共 ' + total + ' 条 · 第 ' + state.page + '/' + pages + ' 页 · 本页 ' + (Number(pageCount) || 0) + ' 条') : '服务端无审计记录' }));
    // 每页条数选择器
    var sizeSel = DN.h('select', { class: 'dn-form-select', style: 'height:30px;width:auto;' });
    [20, 50, 100, 200].forEach(function (n) { sizeSel.appendChild(DN.h('option', { value: String(n), text: n + ' 条/页' })); });
    sizeSel.value = String(state.size);
    sizeSel.onchange = function () { if (loading) return; state.size = Number(sizeSel.value) || 50; state.page = 1; rebuildTable(); load(); };
    box.appendChild(sizeSel);
    // 边界处禁用上一页/下一页：到首/末页时变灰且不可点，避免无效请求
    var atFirst = state.page <= 1, atLast = state.page >= pages;
    box.appendChild(mkPagerBtn('上一页', atFirst, function () { if (!loading && state.page > 1) { state.page--; load(); } }));
    box.appendChild(mkPagerBtn('下一页', atLast, function () { if (!loading && state.page < pages) { state.page++; load(); } }));
    if (auditTbl && auditTbl.parentNode) auditTbl.parentNode.appendChild(box);
  }

  // 翻页按钮：disabled 时置灰并屏蔽点击/键盘，避免越界翻页发空请求。
  function mkPagerBtn(text, disabled, fn) {
    var a = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: text });
    if (disabled) { a.style.pointerEvents = 'none'; a.style.opacity = '0.45'; a.setAttribute('aria-disabled', 'true'); }
    else a.addEventListener('click', fn);
    return a;
  }

  function exportCsv() {
    // 导出前做与检索一致的日期区间校验，避免导出空/错区间
    var f = (els.from && els.from.value || '').trim();
    var t = (els.to && els.to.value || '').trim();
    if (f && t && f > t) { DN.toast('起始日期不能晚于截止日期', 'warn'); return; }
    var p = qsParams();
    var w = window.open('/api/gov/audit/export' + (p.length ? '?' + p.join('&') : ''), '_blank');
    if (!w) { DN.toast('导出被浏览器拦截，请允许弹出窗口', 'warn'); return; }
    DN.toast('正在导出当前筛选条件的审计 CSV', 'ok');
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
