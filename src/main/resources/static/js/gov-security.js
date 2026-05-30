/* 治理模块：数据安全（M6 多用户 + RBAC 底座） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  /* DN 公共层无 PUT 封装，这里就近补一个，复用 DN.api 的 R<T> 信封解析 */
  function putJson(url, data) {
    return DN.api(url, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: data != null ? JSON.stringify(data) : undefined
    });
  }

  var SENSITIVE_TYPES = ['PHONE', 'EMAIL', 'ID_CARD', 'BANK_CARD', 'USCC'];
  var rolesCache = [];

  window.GOV_RENDERERS.security = function (c) {
    var bar = DN.h('div', { class: 'gov-desc' });
    bar.appendChild(DN.h('a', {
      class: 'gov-btn', href: 'javascript:void(0)', text: '+ 新建用户',
      onclick: function () { openUserForm(null); }
    }));
    c.appendChild(bar);

    c.appendChild(DN.h('div', { class: 'gov-h1', text: '用户' }));
    c.appendChild(DN.h('div', { id: 'rbacUserList' }));
    c.appendChild(DN.h('div', { class: 'gov-h1', text: '角色', style: 'margin-top:24px' }));
    c.appendChild(DN.h('div', { id: 'rbacRoleList' }));

    // M9：脱敏策略
    var maskBar = DN.h('div', { class: 'gov-desc', style: 'margin-top:24px' });
    maskBar.appendChild(DN.h('span', { class: 'gov-h1', text: '脱敏策略' }));
    maskBar.appendChild(DN.h('a', {
      class: 'gov-btn', href: 'javascript:void(0)', text: '+ 新建脱敏策略',
      style: 'margin-left:12px', onclick: function () { openMaskingForm(); }
    }));
    c.appendChild(maskBar);
    c.appendChild(DN.h('div', { id: 'maskingPolicyList' }));

    // M9：行级权限
    var rowBar = DN.h('div', { class: 'gov-desc', style: 'margin-top:24px' });
    rowBar.appendChild(DN.h('span', { class: 'gov-h1', text: '行级权限' }));
    rowBar.appendChild(DN.h('a', {
      class: 'gov-btn', href: 'javascript:void(0)', text: '+ 新建行策略',
      style: 'margin-left:12px', onclick: function () { openRowPolicyForm(); }
    }));
    c.appendChild(rowBar);
    c.appendChild(DN.h('div', { id: 'rowPolicyList' }));

    loadRoles().then(loadUsers).then(loadMaskingPolicies).then(loadRowPolicies);
  };

  // ============== M9 脱敏策略 ==============

  function loadMaskingPolicies() {
    return DN.get('/api/gov/masking/policies').then(function (list) {
      var box = document.getElementById('maskingPolicyList');
      if (!box) return;
      box.innerHTML = '';
      if (!list || !list.length) {
        box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无脱敏策略，请先应用 sql/39_masking.sql 或点击新建' }));
        return;
      }
      var tbl = DN.h('table', { style: 'width:100%;border-collapse:collapse;background:#fff;font-size:13px' });
      tbl.appendChild(DN.h('thead', { html: '<tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        th('策略名') + th('维度') + th('敏感类型') + th('库.表.列') + th('脱敏函数') + th('状态') + th('操作') + '</tr>' }));
      var tbody = DN.h('tbody');
      list.forEach(function (p) {
        var loc = p.matchDim === 'COLUMN'
          ? ((p.dbName || '') + '.' + (p.tableName || '') + '.' + (p.columnName || '')) : '-';
        var tr = DN.h('tr');
        tr.innerHTML = td(p.policyName || '') + td(p.matchDim || '') + td(p.sensitiveType || '-') +
          td(loc) + td(p.maskingFunc || '') + td(p.enabled === 1 ? '启用' : '停用');
        var ops = DN.h('td', { style: 'padding:8px;border-bottom:1px solid #f2f3f5' });
        ops.appendChild(link('编辑', function () { openMaskingForm(p); }));
        ops.appendChild(link('删除', function () { delMasking(p); }));
        tr.appendChild(ops);
        tbody.appendChild(tr);
      });
      tbl.appendChild(tbody);
      box.appendChild(tbl);
    }).catch(function (e) {
      var box = document.getElementById('maskingPolicyList');
      if (box) box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message }));
    });
  }

  function openMaskingForm(p) {
    var isEdit = !!p;
    var nameInput = DN.h('input', { class: 'iw-form-input', value: isEdit ? (p.policyName || '') : '', placeholder: '策略名' });
    var dimSel = DN.h('select', { class: 'iw-form-select' }, [
      DN.h('option', { value: 'SENSITIVE_TYPE', text: '按敏感类型' }),
      DN.h('option', { value: 'COLUMN', text: '按具体列' })
    ]);
    var stSel = DN.h('select', { class: 'iw-form-select' },
      [DN.h('option', { value: '', text: '请选择敏感类型' })].concat(SENSITIVE_TYPES.map(function (t) {
        return DN.h('option', { value: t, text: t });
      })));
    // 库/表/列改用系统级联下拉，避免手输
    var picker = DN.dbTablePicker({ withColumn: true, defaultDb: isEdit ? p.dbName : null });
    var funcSel = DN.h('select', { class: 'iw-form-select' }, [
      DN.h('option', { value: 'MASK', text: 'MASK 掩码' }),
      DN.h('option', { value: 'HASH', text: 'HASH (MD5)' }),
      DN.h('option', { value: 'REPLACE', text: 'REPLACE 常量' }),
      DN.h('option', { value: 'RANGE', text: 'RANGE 区间' })
    ]);
    var statusSel = DN.h('select', { class: 'iw-form-select' }, [
      DN.h('option', { value: '1', text: '启用' }), DN.h('option', { value: '0', text: '停用' })
    ]);
    if (isEdit) {
      dimSel.value = p.matchDim || 'SENSITIVE_TYPE';
      stSel.value = p.sensitiveType || '';
      funcSel.value = p.maskingFunc || 'MASK';
      statusSel.value = String(p.enabled == null ? 1 : p.enabled);
    }
    modal(isEdit ? '编辑脱敏策略' : '新建脱敏策略', [
      field('策略名', nameInput), field('匹配维度', dimSel),
      field('敏感类型（按类型时填）', stSel),
      field('库表列（按列时填）', picker.el),
      field('脱敏函数', funcSel), field('状态', statusSel)
    ], function (close) {
      var body = {
        policyName: nameInput.value.trim(), matchDim: dimSel.value,
        sensitiveType: stSel.value || null,
        dbName: picker.db() || null, tableName: picker.table() || null,
        columnName: picker.column() || null,
        maskingFunc: funcSel.value, enabled: parseInt(statusSel.value, 10)
      };
      if (isEdit) body.id = p.id;
      if (!body.policyName) { DN.toast('策略名不能为空', 'error'); return; }
      DN.post('/api/gov/masking/policies', body)
        .then(function () { DN.toast('保存成功'); close(); loadMaskingPolicies(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  function delMasking(p) {
    confirmModal('确认删除脱敏策略「' + (p.policyName || '') + '」？', function () {
      DN.del('/api/gov/masking/policies/' + p.id)
        .then(function () { DN.toast('已删除'); loadMaskingPolicies(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  // ============== M9 行级权限 ==============

  function loadRowPolicies() {
    return DN.get('/api/gov/masking/row-policies').then(function (list) {
      var box = document.getElementById('rowPolicyList');
      if (!box) return;
      box.innerHTML = '';
      if (!list || !list.length) {
        box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无行级权限策略，点击新建' }));
        return;
      }
      var tbl = DN.h('table', { style: 'width:100%;border-collapse:collapse;background:#fff;font-size:13px' });
      tbl.appendChild(DN.h('thead', { html: '<tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        th('角色编码') + th('库') + th('表') + th('行过滤条件') + th('状态') + th('操作') + '</tr>' }));
      var tbody = DN.h('tbody');
      list.forEach(function (p) {
        var tr = DN.h('tr');
        tr.innerHTML = td(p.roleCode || '') + td(p.dbName || '') + td(p.tableName || '') +
          td(p.rowFilter || '') + td(p.enabled === 1 ? '启用' : '停用');
        var ops = DN.h('td', { style: 'padding:8px;border-bottom:1px solid #f2f3f5' });
        ops.appendChild(link('编辑', function () { openRowPolicyForm(p); }));
        ops.appendChild(link('删除', function () { delRowPolicy(p); }));
        tr.appendChild(ops);
        tbody.appendChild(tr);
      });
      tbl.appendChild(tbody);
      box.appendChild(tbl);
    }).catch(function (e) {
      var box = document.getElementById('rowPolicyList');
      if (box) box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message }));
    });
  }

  function openRowPolicyForm(p) {
    var isEdit = !!p;
    var roleSel = DN.h('select', { class: 'iw-form-select' },
      [DN.h('option', { value: '', text: '请选择角色' })].concat(rolesCache.map(function (r) {
        return DN.h('option', { value: r.roleCode, text: r.roleName + ' (' + r.roleCode + ')' });
      })));
    // 库/表改用系统级联下拉
    var picker = DN.dbTablePicker({ defaultDb: isEdit ? p.dbName : null });
    var filterInput = DN.h('input', { class: 'iw-form-input', value: isEdit ? (p.rowFilter || '') : '', placeholder: "行过滤片段，如 region = 'EAST'" });
    var statusSel = DN.h('select', { class: 'iw-form-select' }, [
      DN.h('option', { value: '1', text: '启用' }), DN.h('option', { value: '0', text: '停用' })
    ]);
    if (isEdit) {
      roleSel.value = p.roleCode || '';
      statusSel.value = String(p.enabled == null ? 1 : p.enabled);
    }
    modal(isEdit ? '编辑行策略' : '新建行策略', [
      field('角色', roleSel), field('库表', picker.el),
      field('行过滤条件', filterInput), field('状态', statusSel)
    ], function (close) {
      var body = {
        roleCode: roleSel.value, dbName: picker.db(), tableName: picker.table(),
        rowFilter: filterInput.value.trim(), enabled: parseInt(statusSel.value, 10)
      };
      if (isEdit) body.id = p.id;
      if (!body.roleCode) { DN.toast('请选择角色', 'error'); return; }
      if (!body.dbName || !body.tableName || !body.rowFilter) { DN.toast('库名/表名/过滤条件不能为空', 'error'); return; }
      DN.post('/api/gov/masking/row-policies', body)
        .then(function () { DN.toast('保存成功'); close(); loadRowPolicies(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  function delRowPolicy(p) {
    confirmModal('确认删除该行策略？', function () {
      DN.del('/api/gov/masking/row-policies/' + p.id)
        .then(function () { DN.toast('已删除'); loadRowPolicies(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  function loadRoles() {
    return DN.get('/api/rbac/roles').then(function (roles) {
      rolesCache = roles || [];
      var box = document.getElementById('rbacRoleList');
      if (!box) return;
      box.innerHTML = '';
      if (!rolesCache.length) {
        box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无角色，请先应用 sql/36_rbac.sql' }));
        return;
      }
      var rows = rolesCache.map(function (r) {
        return '<tr><td>' + DN.esc(r.roleCode || '') + '</td><td>' + DN.esc(r.roleName || '') +
          '</td><td>' + DN.esc((r.perms || []).join(', ')) + '</td><td>' + DN.esc(r.description || '') + '</td></tr>';
      }).join('');
      box.innerHTML = table(['角色编码', '角色名称', '权限点', '描述'], rows);
      padCells(box);
    }).catch(function (e) {
      var box = document.getElementById('rbacRoleList');
      if (box) box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message }));
    });
  }

  function loadUsers() {
    return DN.get('/api/rbac/users').then(function (users) {
      var box = document.getElementById('rbacUserList');
      if (!box) return;
      box.innerHTML = '';
      if (!users || !users.length) {
        box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无用户，请先应用 sql/36_rbac.sql 或点击新建' }));
        return;
      }
      var tbl = DN.h('table', { style: 'width:100%;border-collapse:collapse;background:#fff;font-size:13px' });
      var thead = DN.h('thead', { html: '<tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        th('用户名') + th('昵称') + th('状态') + th('角色') + th('操作') + '</tr>' });
      tbl.appendChild(thead);
      var tbody = DN.h('tbody');
      users.forEach(function (u) {
        var roleNames = (u.roleIds || []).map(roleName).filter(Boolean).join(', ');
        var tr = DN.h('tr');
        tr.innerHTML = td(u.username) + td(u.nickname || '') +
          td(u.status === 1 ? '启用' : '停用') + td(roleNames || '-');
        var ops = DN.h('td', { style: 'padding:8px;border-bottom:1px solid #f2f3f5' });
        ops.appendChild(link('编辑', function () { openUserForm(u); }));
        ops.appendChild(link('分配角色', function () { openAssignRoles(u); }));
        ops.appendChild(link('删除', function () { delUser(u); }));
        tr.appendChild(ops);
        tbody.appendChild(tr);
      });
      tbl.appendChild(tbody);
      box.appendChild(tbl);
    }).catch(function (e) {
      var box = document.getElementById('rbacUserList');
      if (box) box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message }));
    });
  }

  function openUserForm(user) {
    var isEdit = !!user;
    var nameInput = DN.h('input', { class: 'iw-form-input', value: isEdit ? (user.username || '') : '',
      disabled: isEdit ? 'disabled' : null, placeholder: '用户名' });
    var nickInput = DN.h('input', { class: 'iw-form-input', value: isEdit ? (user.nickname || '') : '', placeholder: '昵称' });
    var pwdInput = DN.h('input', { class: 'iw-form-input', type: 'password', placeholder: isEdit ? '留空则不修改密码' : '密码' });
    var statusSel = DN.h('select', { class: 'iw-form-select' }, [
      DN.h('option', { value: '1', text: '启用' }),
      DN.h('option', { value: '0', text: '停用' })
    ]);
    if (isEdit) statusSel.value = String(user.status == null ? 1 : user.status);

    modal(isEdit ? '编辑用户' : '新建用户', [
      field('用户名', nameInput), field('昵称', nickInput),
      field('密码', pwdInput), field('状态', statusSel)
    ], function (close) {
      var body = {
        nickname: nickInput.value.trim(),
        status: parseInt(statusSel.value, 10)
      };
      if (pwdInput.value) body.password = pwdInput.value;
      var p;
      if (isEdit) {
        p = putJson('/api/rbac/users/' + user.id, body);
      } else {
        body.username = nameInput.value.trim();
        if (!body.username) { DN.toast('用户名不能为空', 'error'); return; }
        if (!body.password) { DN.toast('密码不能为空', 'error'); return; }
        p = DN.post('/api/rbac/users', body);
      }
      p.then(function () { DN.toast('保存成功'); close(); loadUsers(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  function openAssignRoles(user) {
    var checks = rolesCache.map(function (r) {
      var cb = DN.h('input', { type: 'checkbox', value: String(r.id) });
      if ((user.roleIds || []).indexOf(r.id) >= 0) cb.checked = true;
      var label = DN.h('label', { style: 'display:block;padding:4px 0' }, [cb, DN.h('span', { text: ' ' + r.roleName + ' (' + r.roleCode + ')' })]);
      return label;
    });
    modal('分配角色 — ' + user.username, checks, function (close) {
      var roleIds = checks.map(function (lab) {
        var cb = lab.querySelector('input');
        return cb.checked ? parseInt(cb.value, 10) : null;
      }).filter(function (v) { return v != null; });
      DN.post('/api/rbac/users/' + user.id + '/roles', { roleIds: roleIds })
        .then(function () { DN.toast('分配成功'); close(); loadUsers(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  function delUser(user) {
    confirmModal('确认删除用户「' + (user.username || '') + '」？', function () {
      DN.del('/api/rbac/users/' + user.id)
        .then(function () { DN.toast('已删除'); loadUsers(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  // ---------- 轻量 UI 辅助 ----------

  function roleName(id) {
    var r = rolesCache.filter(function (x) { return x.id === id; })[0];
    return r ? r.roleName : null;
  }

  function modal(title, bodyNodes, onOk) {
    var mask = DN.h('div', { style: 'position:fixed;inset:0;background:rgba(0,0,0,.35);z-index:9999;display:flex;align-items:center;justify-content:center' });
    var box = DN.h('div', { style: 'background:#fff;border-radius:8px;min-width:360px;max-width:90vw;max-height:80vh;overflow:auto;padding:20px;box-shadow:0 8px 24px rgba(0,0,0,.2)' });
    box.appendChild(DN.h('div', { style: 'font-size:16px;font-weight:600;margin-bottom:16px', text: title }));
    bodyNodes.forEach(function (n) { box.appendChild(n); });
    function close() { if (mask.parentNode) mask.parentNode.removeChild(mask); }
    var footer = DN.h('div', { style: 'text-align:right;margin-top:16px' });
    footer.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '取消',
      style: 'margin-right:8px', onclick: close }));
    footer.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确定',
      onclick: function () { onOk(close); } }));
    box.appendChild(footer);
    mask.appendChild(box);
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    document.body.appendChild(mask);
  }

  /** 就近确认弹窗，替代 window.confirm */
  function confirmModal(text, onOk) {
    modal('确认操作', [DN.h('div', { style: 'font-size:13px;color:var(--text-regular,#4e5969)', text: text })],
      function (close) { close(); onOk(); });
  }

  function field(label, input) {
    return DN.h('div', { style: 'margin-bottom:12px' }, [
      DN.h('div', { style: 'font-size:13px;color:var(--text-muted,#86909c);margin-bottom:4px', text: label }), input
    ]);
  }

  function link(text, fn) {
    return DN.h('a', { href: 'javascript:void(0)', text: text,
      style: 'margin-right:12px;color:var(--primary,#165dff);font-size:13px', onclick: fn });
  }

  function table(heads, rowsHtml) {
    return '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
      '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
      heads.map(th).join('') + '</tr></thead><tbody>' + rowsHtml + '</tbody></table>';
  }

  function th(t) { return '<th style="padding:8px">' + DN.esc(t) + '</th>'; }
  function td(t) { return '<td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(t) + '</td>'; }
  function padCells(box) {
    box.querySelectorAll('td').forEach(function (cell) {
      cell.style.padding = '8px'; cell.style.borderBottom = '1px solid #f2f3f5';
    });
  }
})();
