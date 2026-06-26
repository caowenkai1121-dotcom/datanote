/* 治理模块：数据安全（M6 多用户 + RBAC 底座） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var SENSITIVE_TYPES = ['PHONE', 'EMAIL', 'ID_CARD', 'BANK_CARD', 'USCC'];
  var rolesCache = [];
  var maskTbl = null, rowTbl = null;
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
    // R42审查修复: 概览渲染统一由下方 loadAllOnce(ovBox) 一次拉取4端点驱动, 删除此处重复调用(原致8次请求+渲染竞态)

    // IA 收敛: 用户/角色(RBAC)统一在 系统管理-用户管理 维护, 本页只留入口说明
    var rbacDesc = DN.h('div', { class: 'gov-desc', style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap' });
    rbacDesc.appendChild(DN.h('span', { text: '用户与角色（RBAC）统一在「系统管理 - 用户管理」维护，本页聚焦数据脱敏与行级权限。' }));
    rbacDesc.appendChild(DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '前往用户管理', onclick: goUserMgmt }));
    c.appendChild(rbacDesc);

    // M9：脱敏策略
    var maskCard = DN.card({ title: '脱敏策略', icon: 'lock',
      actions: DN.h('a', { class: 'btn btn-primary btn-sm', href: 'javascript:void(0)', text: '+ 新建脱敏策略', 'data-perm': 'governance:manage', onclick: function () { openMaskingForm(); } }) });
    maskCard.el.id = 'secCardMask';
    maskTbl = DN.h('div'); maskTbl.appendChild(DN.skeleton(3));
    maskCard.body.appendChild(maskTbl);
    c.appendChild(maskCard.el);

    // M9：行级权限
    var rowCard = DN.card({ title: '行级权限', icon: 'layers',
      actions: DN.h('a', { class: 'btn btn-primary btn-sm', href: 'javascript:void(0)', text: '+ 新建行策略', 'data-perm': 'governance:manage', onclick: function () { openRowPolicyForm(); } }) });
    rowCard.el.id = 'secCardRow';
    rowTbl = DN.h('div'); rowTbl.appendChild(DN.skeleton(3));
    rowCard.body.appendChild(rowTbl);
    c.appendChild(rowCard.el);

    // 去重 fetch: 四个端点各只请求一次, 结果同时喂给“概览”与两张表(原先概览另发4次=8次, 现4次)
    loadAllOnce(ovBox);
  };

  /** 首屏统一拉取 + 分发: 一次 Promise.all 拿齐 角色/用户/脱敏/行级, 分别渲染概览与两张表。
   *  整体失败时概览给 errorBox 可重试; 各表自身也保留独立加载/重试能力(增删后单端点刷新)。 */
  function loadAllOnce(ovBox) {
    Promise.all([
      DN.get('/api/rbac/roles'),
      DN.get('/api/rbac/users'),
      DN.get('/api/gov/masking/policies'),
      DN.get('/api/gov/masking/row-policies')
    ]).then(function (r) {
      var roles = r[0] || [], users = r[1] || [], masks = r[2] || [], rowp = r[3] || [];
      rolesCache = roles; // 行策略表单依赖, 必须先就位
      renderMaskTable(masks); renderRowTable(rowp);
      buildSecurityOverview(ovBox, { users: users, roles: roles, masks: masks, rowp: rowp });
    }).catch(function (e) {
      // 首屏整体失败: 概览给可重试错误态, 两张表各自回退到独立加载(便于部分端点恢复)
      if (ovBox && document.body.contains(ovBox)) {
        ovBox.innerHTML = '';
        ovBox.appendChild(DN.errorBox('安全数据加载失败: ' + (e && e.message || '未知错误'), function () { loadAllOnce(ovBox); }));
      }
      DN.get('/api/rbac/roles').then(function (roles) { rolesCache = roles || []; }).catch(function () {});
      loadMaskingPolicies(); loadRowPolicies();   // 各自独立加载/重试: 脱敏失败不再阻断行级策略加载(原串联 .then 会挂起后者)
    });
  }

  // ============== 安全态势概览(大功能) ==============
  // data 省略时自行拉取(独立可用); 传入则复用首屏已取数据, 避免重复请求。
  function buildSecurityOverview(box, data) {
    var src = data
      ? Promise.resolve([data.users, data.roles, data.masks, data.rowp])
      : Promise.all([
          DN.get('/api/rbac/users').catch(function () { return []; }),
          DN.get('/api/rbac/roles').catch(function () { return []; }),
          DN.get('/api/gov/masking/policies').catch(function () { return []; }),
          DN.get('/api/gov/masking/row-policies').catch(function () { return []; })
        ]);
    src.then(function (r) {
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
        { icon: 'user', label: '系统用户', value: users.length, title: '点击前往「系统管理 - 用户管理」', onClick: goUserMgmt },
        { icon: 'shield', label: '角色', value: roles.length, title: '点击前往「系统管理 - 用户管理」', onClick: goUserMgmt },
        { icon: 'lock', label: '脱敏策略', value: masks.length, sub: enabledMask + ' 启用', tone: masks.length ? 'ok' : 'muted' },
        { icon: 'layers', label: '行级策略', value: rowp.length, tone: rowp.length ? 'ok' : 'muted' }
      ]));
      // 全空(疑似未初始化 RBAC/脱敏数据)给出明确引导, 而非只摊一排 0
      if (!users.length && !roles.length && !masks.length && !rowp.length) {
        box.appendChild(DN.alertNode('尚未有任何安全数据，请先应用 sql/36_rbac.sql、sql/39_masking.sql 初始化，或直接点击下方“新建”', 'info'));
        return;
      }
      // 脱敏策略按敏感类型分布环图
      var byType = {};
      masks.forEach(function (m) { var t = m.sensitiveType || '其它'; byType[t] = (byType[t] || 0) + 1; });
      var keys = Object.keys(byType);
      if (keys.length) {
        var palette = ['var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)', 'var(--chart-4)', 'var(--chart-5)', 'var(--chart-6)'];
        var segs = keys.map(function (k, i) { return { label: k, value: byType[k], color: palette[i % palette.length] }; });
        var card = DN.card({ title: '脱敏策略覆盖（按敏感类型）', icon: 'lock' });
        card.body.appendChild(DN.donut(segs, { size: 110, stroke: 15, centerLabel: masks.length, centerSub: '策略', legend: true }));
        box.appendChild(card.el);
      }
      // 创新功能：权限风险概览（纯前端聚合已加载数据，不调新 API）
      var noRoleUsers = users.filter(function (u) { return !(u.roleIds || []).length; }).length;
      var usedRoleIds = {};
      users.forEach(function (u) { (u.roleIds || []).forEach(function (rid) { usedRoleIds[rid] = true; }); });
      var orphanRoles = roles.filter(function (ro) { return !usedRoleIds[ro.id]; }).length;
      var disabledMask = masks.filter(function (m) { return !(m.enabled === 1 || m.enabled === true || m.status === 1); }).length;
      var disabledRow = rowp.filter(function (p) { return !(p.enabled === 1 || p.enabled === true); }).length;
      var totalRisk = noRoleUsers + orphanRoles + disabledMask + disabledRow;
      var riskCard = DN.card({ title: '权限风险概览', icon: 'alert' });
      riskCard.body.appendChild(DN.statRow([
        { icon: 'user', label: '无角色用户', value: noRoleUsers,
          sub: pctSub(noRoleUsers, users.length, '占全部用户'),
          tone: noRoleUsers ? 'warn' : 'ok',
          title: noRoleUsers ? '点击前往「用户管理」处理' : '无此类风险',
          onClick: function () { if (noRoleUsers) goUserMgmt(); else DN.toast('所有用户均已分配角色', 'info'); } },
        { icon: 'shield', label: '孤立角色', value: orphanRoles,
          sub: pctSub(orphanRoles, roles.length, '无任何用户引用'),
          tone: orphanRoles ? 'warn' : 'ok',
          title: orphanRoles ? '点击前往「用户管理」处理' : '无此类风险',
          onClick: function () { if (orphanRoles) goUserMgmt(); else DN.toast('所有角色均已被用户引用', 'info'); } },
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
          html: DN.icon('check', 'style="color:var(--gov-ok)"') +
            '<div class="et">权限态势良好，未发现上述风险项</div>'
        }));
      }
      box.appendChild(riskCard.el);
    }).catch(function (e) {
      // 渲染/取数异常兜底: 给可重试错误态, 避免概览区静默空白(原内部 then 无 catch 会成未处理拒绝)
      if (box && document.body.contains(box)) {
        box.innerHTML = '';
        box.appendChild(DN.errorBox('安全态势概览加载失败: ' + (e && e.message || '未知错误'), function () { buildSecurityOverview(box); }));
      }
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
      el.style.boxShadow = '0 0 0 2px var(--primary)';
      setTimeout(function () { el.style.boxShadow = prev; }, 1600);
    };
  }

  /** IA 收敛: 用户/角色统一跳转 系统管理-用户管理 */
  function goUserMgmt() {
    if (window.navigateTo) navigateTo('settings', { sm: 'user' });
  }

  // ============== M9 脱敏策略 ==============

  function renderMaskTable(list) {
    if (!maskTbl) return;
    maskTbl.innerHTML = '';
    var _selMask = {};
    var maskBatchDel = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '批量删除', 'data-perm': 'governance:manage', style: 'color:var(--error)' });
    maskBatchDel.onclick = function () {
      var ids = Object.keys(_selMask); if (!ids.length) { DN.toast('请先勾选策略', 'warn'); return; }
      DN.confirm('确认删除选中的 ' + ids.length + ' 条脱敏策略？', { title: '批量删除', danger: true }).then(function (ok) {
        if (!ok) return;
        maskBatchDel.style.pointerEvents = 'none'; maskBatchDel.style.opacity = '0.5';
        Promise.all(ids.map(function (id) { return DN.del('/api/gov/masking/policies/' + id).catch(function () {}); })).then(function () { DN.toast('已批量删除', 'ok'); loadMaskingPolicies(); });
      });
    };
    maskTbl.appendChild(DN.table({
      toolbar: [maskBatchDel],
      columns: [
        { key: '_sel', label: '', render: function (p) { var cb = DN.h('input', { type: 'checkbox', 'aria-label': '选择 ' + (p.policyName || p.id) }); cb.checked = !!_selMask[p.id]; cb.onchange = function () { if (cb.checked) _selMask[p.id] = 1; else delete _selMask[p.id]; }; return cb; } },
        { key: 'policyName', label: '策略名', render: function (p) { return truncText(p.policyName, 40); } },
        { key: 'matchDim', label: '维度' },
        { key: 'sensitiveType', label: '敏感类型', render: function (p) { return p.sensitiveType || '-'; } },
        { key: '_loc', label: '库.表.列', render: function (p) {
            if (p.matchDim !== 'COLUMN') return '-';
            var loc = (p.dbName || '') + '.' + (p.tableName || '') + '.' + (p.columnName || '');
            return isFocusRow(p.dbName, p.tableName) ? focusWrap(p.dbName, p.tableName, loc) : truncText(loc, 48);
          } },
        { key: 'maskingFunc', label: '脱敏函数' },
        { key: 'enabled', label: '状态', render: function (p) {
            return p.enabled === 1 ? DN.pill('启用', 'ok') : DN.pill('停用', 'muted');
          } },
        { key: '_op', label: '操作', render: function (p) {
            return ops([
              permWrite(link('编辑', function () { openMaskingForm(p); })),
              permWrite(delLink(function () { return DN.del('/api/gov/masking/policies/' + p.id); }, loadMaskingPolicies))
            ].concat(tableDrill(p.dbName, p.tableName)));
          } }
      ],
      rows: list || [], searchKeys: ['policyName', 'sensitiveType', 'tableName'], searchPlaceholder: '搜索策略名/敏感类型',
      empty: '暂无脱敏策略，请先应用 sql/39_masking.sql 或点击新建', emptyIcon: 'lock'
    }));
  }

  function loadMaskingPolicies() {
    return DN.get('/api/gov/masking/policies').then(function (list) {
      renderMaskTable(list || []);
    }).catch(function (e) {
      if (maskTbl) { maskTbl.innerHTML = ''; maskTbl.appendChild(DN.errorBox('加载失败: ' + (e && e.message || '未知错误'), function () { loadMaskingPolicies(); })); }
    });
  }

  function openMaskingForm(p) {
    var isEdit = !!p;
    var nameInput = DN.h('input', { class: 'dn-form-input', value: isEdit ? (p.policyName || '') : '', placeholder: '策略名' });
    var dimSel = DN.h('select', { class: 'dn-form-select' }, [
      DN.h('option', { value: 'SENSITIVE_TYPE', text: '按敏感类型' }),
      DN.h('option', { value: 'COLUMN', text: '按具体列' })
    ]);
    var stSel = DN.h('select', { class: 'dn-form-select' },
      [DN.h('option', { value: '', text: '请选择敏感类型' })].concat(SENSITIVE_TYPES.map(function (t) {
        return DN.h('option', { value: t, text: t });
      })));
    // 库/表/列改用系统级联下拉，避免手输
    var picker = DN.dbTablePicker({ withColumn: true, defaultDb: isEdit ? p.dbName : null, defaultTable: isEdit ? p.tableName : null, defaultColumn: isEdit ? p.columnName : null });
    var funcSel = DN.h('select', { class: 'dn-form-select' }, [
      DN.h('option', { value: 'MASK', text: 'MASK 掩码' }),
      DN.h('option', { value: 'HASH', text: 'HASH (MD5)' }),
      DN.h('option', { value: 'REPLACE', text: 'REPLACE 常量' }),
      DN.h('option', { value: 'RANGE', text: 'RANGE 区间' })
    ]);
    var statusSel = DN.h('select', { class: 'dn-form-select' }, [
      DN.h('option', { value: '1', text: '启用' }), DN.h('option', { value: '0', text: '停用' })
    ]);
    if (isEdit) {
      dimSel.value = p.matchDim || 'SENSITIVE_TYPE';
      stSel.value = p.sensitiveType || '';
      funcSel.value = p.maskingFunc || 'MASK';
      statusSel.value = String(p.enabled == null ? 1 : p.enabled);
    }
    var secBasic = DN.formSection('基本信息');
    secBasic.add(DN.field('策略名', nameInput, { required: true, hint: '最多 100 字' }));
    secBasic.add(DN.formGrid2([
      DN.field('匹配维度', dimSel, { required: true }),
      DN.field('状态', statusSel)
    ]));
    var secMatch = DN.formSection('匹配规则');
    secMatch.add(DN.field('敏感类型', stSel, { hint: '匹配维度选"按敏感类型"时填写' }));
    secMatch.add(DN.field('库 / 表 / 列', picker.el, { hint: '匹配维度选"按具体列"时填写' }));
    secMatch.add(DN.field('脱敏函数', funcSel, { required: true }));
    drawerForm(isEdit ? '编辑脱敏策略' : '新建脱敏策略', [secBasic.el, secMatch.el], function (close) {
      var body = {
        policyName: nameInput.value.trim(), matchDim: dimSel.value,
        sensitiveType: stSel.value || null,
        dbName: picker.db() || null, tableName: picker.table() || null,
        columnName: picker.column() || null,
        maskingFunc: funcSel.value, enabled: parseInt(statusSel.value, 10)
      };
      if (isEdit) body.id = p.id;
      if (!body.policyName) { DN.toast('策略名不能为空', 'error'); return; }
      if (body.policyName.length > 100) { DN.toast('策略名不能超过 100 字', 'error'); return; }
      if (body.matchDim === 'SENSITIVE_TYPE' && !body.sensitiveType) { DN.toast('按敏感类型匹配时需填敏感类型', 'error'); return; }
      if (body.matchDim === 'COLUMN' && (!body.dbName || !body.tableName || !body.columnName)) { DN.toast('按列匹配时需选库/表/列', 'error'); return; }
      return DN.post('/api/gov/masking/policies', body)
        .then(function () { DN.toast('保存成功', 'success'); close(); loadMaskingPolicies(); })
        .catch(function (e) { DN.toast(e.message || '保存失败', 'error'); });
    });
  }

  // ============== M9 行级权限 ==============

  function renderRowTable(list) {
    if (!rowTbl) return;
    rowTbl.innerHTML = '';
    var _selRow = {};
    var rowBatchDel = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '批量删除', 'data-perm': 'governance:manage', style: 'color:var(--error)' });
    rowBatchDel.onclick = function () {
      var ids = Object.keys(_selRow); if (!ids.length) { DN.toast('请先勾选策略', 'warn'); return; }
      DN.confirm('确认删除选中的 ' + ids.length + ' 条行级策略？', { title: '批量删除', danger: true }).then(function (ok) {
        if (!ok) return;
        rowBatchDel.style.pointerEvents = 'none'; rowBatchDel.style.opacity = '0.5';
        Promise.all(ids.map(function (id) { return DN.del('/api/gov/masking/row-policies/' + id).catch(function () {}); })).then(function () { DN.toast('已批量删除', 'ok'); loadRowPolicies(); });
      });
    };
    rowTbl.appendChild(DN.table({
      toolbar: [rowBatchDel],
      columns: [
        { key: '_sel', label: '', render: function (p) { var cb = DN.h('input', { type: 'checkbox', 'aria-label': '选择 ' + (p.roleCode || p.id) }); cb.checked = !!_selRow[p.id]; cb.onchange = function () { if (cb.checked) _selRow[p.id] = 1; else delete _selRow[p.id]; }; return cb; } },
        { key: 'roleCode', label: '角色编码' },
        { key: 'dbName', label: '库', copyable: true, exportValue: function (p) { return p.dbName || ''; }, render: function (p) { return truncText(p.dbName, 24); } },
        { key: 'tableName', label: '表', copyable: true, exportValue: function (p) { return p.tableName || ''; }, render: function (p) {
            return isFocusRow(p.dbName, p.tableName) ? focusWrap(p.dbName, p.tableName, p.tableName || '-') : truncText(p.tableName, 24);
          } },
        { key: 'rowFilter', label: '行过滤条件', render: function (p) { return truncText(p.rowFilter, 48); } },
        { key: 'enabled', label: '状态', render: function (p) {
            return p.enabled === 1 ? DN.pill('启用', 'ok') : DN.pill('停用', 'muted');
          } },
        { key: '_op', label: '操作', render: function (p) {
            return ops([
              permWrite(link('编辑', function () { openRowPolicyForm(p); })),
              permWrite(delLink(function () { return DN.del('/api/gov/masking/row-policies/' + p.id); }, loadRowPolicies))
            ].concat(tableDrill(p.dbName, p.tableName)));
          } }
      ],
      rows: list || [], searchKeys: ['roleCode', 'dbName', 'tableName'], searchPlaceholder: '搜索角色/库/表',
      empty: '暂无行级权限策略，点击新建', emptyIcon: 'layers'
    }));
  }

  function loadRowPolicies() {
    return DN.get('/api/gov/masking/row-policies').then(function (list) {
      renderRowTable(list || []);
    }).catch(function (e) {
      if (rowTbl) { rowTbl.innerHTML = ''; rowTbl.appendChild(DN.errorBox('加载失败: ' + (e && e.message || '未知错误'), function () { loadRowPolicies(); })); }
    });
  }

  function openRowPolicyForm(p) {
    var isEdit = !!p;
    var roleSel = DN.h('select', { class: 'dn-form-select' },
      [DN.h('option', { value: '', text: '请选择角色' })].concat(rolesCache.map(function (r) {
        return DN.h('option', { value: r.roleCode, text: r.roleName + ' (' + r.roleCode + ')' });
      })));
    // 库/表改用系统级联下拉
    var picker = DN.dbTablePicker({ defaultDb: isEdit ? p.dbName : null, defaultTable: isEdit ? p.tableName : null });
    var filterInput = DN.h('input', { class: 'dn-form-input', value: isEdit ? (p.rowFilter || '') : '', placeholder: "行过滤片段，如 region = 'EAST'" });
    var statusSel = DN.h('select', { class: 'dn-form-select' }, [
      DN.h('option', { value: '1', text: '启用' }), DN.h('option', { value: '0', text: '停用' })
    ]);
    if (isEdit) {
      roleSel.value = p.roleCode || '';
      statusSel.value = String(p.enabled == null ? 1 : p.enabled);
    }
    var secBase = DN.formSection('策略配置');
    secBase.add(DN.formGrid2([
      DN.field('角色', roleSel, { required: true }),
      DN.field('状态', statusSel)
    ]));
    secBase.add(DN.field('库 / 表', picker.el, { required: true }));
    secBase.add(DN.field('行过滤条件', filterInput, { required: true, hint: '如 region = \'EAST\'，不允许含 ; 或注释符' }));
    drawerForm(isEdit ? '编辑行策略' : '新建行策略', [secBase.el], function (close) {
      var body = {
        roleCode: roleSel.value, dbName: picker.db(), tableName: picker.table(),
        rowFilter: filterInput.value.trim(), enabled: parseInt(statusSel.value, 10)
      };
      if (isEdit) body.id = p.id;
      if (!body.roleCode) { DN.toast('请选择角色', 'error'); return; }
      if (!body.dbName || !body.tableName || !body.rowFilter) { DN.toast('库名/表名/过滤条件不能为空', 'error'); return; }
      if (body.rowFilter.length > 500) { DN.toast('过滤条件不能超过 500 字', 'error'); return; }
      // 行过滤片段会拼入 SQL，前端先挡掉分号/注释等危险写法（服务端仍应再校验）
      if (/;|--|\/\*|\*\//.test(body.rowFilter)) { DN.toast('过滤条件不能包含 ; 或注释符', 'error'); return; }
      return DN.post('/api/gov/masking/row-policies', body)
        .then(function () { DN.toast('保存成功', 'success'); close(); loadRowPolicies(); })
        .catch(function (e) { DN.toast(e.message || '保存失败', 'error'); });
    });
  }

  // ---------- 轻量 UI 辅助 ----------

  /** 超长文本截断单元: 超过 max 截断并以 title 挂全文(鼠标悬停可见全量); 空值显示 '-' */
  function truncText(s, max) {
    var t = (s == null || s === '') ? '' : String(s);
    if (!t) return '-';
    max = max || 40;
    if (t.length <= max) return t;
    return DN.h('span', { title: t, text: t.slice(0, max) + '…' });
  }

  /** 抽屉表单：body 节点列表 + 底部确定/取消，使用新表单设计系统组件。
   * 防重复提交：onOk 若返回 Promise 则 foot.busy/foot.reset 驱动；
   * 校验失败(onOk 返回 undefined)则立即恢复，不阻塞用户改填。 */
  function drawerForm(title, bodyNodes, onOk) {
    var form = DN.h('div', { class: 'gov-form', style: 'margin-bottom:0' });
    bodyNodes.forEach(function (n) { form.appendChild(n); });
    var dr, foot;
    var doSave = function () {
      if (foot.ok.disabled) return;
      var ret = onOk(function () { dr.close(); });
      if (ret && typeof ret.then === 'function') {
        foot.busy('保存中…');
        ret.then(function () { foot.reset('确定'); }, function () { foot.reset('确定'); });
      }
    };
    foot = DN.drawerFoot({ okText: '确定', onOk: doSave, onCancel: function () { dr.close(); } });
    dr = DN.drawer(title, form, foot.el);
    DN.enterSubmit(form, doSave);
  }

  function link(text, fn) {
    return DN.h('a', { href: 'javascript:void(0)', text: text,
      style: 'margin-right:12px;color:var(--primary);font-size:13px', onclick: fn });
  }

  /** 给写操作行内链接挂权限点: 无 governance:manage 权限时由 dnApplyBtnPerms 隐藏 */
  function permWrite(node) {
    if (node && node.setAttribute) node.setAttribute('data-perm', 'governance:manage');
    return node;
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
        'border:1px solid var(--primary);border-radius:var(--radius-lg);background:rgba(var(--primary-rgb),.06)' });
    bar.appendChild(DN.h('span', { style: 'font-weight:600',
      html: DN.icon('lock', 'style="margin-right:6px;color:var(--primary)"') + '安全聚焦：' + DN.esc(fqn) }));
    bar.appendChild(DN.h('span', { style: 'color:var(--text-muted);font-size:13px',
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

  /** 内联确认删除链接（二次点击确认即为破坏性操作的确认环节），返回 Node */
  function delLink(delFn, reload) {
    var a = DN.h('a', { href: 'javascript:void(0)', text: '删除',
      style: 'color:var(--gov-err);font-size:13px' });
    var revertTimer = null, busy = false;
    a.onclick = function () {
      if (busy) return; // 删除请求进行中，忽略重复点击防止重复 DELETE
      if (a.getAttribute('data-confirm') === '1') {
        if (revertTimer) { clearTimeout(revertTimer); revertTimer = null; }
        busy = true; a.textContent = '删除中…'; a.style.opacity = '0.6';
        delFn().then(function () { DN.toast('已删除', 'success'); reload(); })
          .catch(function (e) {
            DN.toast(e.message || '删除失败', 'error');
            busy = false; a.removeAttribute('data-confirm'); a.textContent = '删除'; a.style.opacity = '';
          });
        return;
      }
      a.setAttribute('data-confirm', '1');
      a.textContent = '确认删除？';
      revertTimer = setTimeout(function () { revertTimer = null; if (a.parentNode) { a.removeAttribute('data-confirm'); a.textContent = '删除'; } }, 2500);
    };
    return a;
  }

  function ops(nodes) {
    return DN.h('span', {}, nodes);
  }
})();
