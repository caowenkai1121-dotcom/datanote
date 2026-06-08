/* 治理模块：治理总览大屏（F2）
   一屏看板：KPI 磁贴 + 健康总分大环形 + 五维条形 + 资产/质量磁贴 + 敏感分布条形。
   全量走 DN 现代套件（gov-modern.css），零图表库依赖。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var DIMS = ['规范', '质量', '安全', '生命周期', '血缘'];
  // R25 联动：健康分维度 → 对应治理子模块 key（生命周期归在资产模块内）
  var DIM_MODULE = { '规范': 'standard', '质量': 'quality', '安全': 'security', '生命周期': 'assets', '血缘': 'lineage' };

  window.GOV_RENDERERS.overview = function (c) {
    var box = DN.h('div', { id: 'ovBox' });
    c.appendChild(box);
    reload(box);
  };
  var _ovAuto = false, _ovTimer = null;
  function setupAuto(box) {
    if (_ovTimer) { clearInterval(_ovTimer); _ovTimer = null; }
    if (_ovAuto) _ovTimer = setInterval(function () {
      // 面板已离开(被替换)则停
      if (!document.body.contains(box)) { clearInterval(_ovTimer); _ovTimer = null; return; }
      reload(box);
    }, 30000);
  }
  var _reloading = null; // 防重复加载：记录正在加载中的 box（仅守卫同一 box 的重复触发，换面板不受影响）
  function reload(box) {
    if (!box) return;
    if (_reloading === box) return; // 同一面板已在加载中，忽略重复触发，避免并发渲染抖动
    _reloading = box;
    box.innerHTML = ''; box.appendChild(DN.skeleton(5));
    Promise.all([
      DN.get('/api/gov/overview'),
      DN.get('/api/gov/health/score/trend?days=14').catch(function () { return []; })
    ]).then(function (r) {
      render(box, r[0] || {}, Array.isArray(r[1]) ? r[1] : []);
    }).catch(function (e) {
      box.innerHTML = '';
      var msg = (e && e.message) ? e.message : '请稍后重试';
      box.appendChild(DN.errorBox('治理总览加载失败：' + DN.esc(msg), function () { reload(box); }));
    }).then(function () { if (_reloading === box) _reloading = null; }); // finally：释放本 box 的守卫
  }

  // 治理待办行动中心: 聚合质量异常/落标违规/健康分偏低等待处理事项(各源独立 .catch 降级), 提供直达链接
  function loadGovTodo(body, healthTotal) {
    if (!body) return;
    Promise.all([
      DN.get('/api/quality/overview').catch(function () { return null; }),
      DN.get('/api/gov/standard/top-violations?limit=50').catch(function () { return null; })
    ]).then(function (r) {
      if (!document.body.contains(body)) return; // 面板已被替换则放弃渲染，避免写入游离节点
      body.innerHTML = '';
      var q = r[0] || {}, viol = Array.isArray(r[1]) ? r[1] : [];
      var items = [];
      var qBad = (Number(q.failedRuns) || 0) + (Number(q.errorRuns) || 0);
      if (qBad > 0) items.push({ icon: 'check', label: '近 24 小时质量检查失败/异常', n: qBad, unit: '次', key: 'quality', tone: 'err' });
      if (viol.length > 0) items.push({ icon: 'doc', label: '数据标准落标违规库表', n: viol.length + (viol.length >= 50 ? '+' : ''), unit: '个', key: 'standard', tone: 'warn' });
      // score>0 才判低分: total=0 多为"暂无评分数据"而非真得 0 分, 避免无数据误报
      var score = (healthTotal != null) ? Number(healthTotal) : null;
      if (score != null && score > 0 && score < 80) items.push({ icon: 'shield', label: '治理健康分偏低', n: round1(score), unit: '分', key: 'health', tone: score < 60 ? 'err' : 'warn' });
      if (!items.length) { body.appendChild(DN.empty('暂无待处理的治理事项，治理状态良好', 'check')); return; }
      body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 8px', text: '聚合各模块需关注的事项，点“前往”直达处理：' }));
      items.forEach(function (it) {
        body.appendChild(DN.h('div', { style: 'display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid var(--divider,#f0f1f3)' }, [
          DN.h('span', { html: DN.icon(it.icon), style: 'color:' + toneColor(it.tone) + ';display:flex' }),
          DN.h('span', { style: 'flex:1;font-size:13px', text: it.label }),
          DN.pill(it.n + ' ' + it.unit, it.tone),
          DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '前往', title: '前往处理：' + it.label, onclick: (function (k) { return function () { goModule(k); }; })(it.key) })
        ]));
      });
    }).catch(function () {
      if (!document.body.contains(body)) return;
      body.innerHTML = '';
      body.appendChild(DN.errorBox('治理待办聚合加载失败', function () { body.innerHTML = ''; body.appendChild(DN.skeleton(2)); loadGovTodo(body, healthTotal); }));
    });
  }

  var _lastData = null, _lastTrend = null;
  function render(box, d, trend) {
    _lastData = d; _lastTrend = trend;
    var health = d.health || {};
    var assets = d.assets || {};
    var quality = d.quality || {};
    var issues = d.issues || {};
    var sensitive = d.sensitive || {};

    box.innerHTML = '';

    // ---- 顶部工具条: 刷新 + 更新时间 ----
    var autoCb = DN.h('input', { type: 'checkbox' }); autoCb.checked = _ovAuto;
    autoCb.onchange = function () { _ovAuto = autoCb.checked; setupAuto(box); };
    var bar = DN.h('div', { style: 'display:flex;justify-content:flex-end;align-items:center;gap:10px;margin-bottom:10px' }, [
      DN.h('label', { class: 'gov-desc', style: 'margin:0;cursor:pointer;display:flex;align-items:center;gap:4px' }, [autoCb, DN.h('span', { text: '自动刷新(30s)' })]),
      DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '更新于 ' + new Date().toLocaleTimeString() }),
      DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '全屏大屏', onclick: function () { toggleBigScreen(box); } }),
      DN.h('a', { class: 'btn btn-sm btn-primary', href: 'javascript:void(0)', text: '生成体检报告', onclick: function (e) { buildReport(e && e.currentTarget); } }),
      DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '刷新', onclick: function () { if (_reloading === box) { DN.toast('正在刷新中…', 'info'); return; } reload(box); } })
    ]);
    box.appendChild(bar);
    setupAuto(box);

    // ---- 顶部 KPI 磁贴(原生可点击跳转) ----
    var total = Number(health.total) || 0;
    var rate = Number(quality.recentPassRate) || 0;
    var pending = (Number(issues.open) || 0) + (Number(issues.fixing) || 0);
    function jump(k) { return function () { goModule(k); }; }
    box.appendChild(DN.statRow([
      { icon: 'shield', label: '治理健康分', value: round1(total), sub: '满分 100', tone: tone(total), title: '进入治理健康分', onClick: jump('health') },
      { icon: 'db', label: '表数', value: fmtInt(assets.tableCount), title: '进入资产目录', onClick: jump('assets') },
      { icon: 'list', label: '字段数', value: fmtInt(assets.columnCount), title: '进入资产目录', onClick: jump('assets') },
      { icon: 'layers', label: '库数', value: fmtInt(assets.dbCount), title: '进入资产目录', onClick: jump('assets') },
      { icon: 'check', label: '质量分', value: round1(rate) + '%', sub: '近期通过率', tone: tone(rate), title: '进入数据质量', onClick: jump('quality') },
      { icon: 'inbox', label: '待办工单', value: fmtInt(pending), sub: '待处理+处理中', tone: pending > 0 ? 'warn' : 'ok', title: '进入治理健康分', onClick: jump('health') }
    ]));

    // ---- 治理待办行动中心(创新功能): 聚合各模块需关注事项, 一处直达处理 ----
    var todoCard = DN.card({ title: '治理待办行动中心', icon: 'inbox' });
    todoCard.body.appendChild(DN.skeleton(2));
    box.appendChild(todoCard.el);
    loadGovTodo(todoCard.body, total);

    // ---- 健康总分 + 五维雷达 ----
    var dims = health.dims || {};
    var weights = health.weights || {};
    var hc = DN.card({ title: '治理健康总分', icon: 'shield' });
    hc.el.classList.add('primary');
    var hin = DN.h('div', { class: 'gov-card-bd split' });
    hin.style.padding = '0';
    var left = DN.h('div', { style: 'display:flex;flex-direction:column;align-items:center;gap:6px' });
    left.appendChild(DN.gauge(total, { label: '满分 100', decimals: 1 }));
    left.appendChild(DN.pill(level(total), tone(total)));
    hin.appendChild(left);
    var right = DN.h('div', { style: 'flex:1;min-width:240px;display:flex;flex-direction:column;align-items:center' });
    right.appendChild(DN.radar(DIMS.map(function (k) { return { label: k, value: Number(dims[k]) || 0 }; })));
    var legend = DN.h('div', { class: 'gov-legend', style: 'justify-content:center' });
    DIMS.forEach(function (k) {
      var v = Number(dims[k]) || 0, w = weights[k];
      legend.appendChild(DN.h('span', {}, [DN.h('b', { text: k + ' ' }), DN.h('span', { style: 'color:' + toneColor(tone(v)), text: round1(v) }), DN.h('span', { style: 'color:var(--text-muted)', text: w != null ? ' /权重' + w : '' })]));
    });
    right.appendChild(legend);
    hin.appendChild(right);
    hc.body.parentNode.replaceChild(hin, hc.body);
    box.appendChild(hc.el);

    // ---- 健康趋势 + 周对比对标 ----
    var pts = (trend || []).map(function (t) { return Number(t.score || t.totalScore || 0); });
    if (pts.length >= 2) {
      var tc = DN.card({ title: '健康趋势 · 周对比', icon: 'chart' });
      var thisWeek = pts.slice(-7), lastWeek = pts.slice(-14, -7);
      var avg = function (a) { return a.length ? a.reduce(function (x, y) { return x + y; }, 0) / a.length : 0; };
      var tw = avg(thisWeek), lw = avg(lastWeek);
      var head = DN.h('div', { style: 'display:flex;align-items:center;gap:14px;flex-wrap:wrap;margin-bottom:10px' }, [
        DN.h('div', {}, [DN.h('div', { style: 'font-size:24px;font-weight:750', text: round1(tw) }), DN.h('div', { style: 'font-size:12px;color:var(--text-muted)', text: '本周日均' })]),
        DN.delta(tw, lw, { decimals: 1 }),
        DN.h('span', { style: 'font-size:12px;color:var(--text-muted)', text: '上周 ' + round1(lw) })
      ]);
      tc.body.appendChild(head);
      tc.body.appendChild(DN.line(pts, { height: 70 }));
      box.appendChild(tc.el);
    }

    // ---- 健康分趋势研判(创新功能): 方向/均值/极值 + 研判结论 ----
    box.appendChild(insightCard(trend, dims));

    // ---- 数据资产 ----
    var ac = DN.card({ title: '数据资产', icon: 'db' });
    ac.body.appendChild(DN.statRow([
      { icon: 'db', label: '表数', value: fmtInt(assets.tableCount) },
      { icon: 'list', label: '字段数', value: fmtInt(assets.columnCount) },
      { icon: 'layers', label: '库数', value: fmtInt(assets.dbCount) },
      { icon: 'chart', label: '总体量', value: DN.fmtBytes(assets.totalSizeBytes) }
    ]));
    box.appendChild(ac.el);

    // ---- 数据质量 ----
    var qc = DN.card({ title: '数据质量', icon: 'check' });
    qc.body.appendChild(DN.statRow([
      { icon: 'check', label: '近期检查通过率', value: round1(rate) + '%', tone: tone(rate) },
      { icon: 'clock', label: '近 24h 运行数', value: fmtInt(quality.runs24h) }
    ]));
    qc.body.appendChild(DN.bars([
      { label: '通过率', value: Math.max(0, Math.min(100, rate)), max: 100, tone: tone(rate), display: round1(rate) + '%' }
    ]));
    box.appendChild(qc.el);

    // ---- 治理工单 ----
    var ic = DN.card({ title: '治理工单', icon: 'inbox' });
    ic.body.appendChild(DN.bars([
      { label: '待处理', value: Number(issues.open) || 0, tone: 'err', display: fmtInt(issues.open) },
      { label: '处理中', value: Number(issues.fixing) || 0, tone: 'warn', display: fmtInt(issues.fixing) },
      { label: '已关闭', value: Number(issues.closed) || 0, tone: 'muted', display: fmtInt(issues.closed) }
    ]));
    box.appendChild(ic.el);

    // ---- 敏感分布 ----
    box.appendChild(sensitiveCard('敏感等级分布', 'lock', sensitive.byLevel, 'info'));
    box.appendChild(sensitiveCard('敏感类型分布', 'tag', sensitive.byType, 'info'));

    // ---- 健康分90天日历热力(大功能) ----
    var hcCard = DN.card({ title: '治理健康分 · 近 90 天日历', icon: 'clock' });
    box.appendChild(hcCard.el);
    function loadHealthCalendar() {
      hcCard.body.innerHTML = ''; hcCard.body.appendChild(DN.skeleton(2));
      DN.get('/api/gov/health/score/trend?days=90').then(function (t) {
        if (!document.body.contains(hcCard.body)) return; // 面板已切换则放弃，避免写游离节点
        renderHealthCalendar(hcCard.body, Array.isArray(t) ? t : []);
      }).catch(function (e) {
        if (!document.body.contains(hcCard.body)) return;
        hcCard.body.innerHTML = '';
        hcCard.body.appendChild(DN.errorBox('健康分日历加载失败：' + (e && e.message ? e.message : '请重试'), loadHealthCalendar));
      });
    }
    loadHealthCalendar();
  }

  // 健康分趋势研判(创新功能): 前端计算趋势方向/近7天均值/极值, 复用已 fetch 的 trend 数据
  // R25 联动: 额外传入五维得分 dims, 识别短板维度并提供"去改进"直达对应治理子模块
  function insightCard(trend, dims) {
    var c = DN.card({ title: '健康分趋势研判', icon: 'chart' });
    // 归一: 取出 {date, score}, 过滤无效项, 按日期升序
    var pts = (trend || []).map(function (t) {
      return { date: (t.day || t.date || '').toString().slice(0, 10), score: Number(t.score || t.totalScore || 0) };
    }).filter(function (p) { return p.date; });
    if (pts.length < 2) {
      c.body.appendChild(DN.empty('趋势数据不足, 暂无法研判（至少需 2 天健康分历史）', 'chart'));
      return c.el;
    }
    var vals = pts.map(function (p) { return p.score; });
    var n = vals.length;
    // 线性斜率(最小二乘): x=0..n-1, 反映整体走向
    var sx = 0, sy = 0, sxx = 0, sxy = 0;
    for (var i = 0; i < n; i++) { sx += i; sy += vals[i]; sxx += i * i; sxy += i * vals[i]; }
    var denom = (n * sxx - sx * sx) || 1; // 除零守卫
    var slope = (n * sxy - sx * sy) / denom;
    // 方向判定: 斜率阈值 ±0.2 分/天 视为持平
    var dirLabel, dirTone, dirVerb;
    if (slope > 0.2) { dirLabel = '上升'; dirTone = 'ok'; dirVerb = '稳步向好'; }
    else if (slope < -0.2) { dirLabel = '下降'; dirTone = 'err'; dirVerb = '持续下滑'; }
    else { dirLabel = '持平'; dirTone = 'info'; dirVerb = '基本平稳'; }
    // 近7天均值
    var recent = vals.slice(-7);
    var recentAvg = recent.reduce(function (x, y) { return x + y; }, 0) / (recent.length || 1);
    // 极值及其日期
    var hi = pts[0], lo = pts[0];
    pts.forEach(function (p) { if (p.score > hi.score) hi = p; if (p.score < lo.score) lo = p; });
    // 全程变化幅度(首尾差)
    var change = vals[n - 1] - vals[0];

    c.body.appendChild(DN.statRow([
      { icon: 'chart', label: '趋势方向', value: dirLabel, tone: dirTone, sub: (change >= 0 ? '+' : '') + round1(change) + ' 分(' + n + '天)' },
      { icon: 'shield', label: '近 7 天均值', value: round1(recentAvg), tone: tone(recentAvg), sub: '满分 100' },
      { icon: 'check', label: '最高分', value: round1(hi.score), tone: 'ok', sub: DN.esc(hi.date) },
      { icon: 'alert', label: '最低分', value: round1(lo.score), tone: 'err', sub: DN.esc(lo.date) }
    ]));
    c.body.appendChild(DN.line(vals, { height: 60 }));
    // 研判结论
    c.body.appendChild(DN.h('div', {
      class: 'gov-desc', style: 'margin:8px 0 0;line-height:1.6',
      text: insightConclusion(dirLabel, dirVerb, recentAvg, lo, hi)
    }));
    // R25 联动: 识别五维中得分最低的短板维度, 提供"去改进"直达对应治理子模块, 消除纯展示死胡同
    c.body.appendChild(weakDimAction(dims));
    return c.el;
  }
  // 短板维度行动：取五维中可跳转(有模块映射)且得分最低的维度，渲染"去改进"按钮
  function weakDimAction(dims) {
    dims = dims || {};
    var weak = null;
    DIMS.forEach(function (k) {
      if (!DIM_MODULE[k]) return;
      var v = Number(dims[k]) || 0;
      if (!weak || v < weak.v) weak = { dim: k, v: v };
    });
    if (!weak) return DN.h('span');
    var key = DIM_MODULE[weak.dim];
    return DN.h('div', { style: 'display:flex;align-items:center;gap:10px;margin-top:10px;padding-top:10px;border-top:1px solid var(--divider,#f0f1f3)' }, [
      DN.h('span', { class: 'gov-desc', style: 'margin:0;flex:1', text: '当前短板维度：' }),
      DN.pill(weak.dim + ' ' + round1(weak.v), tone(weak.v)),
      DN.h('a', { class: 'btn btn-sm btn-primary', href: 'javascript:void(0)', text: '去改进', title: '前往改进短板维度：' + weak.dim, onclick: function () { goModule(key, {}); } })
    ]);
  }
  function insightConclusion(dirLabel, dirVerb, recentAvg, lo, hi) {
    var parts = [];
    parts.push('近期健康分整体' + dirVerb + '（呈' + dirLabel + '态势）。');
    if (dirLabel === '下降') parts.push('健康分持续下降，建议尽快排查质量/工单等下滑维度并干预。');
    else if (dirLabel === '上升') parts.push('治理成效逐步显现，建议保持当前节奏并固化经验。');
    else parts.push('健康分趋于平稳，可关注是否存在结构性瓶颈。');
    parts.push('近 7 天均值 ' + round1(recentAvg) + '，' + level(recentAvg) + '；');
    parts.push('最低 ' + round1(lo.score) + '（' + lo.date + '），最高 ' + round1(hi.score) + '（' + hi.date + '）。');
    return parts.join('');
  }

  // 健康分日历热力：按天着色(>=85绿/>=60黄/其余红), GitHub风格
  function renderHealthCalendar(box, trend) {
    box.innerHTML = '';
    var byDay = {};
    (trend || []).forEach(function (t) { var d = (t.day || t.date || '').toString().slice(0, 10); if (d) byDay[d] = Number(t.score || t.totalScore || 0); });
    var keys = Object.keys(byDay);
    if (!keys.length) { box.appendChild(DN.empty('暂无健康分历史', 'clock')); return; }
    var WEEKS = 13;
    var today = new Date(); today.setHours(0, 0, 0, 0);
    var end = new Date(today); end.setDate(end.getDate() + (6 - end.getDay()));
    var start = new Date(end); start.setDate(start.getDate() - (WEEKS * 7 - 1));
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    var fmt = function (d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); };
    var colorOf = function (v) { return v == null ? 'var(--bg-hover,#ebedf0)' : v >= 85 ? '#52c41a' : v >= 60 ? '#faad14' : '#ff4d4f'; };
    var wrap = DN.h('div', { style: 'display:flex;gap:3px;overflow-x:auto' });
    for (var wk = 0; wk < WEEKS; wk++) {
      var colEl = DN.h('div', { style: 'display:flex;flex-direction:column;gap:3px' });
      for (var dow = 0; dow < 7; dow++) {
        var d = new Date(start); d.setDate(start.getDate() + wk * 7 + dow);
        if (d > today) { colEl.appendChild(DN.h('div', { style: 'width:13px;height:13px' })); continue; }
        var ds = fmt(d), v = byDay[ds];
        // R25 联动: 点格 → 跳健康分并按该日定位趋势卡片, 消除日历纯展示死胡同
        colEl.appendChild(DN.h('div', {
          title: ds + (v != null ? ' · 健康分 ' + (Math.round(v * 10) / 10) + ' · 点击查看' : ' · 无数据 · 点击查看'),
          style: 'width:13px;height:13px;border-radius:2px;cursor:pointer;background:' + colorOf(v),
          onclick: (function (day) { return function () { goModule('health', { date: day }); }; })(ds)
        }));
      }
      wrap.appendChild(colEl);
    }
    box.appendChild(wrap);
    box.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:8px 0 0', text: '绿≥85 优秀 · 黄≥60 一般 · 红<60 待完善（近13周每日健康分，点格跳健康分并定位该日）' }));
  }

  var SENS_TOP = 12; // 敏感分布最多展示条目数，超出聚合为“其余 N 项”避免卡片无限拉长
  function sensitiveCard(title, icon, map, barTone) {
    var c = DN.card({ title: title, icon: icon });
    map = (map && typeof map === 'object') ? map : {};
    var keys = Object.keys(map);
    if (!keys.length) {
      c.body.appendChild(DN.empty('暂无' + title + '数据，可前往数据分级模块完善敏感标识', 'tag'));
      return c.el;
    }
    // 按数量降序，仅取 Top N，其余合并为一条，杜绝超长列表
    var sorted = keys.map(function (k) { return { k: k, v: Number(map[k]) || 0 }; })
      .sort(function (a, b) { return b.v - a.v; });
    var shown = sorted.slice(0, SENS_TOP);
    var bars = shown.map(function (it) {
      var label = it.k == null ? '' : String(it.k);
      var short = label.length > 18 ? label.slice(0, 18) + '…' : label; // 超长标签截断显示，原文用于点击意图无歧义
      return { label: short, value: it.v, tone: barTone, display: fmtInt(it.v), onClick: function () { goModule('classification'); } };
    });
    if (sorted.length > SENS_TOP) {
      var restSum = sorted.slice(SENS_TOP).reduce(function (s, it) { return s + it.v; }, 0);
      bars.push({ label: '其余 ' + (sorted.length - SENS_TOP) + ' 项', value: restSum, tone: 'muted', display: fmtInt(restSum), onClick: function () { goModule('classification'); } });
    }
    c.body.appendChild(DN.bars(bars));
    return c.el;
  }

  // ========== 全屏大屏模式(大功能): 总览进入全屏展示 ==========
  function toggleBigScreen(box) {
    var target = box.parentNode || box; // #govModuleContent
    var fsEl = document.fullscreenElement || document.webkitFullscreenElement;
    if (fsEl) {
      (document.exitFullscreen || document.webkitExitFullscreen || function () {}).call(document);
      return;
    }
    var req = target.requestFullscreen || target.webkitRequestFullscreen;
    if (!req) { DN.toast('当前浏览器不支持全屏', 'warn'); return; }
    // 防重复绑定：先移除上一次可能残留的监听（_fsOnChange 挂在 target 上），避免监听堆积
    if (target._fsOnChange) {
      document.removeEventListener('fullscreenchange', target._fsOnChange);
      document.removeEventListener('webkitfullscreenchange', target._fsOnChange);
    }
    target.classList.add('gov-bigscreen');
    var p = req.call(target);
    if (p && p.catch) p.catch(function () { DN.toast('进入全屏失败', 'err'); target.classList.remove('gov-bigscreen'); });
    var onChange = function () {
      if (!(document.fullscreenElement || document.webkitFullscreenElement)) {
        target.classList.remove('gov-bigscreen');
        document.removeEventListener('fullscreenchange', onChange);
        document.removeEventListener('webkitfullscreenchange', onChange);
        target._fsOnChange = null;
      }
    };
    target._fsOnChange = onChange;
    document.addEventListener('fullscreenchange', onChange);
    document.addEventListener('webkitfullscreenchange', onChange);
  }

  // ========== 治理体检报告(大功能): 汇编总览数据为可打印报告 ==========
  function buildReport(btn) {
    var d = _lastData;
    if (!d) { DN.toast('数据未就绪, 请稍后重试', 'warn'); return; }
    // 防重复提交：生成期间禁用按钮，0.8s 后恢复（弹窗为同步生成，足够覆盖）
    if (btn && btn.classList) {
      if (btn.classList.contains('is-disabled')) return;
      btn.classList.add('is-disabled'); btn.style.pointerEvents = 'none'; btn.style.opacity = '0.6';
      setTimeout(function () { btn.classList.remove('is-disabled'); btn.style.pointerEvents = ''; btn.style.opacity = ''; }, 800);
    }
    var health = d.health || {}, assets = d.assets || {}, quality = d.quality || {}, issues = d.issues || {}, sensitive = d.sensitive || {};
    var dims = health.dims || {}, weights = health.weights || {};
    var total = round1(Number(health.total) || 0);
    var esc = function (s) { return String(s == null ? '' : s).replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; }); };
    var now = new Date();
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    var ts = now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()) + ' ' + pad(now.getHours()) + ':' + pad(now.getMinutes());
    var dimRows = DIMS.map(function (k) {
      var v = round1(Number(dims[k]) || 0), w = weights[k];
      return '<tr><td>' + k + '</td><td>' + v + '</td><td>' + (w != null ? w : '-') + '</td><td>' + level(v) + '</td></tr>';
    }).join('');
    var sensRows = Object.keys(sensitive.byLevel || {}).map(function (k) { return '<tr><td>' + esc(k) + '</td><td>' + (Number(sensitive.byLevel[k]) || 0) + '</td></tr>'; }).join('') || '<tr><td colspan="2" style="color:#999">无敏感分布数据</td></tr>';
    var rate = round1(Number(quality.recentPassRate) || 0);
    var pending = (Number(issues.open) || 0) + (Number(issues.fixing) || 0);
    var html = '<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8"><title>数据治理体检报告 ' + ts + '</title>'
      + '<style>body{font-family:-apple-system,"Microsoft YaHei",sans-serif;color:#1f2329;max-width:820px;margin:24px auto;padding:0 20px;line-height:1.7}'
      + 'h1{font-size:24px;border-bottom:3px solid #3457d5;padding-bottom:10px}h2{font-size:17px;margin-top:26px;color:#3457d5;border-left:4px solid #3457d5;padding-left:8px}'
      + '.score{font-size:48px;font-weight:800;color:' + (total >= 85 ? '#52c41a' : total >= 60 ? '#faad14' : '#ff4d4f') + '}'
      + 'table{width:100%;border-collapse:collapse;margin:10px 0;font-size:13px}th,td{border:1px solid #e5e6eb;padding:7px 10px;text-align:left}th{background:#f6f7f9}'
      + '.kpi{display:flex;gap:24px;flex-wrap:wrap;margin:12px 0}.kpi div{font-size:13px;color:#666}.kpi b{display:block;font-size:22px;color:#1f2329}'
      + '.muted{color:#86909c;font-size:12px}@media print{.noprint{display:none}body{margin:0}}</style></head><body>'
      + '<div class="noprint" style="text-align:right;margin-bottom:10px"><button onclick="window.print()" style="padding:6px 16px;background:#3457d5;color:#fff;border:0;border-radius:6px;cursor:pointer">打印 / 另存为PDF</button></div>'
      + '<h1>数据治理体检报告</h1><div class="muted">生成时间：' + ts + '　|　DataNote 数据治理平台</div>'
      + '<h2>一、治理健康总分</h2><div class="score">' + total + ' <span style="font-size:18px;color:#86909c">/ 100 · ' + level(total) + '</span></div>'
      + '<h2>二、五维健康明细</h2><table><tr><th>维度</th><th>得分</th><th>权重</th><th>评级</th></tr>' + dimRows + '</table>'
      + '<h2>三、数据资产概览</h2><div class="kpi"><div><b>' + fmtInt(assets.tableCount) + '</b>表数</div><div><b>' + fmtInt(assets.columnCount) + '</b>字段数</div><div><b>' + fmtInt(assets.dbCount) + '</b>库数</div><div><b>' + DN.fmtBytes(assets.totalSizeBytes) + '</b>总体量</div></div>'
      + '<h2>四、数据质量</h2><div class="kpi"><div><b>' + rate + '%</b>近期检查通过率</div><div><b>' + fmtInt(quality.runs24h) + '</b>近24h运行数</div></div>'
      + '<h2>五、治理工单</h2><div class="kpi"><div><b>' + fmtInt(issues.open) + '</b>待处理</div><div><b>' + fmtInt(issues.fixing) + '</b>处理中</div><div><b>' + fmtInt(issues.closed) + '</b>已关闭</div><div><b style="color:' + (pending ? '#faad14' : '#52c41a') + '">' + pending + '</b>待办合计</div></div>'
      + '<h2>六、敏感数据分布</h2><table><tr><th>敏感等级</th><th>列数</th></tr>' + sensRows + '</table>'
      + '<h2>七、体检结论</h2><p>' + reportConclusion(total, rate, pending) + '</p>'
      + '<div class="muted" style="margin-top:30px;border-top:1px solid #e5e6eb;padding-top:10px">本报告由系统依据实时治理指标自动生成，供治理决策参考。</div>'
      + '</body></html>';
    var w = window.open('', '_blank');
    if (!w) { DN.toast('弹窗被拦截, 请允许后重试', 'warn'); return; }
    w.document.open(); w.document.write(html); w.document.close();
    DN.toast('体检报告已生成', 'ok');
  }
  function reportConclusion(total, rate, pending) {
    var parts = [];
    parts.push(total >= 85 ? '治理健康总分优秀，整体治理水平良好。' : total >= 60 ? '治理健康总分中等，部分维度有提升空间。' : '治理健康总分偏低，亟需加强治理投入。');
    parts.push(rate >= 90 ? '数据质量检查通过率高，质量管控有效。' : rate >= 70 ? '数据质量通过率中等，建议核查不达标规则。' : '数据质量通过率偏低，需优先排查高频失败规则。');
    parts.push(pending > 0 ? ('当前有 ' + pending + ' 个待处理治理工单，建议尽快闭环。') : '治理工单已全部闭环，状态良好。');
    return parts.join('');
  }

  // ========== 工具 ==========
  // 统一深链跳转：保留既有 govGoModule 行为，仅在宿主导航函数缺失时降级提示，避免按钮点了无反应
  function goModule(key, ctx) {
    if (window.govGoModule) { govGoModule(key, ctx || {}); return; }
    DN.toast('暂无法跳转该模块', 'warn');
  }
  // 分值映射药丸/磁贴色调：>=85 绿 / >=60 黄 / 其余红
  function tone(v) { return v >= 85 ? 'ok' : (v >= 60 ? 'warn' : 'err'); }
  function toneColor(t) { return t === 'ok' ? '#389e0d' : t === 'warn' ? '#d48806' : t === 'err' ? '#cf1322' : 'var(--primary,#3457d5)'; }
  function level(v) { return v >= 85 ? '优秀' : v >= 70 ? '良好' : v >= 60 ? '一般' : '待完善'; }
  function round1(v) { return Math.round((Number(v) || 0) * 10) / 10; }
  function fmtInt(v) {
    v = Number(v) || 0;
    return String(Math.round(v)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }
})();
