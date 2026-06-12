/* 治理模块：系统联动地图（R31 capstone）
   把 R21-R30 建立的跨模块联动可视化为"业务闭环链 + 实体枢纽"导航中枢。
   每个节点可点击直达对应模块(navigateTo / govGoModule / mdmGoModule)。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  // 安全跳转：目标导航函数缺失时给出明确提示，而不是静默无效点击(死节点)
  function safeGo(fnName, run, label) {
    if (typeof window[fnName] === 'function') {
      try { run(); } catch (e) { if (DN && DN.toast) DN.toast('跳转失败：' + (e && e.message ? e.message : e), 'err'); }
    } else if (DN && DN.toast) {
      DN.toast('暂时无法跳转到「' + (label || '目标模块') + '」', 'warn');
    }
  }

  // 5 条端到端业务闭环链(架构师定义)。每步 {t:标题, go:点击跳转}
  function gv(key) { return function () { safeGo('govGoModule', function () { govGoModule(key, {}); }, key); }; }
  function nv(route, ctx) { return function () { safeGo('navigateTo', function () { navigateTo(route, ctx || {}); }, route); }; }
  function mv(key) { return function () { safeGo('navigateTo', function () { navigateTo('mdm', { mdm: key }); }, 'mdm/' + key); }; }
  // I-1: 消费驾驶舱已并入 指标管理→指标取值
  function cv() { return function () { safeGo('gotoMetricConsume', function () { gotoMetricConsume(null); }, 'metrics/consume'); }; }

  var CHAINS = [
    { name: '① 数据质量闭环', color: 'var(--chart-1)', steps: [
      { t: '资产/探查发现问题', go: nv('catalog') },
      { t: '一键建质量规则', go: gv('quality') },
      { t: '规则失败自动工单', go: gv('health') },
      { t: '修复→关单→健康分回升', go: gv('overview') }
    ]},
    { name: '② 指标可信度闭环', color: 'var(--chart-2)', steps: [
      { t: '消费驾驶舱看指标', go: cv() },
      { t: '输入质量查来源表', go: gv('quality') },
      { t: '来源表血缘定位脏源', go: gv('lineage') },
      { t: '修复后重算指标', go: nv('metrics') }
    ]},
    { name: '③ 变更影响闭环', color: 'var(--chart-3)', steps: [
      { t: '数据地图改表', go: nv('catalog') },
      { t: '反查下游消费指标', go: cv() },
      { t: '血缘影响面评估', go: gv('lineage') },
      { t: '同步/调度联动', go: nv('dbsync') }
    ]},
    { name: '④ 敏感数据治理闭环', color: 'var(--chart-4)', steps: [
      { t: '分类分级识别敏感', go: gv('classification') },
      { t: '数据安全脱敏策略', go: gv('security') },
      { t: '消费查询自动脱敏', go: cv() },
      { t: '审计中心留痕核查', go: gv('audit') }
    ]},
    { name: '⑤ 主数据消费闭环', color: 'var(--chart-5)', steps: [
      { t: '建模定义实体属性', go: mv('modeling') },
      { t: '黄金记录录入', go: mv('goldenrecord') },
      { t: '质量监控→修复', go: mv('quality') },
      { t: '去重/存活/审批', go: mv('approval') },
      { t: '发布订阅推下游', go: mv('pubsub') }
    ]}
  ];

  // 实体枢纽(改一处牵动全局): {t, go}
  var HUBS = [
    { t: '表元数据', d: '资产/质量/血缘/指标/项目', go: gv('assets') },
    { t: '质量规则', d: '工单/指标可信度/健康分', go: gv('quality') },
    { t: '治理工单', d: '质量/指标越界/健康短板', go: gv('health') },
    { t: '指标', d: '消费/影响面/首页', go: cv() },
    { t: '血缘边', d: '影响面/孤儿表/资产详情', go: gv('lineage') },
    { t: '消费日志', d: '热度/排行/审计', go: cv() }
  ];

  // 超长文本截断：节点标题过长时截断并以 title 悬浮显示全文(避免撑破布局)
  function clip(s, n) { s = String(s == null ? '' : s); return s.length > n ? s.slice(0, n) + '…' : s; }
  // 给可点击节点统一加键盘可达性(Enter/空格触发)，与鼠标点击等价(a11y)
  function bindActivate(el, handler) {
    el.addEventListener('click', handler);
    el.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handler(e); } });
  }

  window.GOV_RENDERERS.govmap = function (c) {
    if (!c || !c.appendChild) return;   // 容器缺失则直接返回，避免后续空指针
    c.appendChild(DN.h('div', { class: 'gov-desc', text: '把全系统跨模块联动可视化为 5 条端到端业务闭环链 + 6 个实体枢纽。点击任一节点直达对应模块——一张"活地图"作为导航中枢。' }));

    // 业务闭环链
    var chainCard = DN.card({ title: '端到端业务闭环链', icon: 'share' });
    if (!Array.isArray(CHAINS) || !CHAINS.length) {
      chainCard.body.appendChild(DN.empty('暂无业务闭环链配置', 'inbox'));
    } else CHAINS.forEach(function (ch) {
      if (!ch) return;
      var row = DN.h('div', { style: 'margin:10px 0;padding:10px 12px;border:1px solid var(--divider);border-left:4px solid ' + (ch.color || 'var(--primary)') + ';border-radius:var(--radius-lg);' });
      row.appendChild(DN.h('div', { style: 'font-weight:600;font-size:13px;margin-bottom:8px;color:' + (ch.color || 'var(--primary)'), text: ch.name || '未命名链' }));
      var flow = DN.h('div', { style: 'display:flex;flex-wrap:wrap;align-items:center;gap:6px;' });
      var steps = Array.isArray(ch.steps) ? ch.steps : [];
      if (!steps.length) flow.appendChild(DN.h('span', { style: 'color:var(--text-muted);font-size:12px;', text: '（暂无步骤）' }));
      steps.forEach(function (s, i) {
        if (!s) return;
        var full = String(s.t == null ? '' : s.t);
        var pill = DN.h('a', { href: 'javascript:void(0)', text: clip(full, 18), title: full, role: 'button',
          style: 'display:inline-block;padding:5px 12px;border-radius:var(--radius-xl);background:var(--bg-body);border:1px solid var(--border);font-size:12px;color:var(--text-primary);cursor:pointer;transition:all var(--dur);' });
        pill.onmouseover = function () { pill.style.background = ch.color || 'var(--primary)'; pill.style.color = 'var(--text-inverse)'; pill.style.borderColor = ch.color || 'var(--primary)'; };
        pill.onmouseout = function () { pill.style.background = 'var(--bg-body)'; pill.style.color = 'var(--text-primary)'; pill.style.borderColor = 'var(--border)'; };
        if (typeof s.go === 'function') bindActivate(pill, s.go);
        flow.appendChild(pill);
        if (i < steps.length - 1) flow.appendChild(DN.h('span', { style: 'color:var(--text-muted);font-size:13px;', text: '→' }));
      });
      row.appendChild(flow);
      chainCard.body.appendChild(row);
    });
    c.appendChild(chainCard.el);

    // 实体枢纽
    var hubCard = DN.card({ title: '联动枢纽实体（改一处牵动全局）', icon: 'grid' });
    if (!Array.isArray(HUBS) || !HUBS.length) {
      hubCard.body.appendChild(DN.empty('暂无枢纽实体配置', 'inbox'));
    } else {
      var grid = DN.h('div', { style: 'display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;' });
      HUBS.forEach(function (h) {
        if (!h) return;
        var box = DN.h('div', { role: 'button', tabindex: '0', title: (h.t || '') + ' → ' + (h.d || ''),
          style: 'padding:12px;border:1px solid var(--border);border-radius:var(--radius-lg);cursor:pointer;transition:all var(--dur);background:var(--bg-card);outline-offset:2px;' });
        box.onmouseover = function () { box.style.boxShadow = '0 4px 14px rgba(0,0,0,.1)'; box.style.transform = 'translateY(-2px)'; };
        box.onmouseout = function () { box.style.boxShadow = ''; box.style.transform = ''; };
        if (typeof h.go === 'function') bindActivate(box, h.go);
        box.appendChild(DN.h('div', { style: 'font-weight:600;font-size:14px;margin-bottom:4px;', text: clip(h.t, 20) }));
        box.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;', text: '→ ' + clip(h.d, 40) }));
        grid.appendChild(box);
      });
      hubCard.body.appendChild(grid);
    }
    c.appendChild(hubCard.el);
  };
})();
