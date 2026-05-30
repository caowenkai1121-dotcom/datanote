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
        var db = t.databaseName || '', tb = t.tableName || '';
        var key = DN.esc(db) + '|' + DN.esc(tb);
        return '<tr><td>' + DN.esc(t.dbType || '') + '</td><td>' + DN.esc(db) +
          '</td><td>' + DN.esc(tb) + '</td><td>' + DN.esc(t.tableComment || '') +
          '</td><td style="text-align:right">' + (t.rowCount == null ? '-' : t.rowCount) +
          '</td><td style="text-align:right">' + DN.fmtBytes(t.sizeBytes) +
          '</td><td><a href="javascript:void(0)" data-detail="' + key + '">详情</a></td></tr>' +
          '<tr class="asset-detail-row" data-detail-row="' + key + '" style="display:none"><td colspan="7" style="padding:0"></td></tr>';
      }).join('');
      box.innerHTML = '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
        '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        '<th style="padding:8px">来源</th><th style="padding:8px">库</th><th style="padding:8px">表</th>' +
        '<th style="padding:8px">业务描述</th><th style="padding:8px;text-align:right">行数</th>' +
        '<th style="padding:8px;text-align:right">体量</th><th style="padding:8px">操作</th></tr></thead><tbody>' +
        rows + '</tbody></table>';
      box.querySelectorAll('td').forEach(function (td) {
        if (td.getAttribute('colspan')) return;
        td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5';
      });
      box.querySelectorAll('[data-detail]').forEach(function (a) {
        a.onclick = function () { toggleDetail(a.getAttribute('data-detail')); };
      });
    }).catch(function (e) {
      var box = document.getElementById('assetList');
      if (box) box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message }));
    });
  }

  // ===== F4：资产详情抽屉（字段元数据 + Profiler + 术语表 + 血缘） =====

  function toggleDetail(key) {
    var parts = key.split('|'); var db = parts[0], table = parts[1];
    var row = document.querySelector('[data-detail-row="' + cssEsc(key) + '"]');
    if (!row) return;
    if (row.style.display !== 'none') { row.style.display = 'none'; return; }
    row.style.display = '';
    var cell = row.firstChild; // <td colspan=7>
    cell.innerHTML = '';
    var panel = DN.h('div', { style: 'padding:12px 8px;background:#f7f8fa' });
    cell.appendChild(panel);
    renderColumns(panel, db, table);
    renderProfileSection(panel, db, table);
    renderGlossarySection(panel);
    renderLineageSection(panel, db, table);
  }

  // CSS 选择器转义（库表名可能含特殊字符）
  function cssEsc(s) {
    return String(s).replace(/[^a-zA-Z0-9_一-龥|]/g, function (c) { return '\\' + c; });
  }

  function subTitle(text) {
    return DN.h('div', { text: text, style: 'font-weight:600;font-size:13px;color:#1d2129;margin:10px 0 6px' });
  }
  function tag(text, color, bg) {
    return '<span style="display:inline-block;padding:1px 6px;border-radius:4px;font-size:12px;color:' +
      color + ';background:' + bg + '">' + DN.esc(text) + '</span>';
  }

  // 字段级元数据表（含密级/敏感标签）
  function renderColumns(panel, db, table) {
    panel.appendChild(subTitle('字段级元数据'));
    var box = DN.h('div', { text: '加载中...' });
    panel.appendChild(box);
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
    DN.get('/api/gov/asset/detail' + qs).then(function (d) {
      var cols = (d && d.columns) || [];
      if (!cols.length) { box.innerHTML = '<div class="gov-placeholder">无字段元数据（请先采集）</div>'; return; }
      var rows = cols.map(function (col) {
        var sec = col.securityLevel ? tag(col.securityLevel, '#d4380d', '#fff1f0') : '-';
        var sen = col.sensitiveType ? tag(col.sensitiveType, '#d46b08', '#fff7e6') : '-';
        return '<tr><td>' + DN.esc(col.columnName || '') + '</td><td>' + DN.esc(col.dataType || '') +
          '</td><td>' + DN.esc(col.columnKey || '') + '</td><td>' + DN.esc(col.isNullable || '') +
          '</td><td>' + DN.esc(col.businessDesc || '') + '</td><td>' + sec + '</td><td>' + sen + '</td></tr>';
      }).join('');
      box.innerHTML = tableHtml(
        [{ label: '列名' }, { label: '类型' }, { label: '键' }, { label: '可空' },
          { label: '注释' }, { label: '密级' }, { label: '敏感类型' }], rows);
      styleCells(box);
    }).catch(function (e) { box.innerHTML = '<div class="gov-placeholder">加载失败: ' + DN.esc(e.message) + '</div>'; });
  }

  // Profiler 探查（下推数仓）
  function renderProfileSection(panel, db, table) {
    panel.appendChild(subTitle('Profiler 探查'));
    var resultBox = DN.h('div', { class: 'gov-desc' });
    var btn = DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '执行探查（采样数仓）',
      onclick: function () {
        resultBox.innerHTML = '探查中（数仓聚合可能较慢）...';
        var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
        DN.get('/api/gov/asset/profile' + qs).then(function (p) {
          var fields = (p && p.fields) || [];
          var rows = fields.map(function (f) {
            return '<tr><td>' + DN.esc(f.name) + '</td><td style="text-align:right">' +
              (f.nullRate == null ? (f.error ? '错误' : '-') : DN.esc(f.nullRate)) +
              '</td><td style="text-align:right">' + (f.distinctCount == null ? '-' : f.distinctCount) + '</td></tr>';
          }).join('');
          resultBox.innerHTML = '<div class="gov-desc">总行数: <b>' + (p.totalRows == null ? '-' : p.totalRows) +
            '</b>，字段数: ' + (p.columnCount == null ? '-' : p.columnCount) +
            '，已探查 ' + (p.profiledCount == null ? '-' : p.profiledCount) + ' 个</div>' +
            tableHtml([{ label: '字段' }, { label: '空值率', right: true }, { label: 'distinct', right: true }], rows);
          styleCells(resultBox);
        }).catch(function (e) { resultBox.innerHTML = '<div class="gov-placeholder">' + DN.esc(e.message) + '</div>'; });
      } });
    panel.appendChild(btn);
    panel.appendChild(resultBox);
  }

  // 业务术语表（查看 + 维护）
  function renderGlossarySection(panel) {
    panel.appendChild(subTitle('业务术语表'));
    var form = DN.h('div', { class: 'gov-desc' });
    var term = DN.h('input', { placeholder: '术语', style: 'margin-right:6px' });
    var cat = DN.h('input', { placeholder: '分类', style: 'margin-right:6px' });
    var def = DN.h('input', { placeholder: '定义', style: 'width:260px;margin-right:6px' });
    var addBtn = DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '新增术语',
      onclick: function () {
        if (!term.value.trim()) { DN.toast('术语名必填', 'error'); return; }
        DN.post('/api/gov/asset/glossary', { term: term.value.trim(), category: cat.value.trim(), definition: def.value.trim() })
          .then(function () { DN.toast('已新增'); term.value = ''; cat.value = ''; def.value = ''; loadGlossary(listBox); })
          .catch(function (e) { DN.toast(e.message, 'error'); });
      } });
    form.appendChild(term); form.appendChild(cat); form.appendChild(def); form.appendChild(addBtn);
    panel.appendChild(form);
    var listBox = DN.h('div');
    panel.appendChild(listBox);
    loadGlossary(listBox);
  }

  function loadGlossary(box) {
    DN.get('/api/gov/asset/glossary').then(function (list) {
      list = list || [];
      if (!list.length) { box.innerHTML = '<div class="gov-placeholder">暂无术语</div>'; return; }
      var rows = list.map(function (g) {
        return '<tr><td>' + DN.esc(g.term) + '</td><td>' + DN.esc(g.category || '') +
          '</td><td>' + DN.esc(g.definition || '') +
          '</td><td><a href="javascript:void(0)" data-gdel="' + g.id + '">删</a></td></tr>';
      }).join('');
      box.innerHTML = tableHtml(
        [{ label: '术语' }, { label: '分类' }, { label: '定义' }, { label: '操作' }], rows);
      styleCells(box);
      box.querySelectorAll('[data-gdel]').forEach(function (a) {
        a.onclick = function () {
          DN.del('/api/gov/asset/glossary/' + a.getAttribute('data-gdel'))
            .then(function () { DN.toast('已删除'); loadGlossary(box); })
            .catch(function (e) { DN.toast(e.message, 'error'); });
        };
      });
    }).catch(function () {});
  }

  // 查看血缘（内联调用血缘端点，不跳转）
  function renderLineageSection(panel, db, table) {
    panel.appendChild(subTitle('查看血缘'));
    var box = DN.h('div', { class: 'gov-desc' });
    var btn = DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '查询上下游表',
      onclick: function () {
        box.innerHTML = '加载中...';
        var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
        DN.get('/api/lineage/table-edges' + qs).then(function (nb) {
          nb = nb || {};
          var up = (nb.upstream || []).map(function (e) { return e.srcDb + '.' + e.srcTable; });
          var down = (nb.downstream || []).map(function (e) { return e.dstDb + '.' + e.dstTable; });
          box.innerHTML = '<div class="gov-desc"><b>上游表:</b> ' + (up.length ? up.map(DN.esc).join(', ') : '无') +
            '</div><div class="gov-desc"><b>下游表:</b> ' + (down.length ? down.map(DN.esc).join(', ') : '无') + '</div>';
        }).catch(function (e) { box.innerHTML = '<div class="gov-placeholder">查询失败: ' + DN.esc(e.message) + '</div>'; });
      } });
    panel.appendChild(btn);
    panel.appendChild(box);
  }
})();
