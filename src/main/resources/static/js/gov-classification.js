/* 治理模块：分类分级 + 敏感识别（M8）—— 分级模型 / 规则管理 / 采样识别 / 人工打标 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var levelNames = []; // 当前 scheme 下的密级名称(供确认下拉)
  var clPicker = null; // 对表识别库表选择器

  window.GOV_RENDERERS.classification = function (c) {
    // 1. 分级模型
    var lv = DN.h('div', { class: 'gov-desc', style: 'display:flex;align-items:center;gap:8px' });
    var sel = DN.h('select', { id: 'clScheme', class: 'iw-form-select', style: 'width:auto;min-width:140px' });
    sel.appendChild(DN.h('option', { value: 'NATIONAL', text: '国家三级' }));
    sel.appendChild(DN.h('option', { value: 'FINANCE', text: '金融五级' }));
    sel.addEventListener('change', loadLevels);
    lv.appendChild(DN.h('b', { text: '分级模型: ' }));
    lv.appendChild(sel);
    c.appendChild(lv);
    c.appendChild(DN.h('div', { id: 'clLevels', class: 'gov-desc' }));

    // 2. 敏感规则管理
    c.appendChild(DN.h('h3', { text: '敏感识别规则', style: 'margin:16px 0 8px' }));
    var rbar = DN.h('div', { class: 'gov-desc' });
    rbar.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增规则',
      onclick: toggleRuleForm }));
    c.appendChild(rbar);
    c.appendChild(DN.h('div', { id: 'clRuleForm' })); // 就近 DOM 表单容器（替代 prompt）
    c.appendChild(DN.h('div', { id: 'clRules' }));

    // 3. 对表识别
    c.appendChild(DN.h('h3', { text: '对表采样识别', style: 'margin:16px 0 8px' }));
    var q = DN.h('div', { class: 'gov-desc', style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    clPicker = DN.dbTablePicker({});
    q.appendChild(clPicker.el);
    q.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '识别',
      onclick: scanTable }));
    c.appendChild(q);
    c.appendChild(DN.h('div', { id: 'clScan' }));

    loadLevels();
    loadRules();
  };

  function loadLevels() {
    var scheme = document.getElementById('clScheme').value;
    DN.get('/api/gov/classification/levels?scheme=' + encodeURIComponent(scheme)).then(function (rows) {
      levelNames = (rows || []).map(function (r) { return r.levelName; });
      var box = document.getElementById('clLevels');
      box.innerHTML = (rows || []).map(function (r) {
        return '<span style="display:inline-block;padding:3px 10px;margin:2px;border:1px solid #d4d7de;border-radius:12px;background:#fff">' +
          DN.esc(r.levelName) + '</span>';
      }).join('') || '<span class="gov-placeholder">无分级</span>';
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function loadRules() {
    DN.get('/api/gov/classification/rules').then(function (rows) {
      var box = document.getElementById('clRules');
      if (!rows || !rows.length) {
        box.innerHTML = '<div class="gov-placeholder">暂无规则</div>'; return;
      }
      var body = rows.map(function (r) {
        return '<tr><td>' + DN.esc(r.ruleName) + '</td><td>' + DN.esc(r.matchType) +
          '</td><td>' + DN.esc(r.pattern) + '</td><td>' + DN.esc(r.sensitiveType) +
          '</td><td>' + DN.esc(r.suggestLevel || '') + '</td><td>' + (r.enabled === 1 ? '启用' : '停用') +
          '</td><td><a href="javascript:void(0)" data-toggle="' + r.id + '">' + (r.enabled === 1 ? '停用' : '启用') +
          '</a> <a href="javascript:void(0)" data-del="' + r.id + '" style="color:#f53f3f">删除</a></td></tr>';
      }).join('');
      box.innerHTML = '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
        '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        '<th style="padding:8px">规则名</th><th style="padding:8px">匹配</th><th style="padding:8px">模式</th>' +
        '<th style="padding:8px">类型</th><th style="padding:8px">建议密级</th><th style="padding:8px">状态</th>' +
        '<th style="padding:8px">操作</th></tr></thead><tbody>' + body + '</tbody></table>';
      box.querySelectorAll('td').forEach(function (td) { td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5'; });
      box.querySelectorAll('[data-toggle]').forEach(function (a) {
        a.onclick = function () {
          DN.post('/api/gov/classification/rules/' + a.getAttribute('data-toggle') + '/toggle')
            .then(loadRules).catch(function (e) { DN.toast(e.message, 'error'); });
        };
      });
      box.querySelectorAll('[data-del]').forEach(function (a) {
        a.onclick = function () {
          DN.del('/api/gov/classification/rules/' + a.getAttribute('data-del'))
            .then(function () { DN.toast('已删除'); loadRules(); }).catch(function (e) { DN.toast(e.message, 'error'); });
        };
      });
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  // 就近 DOM 新增规则表单（替代 window.prompt 链）
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

    var panel = DN.h('div', { style: 'background:#fff;border:1px solid #e5e6eb;border-radius:8px;padding:16px;max-width:560px;margin-bottom:12px' });
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

  function scanTable() {
    var db = clPicker.db();
    var table = clPicker.table();
    if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
    var box = document.getElementById('clScan');
    box.innerHTML = '识别中...';
    DN.get('/api/gov/classification/scan?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table))
      .then(function (rows) {
        if (!rows || !rows.length) {
          box.innerHTML = '<div class="gov-placeholder">未识别到敏感列</div>'; return;
        }
        var opts = levelNames.map(function (n) { return '<option value="' + DN.esc(n) + '">' + DN.esc(n) + '</option>'; }).join('');
        var body = rows.map(function (r, i) {
          return '<tr><td><input type="checkbox" data-i="' + i + '"></td>' +
            '<td>' + DN.esc(r.column) + '</td><td>' + DN.esc(r.sensitiveType) + '</td>' +
            '<td>' + (r.confidence || 0) + '%</td><td>' + DN.esc(r.currentLevel || '-') + '</td>' +
            '<td><select class="iw-form-select" style="width:auto;min-width:110px" data-level="' + i + '">' + opts + '</select></td></tr>';
        }).join('');
        box.innerHTML = '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
          '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
          '<th style="padding:8px"><input type="checkbox" id="clSelAll" title="全选"></th>' +
          '<th style="padding:8px">列</th><th style="padding:8px">敏感类型</th>' +
          '<th style="padding:8px">置信度</th><th style="padding:8px">当前密级</th><th style="padding:8px">确认密级</th>' +
          '</tr></thead><tbody>' + body + '</tbody></table>' +
          '<div class="gov-desc" style="display:flex;gap:8px;align-items:center">' +
          '<a class="btn btn-primary" href="javascript:void(0)" id="clConfirmBtn">确认打标(已勾选)</a></div>';
        box.querySelectorAll('td').forEach(function (td) { td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5'; });
        // 预选建议密级
        rows.forEach(function (r, i) {
          var s = box.querySelector('select[data-level="' + i + '"]');
          if (s && r.suggestLevel) s.value = r.suggestLevel;
        });
        // 全选/反选
        box.querySelector('#clSelAll').onclick = function () {
          var checked = this.checked;
          box.querySelectorAll('input[type=checkbox][data-i]').forEach(function (cb) { cb.checked = checked; });
        };
        document.getElementById('clConfirmBtn').onclick = function () { confirmLabels(db, table, rows, box); };
      }).catch(function (e) { box.innerHTML = '<div class="gov-placeholder">识别失败: ' + DN.esc(e.message) + '</div>'; });
  }

  function confirmLabels(db, table, rows, box) {
    var tasks = [];
    box.querySelectorAll('input[type=checkbox][data-i]').forEach(function (cb) {
      if (!cb.checked) return;
      var i = +cb.getAttribute('data-i');
      var r = rows[i];
      var level = box.querySelector('select[data-level="' + i + '"]').value;
      tasks.push(DN.post('/api/gov/classification/confirm', {
        db: db, table: table, column: r.column, newLevel: level,
        sensitiveType: r.sensitiveType, reason: '采样识别确认'
      }));
    });
    if (!tasks.length) { DN.toast('请先勾选要打标的列', 'error'); return; }
    Promise.all(tasks).then(function () { DN.toast('已打标 ' + tasks.length + ' 列'); scanTable(); })
      .catch(function (e) { DN.toast(e.message, 'error'); });
  }
})();
