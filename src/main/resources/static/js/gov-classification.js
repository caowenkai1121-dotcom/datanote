/* 治理模块：分类分级 + 敏感识别（M8）—— 分级模型 / 规则管理 / 采样识别 / 人工打标 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var levelNames = []; // 当前 scheme 下的密级名称(供确认下拉)
  var clPicker = null; // 对表识别库表选择器
  var rulesTable = null; // 敏感规则表格句柄

  window.GOV_RENDERERS.classification = function (c) {
    // 1. 分级模型
    var schemeSel = DN.h('select', { id: 'clScheme', class: 'iw-form-select', style: 'width:auto;min-width:140px' });
    schemeSel.appendChild(DN.h('option', { value: 'NATIONAL', text: '国家三级' }));
    schemeSel.appendChild(DN.h('option', { value: 'FINANCE', text: '金融五级' }));
    schemeSel.addEventListener('change', loadLevels);
    var lvCard = DN.card({ title: '分级模型', icon: 'layers', actions: schemeSel });
    c.appendChild(lvCard.el);
    lvCard.body.appendChild(DN.h('div', { id: 'clLevels' }, [DN.skeleton(1)]));

    // 敏感分布热力（按表敏感列数 Top30）
    var heatCard = DN.card({ title: '敏感分布热力（按表敏感列数）', icon: 'tag' });
    heatCard.el.classList.add('primary');
    heatCard.body.appendChild(DN.h('div', { id: 'clHeat' }, [DN.skeleton(2)]));
    c.appendChild(heatCard.el);

    // 待确认敏感列提醒（基于热力涉及的表，扫描出置信度中等待人工确认的列）
    var pendBtn = DN.h('a', { class: 'btn btn-ghost', href: 'javascript:void(0)', text: '扫描待确认列', onclick: loadPendingCols });
    var pendCard = DN.card({ title: '待确认敏感列提醒（置信度中等）', icon: 'alert', actions: pendBtn });
    c.appendChild(pendCard.el);
    pendCard.body.appendChild(DN.h('div', { class: 'gov-desc', text: '聚合已识别出敏感特征但置信度处于中等区间（50%~80%）的列，需人工确认后到「对表采样识别」中打标。' }));
    pendCard.body.appendChild(DN.h('div', { id: 'clPending' }, [DN.empty('点击右上角「扫描待确认列」开始聚合', 'alert')]));

    // 2. 敏感规则管理
    var addRuleBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增规则', onclick: toggleRuleForm });
    var ruleCard = DN.card({ title: '敏感识别规则', icon: 'shield', actions: addRuleBtn });
    c.appendChild(ruleCard.el);
    ruleCard.body.appendChild(DN.h('div', { id: 'clRuleForm' })); // 就近 .gov-form 容器（替代 prompt）
    var rulesBox = DN.h('div', { id: 'clRules' }, [DN.skeleton(3)]);
    ruleCard.body.appendChild(rulesBox);

    // 3. 对表识别
    clPicker = DN.dbTablePicker({});
    var scanBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '识别', onclick: scanTable });
    var trailBtn = DN.h('a', { class: 'btn btn-ghost', href: 'javascript:void(0)', text: '打标历史', onclick: function () {
      var db = clPicker.db(), table = clPicker.table();
      if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
      showAuditTrail(db, table);
    } });
    var scanCard = DN.card({ title: '对表采样识别', icon: 'search' });
    c.appendChild(scanCard.el);
    var q = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    q.appendChild(clPicker.el);
    q.appendChild(scanBtn);
    q.appendChild(trailBtn);
    scanCard.body.appendChild(q);
    scanCard.body.appendChild(DN.h('div', { id: 'clScan' }));

    loadLevels();
    loadRules();
    loadHeatmap();
  };

  var _clHeatRows = [], _clHeatView = 'heat';
  window.govClSetHeatView = function (v) { _clHeatView = v; renderClHeat(); };
  function loadHeatmap() {
    DN.get('/api/gov/classification/heatmap').then(function (rows) {
      _clHeatRows = rows || [];
      renderClHeat();
    }).catch(function () { var box = document.getElementById('clHeat'); if (box) box.innerHTML = ''; });
  }
  function renderClHeat() {
    var box = document.getElementById('clHeat'); if (!box) return;
    var rows = _clHeatRows;
    box.innerHTML = '';
    if (!rows.length) { box.appendChild(DN.empty('暂无已标敏感列（先在下方采样识别并确认打标）', 'tag')); return; }
    var totalCols = rows.reduce(function (s, r) { return s + (Number(r.count) || 0); }, 0);
    var maxRow = rows.reduce(function (m, r) { return (Number(r.count) || 0) > (Number(m.count) || 0) ? r : m; }, rows[0]);
    box.appendChild(DN.statRow([
      { icon: 'tag', label: '已标敏感列', value: totalCols, tone: 'warn' },
      { icon: 'db', label: '涉及表数', value: rows.length },
      { icon: 'alert', label: '单表最多', value: (Number(maxRow.count) || 0) + ' 列', sub: maxRow.db + '.' + maxRow.table }
    ]));
    // 视图切换: 热力 / 矩形树图
    var tog = DN.h('div', { style: 'display:flex;justify-content:flex-end;margin:6px 0' });
    var mk = function (v, t) { return DN.h('a', { href: 'javascript:void(0)', text: t, style: 'padding:3px 10px;font-size:12px;text-decoration:none;border:1px solid var(--border,#e5e6eb);' + (v === 'heat' ? 'border-radius:6px 0 0 6px;' : 'border-left:0;border-radius:0 6px 6px 0;') + (_clHeatView === v ? 'background:var(--primary,#1890ff);color:#fff;' : 'color:var(--text-regular)'), onclick: function () { govClSetHeatView(v); } }); };
    tog.appendChild(mk('heat', '热力')); tog.appendChild(mk('treemap', '矩形图'));
    box.appendChild(tog);
    if (_clHeatView === 'treemap') { box.appendChild(buildClTreemap(rows)); return; }
    box.appendChild(DN.heat(rows.map(function (r) {
      return { label: r.db + '.' + r.table, value: Number(r.count) || 0, _db: r.db, _table: r.table };
    }), {
      rgb: [255, 77, 79],
      onClick: function (it) { showAuditTrail(it._db, it._table); }
    }));
  }
  // 敏感分布矩形树图: 按库分带, 表块按敏感列数占比
  function buildClTreemap(rows) {
    var byDb = {}; rows.forEach(function (r) { (byDb[r.db || '(库)'] = byDb[r.db || '(库)'] || []).push(r); });
    var groups = Object.keys(byDb).map(function (k) { var ts = byDb[k]; return { db: k, tables: ts, sum: ts.reduce(function (s, t) { return s + (Number(t.count) || 0); }, 0) }; }).sort(function (a, b) { return b.sum - a.sum; });
    var grand = groups.reduce(function (s, g) { return s + g.sum; }, 0) || 1;
    var maxT = rows.reduce(function (m, r) { return Math.max(m, Number(r.count) || 0); }, 1);
    var colorOf = function (v) { var rr = v / maxT; return rr > 0.66 ? '#cf1322' : rr > 0.33 ? '#ff4d4f' : rr > 0.1 ? '#ff7875' : '#ffccc7'; };
    var wrap = DN.h('div', { style: 'display:flex;flex-direction:column;gap:8px;margin-top:6px' });
    groups.forEach(function (g) {
      var bandH = Math.max(26, Math.round(g.sum / grand * 220));
      wrap.appendChild(DN.h('div', { style: 'font-size:12px;margin-bottom:2px', html: '<b>' + DN.esc(g.db) + '</b> <span style="color:var(--text-muted)">' + g.tables.length + ' 表 · ' + g.sum + ' 敏感列</span>' }));
      var band = DN.h('div', { style: 'display:flex;width:100%;height:' + bandH + 'px;gap:2px;border-radius:6px;overflow:hidden' });
      g.tables.slice().sort(function (a, b) { return (b.count || 0) - (a.count || 0); }).forEach(function (t) {
        var w = (Number(t.count) || 0) / (g.sum || 1) * 100;
        var cell = DN.h('div', { title: t.table + ' · ' + (t.count || 0) + ' 敏感列', style: 'flex:0 0 ' + w.toFixed(2) + '%;background:' + colorOf(Number(t.count) || 0) + ';display:flex;align-items:center;justify-content:center;color:#fff;font-size:10px;cursor:pointer;overflow:hidden;white-space:nowrap' });
        cell.textContent = w > 10 ? t.table : '';
        cell.addEventListener('click', function () { showAuditTrail(t.db, t.table); });
        band.appendChild(cell);
      });
      wrap.appendChild(band);
    });
    return wrap;
  }

  function showAuditTrail(db, table) {
    DN.get('/api/gov/classification/audit-trail?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table)).then(function (rows) {
      var body = DN.h('div', {});
      body.appendChild(DN.h('div', { class: 'gov-desc', text: db + '.' + table + ' · 共 ' + (rows ? rows.length : 0) + ' 条打标记录' }));
      if (!rows || !rows.length) { body.appendChild(DN.empty('该表暂无打标历史', 'doc')); }
      else body.appendChild(DN.table({
        search: false, pageSize: 15,
        columns: [
          { key: 'columnName', label: '列' },
          { label: '原密级', render: function (r) { return r.oldLevel || '-'; } },
          { label: '新密级', render: function (r) { return DN.pill(r.newLevel || '-', 'info'); } },
          { key: 'sensitiveType', label: '敏感类型', render: function (r) { return r.sensitiveType || '-'; } },
          { key: 'operator', label: '操作人', render: function (r) { return r.operator || '-'; } },
          { label: '时间', render: function (r) { return DN.timeAgo(r.createdAt); } }
        ],
        rows: rows
      }));
      DN.drawer('打标审计溯源 · ' + db + '.' + table, body);
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function loadLevels() {
    var scheme = document.getElementById('clScheme').value;
    DN.get('/api/gov/classification/levels?scheme=' + encodeURIComponent(scheme)).then(function (rows) {
      levelNames = (rows || []).map(function (r) { return r.levelName; });
      var box = document.getElementById('clLevels');
      box.innerHTML = '';
      if (!rows || !rows.length) { box.appendChild(DN.empty('无分级', 'layers')); return; }
      var wrap = DN.h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' });
      rows.forEach(function (r) { wrap.appendChild(DN.pill(r.levelName, 'info')); });
      box.appendChild(wrap);
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function loadRules() {
    DN.get('/api/gov/classification/rules').then(function (rows) {
      var box = document.getElementById('clRules');
      box.innerHTML = '';
      var tbl = DN.table({
        columns: [
          { key: 'ruleName', label: '规则名' },
          { label: '匹配', render: function (r) { return DN.pill(r.matchType, 'muted'); } },
          { key: 'pattern', label: '模式' },
          { key: 'sensitiveType', label: '类型' },
          { label: '建议密级', render: function (r) { return r.suggestLevel || '-'; } },
          { label: '状态', render: function (r) { return DN.pill(r.enabled === 1 ? '启用' : '停用', r.enabled === 1 ? 'ok' : 'muted'); } },
          { label: '操作', render: renderRuleOps }
        ],
        rows: rows || [],
        searchKeys: ['ruleName', 'matchType', 'pattern', 'sensitiveType', 'suggestLevel'],
        searchPlaceholder: '搜索规则名/类型/模式...',
        empty: '暂无规则', emptyIcon: 'shield'
      });
      rulesTable = tbl;
      box.appendChild(tbl);
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function renderRuleOps(r) {
    var wrap = DN.h('span', {});
    wrap.appendChild(DN.h('a', {
      href: 'javascript:void(0)', text: r.enabled === 1 ? '停用' : '启用',
      style: 'color:var(--primary,#1890ff);margin-right:12px',
      onclick: function () {
        DN.post('/api/gov/classification/rules/' + r.id + '/toggle')
          .then(loadRules).catch(function (e) { DN.toast(e.message, 'error'); });
      }
    }));
    wrap.appendChild(DN.h('a', {
      href: 'javascript:void(0)', text: '删除', style: 'color:var(--error,#ff4d4f)',
      onclick: function () {
        DN.del('/api/gov/classification/rules/' + r.id)
          .then(function () { DN.toast('已删除'); loadRules(); }).catch(function (e) { DN.toast(e.message, 'error'); });
      }
    }));
    return wrap;
  }

  // 就近 .gov-form 新增规则表单（替代 window.prompt 链）
  function toggleRuleForm() {
    var box = document.getElementById('clRuleForm');
    if (box.firstChild) { box.innerHTML = ''; return; }
    box.innerHTML = '';

    function row(labelText, control) {
      var r = DN.h('div', { class: 'ds-form-row' });
      r.appendChild(DN.h('label', { text: labelText }));
      r.appendChild(control);
      return r;
    }
    var name = DN.h('input', { class: 'iw-form-input', placeholder: '规则名' });
    var matchSel = DN.h('select', { class: 'iw-form-select' });
    [['COLUMN_NAME', '按列名关键词'], ['REGEX', '按正则'], ['VALIDATOR', '按校验器']].forEach(function (m) {
      matchSel.appendChild(DN.h('option', { value: m[0], text: m[1] + '(' + m[0] + ')' }));
    });
    var pattern = DN.h('input', { class: 'iw-form-input', placeholder: '关键词逗号分隔 / 正则 / 校验器名(PHONE,EMAIL,ID_CARD,BANKCARD,USCC)' });
    var sensitiveType = DN.h('input', { class: 'iw-form-input', placeholder: '敏感类型(如 PHONE)' });
    var suggestLevel = DN.h('input', { class: 'iw-form-input', placeholder: '建议密级(如 重要，可空)' });

    var panel = DN.h('div', { class: 'gov-form', style: 'max-width:560px' });
    panel.appendChild(row('规则名', name));
    panel.appendChild(row('匹配方式', matchSel));
    panel.appendChild(row('模式', pattern));
    panel.appendChild(row('敏感类型', sensitiveType));
    panel.appendChild(row('建议密级', suggestLevel));
    var actions = DN.h('div', { style: 'display:flex;gap:8px;margin-top:4px' });
    actions.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '保存',
      onclick: function () {
        if (!name.value.trim()) { DN.toast('规则名必填', 'error'); return; }
        if (!pattern.value.trim()) { DN.toast('模式必填', 'error'); return; }
        if (!sensitiveType.value.trim()) { DN.toast('敏感类型必填', 'error'); return; }
        DN.post('/api/gov/classification/rules', {
          ruleName: name.value.trim(), matchType: matchSel.value,
          pattern: pattern.value.trim(), sensitiveType: sensitiveType.value.trim(),
          suggestLevel: suggestLevel.value.trim(), enabled: 1
        }).then(function () { DN.toast('已保存'); box.innerHTML = ''; loadRules(); })
          .catch(function (e) { DN.toast(e.message, 'error'); });
      } }));
    actions.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '取消',
      onclick: function () { box.innerHTML = ''; } }));
    panel.appendChild(actions);
    box.appendChild(panel);
    DN.enterSubmit(panel);
  }

  // 置信度着色：>=80 绿，>=50 黄，否则红
  function confTone(v) {
    v = Number(v) || 0;
    return v >= 80 ? 'ok' : v >= 50 ? 'warn' : 'err';
  }

  function scanTable() {
    var db = clPicker.db();
    var table = clPicker.table();
    if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
    var box = document.getElementById('clScan');
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    DN.get('/api/gov/classification/scan?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table))
      .then(function (rows) {
        box.innerHTML = '';
        if (!rows || !rows.length) { box.appendChild(DN.empty('未识别到敏感列', 'check')); return; }
        rows.sort(function (a, b) { return (b.confidence || 0) - (a.confidence || 0); }); // 高置信度优先

        var opts = levelNames.map(function (n) { return '<option value="' + DN.esc(n) + '">' + DN.esc(n) + '</option>'; }).join('');
        var selAll = DN.h('input', { type: 'checkbox', title: '全选' });
        var confirmBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确认打标(已勾选)' });
        var minConfSel = DN.h('select', { class: 'iw-form-select', style: 'width:auto' });
        [['0', '全部置信度'], ['50', '≥50%'], ['70', '≥70%'], ['80', '≥80%']].forEach(function (o) { minConfSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
        minConfSel.onchange = function () { var mc = Number(minConfSel.value) || 0; rows.forEach(function (r) { if (r._cb) r._cb.checked = false; }); tbl.reload(rows.filter(function (r) { return (Number(r.confidence) || 0) >= mc; })); };
        var checkBtn = DN.h('a', { class: 'btn btn-ghost', href: 'javascript:void(0)', text: '勾选≥阈值', onclick: function () {
          var mc = Number(minConfSel.value) || 0, n = 0;
          rows.forEach(function (r) { if ((Number(r.confidence) || 0) >= mc) { r._cb.checked = true; n++; } });
          DN.toast('已勾选 ' + n + ' 列', 'info');
        } });

        var tbl = DN.table({
          search: false,
          toolbar: [minConfSel, checkBtn, confirmBtn],
          exportName: '敏感识别结果',
          columns: [
            { label: '', render: function (r) { return r._cb; } },
            { key: 'column', label: '列', exportValue: function (r) { return r.column; } },
            { key: 'sensitiveType', label: '敏感类型', exportValue: function (r) { return r.sensitiveType; } },
            { label: '置信度', exportValue: function (r) { return (r.confidence || 0) + '%'; }, render: function (r) { return DN.pill((r.confidence || 0) + '%', confTone(r.confidence)); } },
            { label: '当前密级', exportValue: function (r) { return r.currentLevel || ''; }, render: function (r) { return r.currentLevel || '-'; } },
            { label: '建议密级', exportValue: function (r) { return r.suggestLevel || ''; }, render: function (r) { return r._levelSel; } }
          ],
          rows: rows,
          empty: '未识别到敏感列', emptyIcon: 'check'
        });

        // 为每行就近构造勾选框与密级下拉（render 时已引用，先在 reload 前装配）
        rows.forEach(function (r) {
          r._cb = DN.h('input', { type: 'checkbox', 'aria-label': '选择 ' + (r.db || '') + '.' + (r.table || '') + '.' + (r.column || '') });
          var s = DN.h('select', { class: 'iw-form-select', style: 'width:auto;min-width:110px' });
          s.innerHTML = opts;
          if (r.suggestLevel) s.value = r.suggestLevel;
          r._levelSel = s;
        });
        tbl.reload(rows);

        selAll.onclick = function () {
          var checked = selAll.checked;
          rows.forEach(function (r) { r._cb.checked = checked; });
        };
        // 把全选放进表头第一列标签位（toolbar 旁补一个全选开关，避免依赖表头渲染）
        var selAllWrap = DN.h('label', { style: 'display:inline-flex;align-items:center;gap:4px;font-size:13px;color:var(--text-muted,#86909c)' },
          [selAll, DN.h('span', { text: '全选' })]);
        if (confirmBtn.parentNode) confirmBtn.parentNode.insertBefore(selAllWrap, confirmBtn);

        confirmBtn.onclick = function () { confirmLabels(db, table, rows); };
        box.appendChild(tbl);
      }).catch(function (e) {
        box.innerHTML = '';
        box.appendChild(DN.empty('识别失败: ' + e.message, 'alert'));
      });
  }

  function confirmLabels(db, table, rows) {
    var tasks = [];
    rows.forEach(function (r) {
      if (!r._cb.checked) return;
      tasks.push(DN.post('/api/gov/classification/confirm', {
        db: db, table: table, column: r.column, newLevel: r._levelSel.value,
        sensitiveType: r.sensitiveType, reason: '采样识别确认'
      }));
    });
    if (!tasks.length) { DN.toast('请先勾选要打标的列', 'error'); return; }
    Promise.all(tasks).then(function () { DN.toast('已打标 ' + tasks.length + ' 列'); scanTable(); })
      .catch(function (e) { DN.toast(e.message, 'error'); });
  }

  // 待确认敏感列：对热力涉及的表逐表采样识别，聚合置信度 50%~80% 的列（复用 scan 端点）
  function loadPendingCols() {
    var box = document.getElementById('clPending'); if (!box) return;
    var tables = (_clHeatRows || []).filter(function (r) { return r && r.db && r.table; });
    if (!tables.length) {
      box.innerHTML = '';
      var em0 = DN.empty('暂无已标敏感表可供扫描', 'tag');
      em0.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '前往「对表采样识别」打标',
        style: 'color:var(--primary,#1890ff);font-size:12px;text-decoration:none',
        onclick: function () { if (clPicker && clPicker.el && clPicker.el.closest) { var card = clPicker.el.closest('.gov-card'); if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'start' }); } } }));
      box.appendChild(em0);
      return;
    }
    var scanList = tables.slice(0, 30); // 上限守卫：仅扫描敏感列数最多的前 30 张表
    box.innerHTML = '';
    box.appendChild(DN.h('div', { class: 'gov-desc', text: '正在扫描 ' + scanList.length + ' 张表…' }));
    box.appendChild(DN.skeleton(3));
    var jobs = scanList.map(function (t) {
      return DN.get('/api/gov/classification/scan?db=' + encodeURIComponent(t.db) + '&table=' + encodeURIComponent(t.table))
        .then(function (cols) { return { db: t.db, table: t.table, cols: cols || [] }; })
        .catch(function () { return { db: t.db, table: t.table, cols: [] }; });
    });
    Promise.all(jobs).then(function (results) {
      var pending = [];
      results.forEach(function (res) {
        res.cols.forEach(function (col) {
          var conf = Number(col.confidence) || 0;
          if (conf >= 50 && conf < 80) {
            pending.push({
              db: res.db, table: res.table,
              column: col.column || '',
              sensitiveType: col.sensitiveType || '-',
              confidence: conf,
              currentLevel: col.currentLevel || '',
              suggestLevel: col.suggestLevel || ''
            });
          }
        });
      });
      pending.sort(function (a, b) { return b.confidence - a.confidence; }); // 高置信优先（更接近可确认）
      renderPendingCols(box, pending, scanList.length, tables.length);
    });
  }

  // 定位到「对表采样识别」：回填库/表选择器并重新识别（picker 内部用原生 select，标准 DOM 回填）
  function focusScanOnTable(db, table) {
    if (!clPicker || !db || !table) { DN.toast('无法定位该表', 'error'); return; }
    var sels = clPicker.el.querySelectorAll('select');
    var dbSel = sels[0], tbSel = sels[1];
    if (!dbSel || !tbSel) { showAuditTrail(db, table); return; }
    // 回填库并触发其 onchange 异步加载表清单，轮询等待目标表选项出现后回填表并识别
    if (dbSel.value !== db) { dbSel.value = db; if (typeof dbSel.onchange === 'function') dbSel.onchange(); }
    var tries = 0;
    (function waitTable() {
      var hit = Array.prototype.some.call(tbSel.options, function (o) { return o.value === table; });
      if (hit) {
        tbSel.value = table; if (typeof tbSel.onchange === 'function') tbSel.onchange();
        var card = clPicker.el.closest ? clPicker.el.closest('.gov-card') : null;
        if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
        scanTable();
      } else if (tries++ < 20) { setTimeout(waitTable, 100); }
      else { DN.toast('已定位到库「' + db + '」，请手动选择表「' + table + '」后识别', 'info'); }
    })();
  }

  function renderPendingCols(box, rows, scanned, totalTables) {
    box.innerHTML = '';
    if (!rows.length) {
      var em = DN.empty('已扫描 ' + scanned + ' 张表，未发现置信度中等（50%~80%）待确认的列', 'check');
      em.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-top:6px;font-size:12px',
        text: '说明：识别结果均为高置信（≥80%，可直接打标）或低置信（<50%，可忽略）。如需复核，可在下方「对表采样识别」中逐表查看。' }));
      box.appendChild(em);
      return;
    }
    var tableSet = {}, typeSet = {}, confSum = 0;
    rows.forEach(function (r) { tableSet[r.db + '.' + r.table] = 1; if (r.sensitiveType) typeSet[r.sensitiveType] = 1; confSum += (Number(r.confidence) || 0); });
    var avgConf = rows.length ? Math.round(confSum / rows.length) : 0; // 除零守卫
    box.appendChild(DN.statRow([
      { icon: 'alert', label: '待确认列', value: rows.length, tone: 'warn' },
      { icon: 'db', label: '涉及表数', value: Object.keys(tableSet).length, sub: '已扫描 ' + scanned + (totalTables > scanned ? '/' + totalTables : '') + ' 表' },
      { icon: 'tag', label: '敏感类型数', value: Object.keys(typeSet).length },
      { icon: 'check', label: '平均置信度', value: avgConf + '%', tone: confTone(avgConf) }
    ]));
    box.appendChild(DN.table({
      exportName: '待确认敏感列',
      columns: [
        { key: 'db', label: '库', sortable: true, copyable: true, exportValue: function (r) { return r.db; } },
        { key: 'table', label: '表', sortable: true, exportValue: function (r) { return r.table; },
          render: function (r) {
            return DN.h('a', { href: 'javascript:void(0)', text: r.table || '-',
              title: '定位到「对表采样识别」复核 ' + (r.db || '') + '.' + (r.table || '') + ' 后打标',
              style: 'color:var(--primary,#1890ff);text-decoration:none',
              onclick: function () { focusScanOnTable(r.db, r.table); } });
          } },
        { key: 'column', label: '列', sortable: true, copyable: true, exportValue: function (r) { return r.column; } },
        { key: 'sensitiveType', label: '敏感类型', sortable: true, exportValue: function (r) { return r.sensitiveType; } },
        { key: 'confidence', label: '置信度', align: 'center', sortable: true, exportValue: function (r) { return r.confidence + '%'; }, render: function (r) { return DN.pill(r.confidence + '%', confTone(r.confidence)); } },
        { label: '当前密级', exportValue: function (r) { return r.currentLevel || ''; }, render: function (r) { return r.currentLevel || '-'; } },
        { label: '建议密级', exportValue: function (r) { return r.suggestLevel || ''; }, render: function (r) { return r.suggestLevel ? DN.pill(r.suggestLevel, 'info') : '-'; } }
      ],
      rows: rows,
      searchKeys: ['db', 'table', 'column', 'sensitiveType'],
      searchPlaceholder: '搜索库/表/列/类型...',
      empty: '无待确认列', emptyIcon: 'check'
    }));
  }
})();
