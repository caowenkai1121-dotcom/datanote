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

    loadRoles().then(loadUsers);
  };

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
    var nameInput = DN.h('input', { value: isEdit ? (user.username || '') : '',
      disabled: isEdit ? 'disabled' : null, placeholder: '用户名', style: inputStyle() });
    var nickInput = DN.h('input', { value: isEdit ? (user.nickname || '') : '', placeholder: '昵称', style: inputStyle() });
    var pwdInput = DN.h('input', { type: 'password', placeholder: isEdit ? '留空则不修改密码' : '密码', style: inputStyle() });
    var statusSel = DN.h('select', { style: inputStyle() }, [
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
    if (!window.confirm('确认删除用户 ' + user.username + ' ?')) return;
    DN.del('/api/rbac/users/' + user.id)
      .then(function () { DN.toast('已删除'); loadUsers(); })
      .catch(function (e) { DN.toast(e.message, 'error'); });
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
    footer.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '取消',
      style: 'margin-right:8px', onclick: close }));
    footer.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '确定',
      onclick: function () { onOk(close); } }));
    box.appendChild(footer);
    mask.appendChild(box);
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    document.body.appendChild(mask);
  }

  function field(label, input) {
    return DN.h('div', { style: 'margin-bottom:12px' }, [
      DN.h('div', { style: 'font-size:13px;color:#4e5969;margin-bottom:4px', text: label }), input
    ]);
  }

  function inputStyle() {
    return 'width:100%;box-sizing:border-box;padding:6px 8px;border:1px solid #c9cdd4;border-radius:4px;font-size:13px';
  }

  function link(text, fn) {
    return DN.h('a', { href: 'javascript:void(0)', text: text,
      style: 'margin-right:12px;color:#165dff;font-size:13px', onclick: fn });
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
