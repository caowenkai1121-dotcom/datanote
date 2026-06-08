/* DataNote 首页「数据资产驾驶舱大屏」
   8 个 KPI 磁贴 + 12 张可视化卡片，多源并发拉数、单卡独立 try/catch 降级。
   全量复用 window.DN 套件（dn-common.js）+ gov-modern.css（作用域已含 #homeCockpit）。
   入口：window.renderHomeDashboard(container) —— 由 workspace.html 路由 init 调用。 */
(function () {
  'use strict';

  // ========== 本地工具（与 gov-overview.js 对齐） ==========
  // 分值映射磁贴/药丸色调：>=85 绿(ok) / >=60 黄(warn) / 其余红(err)
  // NaN/null 先归一化为数字，避免 undefined>=85 恒 false 把"无数据"误判成红色危险态
  function tone(v) { var n = Number(v); if (!isFinite(n)) n = 0; return n >= 85 ? 'ok' : (n >= 60 ? 'warn' : 'err'); }
  function round1(v) { return Math.round((Number(v) || 0) * 10) / 10; }
  function fmtInt(v) { return String(Math.round(Number(v) || 0)).replace(/\B(?=(\d{3})+(?!\d))/g, ','); }
  function go(route) { return function () { if (window.navigateTo) navigateTo(route); }; }
  function num(v) { var n = Number(v); return isFinite(n) ? n : 0; }
  // 超长文本截断 + 完整值挂 title（图表 label 防溢出）
  function trunc(s, n) { s = String(s == null ? '' : s); n = n || 18; return s.length > n ? s.slice(0, n - 1) + '…' : s; }
  // 兼容多字段取首个非空值（后端字段命名不统一时容错）
  function pick() { for (var i = 0; i < arguments.length; i++) { if (arguments[i] != null && arguments[i] !== '') return arguments[i]; } return null; }
  // 信封解包为数组：兼容直接数组 / {list|rows|records|data}，其余一律空数组（保证 .length/.filter 安全）
  function unwrap(resp) {
    if (Array.isArray(resp)) return resp;
    var v = resp && (resp.list || resp.rows || resp.records || resp.data);
    return Array.isArray(v) ? v : [];
  }

  var _timer = null;     // 自动刷新定时器（离开首页自停）
  var _box = null;       // 当前挂载容器
  var _ovCache = null;   // /api/gov/overview 单轮缓存：KPI 与 C1 复用，避免重复请求
  var _refreshing = false; // 刷新进行中标记，避免重复点击

  // ========== 入口：路由 init 调用 ==========
  // container 可省略，默认取 #homeCockpit
  window.renderHomeDashboard = function (container) {
    var box = container || document.getElementById('homeCockpit');
    if (!box) return;
    _box = box;
    _ovCache = null;                                  // 每轮重建清缓存，保证刷新拿到新数据
    box.innerHTML = '';
    renderHero(box);                                  // 品牌英雄条 + 问候 + 更新时间 + 刷新按钮

    box.appendChild(DN.sectionTitle('核心指标'));
    var kpiSlot = DN.h('div'); box.appendChild(kpiSlot);
    kpiSlot.appendChild(DN.skeleton(2));

    box.appendChild(DN.sectionTitle('数据资产驾驶舱'));
    var grid = DN.h('div', { class: 'dash-grid' }); box.appendChild(grid);

    loadKpis(kpiSlot);                                // 8 KPI（多源并发，各源 .catch 降级）
    mountCards(grid);                                 // 12 卡片分批挂载 + 独立加载
    setupAuto(box);                                   // 60s 自动刷新，离开首页自停
    _refreshing = false;
  };

  // 单轮内复用 /api/gov/overview：首次发起请求并缓存 Promise，KPI 与 C1 共用
  function getOverview() {
    if (!_ovCache) _ovCache = DN.get('/api/gov/overview');
    return _ovCache;
  }
  // 向后兼容别名（蓝图里曾用 renderHomeCockpit）
  window.renderHomeCockpit = window.renderHomeDashboard;

  // ========== 品牌英雄条（替代旧 #homeGreeting） ==========
  function renderHero(box) {
    var h = new Date().getHours();
    var greet = h < 6 ? '夜深了' : h < 12 ? '上午好' : h < 18 ? '下午好' : '晚上好';
    var refreshBtn = DN.h('a', {
      class: 'btn btn-sm', href: 'javascript:void(0)', text: '刷新',
      style: 'background:rgba(255,255,255,.18);color:#fff;border-color:rgba(255,255,255,.35)',
      onclick: function () {
        if (_refreshing) return;                       // 防抖：刷新进行中忽略重复点击
        _refreshing = true;
        refreshBtn.textContent = '刷新中…';
        window.renderHomeDashboard(_box);              // 整页重载（内部会复位 _refreshing）
      }
    });
    var briefBtn = DN.h('a', {
      class: 'btn btn-sm', href: 'javascript:void(0)', text: '🤖 今日简报',
      style: 'background:rgba(255,255,255,.18);color:#fff;border-color:rgba(255,255,255,.35);margin-right:8px;',
      onclick: function () { if (window.homeAskAi) window.homeAskAi(); }
    });
    box.appendChild(DN.h('div', { class: 'dash-hero' }, [
      DN.h('div', { class: 'h-title', text: greet + '，欢迎回来' }),
      DN.h('div', { class: 'h-sub', text: 'DataNote · 数据资产驾驶舱 — 一屏掌握资产 / 治理 / 质量 / 消费 / 同步全景' }),
      DN.h('div', { class: 'h-meta' }, [
        DN.h('span', { text: '更新于 ' + new Date().toLocaleTimeString() }),
        DN.h('span', { text: '　' }),
        briefBtn,
        refreshBtn
      ])
    ]));
  }

  // ========== 8 KPI：多源并发，各源独立 .catch 降级为 {} ==========
  function loadKpis(slot) {
    // 4 源并发，单源失败各自 .catch 降级为 {}，不拖垮整排；gov/overview 复用单轮缓存
    Promise.all([
      getOverview().catch(function () { return {}; }),
      DN.get('/api/quality/score').catch(function () { return {}; }),
      DN.get('/api/consumption/overview').catch(function () { return {}; }),
      DN.get('/api/sync-job/dashboard/summary').catch(function () { return {}; })
    ]).then(function (r) {
      var g = r[0] || {}, qs = r[1] || {}, cs = r[2] || {}, sy = r[3] || {};
      var a = g.assets || {}, hh = g.health || {}, iss = g.issues || {};
      var pending = num(iss.open) + num(iss.fixing);
      var ht = num(hh.total), qScore = num(qs.score);
      slot.innerHTML = '';
      slot.appendChild(DN.statRow([
        { icon: 'db', label: '数据资产·表数', value: fmtInt(a.tableCount), title: '进入资产目录', onClick: go('catalog') },
        { icon: 'list', label: '字段数', value: fmtInt(a.columnCount), title: '进入资产目录', onClick: go('catalog') },
        { icon: 'shield', label: '治理健康分', value: round1(ht), sub: '满分100', tone: tone(ht), title: '进入数据治理', onClick: go('governance') },
        { icon: 'check', label: '质量通过率', value: round1(qScore) + '%', sub: '近7天', tone: tone(qScore), title: '进入数据质量', onClick: go('quality') },
        { icon: 'chart', label: '启用指标数', value: fmtInt(cs.enabledMetrics), title: '进入数据消费', onClick: go('metrics') },
        { icon: 'layers', label: '近7天消费量', value: fmtInt(cs.consume7d), sub: '次调用', title: '进入数据消费', onClick: go('metrics') },
        { icon: 'inbox', label: '待办工单', value: fmtInt(pending), sub: '待处理+处理中', tone: pending > 0 ? 'warn' : 'ok', title: '进入数据治理', onClick: go('governance') },
        { icon: 'grid', label: '同步运行中', value: fmtInt(sy.running), sub: '共' + fmtInt(sy.jobsTotal), tone: num(sy.failed) > 0 ? 'warn' : 'ok', title: '进入数据同步', onClick: go('dbsync') }
      ]));
    }).catch(function () {
      slot.innerHTML = '';
      slot.appendChild(DN.errorBox('核心指标加载失败', function () { loadKpis(slot); }));
    });
  }

  // ========== 12 卡片：声明式表，每张独立 col 跨度 + 标题 + loader ==========
  function mountCards(grid) {
    var defs = [
      { col: 8, title: '治理健康总分', icon: 'shield', primary: true, build: cardHealth },   // C1 主卡 跨8列
      { col: 4, title: '健康分趋势', icon: 'chart', build: cardHealthTrend },                  // C2
      { col: 6, title: '热门指标 Top10', icon: 'chart', build: cardMetricRank },               // C3
      { col: 6, title: '近24h质量执行', icon: 'check', build: cardQualityRuns },               // C4
      { col: 4, title: '资产分布·按库', icon: 'db', build: cardAssetDist },                     // C5
      { col: 8, title: '指标新鲜度·陈旧告警', icon: 'clock', build: cardFreshness },           // C6
      { col: 6, title: '待治理僵尸指标', icon: 'alert', build: cardZombies },                   // C7
      { col: 6, title: '同步任务监控大盘', icon: 'grid', build: cardSyncBoard },               // C8
      { col: 6, title: '最新治理工单', icon: 'inbox', build: cardIssues },                      // C9
      { col: 6, title: '系统资源监控', icon: 'layers', build: cardSysMetrics },                // C10
      { col: 6, title: '敏感字段最多的表', icon: 'lock', build: cardSensitive },               // C11
      { col: 6, title: '最近消费活动', icon: 'list', build: cardConsumeLog }                    // C12
    ];
    // 先同步铺好 12 个卡壳 + 骨架（占位不抖动），再分批触发 fetch，避免一次性 12 并发卡顿
    defs.forEach(function (d) {
      var c = DN.card({ title: d.title, icon: d.icon });
      if (d.primary) c.el.classList.add('dash-primary');
      var col = DN.h('div', { class: 'dash-col-' + d.col });
      col.appendChild(c.el); grid.appendChild(col);
      loading(c, 3);
      d._card = c;
    });
    // 每批 4 张，分帧触发各自加载；单卡独立 skeleton → fetch → render/errorBox，互不拖累
    var i = 0, BATCH = 4;
    (function next() {
      for (var k = 0; k < BATCH && i < defs.length; k++, i++) {
        var d = defs[i];
        try { d.build(d._card, d); } catch (e) { fail(d._card, d, e); }
      }
      if (i < defs.length) requestAnimationFrame(next);
    })();
  }

  // 统一错误态：清空卡 body，挂错误盒 + 重试（重跑同一 build）
  function fail(c, d, e) {
    c.body.innerHTML = '';
    c.body.appendChild(DN.errorBox('加载失败: ' + DN.esc(e && e.message ? e.message : '请重试'),
      function () { d.build(c, d); }));
  }
  // 统一加载态
  function loading(c, rows) { c.body.innerHTML = ''; c.body.appendChild(DN.skeleton(rows || 3)); }

  // ---- C1 治理健康总分（主卡：gauge + radar 并排）----
  var DIMS = ['规范', '质量', '安全', '生命周期', '血缘'];
  function cardHealth(c, d) {
    loading(c, 4);
    // 复用单轮 gov/overview 缓存（与 KPI 共享一次请求）；retry 时缓存若已失效则重新拉
    getOverview().then(function (data) {
      data = data || {};
      var hh = data.health || {}, dims = hh.dims || {};
      var total = num(hh.total);
      c.body.innerHTML = '';
      var hasDims = DIMS.some(function (k) { return num(dims[k]) > 0; });
      if (!total && !hasDims) {
        c.body.appendChild(DN.empty('暂无健康评分数据', 'shield')); return;
      }
      var wrap = DN.h('div', { style: 'display:flex;gap:24px;flex-wrap:wrap;align-items:center;justify-content:center' });
      var left = DN.h('div', { style: 'display:flex;flex-direction:column;align-items:center;gap:8px' });
      left.appendChild(DN.gauge(total, { label: '满分100', decimals: 1 }));
      left.appendChild(DN.pill(level(total), tone(total)));
      wrap.appendChild(left);
      var right = DN.h('div', { style: 'flex:1;min-width:240px;display:flex;justify-content:center' });
      right.appendChild(DN.radar(DIMS.map(function (k) { return { label: k, value: num(dims[k]) }; })));
      wrap.appendChild(right);
      c.body.appendChild(wrap);
    }).catch(function (e) { _ovCache = null; fail(c, d, e); });
  }

  // ---- C2 健康分趋势（近30天折线）----
  function cardHealthTrend(c, d) {
    loading(c, 3);
    DN.get('/api/gov/health/score/trend?days=30').then(function (list) {
      c.body.innerHTML = '';
      var pts = (Array.isArray(list) ? list : []).map(function (t) { return num(pick(t.totalScore, t.score)); });
      if (pts.length < 2) { c.body.appendChild(DN.empty('趋势数据不足（需≥2天历史）', 'chart')); return; }
      c.body.appendChild(DN.line(pts, { height: 120 }));
      c.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:8px 0 0', text: '近30天治理健康分走势' }));
    }).catch(function (e) { fail(c, d, e); });
  }

  // ---- C3 热门指标 Top10（消费排行条形图）----
  function cardMetricRank(c, d) {
    loading(c, 4);
    DN.get('/api/consumption/metric-ranking').then(function (list) {
      c.body.innerHTML = '';
      list = (Array.isArray(list) ? list : []).slice(0, 10);
      if (!list.length) { c.body.appendChild(DN.empty('暂无指标消费记录', 'chart')); return; }
      c.body.appendChild(DN.bars(list.map(function (i) {
        var full = pick(i.metricName, i.target_code, i.targetCode) || '-';
        var cnt = num(pick(i.cnt, i.count));
        return { label: trunc(full, 16), value: cnt, display: fmtInt(cnt), onClick: go('metrics') };
      })));
    }).catch(function (e) { fail(c, d, e); });
  }

  // ---- C4 近24h质量执行分布（环形）----
  function cardQualityRuns(c, d) {
    loading(c, 3);
    DN.get('/api/quality/overview').then(function (q) {
      q = q || {};
      c.body.innerHTML = '';
      var ok = num(q.successRuns), bad = num(q.failedRuns), err = num(q.errorRuns);
      var total = ok + bad + err;
      if (!total) { c.body.appendChild(DN.empty('近24h暂无质量执行', 'check')); return; }
      c.body.appendChild(DN.donut([
        { label: '成功', value: ok, color: '#52c41a' },
        { label: '失败', value: bad, color: '#ff4d4f' },
        { label: '异常', value: err, color: '#faad14' }
      ], { centerLabel: fmtInt(total), centerSub: '近24h', legend: true }));
    }).catch(function (e) { fail(c, d, e); });
  }

  // ---- C5 资产分布·按库（前端按 databaseName group 取 Top6+其他，环形）----
  function cardAssetDist(c, d) {
    loading(c, 3);
    DN.get('/api/metadata-center/tables').then(function (resp) {
      c.body.innerHTML = '';
      var rows = unwrap(resp);
      if (!rows.length) { c.body.appendChild(DN.empty('暂无表元数据', 'db')); return; }
      var byDb = {};
      rows.forEach(function (t) {
        var k = pick(t.databaseName, t.dbName, t.dbType) || '未知';
        byDb[k] = (byDb[k] || 0) + 1;
      });
      var arr = Object.keys(byDb).map(function (k) { return { label: k, value: byDb[k] }; });
      arr.sort(function (x, y) { return y.value - x.value; });
      var palette = ['#3457d5', '#52c41a', '#faad14', '#722ed1', '#13c2c2', '#eb2f96'];
      var segs = arr.slice(0, 6).map(function (s, i) { return { label: trunc(s.label, 14), value: s.value, color: palette[i % palette.length] }; });
      if (arr.length > 6) {
        var other = arr.slice(6).reduce(function (a, s) { return a + s.value; }, 0);
        segs.push({ label: '其他', value: other, color: '#bfbfbf' });
      }
      var totalT = arr.reduce(function (a, s) { return a + s.value; }, 0);
      c.body.appendChild(DN.donut(segs, { centerLabel: fmtInt(totalT), centerSub: '表(样本)', legend: true }));
    }).catch(function (e) { fail(c, d, e); });
  }

  // ---- C6 指标新鲜度·陈旧告警（表格，仅 stale===true）----
  function cardFreshness(c, d) {
    loading(c, 4);
    DN.get('/api/consumption/metric/freshness').then(function (list) {
      c.body.innerHTML = '';
      var stale = (Array.isArray(list) ? list : []).filter(function (x) { return x && x.stale === true; });
      if (!stale.length) { c.body.appendChild(DN.empty('全部指标新鲜，无陈旧', 'check')); return; }
      c.body.appendChild(DN.table({
        columns: [
          { key: 'metricName', label: '指标名称', render: function (r) { return DN.h('span', { text: trunc(pick(r.metricName, r.metricCode) || '-', 24), title: pick(r.metricName, r.metricCode) || '' }); } },
          { key: 'lastValueAt', label: '最近取值', render: function (r) { return r.lastValueAt ? DN.timeAgo(r.lastValueAt) : '-'; } },
          { key: 'ageHours', label: '陈旧', align: 'right', render: function (r) { return DN.pill(round1(r.ageHours) + 'h', 'err'); } }
        ],
        rows: stale, pageSize: 8, search: false, emptyIcon: 'check'
      }));
    }).catch(function (e) { fail(c, d, e); });
  }

  // ---- C7 待治理僵尸指标（表格 + 导出）----
  function cardZombies(c, d) {
    loading(c, 4);
    DN.get('/api/consumption/metric/zombies').then(function (list) {
      c.body.innerHTML = '';
      list = Array.isArray(list) ? list : [];
      if (!list.length) { c.body.appendChild(DN.empty('无僵尸指标，状态良好', 'check')); return; }
      c.body.appendChild(DN.table({
        columns: [
          { key: 'metricName', label: '指标名称', copyable: true, exportValue: function (r) { return pick(r.metricCode, r.metricName) || ''; }, render: function (r) { return r.metricName || r.metricCode || '-'; } },
          { key: 'category', label: '分类', render: function (r) { return r.category || '-'; } },
          { key: 'owner', label: '负责人', render: function (r) { return r.owner || '-'; } }
        ],
        rows: list, pageSize: 8, search: false, exportName: 'zombies', emptyIcon: 'check'
      }));
    }).catch(function (e) { fail(c, d, e); });
  }

  // ---- C8 同步任务监控大盘（环形状态 + 成功率仪表）----
  function cardSyncBoard(c, d) {
    loading(c, 3);
    DN.get('/api/sync-job/dashboard/summary').then(function (s) {
      s = s || {};
      c.body.innerHTML = '';
      var running = num(s.running), paused = num(s.paused), failed = num(s.failed);
      if (!running && !paused && !failed && !num(s.jobsTotal)) {
        c.body.appendChild(DN.empty('暂无同步任务', 'grid')); return;
      }
      var wrap = DN.h('div', { style: 'display:flex;gap:24px;flex-wrap:wrap;align-items:center;justify-content:center' });
      wrap.appendChild(DN.donut([
        { label: '运行', value: running, color: '#3457d5' },
        { label: '暂停', value: paused, color: '#faad14' },
        { label: '失败', value: failed, color: '#ff4d4f' }
      ], { legend: true }));
      // successRate 约定为 0-1，gauge 前 *100；若后端已给百分数(>1)则原样用，避免被钳到 100
      var sr = num(s.successRate); var srPct = sr > 1 ? sr : sr * 100;
      wrap.appendChild(DN.gauge(srPct, { label: '成功率', decimals: 1 }));
      c.body.appendChild(wrap);
    }).catch(function (e) { fail(c, d, e); });
  }

  // ---- C9 最新治理工单（待办，表格）----
  function cardIssues(c, d) {
    loading(c, 4);
    DN.get('/api/gov/health/issues?status=OPEN').then(function (resp) {
      c.body.innerHTML = '';
      var rows = unwrap(resp);
      if (!rows.length) { c.body.appendChild(DN.empty('暂无待处理工单', 'check')); return; }
      c.body.appendChild(DN.table({
        columns: [
          { key: 'title', label: '标题', render: function (r) { return DN.h('span', { text: trunc(r.title || '-', 28), title: r.title || '' }); } },
          { key: 'severity', label: '级别', render: function (r) { return DN.pill(r.severity || '-', sevTone(r.severity)); } },
          { key: 'owner', label: '负责人', render: function (r) { return r.owner || '-'; } },
          { key: 'overdue', label: '超期', align: 'center', render: function (r) { return r.overdue ? DN.pill('超期', 'err') : DN.h('span', { text: '-' }); } },
          { key: 'createdAt', label: '创建', render: function (r) { return r.createdAt ? DN.timeAgo(r.createdAt) : '-'; } }
        ],
        rows: rows, pageSize: 8, search: false, emptyIcon: 'check'
      }));
    }).catch(function (e) { fail(c, d, e); });
  }
  function sevTone(s) {
    s = String(s || '').toUpperCase();
    return s === 'HIGH' ? 'err' : s === 'MEDIUM' ? 'warn' : 'muted';
  }

  // ---- C10 系统资源监控（3 个仪表横排）----
  function cardSysMetrics(c, d) {
    loading(c, 3);
    DN.get('/api/dashboard/metrics').then(function (m) {
      m = m || {};
      c.body.innerHTML = '';
      var jvm = m.jvm || {}, sys = m.system || {};
      var heap = num(jvm.heapPct), cpu = sys.cpuPct, disk = num(sys.diskUsedPct);
      var wrap = DN.h('div', { style: 'display:flex;gap:18px;flex-wrap:wrap;align-items:center;justify-content:space-around' });
      wrap.appendChild(gaugeBlock(heap, '堆内存', false));
      wrap.appendChild(gaugeBlock(cpu, 'CPU', Number(cpu) === -1));   // cpuPct===-1 显"不可用"
      wrap.appendChild(gaugeBlock(disk, '磁盘', false));
      c.body.appendChild(wrap);
    }).catch(function (e) { fail(c, d, e); });
  }
  function gaugeBlock(pct, label, unavailable) {
    var block = DN.h('div', { style: 'display:flex;flex-direction:column;align-items:center;gap:6px' });
    if (unavailable) {
      block.appendChild(DN.h('div', { style: 'width:96px;height:96px;display:flex;align-items:center;justify-content:center;color:var(--text-muted);font-size:12px;border:1px dashed var(--border,#e0e0e6);border-radius:50%', text: '不可用' }));
    } else {
      block.appendChild(DN.gauge(num(pct), { size: 96, decimals: 0 }));
    }
    block.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted)', text: label }));
    return block;
  }

  // ---- C11 敏感字段最多的表 Top（热力清单）----
  function cardSensitive(c, d) {
    loading(c, 4);
    DN.get('/api/gov/classification/heatmap').then(function (list) {
      c.body.innerHTML = '';
      var items = unwrap(list).slice(0, 12);
      if (!items.length) { c.body.appendChild(DN.empty('暂无敏感字段标注', 'lock')); return; }
      c.body.appendChild(DN.heat(items.map(function (i) {
        var tbl = pick(i.table, i.tableName);
        var dbn = pick(i.db, i.database);
        var lbl = (dbn ? dbn : '') + (tbl ? (dbn ? '.' : '') + tbl : '');
        return { label: trunc(lbl || tbl || '-', 22), value: num(pick(i.count, i.cnt)) };
      }), { rgb: [255, 77, 79] }));
    }).catch(function (e) { fail(c, d, e); });
  }

  // ---- C12 最近消费活动流水（表格 + 导出）----
  function cardConsumeLog(c, d) {
    loading(c, 4);
    DN.get('/api/consumption/log/list').then(function (resp) {
      c.body.innerHTML = '';
      var rows = unwrap(resp);
      if (!rows.length) { c.body.appendChild(DN.empty('暂无消费活动', 'list')); return; }
      c.body.appendChild(DN.table({
        columns: [
          { key: 'consumer', label: '消费方', render: function (r) { return r.consumer || '-'; } },
          { key: 'targetCode', label: '对象', copyable: true, render: function (r) { return r.targetCode || '-'; } },
          { key: 'action', label: '动作', render: function (r) { return r.action || '-'; } },
          { key: 'success', label: '结果', align: 'center', render: function (r) { return num(r.success) === 1 ? DN.pill('成功', 'ok') : DN.pill('失败', 'err'); } },
          { key: 'durationMs', label: '耗时', align: 'right', render: function (r) { return (r.durationMs != null ? fmtInt(r.durationMs) : '-') + 'ms'; } },
          { key: 'createdAt', label: '时间', render: function (r) { return r.createdAt ? DN.timeAgo(r.createdAt) : '-'; } }
        ],
        rows: rows, pageSize: 10, search: false, exportName: 'consume_log'
      }));
    }).catch(function (e) { fail(c, d, e); });
  }

  // ========== 自动刷新：60s，离开首页自停 ==========
  function setupAuto(box) {
    if (_timer) { clearInterval(_timer); _timer = null; }
    _timer = setInterval(function () {
      var vh = document.getElementById('viewHome');
      // 离开首页（viewHome 隐藏或节点被移除）则停止刷新
      if (!vh || vh.style.display === 'none' || !document.body.contains(box)) {
        clearInterval(_timer); _timer = null; return;
      }
      window.renderHomeDashboard(box);
    }, 60000);
  }

  // ========== 文案工具 ==========
  function level(v) { return v >= 85 ? '优秀' : v >= 70 ? '良好' : v >= 60 ? '一般' : '待完善'; }
})();
