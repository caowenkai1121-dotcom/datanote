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
    var scanCard = DN.card({ title: '对表采样识别', icon: 'search' });
    c.appendChild(scanCard.el);
    var q = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    q.appendChild(clPicker.el);
    q.appendChild(scanBtn);
    scanCard.body.appendChild(q);
    scanCard.body.appendChild(DN.h('div', { id: 'clScan' }));

    loadLevels();
    loadRules();
  };

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

        var opts = levelNames.map(function (n) { return '<option value="' + DN.esc(n) + '">' + DN.esc(n) + '</option>'; }).join('');
        var selAll = DN.h('input', { type: 'checkbox', title: '全选' });
        var confirmBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确认打标(已勾选)' });

        var tbl = DN.table({
          search: false,
          toolbar: confirmBtn,
          columns: [
            { label: '', render: function (r) { return r._cb; } },
            { key: 'column', label: '列' },
            { key: 'sensitiveType', label: '敏感类型' },
            { label: '置信度', render: function (r) { return DN.pill((r.confidence || 0) + '%', confTone(r.confidence)); } },
            { label: '当前密级', render: function (r) { return r.currentLevel || '-'; } },
            { label: '确认密级', render: function (r) { return r._levelSel; } }
          ],
          rows: rows,
          empty: '未识别到敏感列', emptyIcon: 'check'
        });

        // 为每行就近构造勾选框与密级下拉（render 时已引用，先在 reload 前装配）
        rows.forEach(function (r) {
          r._cb = DN.h('input', { type: 'checkbox' });
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
        confirmBtn.parentNode.insertBefore(selAllWrap, confirmBtn);

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
})();
