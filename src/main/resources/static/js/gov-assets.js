/* 治理模块：资产目录（M2 元数据采集 + 资产清单） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  window.GOV_RENDERERS.assets = function (c) {
    var bar = DN.h('div', { class: 'gov-desc' });
    bar.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '采集全部(源库+数仓)',
      onclick: function () {
        DN.post('/api/metadata-center/crawl/all').then(function (msg) {
          DN.toast(msg || '采集已启动'); setTimeout(loadAssets, 1500);
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      } }));
    c.appendChild(bar);
    c.appendChild(DN.h('div', { id: 'assetLogs', class: 'gov-desc' }));
    c.appendChild(DN.h('div', { id: 'assetList' }));
    loadAssets();
  };

  function loadAssets() {
    DN.get('/api/metadata-center/collect-logs').then(function (logs) {
      var box = document.getElementById('assetLogs');
      if (!box) return;
      if (!logs || !logs.length) { box.textContent = '尚无采集记录'; return; }
      var last = logs[0];
      box.textContent = '最近采集: ' + (last.dbType || '') + ' / ' + (last.status || '') +
        ' / 表' + (last.tableCount || 0) + ' 字段' + (last.columnCount || 0) +
        ' / ' + (last.startedAt || '');
    }).catch(function () {});

    DN.get('/api/metadata-center/tables').then(function (tables) {
      var box = document.getElementById('assetList');
      if (!box) return;
      box.innerHTML = '';
      if (!tables || !tables.length) {
        box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无资产，点击上方采集' }));
        return;
      }
      var rows = tables.slice(0, 200).map(function (t) {
        return '<tr><td>' + DN.esc(t.dbType || '') + '</td><td>' + DN.esc(t.databaseName || '') +
          '</td><td>' + DN.esc(t.tableName || '') + '</td><td>' + DN.esc(t.tableComment || '') +
          '</td><td style="text-align:right">' + (t.rowCount == null ? '-' : t.rowCount) +
          '</td><td style="text-align:right">' + DN.fmtBytes(t.sizeBytes) + '</td></tr>';
      }).join('');
      box.innerHTML = '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
        '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        '<th style="padding:8px">来源</th><th style="padding:8px">库</th><th style="padding:8px">表</th>' +
        '<th style="padding:8px">业务描述</th><th style="padding:8px;text-align:right">行数</th>' +
        '<th style="padding:8px;text-align:right">体量</th></tr></thead><tbody>' + rows + '</tbody></table>';
      box.querySelectorAll('td').forEach(function (td) { td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5'; });
    }).catch(function (e) {
      var box = document.getElementById('assetList');
      if (box) box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message }));
    });
  }
})();
