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
  var userTbl = null, roleTbl = null, maskTbl = null, rowTbl = null;
  var focusTable = null; // R21 深链: 入口 ctx.table 聚焦的目标表 {db,table}

  window.GOV_RENDERERS.security = function (c) {
    // R21 跨模块深链: 入口读 ctx, 带 ctx.table 时聚焦该表的安全信息
    var ctx = window.__govCtx || {};
    focusTable = (ctx.table && ctx.table.table) ? { db: ctx.table.db || '', table: ctx.table.table } : null;
    if (focusTable) c.appendChild(buildFocusBanner(focusTable));

    // 安全态势概览(大功能): 聚合 用户/角色/脱敏/行级 + 脱敏类型分布环图
    var ovBox = DN.h('div', { id: 'secOverview' });
    ovBox.appendChild(DN.skeleton(2));
    c.appendChild(ovBox);
    buildSecurityOverview(ovBox);

    // 用户
    var userCard = DN.card({ title: '用户', icon: 'user',
      actions: DN.h('a', { class: 'btn btn-primary btn-sm', href: 'javascript:void(0)', text: '+ 新建用户', onclick: function () { openUserForm(null); } }) });
    userCard.el.id = 'secCardUser';
    userTbl = DN.h('div'); userTbl.appendChild(DN.skeleton(3));
    userCard.body.appendChild(userTbl);
    c.appendChild(userCard.el);

    // 角色
    var roleCard = DN.card({ title: '角色', icon: 'shield' });
    roleCard.el.id = 'secCardRole';
    roleTbl = DN.h('div'); roleTbl.appendChild(DN.skeleton(3));
    roleCard.body.appendChild(roleTbl);
    c.appendChild(roleCard.el);

    // M9：脱敏策略
    var maskCard = DN.card({ title: '脱敏策略', icon: 'lock',
      actions: DN.h('a', { class: 'btn btn-primary btn-sm', href: 'javascript:void(0)', text: '+ 新建脱敏策略', onclick: function () { openMaskingForm(); } }) });
    maskCard.el.id = 'secCardMask';
    maskTbl = DN.h('div'); maskTbl.appendChild(DN.skeleton(3));
    maskCard.body.appendChild(maskTbl);
    c.appendChild(maskCard.el);

    // M9：行级权限
    var rowCard = DN.card({ title: '行级权限', icon: 'layers',
      actions: DN.h('a', { class: 'btn btn-primary btn-sm', href: 'javascript:void(0)', text: '+ 新建行策略', onclick: function () { openRowPolicyForm(); } }) });
    rowCard.el.id = 'secCardRow';
    rowTbl = DN.h('div'); rowTbl.appendChild(DN.skeleton(3));
    rowCard.body.appendChild(rowTbl);
    c.appendChild(rowCard.el);

    loadRoles().then(loadUsers).then(loadMaskingPolicies).then(loadRowPolicies);
  };

  // ============== 安全态势概览(大功能) ==============
  function buildSecurityOverview(box) {
    Promise.all([
      DN.get('/api/rbac/users').catch(function () { return []; }),
      DN.get('/api/rbac/roles').catch(function () { return []; }),
      DN.get('/api/gov/masking/policies').catch(function () { return []; }),
      DN.get('/api/gov/masking/row-policies').catch(function () { return []; })
    ]).then(function (r) {
      if (!document.body.contains(box)) return;
      var users = r[0] || [], roles = r[1] || [], masks = r[2] || [], rowp = r[3] || [];
      var enabledMask = masks.filter(function (m) { return m.enabled === 1 || m.enabled === true || m.status === 1; }).length;
      box.innerHTML = '';
      // R21 深链: 操作审计入口 —— 安全事件统一去审计中心追溯
      var auditBar = DN.h('div', { style: 'text-align:right;margin-bottom:8px' });
      auditBar.appendChild(DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)',
        text: '查看全部审计', title: '前往审计中心追溯所有安全相关操作',
        onclick: function () { if (window.govGoModule) govGoModule('audit', {}); } }));
      box.appendChild(auditBar);
      box.appendChild(DN.statRow([
        { icon: 'user', label: '系统用户', value: users.length },
        { icon: 'shield', label: '角色', value: roles.length },
        { icon: 'lock', label: '脱敏策略', value: masks.length, sub: enabledMask + ' 启用', tone: masks.length ? 'ok' : 'muted' },
        { icon: 'layers', label: '行级策略', value: rowp.length, tone: rowp.length ? 'ok' : 'muted' }
      ]));
      // 脱敏策略按敏感类型分布环图
      var byType = {};
      masks.forEach(function (m) { var t = m.sensitiveType || '其它'; byType[t] = (byType[t] || 0) + 1; });
      var keys = Object.keys(byType);
      if (keys.length) {
        var palette = ['#1890ff', '#52c41a', '#faad14', '#ff4d4f', '#13c2c2', '#722ed1'];
        var segs = keys.map(function (k, i) { return { label: k, value: byType[k], color: palette[i % palette.length] }; });
        var card = DN.card({ title: '脱敏策略覆盖（按敏感类型）', icon: 'lock' });
        card.body.appendChild(DN.donut(segs, { size: 110, stroke: 15, centerLabel: masks.length, centerSub: '策略', legend: true }));
        box.appendChild(card.el);
      }
      // 创新功能：权限风险概览（纯前端聚合已加载数据，不调新 API）
      var noRoleUsers = users.filter(function (u) { return !(u.roleIds || []).length; }).length;
      var usedRoleIds = {};
      users.forEach(function (u) { (u.roleIds || []).forEach(function (rid) { usedRoleIds[rid] = true; }); });
      var orphanRoles = roles.filter(function (r) { return !usedRoleIds[r.id]; }).length;
      var disabledMask = masks.filter(function (m) { return !(m.enabled === 1 || m.enabled === true || m.status === 1); }).length;
      var disabledRow = rowp.filter(function (p) { return !(p.enabled === 1 || p.enabled === true); }).length;
      var totalRisk = noRoleUsers + orphanRoles + disabledMask + disabledRow;
      var riskCard = DN.card({ title: '权限风险概览', icon: 'alert' });
      riskCard.body.appendChild(DN.statRow([
        { icon: 'user', label: '无角色用户', value: noRoleUsers,
          sub: pctSub(noRoleUsers, users.length, '占全部用户'),
          tone: noRoleUsers ? 'warn' : 'ok',
          title: noRoleUsers ? '点击定位到“用户”区域处理' : '无此类风险',
          onClick: drillTo(noRoleUsers, 'secCardUser', '所有用户均已分配角色') },
        { icon: 'shield', label: '孤立角色', value: orphanRoles,
          sub: pctSub(orphanRoles, roles.length, '无任何用户引用'),
          tone: orphanRoles ? 'warn' : 'ok',
          title: orphanRoles ? '点击定位到“角色”区域处理' : '无此类风险',
          onClick: drillTo(orphanRoles, 'secCardRole', '所有角色均已被用户引用') },
        { icon: 'lock', label: '未启用脱敏策略', value: disabledMask,
          sub: pctSub(disabledMask, masks.length, '占全部策略'),
          tone: disabledMask ? 'warn' : 'ok',
          title: disabledMask ? '点击定位到“脱敏策略”区域处理' : '无此类风险',
          onClick: drillTo(disabledMask, 'secCardMask', '脱敏策略均已启用') },
        { icon: 'layers', label: '未启用行级策略', value: disabledRow,
          sub: pctSub(disabledRow, rowp.length, '占全部策略'),
          tone: disabledRow ? 'warn' : 'ok',
          title: disabledRow ? '点击定位到“行级权限”区域处理' : '无此类风险',
          onClick: drillTo(disabledRow, 'secCardRow', '行级策略均已启用') }
      ]));
      // 全部为 0 时给出友好、可操作的安全态势结论
      if (!totalRisk) {
        riskCard.body.appendChild(DN.h('div', {
          class: 'gov-empty', style: 'padding:10px 0 2px',
          html: DN.icon('check', 'style="color:var(--gov-ok,#52c41a)"') +
            '<div class="et">权限态势良好，未发现上述风险项</div>'
        }));
      }
      box.appendChild(riskCard.el);
    });
  }

  /** 占比文案，带除零守卫：count/total 为 0 总数时退化为纯说明 */
  function pctSub(count, total, suffix) {
    if (!total) return '暂无数据';
    if (!count) return suffix;
    var pct = Math.round(count * 1000 / total) / 10; // 保留一位小数
    return pct + '% · ' + suffix;
  }

  /** 风险指标下钻：值>0 滚动到目标卡片并临时高亮；值=0 给出友好提示 */
  function drillTo(count, anchorId, okHint) {
    return function () {
      if (!count) { DN.toast(okHint, 'info'); return; }
      var el = document.getElementById(anchorId);
      if (!el) { DN.toast('未找到对应区域', 'error'); return; }
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      var prev = el.style.boxShadow;
      el.style.transition = 'box-shadow .3s';
      el.style.boxShadow = '0 0 0 2px var(--primary,#1890ff)';
      setTimeout(function () { el.style.boxShadow = prev; }, 1600);
    };
  }

  // ============== M9 脱敏策略 ==============

  function loadMaskingPolicies() {
    return DN.get('/api/gov/masking/policies').then(function (list) {
      if (!maskTbl) return;
      maskTbl.innerHTML = '';
      maskTbl.appendChild(DN.table({
        columns: [
          { key: 'policyName', label: '策略名' },
          { key: 'matchDim', label: '维度' },
          { key: 'sensitiveType', label: '敏感类型', render: function (p) { return p.sensitiveType || '-'; } },
          { key: '_loc', label: '库.表.列', render: function (p) {
              if (p.matchDim !== 'COLUMN') return '-';
              var loc = (p.dbName || '') + '.' + (p.tableName || '') + '.' + (p.columnName || '');
              return focusWrap(p.dbName, p.tableName, loc);
            } },
          { key: 'maskingFunc', label: '脱敏函数' },
          { key: 'enabled', label: '状态', render: function (p) {
              return p.enabled === 1 ? DN.pill('启用', 'ok') : DN.pill('停用', 'muted');
            } },
          { key: '_op', label: '操作', render: function (p) {
              return ops([
                link('编辑', function () { openMaskingForm(p); }),
                delLink(function () { return DN.del('/api/gov/masking/policies/' + p.id); }, loadMaskingPolicies)
              ].concat(tableDrill(p.dbName, p.tableName)));
            } }
        ],
        rows: list || [], searchKeys: ['policyName', 'sensitiveType', 'tableName'], searchPlaceholder: '搜索策略名/敏感类型',
        empty: '暂无脱敏策略，请先应用 sql/39_masking.sql 或点击新建', emptyIcon: 'lock'
      }));
    }).catch(function (e) {
      if (maskTbl) { maskTbl.innerHTML = ''; maskTbl.appendChild(DN.errorBox('加载失败: ' + e.message, function () { loadMaskingPolicies(); })); }
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
    drawerForm(isEdit ? '编辑脱敏策略' : '新建脱敏策略', [
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
      if (body.matchDim === 'SENSITIVE_TYPE' && !body.sensitiveType) { DN.toast('按敏感类型匹配时需填敏感类型', 'error'); return; }
      if (body.matchDim === 'COLUMN' && (!body.dbName || !body.tableName || !body.columnName)) { DN.toast('按列匹配时需选库/表/列', 'error'); return; }
      DN.post('/api/gov/masking/policies', body)
        .then(function () { DN.toast('保存成功'); close(); loadMaskingPolicies(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  // ============== M9 行级权限 ==============

  function loadRowPolicies() {
    return DN.get('/api/gov/masking/row-policies').then(function (list) {
      if (!rowTbl) return;
      rowTbl.innerHTML = '';
      rowTbl.appendChild(DN.table({
        columns: [
          { key: 'roleCode', label: '角色编码' },
          { key: 'dbName', label: '库' },
          { key: 'tableName', label: '表', render: function (p) { return focusWrap(p.dbName, p.tableName, p.tableName || '-'); } },
          { key: 'rowFilter', label: '行过滤条件' },
          { key: 'enabled', label: '状态', render: function (p) {
              return p.enabled === 1 ? DN.pill('启用', 'ok') : DN.pill('停用', 'muted');
            } },
          { key: '_op', label: '操作', render: function (p) {
              return ops([
                link('编辑', function () { openRowPolicyForm(p); }),
                delLink(function () { return DN.del('/api/gov/masking/row-policies/' + p.id); }, loadRowPolicies)
              ].concat(tableDrill(p.dbName, p.tableName)));
            } }
        ],
        rows: list || [], searchKeys: ['roleCode', 'dbName', 'tableName'], searchPlaceholder: '搜索角色/库/表',
        empty: '暂无行级权限策略，点击新建', emptyIcon: 'layers'
      }));
    }).catch(function (e) {
      if (rowTbl) { rowTbl.innerHTML = ''; rowTbl.appendChild(DN.errorBox('加载失败: ' + e.message, function () { loadRowPolicies(); })); }
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
    drawerForm(isEdit ? '编辑行策略' : '新建行策略', [
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

  // ============== 角色 / 用户 ==============

  function loadRoles() {
    return DN.get('/api/rbac/roles').then(function (roles) {
      rolesCache = roles || [];
      if (!roleTbl) return;
      roleTbl.innerHTML = '';
      roleTbl.appendChild(DN.table({
        columns: [
          { key: 'roleCode', label: '角色编码' },
          { key: 'roleName', label: '角色名称' },
          { key: '_perms', label: '权限点', render: function (r) { return (r.perms || []).join(', '); } },
          { key: 'description', label: '描述' }
        ],
        rows: rolesCache, searchKeys: ['roleCode', 'roleName'], searchPlaceholder: '搜索角色',
        empty: '暂无角色，请先应用 sql/36_rbac.sql', emptyIcon: 'shield'
      }));
    }).catch(function (e) {
      if (roleTbl) { roleTbl.innerHTML = ''; roleTbl.appendChild(DN.errorBox('加载失败: ' + e.message, function () { loadRoles(); })); }
    });
  }

  function loadUsers() {
    return DN.get('/api/rbac/users').then(function (users) {
      if (!userTbl) return;
      userTbl.innerHTML = '';
      userTbl.appendChild(DN.table({
        columns: [
          { key: 'username', label: '用户名' },
          { key: 'nickname', label: '昵称', render: function (u) { return u.nickname || ''; } },
          { key: 'status', label: '状态', render: function (u) {
              return u.status === 1 ? DN.pill('启用', 'ok') : DN.pill('停用', 'muted');
            } },
          { key: '_roles', label: '角色', render: function (u) {
              return (u.roleIds || []).map(roleName).filter(Boolean).join(', ') || '-';
            } },
          { key: '_op', label: '操作', render: function (u) {
              return ops([
                link('编辑', function () { openUserForm(u); }),
                link('分配角色', function () { openAssignRoles(u); }),
                delLink(function () { return DN.del('/api/rbac/users/' + u.id); }, loadUsers)
              ]);
            } }
        ],
        rows: users || [], searchKeys: ['username', 'nickname'], searchPlaceholder: '搜索用户名/昵称',
        empty: '暂无用户，请先应用 sql/36_rbac.sql 或点击新建', emptyIcon: 'user'
      }));
    }).catch(function (e) {
      if (userTbl) { userTbl.innerHTML = ''; userTbl.appendChild(DN.errorBox('加载失败: ' + e.message, function () { loadUsers(); })); }
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

    drawerForm(isEdit ? '编辑用户' : '新建用户', [
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
      return DN.h('label', { style: 'display:block;padding:6px 0' }, [cb, DN.h('span', { text: ' ' + r.roleName + ' (' + r.roleCode + ')' })]);
    });
    drawerForm('分配角色 — ' + user.username, checks, function (close) {
      var roleIds = checks.map(function (lab) {
        var cb = lab.querySelector('input');
        return cb.checked ? parseInt(cb.value, 10) : null;
      }).filter(function (v) { return v != null; });
      DN.post('/api/rbac/users/' + user.id + '/roles', { roleIds: roleIds })
        .then(function () { DN.toast('分配成功'); close(); loadUsers(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  // ---------- 轻量 UI 辅助 ----------

  function roleName(id) {
    var r = rolesCache.filter(function (x) { return x.id === id; })[0];
    return r ? r.roleName : null;
  }

  /** 抽屉表单：body 节点列表 + 底部确定/取消（替代旧居中弹窗） */
  function drawerForm(title, bodyNodes, onOk) {
    var form = DN.h('div', { class: 'gov-form', style: 'margin-bottom:0' });
    bodyNodes.forEach(function (n) { form.appendChild(n); });
    var d = DN.drawer(title, form);
    var footer = DN.h('div', { style: 'text-align:right;margin-top:16px' });
    footer.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '取消', style: 'margin-right:8px', onclick: d.close }));
    footer.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确定', onclick: function () { onOk(d.close); } }));
    d.body.appendChild(footer);
    DN.enterSubmit(d.body);
  }

  function field(label, input) {
    return DN.h('div', { class: 'ds-form-row', style: 'flex-direction:column;align-items:stretch' }, [
      DN.h('label', { style: 'width:auto;margin-bottom:4px', text: label }), input
    ]);
  }

  function link(text, fn) {
    return DN.h('a', { href: 'javascript:void(0)', text: text,
      style: 'margin-right:12px;color:var(--primary,#1890ff);font-size:13px', onclick: fn });
  }

  /** R21 深链: 命中入口 ctx.table 的行做聚焦标记(库可空, 仅比表名时忽略库) */
  function isFocusRow(db, table) {
    if (!focusTable || !table) return false;
    if (table !== focusTable.table) return false;
    return !focusTable.db || !db || db === focusTable.db;
  }

  /** 库表单元渲染: 命中聚焦表时前置高亮 pill, 否则返回纯文本 */
  function focusWrap(db, table, text) {
    if (!isFocusRow(db, table)) return text;
    var span = DN.h('span', {});
    span.appendChild(DN.pill('聚焦', 'ok'));
    span.appendChild(DN.h('span', { text: ' ' + text }));
    return span;
  }

  /** R21 深链: 由策略所属库.表生成跨模块下钻链接(无库表时返回空, 不渲染死链) */
  function tableDrill(db, table) {
    if (!table) return [];
    var fqn = (db || '') + '.' + table;
    var nodes = [];
    if (window.govGoModule) {
      nodes.push(link('敏感分级', function () {
        govGoModule('classification', { table: { db: db || '', table: table } });
      }));
    }
    if (window.navigateTo) {
      nodes.push(link('数据地图', function () {
        navigateTo('catalog', { openTable: { db: db || '', table: table } });
      }));
    }
    if (nodes.length) nodes[0].title = '处理 ' + fqn + ' 的敏感信息';
    return nodes;
  }

  /** R21 深链: 入口带 ctx.table 时, 顶部聚焦提示条 + 一键去敏感分级/数据地图 */
  function buildFocusBanner(ft) {
    var fqn = (ft.db ? ft.db + '.' : '') + ft.table;
    var bar = DN.h('div', { class: 'gov-focus-banner',
      style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:12px;padding:10px 14px;' +
        'border:1px solid var(--primary,#1890ff);border-radius:8px;background:rgba(24,144,255,.06)' });
    bar.appendChild(DN.h('span', { style: 'font-weight:600',
      html: DN.icon('lock', 'style="margin-right:6px;color:var(--primary,#1890ff)"') + '安全聚焦：' + DN.esc(fqn) }));
    bar.appendChild(DN.h('span', { style: 'color:var(--text-muted,#888);font-size:13px',
      text: '下方脱敏/行级策略中涉及该表的策略已高亮' }));
    var acts = DN.h('span', { style: 'margin-left:auto' });
    if (window.govGoModule) {
      acts.appendChild(link('去敏感分级', function () {
        govGoModule('classification', { table: { db: ft.db || '', table: ft.table } });
      }));
    }
    if (window.navigateTo) {
      acts.appendChild(link('在数据地图打开', function () {
        navigateTo('catalog', { openTable: { db: ft.db || '', table: ft.table } });
      }));
    }
    bar.appendChild(acts);
    return bar;
  }

  /** 内联确认删除链接，返回 Node */
  function delLink(delFn, reload) {
    var a = DN.h('a', { href: 'javascript:void(0)', text: '删除',
      style: 'color:var(--gov-err,#ff4d4f);font-size:13px' });
    a.onclick = function () {
      if (a.getAttribute('data-confirm') === '1') {
        delFn().then(function () { DN.toast('已删除'); reload(); }).catch(function (e) { DN.toast(e.message, 'error'); });
        return;
      }
      a.setAttribute('data-confirm', '1');
      a.textContent = '确认删除？';
      setTimeout(function () { if (a.parentNode) { a.removeAttribute('data-confirm'); a.textContent = '删除'; } }, 2500);
    };
    return a;
  }

  function ops(nodes) {
    return DN.h('span', {}, nodes);
  }
})();
