/* 治理模块：数据消费层（R11）— 指标驾驶舱：概览/指标值看板/新鲜度/僵尸指标/消费热度/一键计算/导出。
   填补断点④治理成果无消费出口。全量走 DN 现代套件 + /api/consumption/*。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  window.GOV_RENDERERS.consumption = function (c) {
    var ov = DN.h('div', { id: 'consOverview' }); c.appendChild(ov);
    var calcAll = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '一键计算全部指标' });
    var boardCard = DN.card({ title: '指标驾驶舱（最新值 / 新鲜度）', icon: 'chart', actions: [calcAll] });
    boardCard.body.appendChild(DN.h('div', { id: 'consBoard' }, DN.skeleton(4)));
    c.appendChild(boardCard.el);
    var heatCard = DN.card({ title: '消费热度 Top（近30天调用）', icon: 'bars' });
    heatCard.body.appendChild(DN.h('div', { id: 'consHeat' }, DN.skeleton(2)));
    c.appendChild(heatCard.el);
    var zCard = DN.card({ title: '僵尸指标（启用但从未被消费取值）', icon: 'shield' });
    zCard.body.appendChild(DN.h('div', { id: 'consZombie' }, DN.skeleton(2)));
    c.appendChild(zCard.el);

    calcAll.onclick = function () {
      if (!window.confirm('将计算全部启用指标的当前值，可能耗时，确认？')) return;
      calcAll.disabled = true; calcAll.textContent = '计算中…';
      DN.post('/api/consumption/metric/calc-all?operator=ui').then(function (r) {
        DN.toast('计算完成：成功 ' + (r.success || 0) + ' / 失败 ' + (r.failed || 0) + ' / 共 ' + (r.total || 0), 'ok');
        calcAll.disabled = false; calcAll.textContent = '一键计算全部指标';
        loadOverview(); loadBoard(); loadHeat();
      }).catch(function (e) {
        DN.toast('计算失败：' + (e && e.message || ''), 'err');
        calcAll.disabled = false; calcAll.textContent = '一键计算全部指标';
      });
    };

    loadOverview(); loadBoard(); loadHeat(); loadZombies();
  };

  function loadOverview() {
    DN.get('/api/consumption/overview').then(function (o) {
      var box = document.getElementById('consOverview'); if (!box) return; box.innerHTML = '';
      box.appendChild(DN.statRow([
        { icon: 'list', label: '启用指标', value: o.enabledMetrics || 0 },
        { icon: 'check', label: '指标值快照', value: o.totalValues || 0, tone: 'ok' },
        { icon: 'clock', label: '陈旧指标', value: o.staleMetrics || 0, tone: (o.staleMetrics ? 'warn' : 'ok') },
        { icon: 'shield', label: '僵尸指标', value: o.zombieMetrics || 0, tone: (o.zombieMetrics ? 'err' : 'ok') },
        { icon: 'chart', label: '近7天消费', value: o.consume7d || 0 }
      ]));
    }).catch(function () {});
  }

  function loadBoard() {
    DN.get('/api/consumption/metric/freshness').then(function (rows) {
      var box = document.getElementById('consBoard'); if (!box) return; box.innerHTML = '';
      if (!rows || !rows.length) { box.appendChild(DN.empty('暂无启用指标，请先在指标管理中定义并启用', 'chart')); return; }
      box.appendChild(DN.table({
        rows: rows, pageSize: 10, searchKeys: ['metricCode', 'metricName'], searchPlaceholder: '搜索指标',
        empty: '暂无指标', emptyIcon: 'chart',
        columns: [
          { key: 'metricName', label: '指标', render: function (r) { return r.metricName || r.metricCode; } },
          { key: 'metricCode', label: '编码', render: function (r) { return r.metricCode || '-'; } },
          { key: 'lastValue', label: '最新值', align: 'right', render: function (r) { return r.lastValue == null ? '—' : String(r.lastValue); } },
          { key: 'lastValueAt', label: '取值时间', render: function (r) { return r.lastValueAt ? DN.timeAgo(r.lastValueAt) : '从未'; } },
          { key: 'stale', label: '新鲜度', render: function (r) { return r.stale ? DN.pill('陈旧', 'warn') : DN.pill('新鲜', 'ok'); } },
          { key: '_op', label: '操作', render: function (r) { return opCell(r); } }
        ]
      }));
    }).catch(function (e) {
      var box = document.getElementById('consBoard'); if (box) { box.innerHTML = ''; box.appendChild(DN.errorBox('看板加载失败：' + (e && e.message || ''), loadBoard)); }
    });
  }

  function opCell(r) {
    var wrap = DN.h('span', { style: 'display:inline-flex;gap:8px;align-items:center' });
    var calc = DN.h('a', { href: 'javascript:void(0)', text: '计算' });
    calc.onclick = function () {
      calc.textContent = '…';
      DN.post('/api/consumption/metric/' + r.metricId + '/calc?operator=ui').then(function (v) {
        DN.toast(r.metricName + ' = ' + (v && v.metricValue != null ? v.metricValue : (v && v.runStatus === 'error' ? '计算异常' : '—')), v && v.runStatus === 'success' ? 'ok' : 'warn');
        loadBoard(); loadOverview();
      }).catch(function (e) { DN.toast('计算失败：' + (e && e.message || ''), 'err'); calc.textContent = '计算'; });
    };
    wrap.appendChild(calc);
    var exp = DN.h('a', { href: '/api/consumption/metric/' + r.metricId + '/export?format=csv', text: '导出', style: 'color:var(--primary,#1890ff)' });
    wrap.appendChild(exp);
    return wrap;
  }

  function loadHeat() {
    DN.get('/api/consumption/log/heat').then(function (rows) {
      var box = document.getElementById('consHeat'); if (!box) return; box.innerHTML = '';
      if (!rows || !rows.length) { box.appendChild(DN.empty('暂无消费记录', 'bars')); return; }
      box.appendChild(DN.bars(rows.map(function (x) {
        return { label: x.target_code || x.targetCode || '-', value: Number(x.cnt || x.CNT || 0), tone: 'info', display: String(x.cnt || x.CNT || 0) };
      })));
    }).catch(function () {});
  }

  function loadZombies() {
    DN.get('/api/consumption/metric/zombies').then(function (rows) {
      var box = document.getElementById('consZombie'); if (!box) return; box.innerHTML = '';
      if (!rows || !rows.length) { box.appendChild(DN.empty('无僵尸指标，全部已被消费 👍', 'check')); return; }
      box.appendChild(DN.table({
        rows: rows, pageSize: 8, empty: '无',
        columns: [
          { key: 'metricName', label: '指标', render: function (r) { return r.metricName || r.metricCode; } },
          { key: 'metricCode', label: '编码' },
          { key: 'category', label: '分类', render: function (r) { return r.category || '-'; } },
          { key: 'owner', label: '负责人', render: function (r) { return r.owner || '-'; } }
        ]
      }));
    }).catch(function () {});
  }
})();
