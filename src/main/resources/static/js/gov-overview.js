/* 治理模块：治理总览 —— 精简为【行动中心】(R117 砍报表堆砌)
   只留具体需要: 可点 KPI 磁贴(直达各操作模块) + 治理待办行动中心(聚合需处理事项,一处直达处理)。
   砍掉纯可视化: 全屏大屏/体检报告打印/五维雷达/健康趋势折线/资产卡/质量卡/工单条形/敏感分布/90天日历热力
   (这些指标在各自子模块已有, 总览不再堆砌重复图表)。零图表库依赖。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  window.GOV_RENDERERS.overview = function (c) {
    var box = DN.h('div', { id: 'ovBox' });
    c.appendChild(box);
    reload(box);
  };

  var _reloading = null;
  function reload(box) {
    if (!box) return;
    if (_reloading === box) return;
    _reloading = box;
    box.innerHTML = ''; box.appendChild(DN.skeleton(3));
    DN.get('/api/gov/overview').then(function (d) {
      render(box, d || {});
    }).catch(function (e) {
      box.innerHTML = '';
      var msg = (e && e.message) ? e.message : '请稍后重试';
      box.appendChild(DN.errorBox('治理总览加载失败：' + msg, function () { reload(box); }));
    }).then(function () { if (_reloading === box) _reloading = null; });
  }

  function render(box, d) {
    var health = d.health || {}, assets = d.assets || {}, quality = d.quality || {}, issues = d.issues || {};
    box.innerHTML = '';

    // 顶栏: 仅刷新 + 更新时间(去掉全屏大屏/体检报告等纯展示按钮)
    box.appendChild(DN.h('div', { style: 'display:flex;justify-content:flex-end;align-items:center;margin-bottom:12px' }, [
      DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '刷新', onclick: function () { if (_reloading === box) { DN.toast('正在刷新中…', 'info'); return; } reload(box); } })
    ]));

    // 可点 KPI 磁贴(每块直达对应操作模块)
    var total = Number(health.total) || 0;
    var rate = Number(quality.recentPassRate) || 0;
    var pending = (Number(issues.open) || 0) + (Number(issues.fixing) || 0);
    function jump(k) { return function () { goModule(k); }; }
    box.appendChild(DN.statRow([
      { icon: 'shield', label: '治理健康分', value: round1(total), sub: '满分 100', tone: tone(total), title: '进入治理健康分', onClick: jump('health') },
      { icon: 'db', label: '数据资产', value: fmtInt(assets.tableCount) + ' 表', sub: '字段 ' + fmtInt(assets.columnCount) + ' · 库 ' + fmtInt(assets.dbCount), title: '进入资产目录', onClick: jump('assets') },
      { icon: 'check', label: '整体质量分', value: round1(rate) + '%', sub: '近 7 天通过率均值', tone: tone(rate), title: '进入数据质量', onClick: jump('quality') },
      { icon: 'inbox', label: '待办工单', value: fmtInt(pending), sub: '待处理+处理中', tone: pending > 0 ? 'warn' : 'ok', title: '进入治理工单', onClick: jump('health') }
    ]));

    // 治理待办行动中心: 聚合各模块需处理事项, 一处直达处理(这是总览的核心价值)
    var todoCard = DN.card({ title: '治理待办行动中心', icon: 'inbox' });
    todoCard.body.appendChild(DN.skeleton(2));
    box.appendChild(todoCard.el);
    loadGovTodo(todoCard.body, total);
  }

  // 聚合质量异常/落标违规/健康分偏低等待处理事项(各源独立 .catch 降级), 提供直达链接
  function loadGovTodo(body, healthTotal) {
    if (!body) return;
    Promise.all([
      DN.get('/api/quality/overview').catch(function () { return null; }),
      DN.get('/api/gov/standard/top-violations?limit=50').catch(function () { return null; })
    ]).then(function (r) {
      if (!document.body.contains(body)) return;
      body.innerHTML = '';
      var q = r[0] || {}, viol = Array.isArray(r[1]) ? r[1] : [];
      var items = [];
      var qBad = (Number(q.failedRuns) || 0) + (Number(q.errorRuns) || 0);
      if (qBad > 0) items.push({ icon: 'check', label: '近 24 小时质量检查失败/异常', n: qBad, unit: '次', key: 'quality', tone: 'err' });
      if (viol.length > 0) items.push({ icon: 'doc', label: '数据标准落标违规库表', n: viol.length + (viol.length >= 50 ? '+' : ''), unit: '个', key: 'standard', tone: 'warn' });
      var score = (healthTotal != null) ? Number(healthTotal) : null;
      if (score != null && score > 0 && score < 80) items.push({ icon: 'shield', label: '治理健康分偏低', n: round1(score), unit: '分', key: 'health', tone: score < 60 ? 'err' : 'warn' });
      if (!items.length) { body.appendChild(DN.empty('暂无待处理的治理事项，治理状态良好', 'check')); return; }
      body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 8px', text: '聚合各模块需关注的事项，点“前往”直达处理：' }));
      items.forEach(function (it) {
        body.appendChild(DN.h('div', { style: 'display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid var(--divider)' }, [
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

  // ===== 工具 =====
  function goModule(key, ctx) {
    if (window.govGoModule) { govGoModule(key, ctx || {}); return; }
    DN.toast('暂无法跳转该模块', 'warn');
  }
  function tone(v) { return v >= 85 ? 'ok' : (v >= 60 ? 'warn' : 'err'); }
  function toneColor(t) { return t === 'ok' ? 'var(--success)' : t === 'warn' ? 'var(--warning)' : t === 'err' ? 'var(--error)' : 'var(--primary)'; }
  function round1(v) { return Math.round((Number(v) || 0) * 10) / 10; }
  function fmtInt(v) { v = Number(v) || 0; return String(Math.round(v)).replace(/\B(?=(\d{3})+(?!\d))/g, ','); }
})();
