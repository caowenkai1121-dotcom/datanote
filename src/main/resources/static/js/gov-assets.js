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

    // M10：追加生命周期 / 无用表 / 成本三小节（不删既有采集+资产列表）
    renderLifecycle(c);
    renderUnused(c);
    renderCost(c);
  };

  // ===== M10 公共小样式 =====
  function sectionTitle(text) {
    return DN.h('h3', { class: 'gov-desc', text: text, style: 'margin:18px 0 8px;font-size:14px;color:#1d2129' });
  }
  function tableHtml(headers, rowsHtml) {
    var ths = headers.map(function (h) {
      return '<th style="padding:8px;' + (h.right ? 'text-align:right' : 'text-align:left') + '">' + DN.esc(h.label) + '</th>';
    }).join('');
    return '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
      '<thead><tr style="color:#86909c;border-bottom:1px solid #e5e6eb">' + ths + '</tr></thead>' +
      '<tbody>' + rowsHtml + '</tbody></table>';
  }
  function styleCells(box) {
    if (box) box.querySelectorAll('td').forEach(function (td) { td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5'; });
  }

  // ===== 生命周期策略 =====
  function renderLifecycle(c) {
    c.appendChild(sectionTitle('生命周期策略（应用后自动下发 Doris DDL，失败降级 PENDING）'));
    var form = DN.h('div', { class: 'gov-desc' });
    var db = DN.h('input', { placeholder: '库名', style: 'margin-right:6px' });
    var tb = DN.h('input', { placeholder: '表名', style: 'margin-right:6px' });
    var type = DN.h('select', { style: 'margin-right:6px' });
    ['TTL', 'HOT_COLD', 'ARCHIVE'].forEach(function (t) { type.appendChild(DN.h('option', { value: t, text: t })); });
    var days = DN.h('input', { placeholder: 'TTL天/冷下沉天', style: 'width:120px;margin-right:6px' });
    var addBtn = DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '新增策略',
      onclick: function () {
        var d = parseInt(days.value, 10);
        var body = { dbName: db.value.trim(), tableName: tb.value.trim(), policyType: type.value, enabled: 1 };
        if (type.value === 'HOT_COLD') body.coldDays = isNaN(d) ? null : d; else body.ttlDays = isNaN(d) ? null : d;
        DN.post('/api/gov/lifecycle/policies', body).then(function () { DN.toast('已新增'); loadPolicies(); })
          .catch(function (e) { DN.toast(e.message, 'error'); });
      } });
    form.appendChild(db); form.appendChild(tb); form.appendChild(type); form.appendChild(days); form.appendChild(addBtn);
    c.appendChild(form);
    c.appendChild(DN.h('div', { id: 'policyList' }));
    loadPolicies();
  }

  function loadPolicies() {
    DN.get('/api/gov/lifecycle/policies').then(function (list) {
      var box = document.getElementById('policyList');
      if (!box) return;
      box.innerHTML = '';
      if (!list || !list.length) { box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无策略' })); return; }
      var rows = list.map(function (p) {
        return '<tr><td>' + DN.esc(p.dbName) + '.' + DN.esc(p.tableName) + '</td><td>' + DN.esc(p.policyType) +
          '</td><td>' + (p.policyType === 'HOT_COLD' ? (p.coldDays || '-') + '天冷' : (p.ttlDays || '-') + '天TTL') +
          '</td><td>' + DN.esc(p.status || '') + '</td><td>' + DN.esc(p.lastMsg || '') +
          '</td><td><a href="javascript:void(0)" data-apply="' + p.id + '">应用</a> | ' +
          '<a href="javascript:void(0)" data-del="' + p.id + '">删</a></td></tr>';
      }).join('');
      box.innerHTML = tableHtml(
        [{ label: '库.表' }, { label: '类型' }, { label: '参数' }, { label: '状态' }, { label: '信息' }, { label: '操作' }], rows);
      styleCells(box);
      box.querySelectorAll('[data-apply]').forEach(function (a) {
        a.onclick = function () {
          DN.post('/api/gov/lifecycle/policies/' + a.getAttribute('data-apply') + '/apply')
            .then(function (p) { DN.toast('应用结果: ' + (p && p.status)); loadPolicies(); })
            .catch(function (e) { DN.toast(e.message, 'error'); });
        };
      });
      box.querySelectorAll('[data-del]').forEach(function (a) {
        a.onclick = function () {
          DN.del('/api/gov/lifecycle/policies/' + a.getAttribute('data-del'))
            .then(function () { DN.toast('已删除'); loadPolicies(); })
            .catch(function (e) { DN.toast(e.message, 'error'); });
        };
      });
    }).catch(function () {});
  }

  // ===== 无用表识别 =====
  function renderUnused(c) {
    c.appendChild(sectionTitle('无用表识别（四要素：久未访问+体量+无下游血缘+无任务引用）'));
    var bar = DN.h('div', { class: 'gov-desc' });
    bar.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '采集资产快照',
      onclick: function () {
        DN.post('/api/gov/lifecycle/stats/collect').then(function (m) { DN.toast(m); loadUnused(); loadCost(); })
          .catch(function (e) { DN.toast(e.message, 'error'); });
      } }));
    bar.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '执行到期销毁',
      style: 'margin-left:8px',
      onclick: function () {
        DN.post('/api/gov/lifecycle/drop/execute').then(function (r) {
          DN.toast('到期销毁处理 ' + (r ? r.length : 0) + ' 张'); loadUnused();
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      } }));
    c.appendChild(bar);
    c.appendChild(DN.h('div', { id: 'unusedList' }));
    loadUnused();
  }

  function loadUnused() {
    DN.get('/api/gov/lifecycle/unused').then(function (list) {
      var box = document.getElementById('unusedList');
      if (!box) return;
      box.innerHTML = '';
      if (!list || !list.length) { box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无无用表候选（先采集资产快照）' })); return; }
      var rows = list.map(function (u) {
        return '<tr><td>' + DN.esc(u.db) + '.' + DN.esc(u.table) + '</td><td style="text-align:right">' + u.score +
          '</td><td style="text-align:right">' + u.lastAccessDays + '天</td><td style="text-align:right">' + DN.fmtBytes(u.sizeBytes) +
          '</td><td>' + (u.hasDownstreamLineage ? '有' : '无') + '</td><td>' + (u.hasTaskRef ? '有' : '无') +
          '</td><td><a href="javascript:void(0)" data-drop="' + DN.esc(u.db) + '|' + DN.esc(u.table) + '">标记销毁</a></td></tr>';
      }).join('');
      box.innerHTML = tableHtml(
        [{ label: '库.表' }, { label: '分数', right: true }, { label: '未访问', right: true }, { label: '体量', right: true },
          { label: '下游血缘' }, { label: '任务引用' }, { label: '操作' }], rows);
      styleCells(box);
      box.querySelectorAll('[data-drop]').forEach(function (a) {
        a.onclick = function () {
          var parts = a.getAttribute('data-drop').split('|');
          var approver = window.prompt('销毁审批人（必填，留痕）');
          if (!approver) { DN.toast('已取消（审批人必填）', 'error'); return; }
          var reason = window.prompt('销毁原因') || '';
          DN.post('/api/gov/lifecycle/drop', { db: parts[0], table: parts[1], approver: approver, reason: reason })
            .then(function (p) { DN.toast('已进入宽限期，至 ' + (p && p.dropDueAt)); loadUnused(); loadPolicies(); })
            .catch(function (e) { DN.toast(e.message, 'error'); });
        };
      });
    }).catch(function () {});
  }

  // ===== 成本排行 =====
  function renderCost(c) {
    c.appendChild(sectionTitle('成本排行（单价 × 体量，可在系统配置调单价）'));
    c.appendChild(DN.h('div', { id: 'costList' }));
    loadCost();
  }

  function loadCost() {
    DN.get('/api/gov/lifecycle/cost?limit=50').then(function (list) {
      var box = document.getElementById('costList');
      if (!box) return;
      box.innerHTML = '';
      if (!list || !list.length) { box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无成本数据（先采集资产快照）' })); return; }
      var rows = list.map(function (s) {
        return '<tr><td>' + DN.esc(s.db) + '.' + DN.esc(s.table) + '</td><td style="text-align:right">' + DN.fmtBytes(s.sizeBytes) +
          '</td><td style="text-align:right">' + (s.rowCount == null ? '-' : s.rowCount) +
          '</td><td style="text-align:right">' + (s.costEstimate == null ? '-' : s.costEstimate) + '</td></tr>';
      }).join('');
      box.innerHTML = tableHtml(
        [{ label: '库.表' }, { label: '体量', right: true }, { label: '行数', right: true }, { label: '月成本(元)', right: true }], rows);
      styleCells(box);
    }).catch(function () {});
  }

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
