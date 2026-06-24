/* 治理模块：数据血缘（M3 字段映射血缘查询 / M4 SQL 解析血缘扩展） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  // SVG 颜色走 v3 令牌：presentation 属性不支持 var()，统一经 style 属性写入
  var C = { primary: 'var(--primary)', up: 'var(--error)', down: 'var(--success)', node: 'var(--bg-sunken)', nodeBorder: 'var(--divider)', text: 'var(--text-primary)' };

  // 共享库表选择器（血缘探查：查询/溯源/图谱共用一组，级联真实元数据）
  var lnPicker = null;
  // centerGraphOn 的轮询定时器句柄: 新一次聚焦前先清掉上一次, 避免多个轮询竞争/泄漏
  var centerWaitTimer = null;
  // 进行中的 GET 去重: 同 url 复用同一 Promise, 杜绝重复点击产生的并发同请求
  var inflight = {};

  /** 异步期间锁定按钮防重复提交: 加 loading 态 + 禁交互, 返回 done() 还原(幂等)。与各治理模块一致。 */
  function lockBtn(btn) {
    if (!btn) return function () {};
    btn.classList.add('is-loading');
    btn.style.pointerEvents = 'none';
    btn.style.opacity = '0.6';
    btn.setAttribute('aria-busy', 'true');
    var done = false;
    return function () {
      if (done) return; done = true;
      btn.classList.remove('is-loading');
      btn.style.pointerEvents = '';
      btn.style.opacity = '';
      btn.removeAttribute('aria-busy');
    };
  }

  /** 去重 GET: 相同 url 在途时复用同一 Promise(完成即释放), 避免重复请求风暴。 */
  function getOnce(url) {
    if (inflight[url]) return inflight[url];
    var p = DN.get(url);
    inflight[url] = p;
    p.then(function () { delete inflight[url]; }, function () { delete inflight[url]; });
    return p;
  }

  /** 超长文本截断 + title 悬浮看全文(返回安全转义的 DOM 节点)。 */
  function clip(s, max) {
    s = s == null ? '' : String(s);
    max = max || 60;
    if (s.length <= max) return DN.h('span', { text: s });
    return DN.h('span', { text: s.slice(0, max) + '…', title: s });
  }

  window.GOV_RENDERERS.lineage = function (c) {
    // 重新进入本页时清理上一次挂载遗留的轮询定时器/在途请求映射, 避免跨挂载的定时器泄漏与状态串台
    if (centerWaitTimer) { clearTimeout(centerWaitTimer); centerWaitTimer = null; }
    inflight = {};
    // 顶部操作条
    var bar = DN.h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px' });
    bar.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '从同步任务重建血缘', 'data-perm': 'governance:manage',
      onclick: function (e) {
        // 重建会全量覆盖血缘边(破坏性), 二次确认; 期间锁按钮防重复提交
        var btn = e.currentTarget;
        DN.confirm('将依据同步任务全量重建血缘边, 可能覆盖现有边数据。确认继续？', { title: '重建确认', danger: true }).then(function (ok) {
          if (!ok) return;
          var done = lockBtn(btn);
          DN.post('/api/lineage/rebuild-edges').then(function (r) {
            DN.toast('已重建 ' + (r && r.edgeCount != null ? r.edgeCount : 0) + ' 条血缘边', 'success');
          }).catch(function (err) { DN.toast(err && err.message ? err.message : '重建失败', 'error'); })
            .then(done);
        });
      } }));
    bar.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '解析脚本SQL血缘', 'data-perm': 'governance:manage',
      onclick: function (e) {
        var done = lockBtn(e.currentTarget);
        DN.post('/api/lineage/parse-scripts').then(function (r) {
          DN.toast('SQL血缘已解析 ' + (r && r.edgeCount != null ? r.edgeCount : 0) + ' 条边', 'success');
        }).catch(function (err) { DN.toast(err && err.message ? err.message : '解析失败', 'error'); })
          .then(done);
      } }));
    c.appendChild(bar);

    // 1. 血缘探查（查询/影响溯源/图谱合一：共享一组库表选择器 + 共享结果区）
    var qCard = DN.card({ title: '血缘探查', icon: 'lineage' });
    c.appendChild(qCard.el);
    lnPicker = DN.dbTablePicker({});
    var q = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    q.appendChild(lnPicker.el);
    q.appendChild(DN.h('span', { text: '跳数', style: 'color:var(--text-muted);font-size:13px' }));
    var gDep = DN.h('input', { id: 'grDepth', type: 'number', value: '2', min: '1', max: '6',
      class: 'dn-form-input', title: '跳数（仅画血缘图用）', style: 'width:60px' });
    q.appendChild(gDep);
    q.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '查询血缘', onclick: function (e) { queryLineage(e); } }));
    q.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '下游影响',
      onclick: function (e) { queryFlow('impact', e); } }));
    q.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '上游溯源',
      onclick: function (e) { queryFlow('trace', e); } }));
    q.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '画血缘图', onclick: function (e) { queryGraph(e); } }));
    qCard.body.appendChild(q);
    // 图例
    qCard.body.appendChild(DN.h('div', { style: 'color:var(--text-muted);font-size:12px;margin-bottom:8px',
      html: '<span style="display:inline-block;width:12px;height:12px;background:' + C.primary + ';border-radius:var(--radius-sm);vertical-align:middle;margin-right:4px"></span>★ 中心表 ' +
        '<span style="display:inline-block;width:18px;border-top:2px solid ' + C.up + ';vertical-align:middle;margin:0 4px 0 12px"></span>上游边 ' +
        '<span style="display:inline-block;width:18px;border-top:2px solid ' + C.down + ';vertical-align:middle;margin:0 4px 0 12px"></span>下游边 · 单击节点高亮相邻，双击节点设为中心，悬停边看来源/层级' }));
    qCard.body.appendChild(DN.h('div', { id: 'lnResult' }));

    // R21 深链：接收 ctx.table({db,table}) → 自动以该表为中心加载并绘制血缘图谱
    var ctx = window.__govCtx || {};
    if (ctx.table && ctx.table.db && ctx.table.table) {
      var cd = ctx.table.db, ct = ctx.table.table;
      // 在“血缘探查”卡片标题下提示当前聚焦中心
      qCard.body.insertBefore(DN.h('div', { id: 'lnFocusHint',
        style: 'color:var(--primary);font-size:12px;margin-bottom:8px',
        text: '已按深链聚焦：以 ' + cd + '.' + ct + ' 为中心' }), q);
      // 复用 lnPicker 填表 + 触发画图（与孤儿表下钻同一逻辑）
      centerGraphOn(cd, ct);
    }

    // 4. 孤儿表检测（创新功能）：基于已有血缘边数据，识别某库中“既无上游也无下游血缘边”的表（疑似废弃/未接入），供治理清理参考
    var oCard = DN.card({ title: '孤儿表检测', icon: 'alert' });
    c.appendChild(oCard.el);
    oCard.body.appendChild(DN.h('div', { class: 'gov-desc',
      style: 'color:var(--text-muted);font-size:12px;margin-bottom:10px',
      text: '扫描所选库下所有表的表级血缘，列出既无上游来源、也无下游去向的表（疑似废弃或未接入血缘），供治理清理参考。' }));
    var oSel = DN.h('select', { id: 'orphanDb', class: 'dn-form-select', style: 'width:auto;min-width:240px' });
    oSel.innerHTML = '<option value="">选择库</option>';
    DN.metaDatabases().then(function (dbs) {
      (dbs || []).forEach(function (d) {
        var o = document.createElement('option'); o.value = d; o.textContent = d; oSel.appendChild(o);
      });
    }).catch(function () {});
    var oq = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    oq.appendChild(DN.h('span', { text: '库', style: 'color:var(--text-muted);font-size:13px' }));
    oq.appendChild(oSel);
    oq.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '检测孤儿表', onclick: function (e) { detectOrphans(e); } }));
    oCard.body.appendChild(oq);
    oCard.body.appendChild(DN.h('div', { id: 'orphanResult' }));
  };

  /** 并发受限地对一组表查询表级血缘，避免请求风暴。fn 返回 Promise，limit 为最大并发。 */
  function mapLimit(items, limit, fn) {
    return new Promise(function (resolve) {
      var results = new Array(items.length), idx = 0, done = 0;
      if (!items.length) { resolve(results); return; }
      function next() {
        if (idx >= items.length) return;
        var cur = idx++;
        fn(items[cur], cur).then(function (r) { results[cur] = r; }, function () { results[cur] = null; })
          .then(function () { if (++done === items.length) resolve(results); else next(); });
      }
      for (var i = 0; i < Math.min(limit, items.length); i++) next();
    });
  }

  // 孤儿表检测：取所选库全部表，逐表查表级上下游边，聚合出上下游皆空者
  var ORPHAN_SCAN_CAP = 300; // 超大库逐表查边会造成请求风暴, 限定单次扫描上限
  function detectOrphans(e) {
    var sel = document.getElementById('orphanDb');
    var db = sel ? sel.value : '';
    if (!db) { DN.toast('请先选择库', 'error'); if (sel) sel.focus(); return; }
    var box = document.getElementById('orphanResult');
    if (!box) return;
    var btn = e && e.currentTarget ? e.currentTarget : null;
    var done = lockBtn(btn);
    box.innerHTML = '';
    box.appendChild(DN.skeleton(4));
    DN.metaTables(db).then(function (tables) {
      tables = tables || [];
      if (!tables.length) { box.innerHTML = ''; box.appendChild(DN.empty('该库下暂无表', 'db')); return; }
      var total = tables.length;
      var scanList = tables;
      var capped = false;
      if (total > ORPHAN_SCAN_CAP) {   // 边界: 仅扫描前 N 张, 提示用户已截断, 防止上千请求卡死
        scanList = tables.slice(0, ORPHAN_SCAN_CAP);
        capped = true;
        DN.toast('该库表数过多, 本次仅扫描前 ' + ORPHAN_SCAN_CAP + ' 张', 'warn');
      }
      return mapLimit(scanList, 6, function (t) {
        var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(t);
        return getOnce('/api/lineage/table-edges' + qs).then(function (nb) {
          nb = nb || {};
          var up = (nb.upstream || []).length, down = (nb.downstream || []).length;
          return { table: t, up: up, down: down };
        });
      }).then(function (stats) {
        renderOrphans(box, db, capped ? scanList.length : total, stats.filter(function (s) { return s; }), capped ? total : 0);
      });
    }).catch(function (err) { box.innerHTML = ''; box.appendChild(DN.errorBox('检测失败: ' + (err && err.message ? err.message : err), function () { detectOrphans(); })); })
      .then(done);
  }

  /** 把指定库表填入“血缘探查”选择器并触发画图（以该表为中心）。
      复用现有 lnPicker(其 el 内含库/表两个 select)与 queryGraph，不臆造接口。
      用于：孤儿表下钻核验、R21 深链聚焦、图节点双击切换中心。 */
  function centerGraphOn(db, table) {
    if (!db || !table) { DN.toast('缺少库或表, 无法聚焦图谱', 'error'); return; }
    if (!lnPicker || !lnPicker.el) { DN.toast('请到“血缘探查”手动查询', 'info'); return; }
    var sels = lnPicker.el.querySelectorAll('select');
    var dbSel = sels[0], tbSel = sels[1];
    if (!dbSel || !tbSel) { DN.toast('请到“血缘探查”手动查询', 'info'); return; }
    // 新一次聚焦前清掉上一次未完成的轮询, 避免多个 waitTable 定时器同时跑(竞争 + 泄漏)
    if (centerWaitTimer) { clearTimeout(centerWaitTimer); centerWaitTimer = null; }
    dbSel.value = db;
    // 选库后需触发级联加载表列表，再等待目标表选项出现后选中并画图
    dbSel.dispatchEvent(new Event('change'));
    var tries = 0;
    (function waitTable() {
      var hit = Array.prototype.some.call(tbSel.options, function (o) { return o.value === table; });
      if (hit) {
        centerWaitTimer = null;
        tbSel.value = table;
        tbSel.dispatchEvent(new Event('change'));
        var gr = document.getElementById('lnResult');
        if (gr && gr.scrollIntoView) gr.scrollIntoView({ behavior: 'smooth', block: 'center' });
        queryGraph();
      } else if (tries++ < 40) {
        centerWaitTimer = setTimeout(waitTable, 100); // 表列表为异步加载，轮询至多约 4 秒
      } else {
        centerWaitTimer = null;
        DN.toast('已为你选择「' + db + '」库，请在“血缘探查”手动选表查询', 'info');
        if (lnPicker.el.scrollIntoView) lnPicker.el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    })();
  }

  /** R25 联动：把血缘结果里的某张表打通到其它治理子模块/资产目录，消除“看到了却去不了”的死胡同。
      复用全局 navigateTo / govGoModule(与 gov-assets 一致的契约)，独立页面无路由时静默不渲染。 */
  function tableActionMenu(db, table, opts) {
    opts = opts || {};
    var wrap = DN.h('span', { style: 'display:inline-flex;gap:4px;flex-wrap:wrap;justify-content:center' });
    var fqn = (db || '') + '.' + (table || '');
    var acts = [];
    // 自己已是图谱中心时无需再给“图谱”按钮(避免原地重绘的无效操作)
    if (!opts.skipGraph) {
      acts.push({ text: '图谱', title: '以 ' + fqn + ' 为中心画血缘图', go: function () { centerGraphOn(db, table); } });
    }
    // 仅在工作台(有路由)时给出跨模块下钻，独立页直接降级为“图谱”单项
    if (window.navigateTo) {
      acts.push({ text: '质量', title: '查看 ' + fqn + ' 的质量规则', go: function () { navigateTo('governance', { gov: 'quality', table: { db: db, table: table } }); } });
      acts.push({ text: '工单', title: '查看 ' + fqn + ' 的相关工单', go: function () { navigateTo('governance', { gov: 'health', issueFilter: { relTable: fqn } }); } });
      acts.push({ text: '目录', title: '在资产目录打开 ' + fqn, go: function () { navigateTo('catalog', { openTable: { db: db, table: table } }); } });
      acts.push({ text: '🤖AI', title: '让AI分析 ' + fqn + ' 的血缘与下游影响', go: function () {
        if (window.dnAskAi) window.dnAskAi('评估表 ' + fqn + ' 的下游影响面与变更/下线前检查清单。[表:' + fqn + ']',
          { route: 'governance', gov: 'lineage', db: db, table: table }); } });
    }
    acts.forEach(function (a) {
      wrap.appendChild(DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: a.text, title: a.title,
        onclick: function (e) { e.stopPropagation(); a.go(); } }));
    });
    return wrap;
  }

  function renderOrphans(box, db, total, stats, fullTotal) {
    box.innerHTML = '';
    stats = stats || [];
    var orphans = stats.filter(function (s) { return s.up === 0 && s.down === 0; });
    var scanned = stats.length;                 // 实际成功取到边数据的表(排除请求失败)
    var connected = Math.max(0, scanned - orphans.length);
    var pct = scanned > 0 ? Math.round(connected * 100 / scanned) : 0;
    if (pct < 0) pct = 0; else if (pct > 100) pct = 100;   // 占比夹取至 0–100
    var failed = total - scanned;               // 检测失败的表数(若有)
    box.appendChild(DN.statRow([
      { icon: 'db', label: fullTotal ? '已扫描/库内表数' : '库内表数',
        value: fullTotal ? total + '/' + fullTotal : total,
        sub: fullTotal ? '超量已截断扫描' : '', tone: fullTotal ? 'info' : '' },
      { icon: 'alert', label: '孤儿表数', value: orphans.length, tone: orphans.length > 0 ? 'warn' : 'ok',
        sub: orphans.length > 0 ? '疑似废弃/未接入' : '全部已接入血缘' },
      { icon: 'check', label: '血缘覆盖率', value: pct + '%',
        sub: failed > 0 ? '(' + failed + ' 张检测失败已排除)' : '已接入 ' + connected + '/' + scanned,
        tone: pct >= 80 ? 'ok' : (pct >= 50 ? 'info' : 'warn') }
    ]));
    if (!orphans.length) {
      var ok = DN.empty('未发现孤儿表，' + DN.esc(db) + ' 库内 ' + scanned + ' 张表血缘覆盖良好', 'check');
      ok.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '换个库继续检测',
        style: 'color:var(--primary);font-size:12px',
        onclick: function () { var sel = document.getElementById('orphanDb'); if (sel) sel.focus(); } }));
      box.appendChild(ok);
      return;
    }
    box.appendChild(DN.table({
      columns: [
        { label: '孤儿表', copyable: true, render: function (s) { return clip(db + '.' + s.table, 64); },
          exportValue: function (s) { return db + '.' + s.table; } },
        { label: '判定', align: 'center', render: function () { return DN.pill('孤儿表', 'err'); }, exportValue: function () { return '孤儿表'; } },
        { label: '操作', align: 'center', render: function (s) {
            // R25：孤儿表是治理清理的首要对象，除核验血缘外，直接提供质量/工单/目录下钻
            return tableActionMenu(db, s.table);
          } }
      ],
      rows: orphans,
      pageSize: 15,                 // 边界: 孤儿表可能成百, 分页避免长列表撑爆视口
      searchKeys: ['table'],
      searchPlaceholder: '搜索孤儿表...',
      exportName: '孤儿表清单_' + db,
      empty: '未发现孤儿表', emptyIcon: 'check'
    }));
  }

  var SVG_NS = 'http://www.w3.org/2000/svg';
  function svg(tag, attrs) {
    var el = document.createElementNS(SVG_NS, tag);
    if (attrs) Object.keys(attrs).forEach(function (k) { el.setAttribute(k, attrs[k]); });
    return el;
  }

  function queryGraph(e) {
    if (!lnPicker) { DN.toast('图谱选择器未就绪', 'error'); return; }
    var db = lnPicker.db();
    var table = lnPicker.table();
    if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
    var depEl = document.getElementById('grDepth');
    var depth = parseInt(depEl ? depEl.value : '', 10);
    if (isNaN(depth)) depth = 2;
    if (depth < 1 || depth > 6) { DN.toast('跳数需在 1-6 之间', 'error'); if (depEl) depEl.focus(); return; }
    var box = document.getElementById('lnResult');
    if (!box) return;
    var done = lockBtn(e && e.currentTarget ? e.currentTarget : null);
    box.innerHTML = '';
    box.appendChild(DN.skeleton(4));
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table) + '&depth=' + depth;
    getOnce('/api/lineage/graph' + qs).then(function (g) {
      g = g || {};
      var nodes = g.nodes || [], edges = g.edges || [];
      box.innerHTML = '';
      if (nodes.length <= 1 && !edges.length) {
        box.appendChild(DN.empty('该表暂无血缘边（先点上方重建/解析）', 'lineage')); return;
      }
      box.appendChild(renderGraph(nodes, edges, db + '.' + table));
    }).catch(function (err) { box.innerHTML = ''; box.appendChild(DN.errorBox('查询失败: ' + (err && err.message ? err.message : err), function () { queryGraph(); })); })
      .then(done);
  }

  /** 分层布局：以中心表为第 0 列，下游为正列(右)、上游为负列(左)，纯 SVG 绘制有向图。 */
  function renderGraph(nodes, edges, centerId) {
    nodes = nodes || []; edges = edges || [];
    // 性能边界: 超大图(数百节点)纯 SVG 渲染易卡顿, 提示用户缩小跳数而非直接卡死
    if (nodes.length > 200) DN.toast('血缘节点较多(' + nodes.length + '), 渲染可能略慢, 建议减小跳数', 'warn');
    // 邻接：out(下游) / in(上游)
    var out = {}, inc = {};
    edges.forEach(function (e) {
      (out[e.src] = out[e.src] || []).push(e.dst);
      (inc[e.dst] = inc[e.dst] || []).push(e.src);
    });
    // BFS 计算每个节点的列(相对中心)：下游 +1，上游 -1
    var col = {}; col[centerId] = 0;
    var queue = [centerId];
    while (queue.length) {
      var cur = queue.shift();
      (out[cur] || []).forEach(function (n) {
        if (col[n] == null) { col[n] = col[cur] + 1; queue.push(n); }
      });
      (inc[cur] || []).forEach(function (n) {
        if (col[n] == null) { col[n] = col[cur] - 1; queue.push(n); }
      });
    }
    // 未连通到中心的孤立节点放第 0 列
    nodes.forEach(function (n) { if (col[n.id] == null) col[n.id] = 0; });

    // 按列分组、纵向排布
    var cols = {};
    nodes.forEach(function (n) { (cols[col[n.id]] = cols[col[n.id]] || []).push(n.id); });
    var colKeys = Object.keys(cols).map(Number).sort(function (a, b) { return a - b; });

    var NW = 150, NH = 36, GAPX = 90, GAPY = 22, PAD = 20;
    var pos = {}; // id -> {x,y}
    var maxRows = 0;
    colKeys.forEach(function (ck) { if (cols[ck].length > maxRows) maxRows = cols[ck].length; });
    colKeys.forEach(function (ck, ci) {
      var list = cols[ck];
      var colH = list.length * NH + (list.length - 1) * GAPY;
      var totalH = maxRows * NH + (maxRows - 1) * GAPY;
      var y0 = PAD + (totalH - colH) / 2;
      list.forEach(function (id, ri) {
        pos[id] = { x: PAD + ci * (NW + GAPX), y: y0 + ri * (NH + GAPY) };
      });
    });
    var width = PAD * 2 + colKeys.length * NW + (colKeys.length - 1) * GAPX;
    var height = PAD * 2 + maxRows * NH + (maxRows - 1) * GAPY;
    if (width < 320) width = 320;

    var s = svg('svg', { width: String(width), height: String(height),
      viewBox: '0 0 ' + width + ' ' + height,
      style: 'background:var(--bg-card);border:1px solid var(--border);border-radius:var(--radius-lg);max-width:100%' });
    // 箭头标记（上游红 / 下游绿）
    var defs = svg('defs');
    [['arrU', C.up], ['arrD', C.down]].forEach(function (a) {
      var m = svg('marker', { id: a[0], viewBox: '0 0 10 10', refX: '9', refY: '5',
        markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse' });
      m.appendChild(svg('path', { d: 'M0,0 L10,5 L0,10 z', style: 'fill:' + a[1] }));
      defs.appendChild(m);
    });
    s.appendChild(defs);

    var edgeEls = []; // {el, src, dst}
    var gEdges = svg('g');
    edges.forEach(function (e) {
      var a = pos[e.src], b = pos[e.dst];
      if (!a || !b) return;
      var downstream = (col[e.dst] || 0) >= (col[e.src] || 0);
      var color = downstream ? C.down : C.up;
      var marker = downstream ? 'url(#arrD)' : 'url(#arrU)';
      var x1 = a.x + NW, y1 = a.y + NH / 2, x2 = b.x, y2 = b.y + NH / 2;
      // 若目标在左侧（上游边方向反向），从左边出、右边入
      if (b.x < a.x) { x1 = a.x; x2 = b.x + NW; }
      var line = svg('line', { x1: x1, y1: y1, x2: x2, y2: y2,
        style: 'stroke:' + color, 'stroke-width': '1.6', 'marker-end': marker });
      var tip = (downstream ? '下游' : '上游') + ' · 来源:' + (e.source || '-') + ' · ' + (e.level || 'TABLE');
      var title = svg('title'); title.textContent = tip; line.appendChild(title);
      gEdges.appendChild(line);
      edgeEls.push({ el: line, src: e.src, dst: e.dst, color: color });
    });
    s.appendChild(gEdges);

    var nodeEls = {}; // id -> rect
    var selected = null;
    var gNodes = svg('g'); // 节点统一挂到一个组里, 一次性 append, 减少 reflow(性能)
    nodes.forEach(function (n) {
      var p = pos[n.id]; if (!p) return;
      var g = svg('g', { style: 'cursor:pointer' });
      var isCenter = n.id === centerId;
      var nid = String(n.id == null ? '' : n.id); // 防御: id 异常时不抛 .length/.slice 错
      var rect = svg('rect', { x: p.x, y: p.y, width: NW, height: NH, rx: '8',
        style: 'fill:' + (isCenter ? C.primary : C.node) + ';stroke:' + (isCenter ? C.primary : C.nodeBorder),
        'stroke-width': '1' });
      var label = nid.length > 22 ? nid.slice(0, 21) + '…' : nid;
      // 非色cue(WCAG 1.4.1)：中心表此前仅靠蓝色填充区分，色盲不可辨，前缀★标记
      var txt = svg('text', { x: p.x + NW / 2, y: p.y + NH / 2 + 4, 'text-anchor': 'middle',
        'font-size': '12', style: 'fill:' + (isCenter ? 'var(--text-inverse)' : C.text) });
      txt.textContent = isCenter ? '★ ' + label : label;
      var ttl = svg('title'); ttl.textContent = nid + (isCenter ? '（当前中心）' : '（单击高亮 · 双击设为中心）'); rect.appendChild(ttl);
      g.appendChild(rect); g.appendChild(txt);
      g.addEventListener('click', function () {
        selected = (selected === n.id) ? null : n.id;
        highlight(selected);
      });
      // 双击非中心节点 → 以该表为中心重新画图（图内下钻，nid 形如 db.table）
      if (!isCenter) {
        g.addEventListener('dblclick', function () {
          var dot = nid.indexOf('.');
          if (dot <= 0) return;
          centerGraphOn(nid.slice(0, dot), nid.slice(dot + 1));
        });
      }
      gNodes.appendChild(g);
      nodeEls[n.id] = { rect: rect, center: isCenter };
    });
    s.appendChild(gNodes);

    function highlight(id) {
      // 复位
      Object.keys(nodeEls).forEach(function (k) {
        var ne = nodeEls[k];
        ne.rect.setAttribute('opacity', id ? '0.35' : '1');
      });
      edgeEls.forEach(function (ee) {
        ee.el.setAttribute('opacity', id ? '0.15' : '1');
        ee.el.setAttribute('stroke-width', '1.6');
      });
      if (!id) return;
      var keep = {}; keep[id] = 1;
      edgeEls.forEach(function (ee) {
        if (ee.src === id || ee.dst === id) {
          ee.el.setAttribute('opacity', '1');
          ee.el.setAttribute('stroke-width', '2.6');
          keep[ee.src] = 1; keep[ee.dst] = 1;
        }
      });
      Object.keys(keep).forEach(function (k) {
        if (nodeEls[k]) nodeEls[k].rect.setAttribute('opacity', '1');
      });
    }

    // ---- 缩放 / 平移 / 导出 ----
    var scale = 1;
    function applyScale() { s.setAttribute('width', (width * scale).toFixed(0)); s.setAttribute('height', (height * scale).toFixed(0)); }
    function zoom(d) { scale = Math.min(3, Math.max(0.4, Math.round((scale + d) * 10) / 10)); applyScale(); }
    function exportSvg() {
      try {
        var xml = new XMLSerializer().serializeToString(s);
        // 导出的独立 SVG 无页面 CSS 上下文, 把 var(--*) 解析为当前主题具体色值, 保证脱机打开颜色正确
        var rootCs = getComputedStyle(document.documentElement);
        xml = xml.replace(/var\((--[\w-]+)\)/g, function (m0, name) {
          var v = rootCs.getPropertyValue(name);
          return v ? v.trim() : m0;
        });
        var blob = new Blob(['<?xml version="1.0" encoding="UTF-8"?>\n' + xml], { type: 'image/svg+xml' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a'); a.href = url; a.download = 'lineage_' + String(centerId || '').replace(/[^a-zA-Z0-9]/g, '_') + '.svg';
        document.body.appendChild(a); a.click(); document.body.removeChild(a);
        setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
      } catch (e) { DN.toast('导出失败', 'error'); }
    }
    var scroll = DN.h('div', { style: 'overflow:auto;max-width:100%;max-height:600px;border:1px solid var(--border);border-radius:var(--radius-lg)' }, [s]);
    scroll.addEventListener('wheel', function (e) {
      if (!e.ctrlKey) return; e.preventDefault(); zoom(e.deltaY < 0 ? 0.1 : -0.1);
    }, { passive: false });
    var tb = DN.h('div', { style: 'display:flex;gap:6px;align-items:center;margin-bottom:8px' }, [
      DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '＋', title: '放大', onclick: function () { zoom(0.2); } }),
      DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '－', title: '缩小', onclick: function () { zoom(-0.2); } }),
      DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '复位', onclick: function () { scale = 1; applyScale(); } }),
      DN.h('a', { class: 'btn btn-sm btn-ghost', href: 'javascript:void(0)', text: '导出 SVG', onclick: exportSvg }),
      DN.h('span', { style: 'font-size:var(--fs-xs);color:var(--text-muted)', text: 'Ctrl+滚轮缩放 · 拖动滚动条平移 · 双击节点设为中心' })
    ]);
    var out = DN.h('div', {}, [tb, scroll]);
    // R25：中心表的跨模块下钻条，让图谱不再是“看完即止”的死胡同
    var dot = String(centerId || '').indexOf('.');
    if (dot > 0 && window.navigateTo) {   // 独立页(无路由)中心表无跨模块去处, 不渲染空下钻条
      var cdb = centerId.slice(0, dot), ctb = centerId.slice(dot + 1);
      var drill = DN.h('div', { style: 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:10px;padding:8px 12px;background:var(--bg-hover);border:1px solid var(--border);border-radius:var(--radius-lg)' });
      drill.appendChild(DN.h('span', { text: '中心表 ' + centerId + ' 下钻：', style: 'font-size:12px;font-weight:600;color:var(--text-muted)' }));
      drill.appendChild(tableActionMenu(cdb, ctb, { skipGraph: true }));
      out.appendChild(drill);
    }
    return out;
  }

  // 影响面板(大功能): 受影响表数/库数/最大层级 统计 + 按层级分组芯片
  var CHIPS_PER_LEVEL = 40; // 边界: 单层受影响表可能上百, 芯片超量折叠为“+N 张”避免卡顿/撑爆
  function buildImpactPanel(list, kind, centerId) {
    list = list || [];
    var dbs = {}, maxDepth = 0, byDepth = {};
    list.forEach(function (n) {
      dbs[n.db] = 1;
      var d = Number(n.depth) || 1; if (d > maxDepth) maxDepth = d;
      (byDepth[d] = byDepth[d] || []).push(n);
    });
    var wrap = DN.h('div', { style: 'margin-bottom:12px' });
    var verb = kind === 'impact' ? '下游受影响' : '上游依赖';
    wrap.appendChild(DN.statRow([
      { icon: 'db', label: verb + '表数', value: list.length, tone: list.length > 10 ? 'warn' : 'info' },
      { icon: 'layers', label: '涉及库数', value: Object.keys(dbs).length },
      { icon: 'chart', label: '最大层级', value: '第 ' + maxDepth + ' 层' }
    ]));
    // 按层级分组芯片
    var lvWrap = DN.h('div', { style: 'margin-top:8px' });
    Object.keys(byDepth).map(Number).sort(function (a, b) { return a - b; }).forEach(function (d) {
      var row = DN.h('div', { style: 'display:flex;align-items:flex-start;gap:8px;padding:5px 0;border-bottom:1px solid var(--divider)' });
      row.appendChild(DN.h('span', { style: 'flex-shrink:0;font-size:var(--fs-xs);color:var(--text-muted);width:54px;padding-top:2px', text: '第 ' + d + ' 层' }));
      var chips = DN.h('div', { style: 'display:flex;flex-wrap:wrap;gap:4px' });
      var group = byDepth[d];
      // 芯片可点(消除"看到了却去不了"): 有路由时点击跳资产目录打开该表(复用 tableActionMenu「目录」同款契约), 独立页降级为纯文本
      var mkChip = function (n) {
        var fqn = (n.db || '') + '.' + (n.table || '');
        var attrs = { style: 'font-size:var(--fs-xs);padding:1px 8px;border-radius:var(--radius-lg);background:var(--bg-hover);color:var(--text-regular)',
          text: fqn.length > 48 ? fqn.slice(0, 47) + '…' : fqn, title: fqn }; // 超长 FQN 截断 + title 看全
        if (window.navigateTo) {
          attrs.href = 'javascript:void(0)';
          attrs.title = fqn + ' · 点击在资产目录打开';
          attrs.style += ';cursor:pointer;text-decoration:none';
          attrs.onclick = function () { if (DN.tablePreview) DN.tablePreview(n.db, n.table); else navigateTo('catalog', { openTable: { db: n.db, table: n.table } }); }; // 原地预览, 不离开血缘图
          return DN.h('a', attrs);
        }
        return DN.h('span', attrs);
      };
      group.slice(0, CHIPS_PER_LEVEL).forEach(function (n) { chips.appendChild(mkChip(n)); });
      if (group.length > CHIPS_PER_LEVEL) {
        var more = DN.h('a', { href: 'javascript:void(0)', style: 'font-size:var(--fs-xs);padding:1px 8px;border-radius:var(--radius-lg);background:var(--bg-hover);color:var(--text-muted);cursor:pointer;text-decoration:none',
          text: '+' + (group.length - CHIPS_PER_LEVEL) + ' 张…', title: '点击展开该层其余 ' + (group.length - CHIPS_PER_LEVEL) + ' 张',
          onclick: function () { group.slice(CHIPS_PER_LEVEL).forEach(function (n) { chips.insertBefore(mkChip(n), more); }); more.remove(); } });
        chips.appendChild(more);
      }
      row.appendChild(chips);
      lvWrap.appendChild(row);
    });
    var card = DN.card({ title: '影响面分析 · ' + centerId, icon: 'lineage' });
    card.body.appendChild(wrap); card.body.appendChild(lvWrap);
    // 变更风险评估 + 变更前检查清单(创新功能): 基于下游受影响表数 + 最大传播层级综合定级, 复用已加载数据零新请求
    if (kind === 'impact') {
      var n = list.length;
      var risk = (n > 10 || maxDepth >= 4) ? ['高风险', 'err'] : (n > 3 || maxDepth >= 2) ? ['中风险', 'warn'] : ['低风险', 'ok'];
      var rc = DN.h('div', { style: 'margin-top:12px;padding:10px 12px;border-radius:var(--radius-lg);background:var(--bg-hover)' });
      rc.appendChild(DN.h('div', { style: 'display:flex;align-items:center;gap:8px;margin-bottom:6px' }, [
        DN.h('span', { style: 'font-weight:600;font-size:13px', text: '变更风险评估' }), DN.pill(risk[0], risk[1])
      ]));
      rc.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 6px', text: '依据下游受影响 ' + n + ' 张表、最大传播 ' + maxDepth + ' 层综合评定。变更前建议核对：' }));
      var checks = ['已通知所有下游表的负责人/使用方', '已确认下游 ETL/报表对字段或类型变更的兼容性', '已准备回滚方案与数据备份', maxDepth >= 3 ? '已评估跨多层链路的级联影响' : '已评估直接下游的影响范围'];
      var ul = DN.h('ul', { style: 'margin:0;padding-left:18px;font-size:12px;color:var(--text-regular);line-height:1.9' });
      checks.forEach(function (t) { ul.appendChild(DN.h('li', { text: t })); });
      rc.appendChild(ul);
      card.body.appendChild(rc);
    }
    return card.el;
  }

  function queryFlow(kind, e) {
    if (!lnPicker) { DN.toast('选择器未就绪', 'error'); return; }
    var db = lnPicker.db();
    var table = lnPicker.table();
    if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
    var box = document.getElementById('lnResult');
    if (!box) return;
    var done = lockBtn(e && e.currentTarget ? e.currentTarget : null);
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
    getOnce('/api/lineage/' + kind + qs).then(function (list) {
      list = list || [];
      var title = kind === 'impact' ? '下游影响' : '上游溯源';
      box.innerHTML = '';
      if (!list.length) { box.appendChild(DN.empty('无' + title + '（先解析脚本SQL血缘或重建）', 'lineage')); return; }
      box.appendChild(DN.h('div', { style: 'font-size:13px;font-weight:600;margin-bottom:10px',
        text: title + '（共 ' + list.length + ' 个表）' }));
      // 影响面统计 + 按层分组(大功能)
      box.appendChild(buildImpactPanel(list, kind, db + '.' + table));
      box.appendChild(DN.table({
        columns: [
          { label: '表', copyable: true, render: function (n) { return clip((n.db || '') + '.' + (n.table || ''), 60); }, exportValue: function (n) { return (n.db || '') + '.' + (n.table || ''); } },
          { label: '层级', align: 'center', render: function (n) { return DN.pill('第 ' + (n.depth != null ? n.depth : '?') + ' 层', 'info'); }, exportValue: function (n) { return '第 ' + (n.depth != null ? n.depth : '?') + ' 层'; } },
          { label: '来源', render: function (n) { return n.source ? DN.pill(n.source, 'muted') : '-'; }, exportValue: function (n) { return n.source || ''; } },
          { label: '下钻', align: 'center', render: function (n) { return tableActionMenu(n.db, n.table); } }
        ],
        rows: list,
        pageSize: 20,
        searchKeys: ['db', 'table', 'source'],
        searchPlaceholder: '搜索表/来源...',
        exportName: title,
        empty: '无' + title, emptyIcon: 'lineage'
      }));
    }).catch(function (err) { box.innerHTML = ''; box.appendChild(DN.errorBox('查询失败: ' + (err && err.message ? err.message : err), function () { queryFlow(kind); })); })
      .then(done);
  }

  function queryLineage(e) {
    if (!lnPicker) { DN.toast('选择器未就绪', 'error'); return; }
    var db = lnPicker.db();
    var table = lnPicker.table();
    if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
    var box = document.getElementById('lnResult');
    if (!box) return;
    var done = lockBtn(e && e.currentTarget ? e.currentTarget : null);
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
    Promise.all([
      getOnce('/api/lineage/table-edges' + qs),
      getOnce('/api/lineage/column-edges' + qs)
    ]).then(function (res) {
      var nb = res[0] || {}, cols = res[1] || [];
      var up = (nb.upstream || []).map(function (ed) { return { db: ed.srcDb, table: ed.srcTable }; });
      var down = (nb.downstream || []).map(function (ed) { return { db: ed.dstDb, table: ed.dstTable }; });
      box.innerHTML = '';

      // 上下游表：用药丸罗列
      var tblSummary = DN.h('div', { style: 'margin-bottom:14px' });
      tblSummary.appendChild(neighborLine('上游表', up, 'up'));
      tblSummary.appendChild(neighborLine('下游表', down, 'down'));
      box.appendChild(tblSummary);

      // 字段级血缘入边：用 DN.table
      if (cols.length) {
        box.appendChild(DN.table({
          columns: [
            { key: 'dstColumn', label: '目标列', copyable: true, render: function (ed) { return clip(ed.dstColumn, 40); }, exportValue: function (ed) { return ed.dstColumn || ''; } },
            { label: '来源', copyable: true, render: function (ed) { return clip((ed.srcDb || '') + '.' + (ed.srcTable || '') + '.' + (ed.srcColumn || ''), 60); }, exportValue: function (ed) { return (ed.srcDb || '') + '.' + (ed.srcTable || '') + '.' + (ed.srcColumn || ''); } },
            { label: '变换', render: function (ed) { return ed.transformType ? DN.pill(ed.transformType, 'info') : '-'; }, exportValue: function (ed) { return ed.transformType || ''; } },
            { label: '穿透', render: function (ed) { return DN.h('a', { href: 'javascript:void(0)', text: '上下游', style: 'color:var(--primary)', title: '查看该字段的多跳上游来源与下游影响', onclick: function () { lnColumnFlow(db, table, ed.dstColumn); } }); } }
          ],
          rows: cols,
          pageSize: 20,
          searchKeys: ['dstColumn', 'srcDb', 'srcTable', 'srcColumn', 'transformType'],
          searchPlaceholder: '搜索字段...',
          exportName: '字段级血缘_' + db + '.' + table,
          empty: '无字段级血缘', emptyIcon: 'lineage'
        }));
      } else {
        box.appendChild(DN.empty('无字段级血缘（先点上方重建，或该表非同步目标）', 'lineage'));
      }
    }).catch(function (err) { box.innerHTML = ''; box.appendChild(DN.errorBox('查询失败: ' + (err && err.message ? err.message : err), function () { queryLineage(); })); })
      .then(done);
  }

  // 字段级血缘穿透: 抽屉展示某字段的多跳上游来源 + 下游影响
  function lnColumnFlow(db, table, column) {
    if (!column) { DN.toast('该行无目标列', 'warn'); return; }
    var body = DN.h('div', { style: 'min-width:460px;max-width:720px' });
    body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 12px', text: '字段 ' + db + '.' + table + '.' + column + ' 的多跳上下游穿透' }));
    // 字段血缘图谱(可视): 复用表级图渲染器, 节点为 db.table.column
    var graphTitle = DN.h('div', { style: 'font-weight:600;font-size:13px;margin-bottom:6px', text: '🔗 字段血缘图谱' });
    var graphBox = DN.h('div', { style: 'overflow:auto;margin-bottom:14px' }); graphBox.appendChild(DN.skeleton(3));
    body.appendChild(graphTitle); body.appendChild(graphBox);
    var gqs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table) + '&column=' + encodeURIComponent(column) + '&depth=3';
    DN.get('/api/lineage/column-graph' + gqs).then(function (g) {
      g = g || {}; var nodes = g.nodes || [], edges = g.edges || [];
      graphBox.innerHTML = '';
      if (nodes.length <= 1 && !edges.length) { graphBox.appendChild(DN.empty('该字段暂无血缘边', 'lineage')); return; }
      graphBox.appendChild(renderGraph(nodes, edges, (db + '.' + table + '.' + column).toLowerCase()));
    }).catch(function () { graphBox.innerHTML = ''; graphBox.appendChild(DN.empty('图谱查询失败', 'lineage')); });
    var upTitle = DN.h('div', { style: 'font-weight:600;font-size:13px;margin-bottom:6px', text: '⬆ 上游来源' });
    var up = DN.h('div'); up.appendChild(DN.skeleton(2));
    var downTitle = DN.h('div', { style: 'font-weight:600;font-size:13px;margin:14px 0 6px', text: '⬇ 下游影响' });
    var down = DN.h('div'); down.appendChild(DN.skeleton(2));
    body.appendChild(upTitle); body.appendChild(up);
    body.appendChild(downTitle); body.appendChild(down);
    DN.drawer('字段血缘穿透 · ' + column, body);
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table) + '&column=' + encodeURIComponent(column);
    DN.get('/api/lineage/column-trace' + qs).then(function (d) { up.innerHTML = ''; up.appendChild(renderColFlow(d, 'up')); })
      .catch(function () { up.innerHTML = ''; up.appendChild(DN.empty('查询失败', 'lineage')); });
    DN.get('/api/lineage/column-impact' + qs).then(function (d) { down.innerHTML = ''; down.appendChild(renderColFlow(d, 'down')); })
      .catch(function () { down.innerHTML = ''; down.appendChild(DN.empty('查询失败', 'lineage')); });
  }

  function renderColFlow(list, dir) {
    list = list || [];
    if (!list.length) return DN.empty(dir === 'up' ? '无上游来源（可能是源头字段）' : '无下游消费', 'lineage');
    var wrap = DN.h('div');
    list.forEach(function (n) {
      var row = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:5px 6px;border-bottom:1px solid var(--border);font-size:13px' });
      row.appendChild(DN.h('span', { style: 'flex:0 0 auto;font-size:11px;color:var(--text-muted);background:var(--bg-hover);border-radius:var(--radius-full);padding:1px 7px', text: '第' + n.depth + '跳' }));
      row.appendChild(DN.h('span', { style: 'flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-family:monospace', title: (n.db || '') + '.' + (n.table || '') + '.' + (n.column || ''), text: (n.db || '') + '.' + (n.table || '') + '.' + (n.column || '') }));
      if (n.transformType && n.transformType !== 'DIRECT') row.appendChild(DN.pill(n.transformType, n.transformType === 'MASK' ? 'err' : 'warn'));
      wrap.appendChild(row);
    });
    return wrap;
  }

  // 上/下游表的一行药丸罗列（R25：药丸可点 → 以该表为中心画图，消除“看到却去不了”的死胡同）
  var NEIGHBOR_CAP = 60; // 边界: 上/下游表可能上百, 超量折叠为“+N 张”避免一行铺满整屏
  function neighborLine(label, arr, dir) {
    arr = arr || [];
    var tone = dir === 'up' ? 'err' : 'ok';
    var row = DN.h('div', { style: 'display:flex;align-items:baseline;gap:8px;flex-wrap:wrap;margin-bottom:8px' });
    row.appendChild(DN.h('b', { text: label + '（' + arr.length + '）：', style: 'font-size:13px;flex:none' }));
    if (!arr.length) { row.appendChild(DN.h('span', { text: '无', style: 'color:var(--text-muted);font-size:13px' })); return row; }
    arr.slice(0, NEIGHBOR_CAP).forEach(function (t) {
      var fqn = (t.db || '') + '.' + (t.table || '');
      var p = DN.pill(fqn.length > 48 ? fqn.slice(0, 47) + '…' : fqn, tone);
      p.style.cursor = 'pointer';
      p.title = '点击以 ' + fqn + ' 为中心画血缘图';
      p.addEventListener('click', function () { centerGraphOn(t.db, t.table); });
      row.appendChild(p);
    });
    if (arr.length > NEIGHBOR_CAP) {
      row.appendChild(DN.h('span', { text: '+' + (arr.length - NEIGHBOR_CAP) + ' 张…',
        title: '共 ' + arr.length + ' 张, 可点上方“画血缘图”查看全部', style: 'color:var(--text-muted);font-size:12px' }));
    }
    return row;
  }
})();
