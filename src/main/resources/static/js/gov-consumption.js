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

    var newDs = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建数据集', onclick: addDataset });
    var dsCard = DN.card({ title: '数据集 / 数据产品（受治理可复用查询）', icon: 'list', actions: [newDs] });
    dsCard.body.appendChild(DN.h('div', { id: 'consDataset' }, DN.skeleton(3)));
    c.appendChild(dsCard.el);

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

    loadOverview(); loadBoard(); loadHeat(); loadZombies(); loadDatasets();
  };

  function loadDatasets() {
    DN.get('/api/consumption/dataset/list').then(function (rows) {
      var box = document.getElementById('consDataset'); if (!box) return; box.innerHTML = '';
      if (!rows || !rows.length) { box.appendChild(DN.empty('暂无数据集，点「新建数据集」把精选 SQL 注册为可复用查询', 'list')); return; }
      box.appendChild(DN.table({
        rows: rows, pageSize: 10, searchKeys: ['datasetName', 'datasetCode', 'owner'], searchPlaceholder: '搜索数据集',
        columns: [
          { key: 'datasetName', label: '名称', render: function (r) { return r.datasetName || r.datasetCode; } },
          { key: 'datasetCode', label: '编码' },
          { key: 'owner', label: '负责人', render: function (r) { return r.owner || '-'; } },
          { key: 'status', label: '状态', render: function (r) { return r.status === 1 ? DN.pill('启用', 'ok') : DN.pill('下线', 'muted'); } },
          { key: '_op', label: '操作', render: function (r) { return dsOps(r); } }
        ]
      }));
    }).catch(function (e) {
      var box = document.getElementById('consDataset'); if (box) { box.innerHTML = ''; box.appendChild(DN.errorBox('数据集加载失败：' + (e && e.message || ''), loadDatasets)); }
    });
  }

  function dsOps(r) {
    var wrap = DN.h('span', { style: 'display:inline-flex;gap:8px;align-items:center' });
    var run = DN.h('a', { href: 'javascript:void(0)', text: '运行' });
    run.onclick = function () {
      run.textContent = '运行中…';
      DN.post('/api/consumption/dataset/' + r.id + '/query?consumer=ui').then(function (res) {
        run.textContent = '运行';
        showDatasetResult(r, res || {});
      }).catch(function (e) { run.textContent = '运行'; DN.toast('运行失败：' + (e && e.message || ''), 'err'); });
    };
    wrap.appendChild(run);
    var del = DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error,#f53f3f)' });
    del.onclick = function () {
      if (!window.confirm('确认删除数据集「' + (r.datasetName || r.datasetCode) + '」？')) return;
      DN.del('/api/consumption/dataset/' + r.id).then(function () { DN.toast('已删除'); loadDatasets(); }).catch(function (e) { DN.toast(e && e.message || '删除失败', 'err'); });
    };
    wrap.appendChild(del);
    return wrap;
  }

  function showDatasetResult(r, res) {
    var cols = res.columns || [], rows = res.rows || [];
    var rowsObj = rows.map(function (rowArr) {
      var o = {}; (cols || []).forEach(function (c, i) { o[c] = Array.isArray(rowArr) ? rowArr[i] : rowArr[c]; }); return o;
    });
    var body = DN.h('div');
    body.appendChild(DN.h('div', { class: 'gov-desc', text: '行数 ' + (res.rowCount != null ? res.rowCount : rowsObj.length) + (res.truncated ? '（已截断）' : '') + ' · 受治理脱敏后结果' }));
    body.appendChild(DN.table({
      rows: rowsObj, pageSize: 10, empty: '无数据',
      columns: (cols.length ? cols : ['(空)']).map(function (cn) { return { key: cn, label: cn, render: function (x) { var v = x[cn]; return v == null ? 'NULL' : String(v); } }; })
    }));
    DN.drawer('数据集结果 · ' + (r.datasetName || r.datasetCode), body);
  }

  function addDataset() {
    var nameI = DN.h('input', { class: 'iw-form-input', placeholder: '数据集名称' });
    var codeI = DN.h('input', { class: 'iw-form-input', placeholder: '编码(唯一,如 daily_gmv)' });
    var dbI = DN.h('input', { class: 'iw-form-input', placeholder: '默认库(如 ods,可空)' });
    var sqlI = DN.h('textarea', { class: 'iw-form-input', rows: '4', placeholder: '精选 SELECT 查询' });
    var ownerI = DN.h('input', { class: 'iw-form-input', placeholder: '负责人(可空)' });
    drawerForm('新建数据集', [
      formRow('名称', nameI), formRow('编码', codeI), formRow('默认库', dbI), formRow('查询SQL', sqlI), formRow('负责人', ownerI)
    ], function (close) {
      var name = nameI.value.trim(), code = codeI.value.trim(), sql = sqlI.value.trim();
      if (!name || !code || !sql) { DN.toast('名称/编码/SQL 必填', 'err'); return; }
      DN.post('/api/consumption/dataset/save', { datasetName: name, datasetCode: code, defaultDb: dbI.value.trim(), querySql: sql, owner: ownerI.value.trim(), status: 1 })
        .then(function () { DN.toast('已保存'); close(); loadDatasets(); })
        .catch(function (e) { DN.toast('保存失败：' + (e && e.message || ''), 'err'); });
    });
  }

  // 抽屉表单:基于 DN.drawer(title, bodyNode) 构建表单 + 确定按钮
  function drawerForm(title, rows, onOk) {
    var body = DN.h('div'); rows.forEach(function (r) { body.appendChild(r); });
    var ok = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确定', style: 'margin-top:10px' });
    body.appendChild(ok);
    var d = DN.drawer(title, body);
    ok.onclick = function () { onOk(function () { if (d && d.close) d.close(); }); };
  }
  function formRow(label, input) {
    var row = DN.h('div', { class: 'ds-form-row', style: 'margin-bottom:10px' });
    row.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);margin-bottom:4px', text: label }));
    row.appendChild(input);
    return row;
  }

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
