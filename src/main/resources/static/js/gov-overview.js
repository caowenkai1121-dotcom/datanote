/* 治理模块：治理总览大屏（F2）
   一屏看板：KPI 磁贴 + 健康总分大环形 + 五维条形 + 资产/质量磁贴 + 敏感分布条形。
   全量走 DN 现代套件（gov-modern.css），零图表库依赖。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var DIMS = ['规范', '质量', '安全', '生命周期', '血缘'];

  window.GOV_RENDERERS.overview = function (c) {
    var box = DN.h('div', { id: 'ovBox' });
    box.appendChild(DN.skeleton(5));
    c.appendChild(box);
    DN.get('/api/gov/overview').then(function (d) {
      render(box, d || {});
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.empty('加载失败: ' + DN.esc(e.message), 'alert'));
    });
  };

  function render(box, d) {
    var health = d.health || {};
    var assets = d.assets || {};
    var quality = d.quality || {};
    var issues = d.issues || {};
    var sensitive = d.sensitive || {};

    box.innerHTML = '';

    // ---- 顶部 KPI 磁贴 ----
    var total = Number(health.total) || 0;
    var rate = Number(quality.recentPassRate) || 0;
    var pending = (Number(issues.open) || 0) + (Number(issues.fixing) || 0);
    box.appendChild(DN.statRow([
      { icon: 'shield', label: '治理健康分', value: round1(total), sub: '满分 100', tone: tone(total) },
      { icon: 'db', label: '表数', value: fmtInt(assets.tableCount) },
      { icon: 'list', label: '字段数', value: fmtInt(assets.columnCount) },
      { icon: 'layers', label: '库数', value: fmtInt(assets.dbCount) },
      { icon: 'check', label: '质量分', value: round1(rate) + '%', sub: '近期通过率', tone: tone(rate) },
      { icon: 'inbox', label: '待办工单', value: fmtInt(pending), sub: '待处理+处理中', tone: pending > 0 ? 'warn' : 'ok' }
    ]));

    // ---- 健康总分 + 五维 ----
    var hc = DN.card({ title: '治理健康总分', icon: 'shield' });
    var hin = DN.h('div', { style: 'display:flex;gap:32px;align-items:center;flex-wrap:wrap' });
    hin.appendChild(DN.gauge(total, { label: '满分 100', decimals: 1 }));
    var dims = health.dims || {};
    var weights = health.weights || {};
    var barWrap = DN.h('div', { style: 'flex:1;min-width:280px' });
    barWrap.appendChild(DN.bars(DIMS.map(function (k) {
      var v = Number(dims[k]) || 0;
      var w = weights[k];
      return { label: k, value: v, max: 100, tone: tone(v), display: round1(v) + (w != null ? '  (权重' + w + ')' : '') };
    })));
    hin.appendChild(barWrap);
    hc.body.appendChild(hin);
    box.appendChild(hc.el);

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
  }

  function sensitiveCard(title, icon, map, barTone) {
    var c = DN.card({ title: title, icon: icon });
    map = map || {};
    var keys = Object.keys(map);
    if (!keys.length) {
      c.body.appendChild(DN.empty('暂无敏感分布数据', 'tag'));
      return c.el;
    }
    c.body.appendChild(DN.bars(keys.map(function (k) {
      var v = Number(map[k]) || 0;
      return { label: k, value: v, tone: barTone, display: fmtInt(v) };
    })));
    return c.el;
  }

  // ========== 工具 ==========
  // 分值映射药丸/磁贴色调：>=85 绿 / >=60 黄 / 其余红
  function tone(v) { return v >= 85 ? 'ok' : (v >= 60 ? 'warn' : 'err'); }
  function round1(v) { return Math.round((Number(v) || 0) * 10) / 10; }
  function fmtInt(v) {
    v = Number(v) || 0;
    return String(Math.round(v)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }
})();
