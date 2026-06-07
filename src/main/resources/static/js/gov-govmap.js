/* 治理模块：系统联动地图（R31 capstone）
   把 R21-R30 建立的跨模块联动可视化为"业务闭环链 + 实体枢纽"导航中枢。
   每个节点可点击直达对应模块(navigateTo / govGoModule / mdmGoModule)。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  // 5 条端到端业务闭环链(架构师定义)。每步 {t:标题, go:点击跳转}
  function gv(key) { return function () { if (window.govGoModule) govGoModule(key, {}); }; }
  function nv(route, ctx) { return function () { if (window.navigateTo) navigateTo(route, ctx || {}); }; }
  function mv(key) { return function () { if (window.navigateTo) navigateTo('mdm', { mdm: key }); }; }

  var CHAINS = [
    { name: '① 数据质量闭环', color: '#1d6fff', steps: [
      { t: '资产/探查发现问题', go: nv('catalog') },
      { t: '一键建质量规则', go: gv('quality') },
      { t: '规则失败自动工单', go: gv('health') },
      { t: '修复→关单→健康分回升', go: gv('overview') }
    ]},
    { name: '② 指标可信度闭环', color: '#52c41a', steps: [
      { t: '消费驾驶舱看指标', go: gv('consumption') },
      { t: '输入质量查来源表', go: gv('quality') },
      { t: '来源表血缘定位脏源', go: gv('lineage') },
      { t: '修复后重算指标', go: nv('metrics') }
    ]},
    { name: '③ 变更影响闭环', color: '#fa8c16', steps: [
      { t: '数据地图改表', go: nv('catalog') },
      { t: '反查下游消费指标', go: gv('consumption') },
      { t: '血缘影响面评估', go: gv('lineage') },
      { t: '同步/调度联动', go: nv('dbsync') }
    ]},
    { name: '④ 敏感数据治理闭环', color: '#722ed1', steps: [
      { t: '分类分级识别敏感', go: gv('classification') },
      { t: '数据安全脱敏策略', go: gv('security') },
      { t: '消费查询自动脱敏', go: gv('consumption') },
      { t: '审计中心留痕核查', go: gv('audit') }
    ]},
    { name: '⑤ 主数据消费闭环', color: '#eb2f96', steps: [
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
    { t: '指标', d: '消费/影响面/首页', go: gv('consumption') },
    { t: '血缘边', d: '影响面/孤儿表/资产详情', go: gv('lineage') },
    { t: '消费日志', d: '热度/排行/审计', go: gv('consumption') }
  ];

  window.GOV_RENDERERS.govmap = function (c) {
    c.appendChild(DN.h('div', { class: 'gov-desc', text: '把全系统跨模块联动可视化为 5 条端到端业务闭环链 + 6 个实体枢纽。点击任一节点直达对应模块——一张"活地图"作为导航中枢。' }));

    // 业务闭环链
    var chainCard = DN.card({ title: '端到端业务闭环链', icon: 'share' });
    CHAINS.forEach(function (ch) {
      var row = DN.h('div', { style: 'margin:10px 0;padding:10px 12px;border:1px solid var(--divider,#eee);border-left:4px solid ' + ch.color + ';border-radius:8px;' });
      row.appendChild(DN.h('div', { style: 'font-weight:600;font-size:13px;margin-bottom:8px;color:' + ch.color, text: ch.name }));
      var flow = DN.h('div', { style: 'display:flex;flex-wrap:wrap;align-items:center;gap:6px;' });
      ch.steps.forEach(function (s, i) {
        var pill = DN.h('a', { href: 'javascript:void(0)', text: s.t,
          style: 'display:inline-block;padding:5px 12px;border-radius:16px;background:var(--bg-body,#f5f6fa);border:1px solid var(--border,#e5e6eb);font-size:12px;color:var(--text-primary);cursor:pointer;transition:all .15s;' });
        pill.onmouseover = function () { pill.style.background = ch.color; pill.style.color = '#fff'; pill.style.borderColor = ch.color; };
        pill.onmouseout = function () { pill.style.background = 'var(--bg-body,#f5f6fa)'; pill.style.color = 'var(--text-primary)'; pill.style.borderColor = 'var(--border,#e5e6eb)'; };
        pill.onclick = s.go;
        flow.appendChild(pill);
        if (i < ch.steps.length - 1) flow.appendChild(DN.h('span', { style: 'color:var(--text-muted);font-size:13px;', text: '→' }));
      });
      row.appendChild(flow);
      chainCard.body.appendChild(row);
    });
    c.appendChild(chainCard.el);

    // 实体枢纽
    var hubCard = DN.card({ title: '联动枢纽实体（改一处牵动全局）', icon: 'grid' });
    var grid = DN.h('div', { style: 'display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;' });
    HUBS.forEach(function (h) {
      var box = DN.h('div', { style: 'padding:12px;border:1px solid var(--border,#e5e6eb);border-radius:10px;cursor:pointer;transition:all .15s;background:var(--bg-card,#fff);' });
      box.onmouseover = function () { box.style.boxShadow = '0 4px 14px rgba(0,0,0,.1)'; box.style.transform = 'translateY(-2px)'; };
      box.onmouseout = function () { box.style.boxShadow = ''; box.style.transform = ''; };
      box.onclick = h.go;
      box.appendChild(DN.h('div', { style: 'font-weight:600;font-size:14px;margin-bottom:4px;', text: h.t }));
      box.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);', text: '→ ' + h.d }));
      grid.appendChild(box);
    });
    hubCard.body.appendChild(grid);
    c.appendChild(hubCard.el);
  };
})();
