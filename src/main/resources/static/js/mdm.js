/* 主数据管理(MDM) 模块 —— 域/实体/属性建模 + 总览。
   复用 DN 共享套件(dn-common.js)与 gov-modern.css(:is(#govModuleContent,#mdmModuleContent) 作用域)，
   与数据治理模块保持完全一致的 UI 风格与交互。
   渲染器注册到 window.MDM_RENDERERS；侧栏/切换在 workspace.html 的 initMdmCenter/switchMdmModule。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  // ===================== 共用小工具（健壮性/性能/边界） =====================
  // 统一从异常对象/字符串中提取可读信息，避免 e.message 在 e 为字符串时崩溃
  function errMsg(e) { return (e && e.message) ? e.message : (e != null ? String(e) : '未知错误'); }
  // 安全 JSON.parse：失败返回兜底值，杜绝未捕获异常
  function safeParse(s, fallback) { try { return s ? JSON.parse(s) : (fallback || {}); } catch (e) { return fallback || {}; } }
  // 超长文本截断展示：返回带 title 的元素，超出长度截断并加省略号
  function clip(v, max) {
    var s = (v == null || v === '') ? '-' : String(v);
    max = max || 60;
    if (s.length <= max) return s;
    return DN.h('span', { text: s.slice(0, max) + '…', title: s });
  }
  // 实体列表去重 fetch：黄金记录/去重/交叉引用三处共用，缓存复用避免重复请求
  var _entCache = null;
  function getEntities(force) {
    if (_entCache && !force) return Promise.resolve(_entCache);
    return DN.get('/api/mdm/entities').then(function (ents) { _entCache = ents || []; return _entCache; });
  }
  // 域/实体/属性变更后令实体缓存失效，避免下钻看到陈旧列表
  function invalidateEntities() { _entCache = null; }
  // R126: 数据标准联动缓存(数据元/词根)，属性表单「绑定数据元」与词根命名校验共用；失败不缓存可重试
  var _elemCache = null, _rootCache = null;
  function getElements() {
    if (_elemCache) return Promise.resolve(_elemCache);
    return DN.get('/api/gov/standard/elements').then(function (r) { _elemCache = r || []; return _elemCache; }).catch(function () { return []; });
  }
  function getRoots() {
    if (_rootCache) return Promise.resolve(_rootCache);
    return DN.get('/api/gov/standard/roots').then(function (r) { _rootCache = r || []; return _rootCache; }).catch(function () { return []; });
  }
  // 数据元标准类型 → MDM 属性类型映射（带入建议用）
  function mapElemType(t) {
    t = String(t || '').toUpperCase();
    if (DATA_TYPES.indexOf(t) >= 0) return t;
    if (/INT|LONG/.test(t)) return 'INT';
    if (/DECIMAL|NUMBER|FLOAT|DOUBLE/.test(t)) return 'DECIMAL';
    if (/DATE|TIME/.test(t)) return 'DATE';
    if (/BOOL/.test(t)) return 'BOOLEAN';
    return 'STRING';
  }
  // 列表行内操作链接的防重提交：执行期间禁用并改字，完成/失败后恢复
  function busyLink(a, busyText, fn) {
    if (a._busy) return; a._busy = true;
    var old = a.textContent, oldPe = a.style.pointerEvents, oldOp = a.style.opacity;
    a.textContent = busyText || '处理中...'; a.style.pointerEvents = 'none'; a.style.opacity = '0.6';
    function restore() { a._busy = false; a.textContent = old; a.style.pointerEvents = oldPe; a.style.opacity = oldOp; }
    var p = fn();
    if (p && typeof p.then === 'function') p.then(restore, restore); else restore();
  }

  // ===================== 主数据总览 =====================
  // R118 精简: 主数据总览只留【可点 KPI 磁贴】(直达建模/黄金记录), 砍掉类别柱状图/各域规模表(纯报表)
  window.MDM_RENDERERS.overview = function (c) {
    // 模块描述由 switchMdmModule 的 meta.desc 统一输出, 此处不再重复
    var statBox = DN.h('div', { id: 'mdmStats' });
    statBox.appendChild(DN.skeleton(2));
    c.appendChild(statBox);
    DN.get('/api/mdm/overview').then(function (d) {
      d = d || {};
      statBox.innerHTML = '';
      statBox.appendChild(DN.statRow([
        { icon: 'grid', label: '主数据域', value: d.domainCount || 0, sub: '已启用 ' + (d.enabledDomainCount || 0), title: '进入域与实体建模', onClick: function () { mdmGoModule('modeling'); } },
        { icon: 'layers', label: '实体', value: d.entityCount || 0, title: '进入域与实体建模', onClick: function () { mdmGoModule('modeling'); } },
        { icon: 'list', label: '属性', value: d.attributeCount || 0, title: '进入域与实体建模', onClick: function () { mdmGoModule('modeling'); } },
        { icon: 'shield', label: '黄金记录', value: d.goldenCount || 0, sub: '已生效 ' + (d.goldenActiveCount || 0), tone: (d.goldenCount ? 'ok' : 'muted'), title: '进入黄金记录', onClick: function () { mdmGoModule('goldenrecord'); } },
        { icon: 'inbox', label: '草稿待复核', value: Math.max(0, (d.goldenCount || 0) - (d.goldenActiveCount || 0)), sub: '数据管家处理', tone: ((d.goldenCount || 0) - (d.goldenActiveCount || 0) > 0 ? 'warn' : 'muted'), title: '进入数据管家', onClick: function () { mdmGoModule('steward'); } }
      ]));
    }).catch(function (e) {
      statBox.innerHTML = '';
      statBox.appendChild(DN.errorBox('总览加载失败: ' + errMsg(e), function () {
        c.innerHTML = ''; MDM_RENDERERS.overview(c);
      }));
    });
  };

  // ===================== 域与实体建模（域 → 实体 → 属性 三级） =====================
  var _selDomain = null;   // 当前选中的域
  var _selEntity = null;   // 当前选中的实体
  var _domainRows = [];    // 当前已加载的域列表（供编码唯一性前置校验复用，免重复请求）
  var _entityRows = [];    // 当前域下已加载的实体列表
  var _attrRows = [];      // 当前实体下已加载的属性列表
  // 在已加载列表里做编码唯一性前置校验：忽略大小写与首尾空白，排除自身
  function codeTaken(rows, key, code, selfId) {
    var lc = String(code || '').trim().toLowerCase();
    return (rows || []).some(function (r) {
      return String(r.id) !== String(selfId) && String(r[key] || '').trim().toLowerCase() === lc;
    });
  }

  window.MDM_RENDERERS.modeling = function (c) {
    _selDomain = null; _selEntity = null;
    renderModeling(c);
  };

  function renderModeling(c) {
    c.innerHTML = '';
    // 面包屑
    var crumb = DN.h('div', { class: 'gov-desc', style: 'margin-bottom:14px;display:flex;align-items:center;gap:6px;flex-wrap:wrap' });
    crumb.appendChild(crumbLink('主数据域', function () { _selDomain = null; _selEntity = null; renderModeling(c); }, !_selDomain));
    if (_selDomain) {
      crumb.appendChild(DN.h('span', { text: '/', style: 'color:var(--text-muted)' }));
      crumb.appendChild(crumbLink(_selDomain.domainName + ' · 实体', function () { _selEntity = null; renderModeling(c); }, !_selEntity));
    }
    if (_selEntity) {
      crumb.appendChild(DN.h('span', { text: '/', style: 'color:var(--text-muted)' }));
      crumb.appendChild(crumbLink(_selEntity.entityName + ' · 属性', null, true));
    }
    c.appendChild(crumb);

    if (!_selDomain) renderDomainLevel(c);
    else if (!_selEntity) renderEntityLevel(c);
    else renderAttributeLevel(c);
  }

  function crumbLink(text, onclick, active) {
    if (active || !onclick) return DN.h('span', { text: text, style: 'font-weight:600;color:var(--text-regular)' });
    return DN.h('a', { href: 'javascript:void(0)', text: text, style: 'color:var(--primary)', onclick: onclick });
  }

  // ---- 第1级：域管理 ----
  function renderDomainLevel(c) {
    var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建域', 'data-perm': 'mdm:manage', onclick: function () { domainForm(null, c); } });
    var card = DN.card({ title: '主数据域', icon: 'grid', actions: addBtn });
    card.body.appendChild(DN.skeleton(3));
    c.appendChild(card.el);
    DN.get('/api/mdm/domains').then(function (rows) {
      rows = rows || [];
      _domainRows = rows;
      card.body.innerHTML = '';
      if (!rows.length) { card.body.appendChild(DN.empty('暂无主数据域，点右上角“新建域”开始建模', 'grid')); return; }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'domainCode', label: '域编码', copyable: true, render: function (r) { return r.domainCode; } },
          { key: 'domainName', label: '域名称', render: function (r) {
              return DN.h('a', { href: 'javascript:void(0)', text: r.domainName, style: 'color:var(--primary)', title: '管理该域下实体', onclick: function () { _selDomain = r; _selEntity = null; renderModeling(c); } });
            } },
          { key: 'category', label: '类别', render: function (r) { return r.category ? DN.pill(r.category, 'info') : '-'; } },
          { key: 'owner', label: '负责人', render: function (r) { return r.owner || '-'; } },
          { key: 'entityCount', label: '实体数', align: 'right', sortable: true, render: function (r) { return String(r.entityCount == null ? 0 : r.entityCount); } },
          { key: 'status', label: '状态', render: function (r) { return r.status === 0 ? DN.pill('停用', 'muted') : DN.pill('启用', 'ok'); } },
          { key: '_op', label: '操作', render: function (r) {
              var box = DN.h('span', { style: 'display:inline-flex;gap:10px' });
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '实体', style: 'color:var(--primary)', onclick: function () { _selDomain = r; _selEntity = null; renderModeling(c); } }));
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary)', 'data-perm': 'mdm:manage', onclick: function () { domainForm(r, c); } }));
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error)', 'data-perm': 'mdm:manage', onclick: function () { delConfirm('删除域「' + r.domainName + '」？将级联删除其下所有实体与属性。', '/api/mdm/domain/' + r.id, c); } }));
              return box;
            } }
        ],
        rows: rows, pageSize: 15, searchKeys: ['domainCode', 'domainName', 'category', 'owner'], searchPlaceholder: '搜索域编码/名称/类别/负责人', exportName: '主数据域'
      }));
    }).catch(function (e) {
      card.body.innerHTML = '';
      card.body.appendChild(DN.errorBox('加载失败: ' + errMsg(e), function () { renderModeling(c); }));
    });
  }

  // ---- 第2级：实体管理 ----
  function renderEntityLevel(c) {
    var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建实体', 'data-perm': 'mdm:manage', onclick: function () { entityForm(null, c); } });
    var card = DN.card({ title: '实体 · ' + _selDomain.domainName, icon: 'layers', actions: addBtn });
    card.body.appendChild(DN.skeleton(3));
    c.appendChild(card.el);
    DN.get('/api/mdm/entities?domainId=' + encodeURIComponent(_selDomain.id)).then(function (rows) {
      rows = rows || [];
      _entityRows = rows;
      card.body.innerHTML = '';
      if (!rows.length) { card.body.appendChild(DN.empty('该域下暂无实体，点右上角“新建实体”创建', 'layers')); return; }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'entityCode', label: '实体编码', copyable: true, render: function (r) { return r.entityCode; } },
          { key: 'entityName', label: '实体名称', render: function (r) {
              return DN.h('a', { href: 'javascript:void(0)', text: r.entityName, style: 'color:var(--primary)', title: '管理该实体的属性', onclick: function () { _selEntity = r; renderModeling(c); } });
            } },
          { key: 'attrCount', label: '属性数', align: 'right', sortable: true, render: function (r) { return String(r.attrCount == null ? 0 : r.attrCount); } },
          { key: 'description', label: '描述', render: function (r) { return r.description ? clip(r.description, 40) : '-'; } },
          { key: 'status', label: '状态', render: function (r) { return r.status === 0 ? DN.pill('停用', 'muted') : DN.pill('启用', 'ok'); } },
          { key: '_op', label: '操作', render: function (r) {
              var box = DN.h('span', { style: 'display:inline-flex;gap:10px' });
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '属性', style: 'color:var(--primary)', onclick: function () { _selEntity = r; renderModeling(c); } }));
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary)', 'data-perm': 'mdm:manage', onclick: function () { entityForm(r, c); } }));
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error)', 'data-perm': 'mdm:manage', onclick: function () { delConfirm('删除实体「' + r.entityName + '」？将级联删除其属性。', '/api/mdm/entity/' + r.id, c); } }));
              return box;
            } }
        ],
        rows: rows, pageSize: 15, searchKeys: ['entityCode', 'entityName', 'description'], searchPlaceholder: '搜索实体编码/名称/描述', exportName: '实体_' + _selDomain.domainCode
      }));
    }).catch(function (e) {
      card.body.innerHTML = '';
      card.body.appendChild(DN.errorBox('加载失败: ' + errMsg(e), function () { renderModeling(c); }));
    });
  }

  // ---- 第3级：属性管理 ----
  var DATA_TYPES = ['STRING', 'INT', 'DECIMAL', 'DATE', 'BOOLEAN', 'ENUM', 'REFERENCE'];
  function renderAttributeLevel(c) {
    var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建属性', 'data-perm': 'mdm:manage', onclick: function () { attrForm(null, c); } });
    var card = DN.card({ title: '属性 · ' + _selEntity.entityName, icon: 'list', actions: addBtn });
    card.body.appendChild(DN.skeleton(3));
    c.appendChild(card.el);
    DN.get('/api/mdm/attributes?entityId=' + encodeURIComponent(_selEntity.id)).then(function (rows) {
      rows = rows || [];
      _attrRows = rows;
      card.body.innerHTML = '';
      card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 10px', text: '关键字段(可用于匹配去重)、必填、唯一等属性元数据，是黄金记录生成的基础。' }));
      if (!rows.length) { card.body.appendChild(DN.empty('该实体下暂无属性，点右上角“新建属性”创建', 'list')); return; }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'attrCode', label: '属性编码', copyable: true, render: function (r) { return r.attrCode; } },
          { key: 'attrName', label: '属性名称', render: function (r) { return r.attrName; } },
          { key: 'elementCode', label: '数据元', render: function (r) { return r.elementCode ? DN.pill(r.elementCode, 'ok') : '-'; } },
          { key: 'dataType', label: '数据类型', render: function (r) { return DN.pill(r.dataType || 'STRING', 'info'); } },
          { key: 'lengthLimit', label: '长度', align: 'right', render: function (r) { return r.lengthLimit == null ? '-' : String(r.lengthLimit); } },
          { key: 'enumValues', label: '候选值', render: function (r) { return r.enumValues ? clip(r.enumValues, 30) : '-'; } },
          { key: '_flags', label: '约束', render: function (r) {
              var box = DN.h('span', { style: 'display:inline-flex;gap:4px;flex-wrap:wrap' });
              if (r.isKey === 1) box.appendChild(DN.pill('关键', 'warn'));
              if (r.required === 1) box.appendChild(DN.pill('必填', 'err'));
              if (r.isUnique === 1) box.appendChild(DN.pill('唯一', 'info'));
              if (!box.childNodes.length) box.appendChild(DN.h('span', { text: '-', style: 'color:var(--text-muted)' }));
              return box;
            } },
          { key: 'description', label: '描述', render: function (r) { return r.description ? clip(r.description, 40) : '-'; } },
          { key: '_op', label: '操作', render: function (r) {
              var box = DN.h('span', { style: 'display:inline-flex;gap:10px' });
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary)', 'data-perm': 'mdm:manage', onclick: function () { attrForm(r, c); } }));
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error)', 'data-perm': 'mdm:manage', onclick: function () { delConfirm('删除属性「' + r.attrName + '」？', '/api/mdm/attribute/' + r.id, c); } }));
              return box;
            } }
        ],
        rows: rows, pageSize: 20, searchKeys: ['attrCode', 'attrName', 'dataType', 'description'], searchPlaceholder: '搜索属性编码/名称/类型', exportName: '属性_' + _selEntity.entityCode
      }));
    }).catch(function (e) {
      card.body.innerHTML = '';
      card.body.appendChild(DN.errorBox('加载失败: ' + errMsg(e), function () { renderModeling(c); }));
    });
  }

  // ===================== 表单（DN.drawer, R83 全新表单设计系统） =====================
  // field(标签, 控件, 提示, 必填) — 用 .dn-* 类, 标签清晰 + 必填红星 + 焦点环输入
  function field(label, input, hint, required) {
    var lab = DN.h('label', { text: label });
    if (required) lab.appendChild(DN.h('span', { class: 'req', text: '*' }));
    var row = DN.h('div', { class: 'dn-field' }, [lab, input]);
    if (hint) row.appendChild(DN.h('div', { class: 'dn-hint', text: hint }));
    return row;
  }
  function inp(val, ph) { return DN.h('input', { class: 'dn-form-input', value: val == null ? '' : String(val), placeholder: ph || '' }); }
  function sel(opts, val) {
    var s = DN.h('select', { class: 'dn-form-select' });
    opts.forEach(function (o) { var ov = Array.isArray(o) ? o[0] : o, ol = Array.isArray(o) ? o[1] : o; var op = DN.h('option', { value: ov, text: ol }); if (String(val) === String(ov)) op.selected = true; s.appendChild(op); });
    return s;
  }
  function chk(label, checked) {
    var cb = DN.h('input', { type: 'checkbox' }); if (checked) cb.checked = true;
    var w = DN.h('label', { style: 'display:inline-flex;align-items:center;gap:6px;font-size:13px;cursor:pointer;margin-right:16px' }, [cb, DN.h('span', { text: label })]);
    w._cb = cb; return w;
  }

  function domainForm(row, c) {
    var isEdit = !!row; row = row || {};
    var fCode = inp(row.domainCode, '如 CUSTOMER');
    var fName = inp(row.domainName, '如 客户主数据');
    var fCat = sel([['', '(未分类)'], '客户', '产品', '供应商', '组织', '财务', '其他'], row.category || '');
    var fOwner = inp(row.owner, '域负责人/部门');
    var fDesc = inp(row.description, '描述');
    var fStatus = sel([[1, '启用'], [0, '停用']], row.status == null ? 1 : row.status);
    var body = DN.h('div', {});
    var sec = DN.formSection('域信息');
    sec.add(DN.formGrid2([field('域编码', fCode, '唯一，建议大写英文', true), field('域名称', fName, null, true)]));
    sec.add(DN.formGrid2([field('业务类别', fCat), field('负责人', fOwner, '域负责人/部门')]));
    sec.add(field('描述', fDesc));
    sec.add(field('状态', fStatus));
    body.appendChild(sec.el);
    var dr, foot;
    var doSave = function () {
      if (foot.ok.disabled) return;
      var payload = { id: row.id, domainCode: fCode.value.trim(), domainName: fName.value.trim(), category: fCat.value, owner: fOwner.value.trim(), description: fDesc.value.trim(), status: Number(fStatus.value) };
      if (!payload.domainCode) { DN.toast('请填写域编码', 'err'); return; }
      if (!payload.domainName) { DN.toast('请填写域名称', 'err'); return; }
      if (codeTaken(_domainRows, 'domainCode', payload.domainCode, row.id)) { DN.toast('域编码「' + payload.domainCode + '」已存在，请改用唯一编码', 'err'); return; }
      foot.busy();
      DN.post('/api/mdm/domain/save', payload).then(function () { invalidateEntities(); DN.toast('已保存', 'ok'); dr.close(); renderModeling(c); })
        .catch(function (e) { DN.toast(errMsg(e) || '保存失败', 'err'); foot.reset(); });
    };
    foot = DN.drawerFoot({ okText: isEdit ? '保存修改' : '创建', onOk: doSave, onCancel: function () { dr.close(); } });
    dr = DN.drawer((isEdit ? '编辑' : '新建') + '主数据域', body, foot.el);
    DN.enterSubmit(body, doSave);
  }

  function entityForm(row, c) {
    if (!_selDomain) { DN.toast('请先选择主数据域', 'warn'); return; }
    var isEdit = !!row; row = row || {};
    var fCode = inp(row.entityCode, '如 customer');
    var fName = inp(row.entityName, '如 客户');
    var fDesc = inp(row.description, '描述');
    var fStatus = sel([[1, '启用'], [0, '停用']], row.status == null ? 1 : row.status);
    var body = DN.h('div', {});
    var sec = DN.formSection('实体信息');
    sec.add(field('所属域', DN.h('input', { class: 'dn-form-input', value: _selDomain.domainName, disabled: 'disabled' })));
    sec.add(DN.formGrid2([field('实体编码', fCode, '同域内唯一', true), field('实体名称', fName, null, true)]));
    sec.add(field('描述', fDesc));
    sec.add(field('状态', fStatus));
    body.appendChild(sec.el);
    var dr, foot;
    var doSave = function () {
      if (foot.ok.disabled) return;
      var payload = { id: row.id, domainId: _selDomain.id, entityCode: fCode.value.trim(), entityName: fName.value.trim(), description: fDesc.value.trim(), status: Number(fStatus.value) };
      if (!payload.entityCode) { DN.toast('请填写实体编码', 'err'); return; }
      if (!payload.entityName) { DN.toast('请填写实体名称', 'err'); return; }
      if (codeTaken(_entityRows, 'entityCode', payload.entityCode, row.id)) { DN.toast('实体编码「' + payload.entityCode + '」在本域内已存在', 'err'); return; }
      foot.busy();
      DN.post('/api/mdm/entity/save', payload).then(function () { invalidateEntities(); DN.toast('已保存', 'ok'); dr.close(); renderModeling(c); })
        .catch(function (e) { DN.toast(errMsg(e) || '保存失败', 'err'); foot.reset(); });
    };
    foot = DN.drawerFoot({ okText: isEdit ? '保存修改' : '创建', onOk: doSave, onCancel: function () { dr.close(); } });
    dr = DN.drawer((isEdit ? '编辑' : '新建') + '实体', body, foot.el);
    DN.enterSubmit(body, doSave);
  }

  function attrForm(row, c) {
    if (!_selEntity) { DN.toast('请先选择实体', 'warn'); return; }
    var isEdit = !!row; row = row || {};
    var fCode = inp(row.attrCode, '如 cust_name');
    var fName = inp(row.attrName, '如 客户名称');
    var fType = sel(DATA_TYPES, row.dataType || 'STRING');
    var fLen = inp(row.lengthLimit, '如 128');
    var fEnum = inp(row.enumValues, 'ENUM/REFERENCE 候选值，逗号分隔（可“选码表”引用参考数据）');
    var fDefault = inp(row.defaultValue, '默认值');
    var fDesc = inp(row.description, '描述');
    var fSort = inp(row.sortOrder == null ? '' : row.sortOrder, '排序');
    var cKey = chk('关键字段(匹配用)', row.isKey === 1);
    var cReq = chk('必填', row.required === 1);
    var cUniq = chk('唯一', row.isUnique === 1);
    var body = DN.h('div', {});
    var sec1 = DN.formSection('基本信息 · ' + _selEntity.entityName);
    sec1.add(DN.formGrid2([field('属性编码', fCode, '同实体内唯一', true), field('属性名称', fName, null, true)]));
    // R126 词根命名校验: 属性编码按 _ 拆段比对数据标准词根缩写，提示不阻断
    var rootHint = DN.h('div', { class: 'dn-hint', style: 'display:none;margin:-6px 0 10px' });
    sec1.add(rootHint);
    function checkRoots() {
      var code = fCode.value.trim();
      if (!code) { rootHint.style.display = 'none'; return; }
      getRoots().then(function (roots) {
        if (!roots.length) { rootHint.style.display = 'none'; return; }
        var abbrs = {};
        roots.forEach(function (r2) { if (r2.abbr) abbrs[String(r2.abbr).toLowerCase()] = r2.wordCn || r2.wordEn || ''; });
        var segs = code.toLowerCase().split('_').filter(Boolean);
        var bad = segs.filter(function (s) { return !abbrs[s]; });
        rootHint.style.display = '';
        if (!bad.length) {
          rootHint.style.color = 'var(--success)';
          rootHint.textContent = '✓ 命名符合词根标准：' + segs.map(function (s) { return s + '=' + abbrs[s]; }).join('，');
        } else {
          rootHint.style.color = 'var(--warning)';
          rootHint.textContent = '词根未收录：' + bad.join('、') + '（建议改用标准缩写，或到 数据治理→数据标准→词根 登记；不阻断保存）';
        }
      });
    }
    var _rtTimer;
    fCode.addEventListener('input', function () { clearTimeout(_rtTimer); _rtTimer = setTimeout(checkRoots, 400); });
    if (row.attrCode) checkRoots();
    // R126 绑定数据元: 关联数据标准，选中自动带入名称/类型/长度建议(落标)
    var fElement = DN.h('select', { class: 'dn-form-select' });
    fElement.appendChild(DN.h('option', { value: '', text: '(不绑定)' }));
    getElements().then(function (els) {
      els.forEach(function (e2) {
        var op = DN.h('option', { value: e2.elementCode, text: e2.elementCode + ' · ' + (e2.nameCn || '') });
        if (row.elementCode === e2.elementCode) op.selected = true;
        fElement.appendChild(op);
      });
      if (row.elementCode && fElement.value !== row.elementCode) {
        var gone = DN.h('option', { value: row.elementCode, text: row.elementCode + '（数据元已不存在）' });
        gone.selected = true; fElement.appendChild(gone);
      }
      fElement._els = els;
    });
    fElement.onchange = function () {
      var e2 = (fElement._els || []).filter(function (x) { return x.elementCode === fElement.value; })[0];
      if (!e2) return;
      if (!fName.value.trim() && e2.nameCn) fName.value = e2.nameCn;
      fType.value = mapElemType(e2.dataType);
      fType.dispatchEvent(new Event('change'));
      if (!fLen.value.trim() && e2.length) fLen.value = e2.length;
      DN.toast('已带入数据元建议：类型 ' + fType.value + (e2.length ? '，长度 ' + e2.length : ''), 'ok');
    };
    sec1.add(field('绑定数据元', fElement, '关联数据标准·数据元（落标）；选中自动带入名称/类型/长度建议'));
    sec1.add(DN.formGrid2([field('数据类型', fType), field('长度限制', fLen, '文本/数值最大长度，可空')]));
    body.appendChild(sec1.el);
    var sec2 = DN.formSection('取值与默认');
    sec2.add(field('枚举候选值', fEnum, '仅 ENUM/REFERENCE 类型时填写，逗号分隔'));
    // “选码表”辅助：ENUM/REFERENCE 时可从参考数据码表引用，避免纯手填
    var refRow = buildRefPicker(fEnum);
    sec2.add(refRow.el);
    function syncRefRow() {
      var t = (fType.value || '').toUpperCase();
      refRow.el.style.display = (t === 'ENUM' || t === 'REFERENCE') ? '' : 'none';
    }
    fType.addEventListener('change', syncRefRow);
    syncRefRow();
    sec2.add(field('默认值', fDefault));
    body.appendChild(sec2.el);
    var sec3 = DN.formSection('约束与排序');
    sec3.add(field('约束', DN.h('div', { style: 'display:flex;flex-wrap:wrap;align-items:center;gap:4px;padding-top:3px' }, [cKey, cReq, cUniq])));
    sec3.add(DN.formGrid2([field('描述', fDesc), field('排序', fSort, '数字，越小越靠前')]));
    body.appendChild(sec3.el);
    var dr, foot;
    var doSave = function () {
      if (foot.ok.disabled) return;
      var dataType = fType.value;
      // 长度/排序按数值解析，非法（NaN/负数）即时拦截，避免脏数据落库
      var lenStr = fLen.value.trim(), len = null;
      if (lenStr) { len = parseInt(lenStr, 10); if (isNaN(len) || len <= 0) { DN.toast('长度限制需为正整数', 'err'); return; } }
      var sortStr = fSort.value.trim(), sort = 0;
      if (sortStr) { sort = parseInt(sortStr, 10); if (isNaN(sort)) { DN.toast('排序需为整数', 'err'); return; } }
      var payload = {
        id: row.id, entityId: _selEntity.id, attrCode: fCode.value.trim(), attrName: fName.value.trim(),
        dataType: dataType, lengthLimit: len,
        enumValues: fEnum.value.trim(), defaultValue: fDefault.value.trim(), description: fDesc.value.trim(),
        sortOrder: sort,
        elementCode: fElement.value || null,
        isKey: cKey._cb.checked ? 1 : 0, required: cReq._cb.checked ? 1 : 0, isUnique: cUniq._cb.checked ? 1 : 0
      };
      if (!payload.attrCode) { DN.toast('请填写属性编码', 'err'); return; }
      if (!payload.attrName) { DN.toast('请填写属性名称', 'err'); return; }
      if (codeTaken(_attrRows, 'attrCode', payload.attrCode, row.id)) { DN.toast('属性编码「' + payload.attrCode + '」在本实体内已存在', 'err'); return; }
      // ENUM/REFERENCE 类型必须提供候选值，否则黄金记录无法按枚举录入
      if ((dataType === 'ENUM' || dataType === 'REFERENCE') && !payload.enumValues) { DN.toast(dataType + ' 类型需填写枚举候选值（可“引用码值”）', 'err'); return; }
      foot.busy();
      DN.post('/api/mdm/attribute/save', payload).then(function () { DN.toast('已保存', 'ok'); dr.close(); renderModeling(c); })
        .catch(function (e) { DN.toast(errMsg(e) || '保存失败', 'err'); foot.reset(); });
    };
    foot = DN.drawerFoot({ okText: isEdit ? '保存修改' : '创建属性', onOk: doSave, onCancel: function () { dr.close(); } });
    dr = DN.drawer((isEdit ? '编辑' : '新建') + '属性', body, foot.el);
    DN.enterSubmit(body, doSave);
  }

  // “选码表”辅助控件：选参考数据类别 → 拉取其码值填入枚举候选值输入框
  function buildRefPicker(fEnum) {
    var catSel = DN.h('select', { class: 'dn-form-select', style: 'flex:1;min-width:140px' });
    catSel.appendChild(DN.h('option', { value: '', text: '加载码表类别…' }));
    var pullBtn = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '引用码值', style: 'white-space:nowrap' });
    var modeSel = sel([['name', '用名称'], ['code', '用码值']], 'name');
    var wrap = DN.h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap;align-items:center' }, [catSel, modeSel, pullBtn]);
    var el = field('选码表（参考数据）', wrap, '从参考数据码表引用候选值，覆盖填入上方“枚举候选值”');
    DN.get('/api/mdm/refdata/categories').then(function (cats) {
      cats = cats || [];
      catSel.innerHTML = '';
      if (!cats.length) { catSel.appendChild(DN.h('option', { value: '', text: '(暂无码表，请先在“参考数据”创建)' })); return; }
      catSel.appendChild(DN.h('option', { value: '', text: '(请选择码表类别)' }));
      cats.forEach(function (ct) { catSel.appendChild(DN.h('option', { value: ct.category, text: ct.category + '（' + (ct.itemCount || 0) + '）' })); });
    }).catch(function () { catSel.innerHTML = ''; catSel.appendChild(DN.h('option', { value: '', text: '(码表加载失败)' })); });
    pullBtn.onclick = function () {
      var cat = catSel.value;
      if (!cat) { DN.toast('请先选择码表类别', 'warn'); return; }
      pullBtn.textContent = '拉取中...'; pullBtn.style.pointerEvents = 'none';
      DN.get('/api/mdm/refdata/list?category=' + encodeURIComponent(cat)).then(function (rows) {
        rows = (rows || []).filter(function (r) { return r.status == null || r.status === 1; });
        var vals = rows.map(function (r) { return modeSel.value === 'code' ? r.code : (r.name || r.code); }).filter(Boolean);
        if (!vals.length) { DN.toast('该码表暂无启用码值', 'warn'); }
        else { fEnum.value = vals.join(','); DN.toast('已引用 ' + vals.length + ' 个码值', 'ok'); }
        pullBtn.textContent = '引用码值'; pullBtn.style.pointerEvents = '';
      }).catch(function (e) { DN.toast(errMsg(e) || '拉取失败', 'err'); pullBtn.textContent = '引用码值'; pullBtn.style.pointerEvents = ''; });
    };
    return { el: el };
  }

  function delConfirm(msg, url, c) {
    DN.confirm(msg, { title: '删除确认', danger: true }).then(function (ok) {
      if (!ok) return;
      DN.del(url).then(function () { invalidateEntities(); DN.toast('已删除', 'ok'); renderModeling(c); })
        .catch(function (e) { DN.toast(errMsg(e) || '删除失败', 'err'); });
    });
  }

  // ===================== 黄金记录（按实体 schema 动态生成表单） =====================
  var _grEntity = null;   // 当前选中实体
  var _grAttrs = [];      // 当前实体属性 schema
  var _grFilter = '';     // 状态筛选

  window.MDM_RENDERERS.goldenrecord = function (c) {
    var ctx = window.__mdmCtx || {};
    var selWrap = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:14px' });
    selWrap.appendChild(DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '选择实体：' }));
    var entSel = DN.h('select', { class: 'dn-form-select', style: 'min-width:240px' });
    entSel.appendChild(DN.h('option', { value: '', text: '加载中…' }));
    selWrap.appendChild(entSel);
    c.appendChild(selWrap);
    var box = DN.h('div', { id: 'grBox' });
    c.appendChild(box);

    getEntities().then(function (ents) {
      ents = ents || [];
      entSel.innerHTML = '';
      if (!ents.length) { entSel.appendChild(DN.h('option', { value: '', text: '(暂无实体，请先在“域与实体建模”创建)' })); box.appendChild(DN.empty('请先在“域与实体建模”创建实体', 'layers')); return; }
      entSel.appendChild(DN.h('option', { value: '', text: '(请选择实体)' }));
      ents.forEach(function (e) { entSel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + e.entityName })); });
      // 深链：ctx.entityId 指定则选中该实体，否则默认第一个
      var ctxEnt = ctx.entityId != null ? ents.filter(function (e) { return String(e.id) === String(ctx.entityId); })[0] : null;
      var initEnt = ctxEnt || ents[0];
      entSel.value = String(initEnt.id);
      _grEntity = initEnt;
      loadGoldenForEntity(box, (ctx.editId != null ? ctx.editId : null), (ctx.historyId != null ? ctx.historyId : null));
      entSel.onchange = function () {
        _grEntity = ents.filter(function (e) { return String(e.id) === entSel.value; })[0] || null;
        box.innerHTML = '';
        if (_grEntity) loadGoldenForEntity(box);
        else box.appendChild(DN.empty('请选择一个实体以管理其黄金记录', 'shield'));
      };
    }).catch(function (e) {
      entSel.innerHTML = ''; entSel.appendChild(DN.h('option', { value: '', text: '(加载失败)' }));
      box.appendChild(DN.errorBox('实体加载失败: ' + errMsg(e), function () { invalidateEntities(); c.innerHTML = ''; MDM_RENDERERS.goldenrecord(c); }));
    });
  };

  function loadGoldenForEntity(box, autoEditId, autoHistId) {
    if (!_grEntity) return;
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    // 先加载该实体属性 schema，再加载记录与统计
    Promise.all([
      DN.get('/api/mdm/attributes?entityId=' + encodeURIComponent(_grEntity.id)),
      DN.get('/api/mdm/golden/stats?entityId=' + encodeURIComponent(_grEntity.id))
    ]).then(function (r) {
      _grAttrs = r[0] || [];
      var stats = r[1] || {};
      box.innerHTML = '';
      box.appendChild(DN.statRow([
        { icon: 'shield', label: '黄金记录', value: stats.total || 0 },
        { icon: 'check', label: '已生效', value: stats.active || 0, tone: 'ok' },
        { icon: 'clock', label: '草稿', value: stats.draft || 0, tone: (stats.draft ? 'warn' : 'ok') },
        { icon: 'alert', label: '已停用', value: stats.inactive || 0, tone: 'muted' }
      ]));
      var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建黄金记录', 'data-perm': 'mdm:manage', onclick: function () { goldenForm(null, box); } });
      var statusSel = DN.h('select', { class: 'dn-form-select', style: 'min-width:120px' });
      [['', '全部状态'], ['active', '已生效'], ['draft', '草稿'], ['inactive', '已停用']].forEach(function (o) { statusSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
      statusSel.value = _grFilter;
      statusSel.onchange = function () { _grFilter = statusSel.value; loadGoldenList(listBox); };
      var card = DN.card({ title: '黄金记录 · ' + _grEntity.entityName, icon: 'shield', actions: addBtn });
      var listBox = DN.h('div', {});
      card.body.appendChild(DN.h('div', { style: 'margin-bottom:10px' }, [statusSel]));
      card.body.appendChild(listBox);
      box.appendChild(card.el);
      loadGoldenList(listBox, autoEditId, autoHistId);
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('加载失败: ' + errMsg(e), function () { loadGoldenForEntity(box); }));
    });
  }

  function loadGoldenList(listBox, autoEditId, autoHistId) {
    if (!_grEntity) return;
    listBox.innerHTML = ''; listBox.appendChild(DN.skeleton(3));
    var qs = '?entityId=' + encodeURIComponent(_grEntity.id) + (_grFilter ? '&status=' + _grFilter : '');
    DN.get('/api/mdm/golden/list' + qs).then(function (rows) {
      rows = rows || [];
      listBox.innerHTML = '';
      if (!rows.length) { listBox.appendChild(DN.empty(_grFilter ? '当前筛选下暂无黄金记录' : '暂无黄金记录，点右上角“新建黄金记录”录入', 'shield')); return; }
      rows.forEach(function (r) { r._vals = safeParse(r.dataJson, {}); });
      // 深链：autoEditId 命中则自动打开该记录编辑（只触发一次）
      if (autoEditId != null) {
        var hit = rows.filter(function (r) { return String(r.id) === String(autoEditId); })[0];
        // #19: 已生效记录不可直改, 深链命中 active 记录改打开变更申请抽屉
        if (hit) { if (hit.status === 'active') goldenChangeRequest(hit); else goldenForm(hit, document.getElementById('grBox')); }
        else DN.toast('未找到指定黄金记录(可能已删除)', 'warn');
      }
      // R128 深链: 审批单「记录历史」跳入, 自动打开该记录的变更历史抽屉
      if (autoHistId != null) {
        var hHit = rows.filter(function (r) { return String(r.id) === String(autoHistId); })[0];
        if (hHit) goldenHistory(hHit);
        else DN.toast('未找到指定黄金记录(可能已删除)', 'warn');
      }
      // 动态列：业务主键 + 前若干关键/普通属性 + 状态 + 版本 + 操作
      var keyAttrs = _grAttrs.filter(function (a) { return a.isKey === 1 || a.isUnique === 1; });
      var showAttrs = (keyAttrs.length ? keyAttrs : _grAttrs).slice(0, 4);
      var cols = [];
      showAttrs.forEach(function (a) {
        cols.push({ key: 'a_' + a.attrCode, label: a.attrName, render: (function (code) { return function (r) { return clip(r._vals ? r._vals[code] : null, 40); }; })(a.attrCode) });
      });
      cols.push({ key: 'status', label: '状态', render: function (r) {
          var s = r.status; return s === 'active' ? DN.pill('已生效', 'ok') : s === 'inactive' ? DN.pill('已停用', 'muted') : DN.pill('草稿', 'warn');
        } });
      cols.push({ key: 'sourceSystem', label: '来源', render: function (r) { return r.sourceSystem ? DN.pill(r.sourceSystem, 'info') : '-'; } });
      cols.push({ key: 'version', label: '版本', align: 'right', render: function (r) { return 'v' + (r.version || 1); } });
      cols.push({ key: 'updatedAt', label: '更新', render: function (r) { return DN.timeAgo(r.updatedAt); } });
      cols.push({ key: '_op', label: '操作', render: function (r) {
          var w = DN.h('span', { style: 'display:inline-flex;gap:9px' });
          // #19: 已生效记录禁止直改/直删, 改为发起变更申请走审批; 草稿/停用仍可直接编辑删除
          if (r.status === 'active') {
            w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '发起变更申请', style: 'color:var(--primary)', title: '已生效记录需走变更审批, 批准后自动应用', 'data-perm': 'mdm:manage', onclick: function () { goldenChangeRequest(r); } }));
          } else {
            w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary)', 'data-perm': 'mdm:manage', onclick: function () { goldenForm(r, document.getElementById('grBox')); } }));
          }
          if (r.status !== 'active') { var pubA = DN.h('a', { href: 'javascript:void(0)', text: '发布', style: 'color:var(--success)', 'data-perm': 'mdm:manage', onclick: function () { DN.confirm('发布黄金记录「' + (r.bizKey || r.id) + '」？发布后将作为单一可信版本生效。', { title: '发布确认', danger: false }).then(function (ok) { if (!ok) return; busyLink(pubA, '发布中...', function () { return goldenAction('/api/mdm/golden/' + r.id + '/publish', '已发布'); }); }); } }); w.appendChild(pubA); }
          if (r.status === 'active') { var deA = DN.h('a', { href: 'javascript:void(0)', text: '停用', style: 'color:var(--warning)', 'data-perm': 'mdm:manage', onclick: function () { DN.confirm('停用黄金记录「' + (r.bizKey || r.id) + '」？停用后将不再作为生效版本。', { title: '停用确认', danger: true }).then(function (ok) { if (!ok) return; busyLink(deA, '停用中...', function () { return goldenAction('/api/mdm/golden/' + r.id + '/deactivate', '已停用'); }); }); } }); w.appendChild(deA); }
          w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '历史', style: 'color:var(--primary)', title: '变更历史与版本diff', onclick: function () { goldenHistory(r); } }));
          if (r.status !== 'active') {
            var delA = DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error)', 'data-perm': 'mdm:manage', onclick: function () { DN.confirm('删除黄金记录「' + (r.bizKey || r.id) + '」？', { title: '删除确认', danger: true }).then(function (ok) { if (!ok) return; busyLink(delA, '删除中...', function () { return DN.del('/api/mdm/golden/' + r.id).then(function () { DN.toast('已删除', 'ok'); reloadGolden(); }).catch(function (e) { DN.toast(errMsg(e) || '删除失败', 'err'); }); }); }); } });
            w.appendChild(delA);
          }
          return w;
        } });
      listBox.appendChild(DN.table({
        columns: cols, rows: rows, pageSize: 15,
        searchKeys: ['bizKey', 'sourceSystem'], searchPlaceholder: '搜索业务主键/来源', exportName: '黄金记录_' + _grEntity.entityCode,
        exportValue: function (r, k) { return r._vals && r._vals[k.replace('a_', '')] != null ? r._vals[k.replace('a_', '')] : undefined; }
      }));
    }).catch(function (e) {
      listBox.innerHTML = '';
      listBox.appendChild(DN.errorBox('加载失败: ' + errMsg(e), function () { loadGoldenList(listBox); }));
    });
  }

  function reloadGolden() { var box = document.getElementById('grBox'); if (box) loadGoldenForEntity(box); }
  // R126: 黄金记录变更历史抽屉 — 快照按新→旧排列，每条与更旧一条做字段级 diff
  function goldenHistory(rec) {
    var body = DN.h('div', {});
    body.appendChild(DN.skeleton(3));
    DN.drawer('变更历史 · ' + (rec.bizKey || ('#' + rec.id)), body);
    var nameOf = {};
    (_grAttrs || []).forEach(function (a) { nameOf[a.attrCode] = a.attrName; });
    var CT = { create: ['创建', 'info'], update: ['修改', 'warn'], publish: ['发布', 'ok'], deactivate: ['停用', 'muted'], merge: ['合并', 'info'] };
    DN.get('/api/mdm/golden/' + rec.id + '/history').then(function (list) {
      list = list || [];
      body.innerHTML = '';
      if (!list.length) { body.appendChild(DN.empty('暂无历史快照（自本功能上线后的变更才会记录）', 'clock')); return; }
      list.forEach(function (h, i) {
        var prev = list[i + 1];
        var cur = safeParse(h.dataJson, {});
        var old = prev ? safeParse(prev.dataJson, {}) : null;
        var ct = CT[h.changeType] || [h.changeType || '-', 'info'];
        var block = DN.h('div', { style: 'padding:10px 12px;border:1px solid var(--border);border-radius:var(--radius-md,8px);margin-bottom:10px' });
        block.appendChild(DN.h('div', { style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' }, [
          DN.h('strong', { text: 'v' + (h.version == null ? '-' : h.version) }),
          DN.pill(ct[0], ct[1]),
          DN.h('span', { class: 'gov-desc', style: 'margin:0', text: (h.status || '') + ' · ' + (h.createdAt ? String(h.createdAt).replace('T', ' ') : '') })
        ]));
        var diffs = [];
        if (old) {
          var keys = {};
          Object.keys(cur).forEach(function (k) { keys[k] = 1; });
          Object.keys(old).forEach(function (k) { keys[k] = 1; });
          Object.keys(keys).forEach(function (k) {
            var a = old[k] == null ? '' : String(old[k]);
            var b = cur[k] == null ? '' : String(cur[k]);
            if (a !== b) diffs.push({ k: k, a: a, b: b });
          });
        }
        if (!old) {
          block.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:6px 0 0', text: '初始快照 · ' + Object.keys(cur).length + ' 个属性值' }));
        } else if (!diffs.length) {
          block.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:6px 0 0', text: '属性值无变化（状态/版本流转）' }));
        } else {
          diffs.forEach(function (d) {
            block.appendChild(DN.h('div', { class: 'dn-diff-row' }, [
              DN.h('span', { class: 'dk', text: nameOf[d.k] || d.k }),
              DN.h('span', { class: 'da', text: d.a === '' ? '(空)' : d.a }),
              DN.h('span', { text: '→' }),
              DN.h('span', { class: 'db', text: d.b === '' ? '(空)' : d.b })
            ]));
          });
        }
        body.appendChild(block);
      });
    }).catch(function (e) {
      body.innerHTML = '';
      body.appendChild(DN.errorBox('历史加载失败: ' + errMsg(e), function () { goldenHistory(rec); }));
    });
  }
  // 返回 Promise 供 busyLink 跟踪按钮忙碌态，防重复提交
  function goldenAction(url, okMsg) {
    return DN.post(url).then(function () { DN.toast(okMsg, 'ok'); reloadGolden(); }).catch(function (e) { DN.toast(errMsg(e) || '操作失败', 'err'); });
  }

  // 按属性类型生成输入控件
  function grInput(attr, val) {
    var t = (attr.dataType || 'STRING').toUpperCase();
    if (t === 'ENUM') {
      var opts = (attr.enumValues || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean);
      return sel([['', '(请选择)']].concat(opts), val == null ? '' : val);
    }
    if (t === 'BOOLEAN') return sel([['', '(请选择)'], ['true', '是'], ['false', '否']], val == null ? '' : String(val));
    if (t === 'DATE') return DN.h('input', { type: 'date', class: 'dn-form-input', style: 'width:100%', value: val || '' });
    if (t === 'INT' || t === 'DECIMAL') return DN.h('input', { type: 'number', class: 'dn-form-input', style: 'width:100%', value: val == null ? '' : val, step: t === 'DECIMAL' ? 'any' : '1' });
    return inp(val, attr.description || '');
  }

  function goldenForm(row, box) {
    if (!_grEntity) { DN.toast('请先选择实体', 'warn'); return; }
    if (!_grAttrs.length) { DN.toast('该实体尚无属性定义，请先在“域与实体建模”补充属性', 'warn'); return; }
    var isEdit = !!row; row = row || {};
    var vals = row.dataJson ? safeParse(row.dataJson, (row._vals || {})) : (row._vals || {});
    var inputs = {};   // attrCode -> input element
    var body = DN.h('div', {});
    // 属性信息分组: 每个属性一字段, 必填红星 + 关键字段徽标, 提示给可读类型
    var secA = DN.formSection('属性信息 · ' + _grEntity.entityName);
    _grAttrs.forEach(function (a) {
      var input = grInput(a, vals[a.attrCode]);
      inputs[a.attrCode] = input;
      var hint = readableType(a.dataType, a.lengthLimit) + (a.isKey === 1 ? ' · 关键字段(匹配去重用)' : '');
      secA.add(field(a.attrName, input, hint, a.required === 1));
    });
    body.appendChild(secA.el);
    // 记录元信息分组
    var secM = DN.formSection('记录元信息');
    var srcInput = inp(row.sourceSystem, '如 CRM / ERP / 人工录入');
    var statusSel = sel([['draft', '草稿'], ['active', '已生效'], ['inactive', '已停用']], row.status || 'draft');
    secM.add(DN.formGrid2([field('来源系统', srcInput, '该记录的数据来源'), field('状态', statusSel, '草稿可继续编辑，发布后生效')]));
    body.appendChild(secM.el);
    var dr, foot;
    var doSave = function () {
      if (foot.ok.disabled) return;
      var data = {};
      var missing = null, invalid = null;
      _grAttrs.forEach(function (a) {
        if (invalid) return;
        var el = inputs[a.attrCode];
        var v = (el && el.value != null ? String(el.value).trim() : '');
        if (a.required === 1 && !v && !missing) missing = a.attrName;
        if (v !== '') {
          var t = (a.dataType || 'STRING').toUpperCase();
          // 类型校验：数值类必须可解析为数字；超长按长度限制拦截
          if ((t === 'INT' || t === 'DECIMAL') && isNaN(Number(v))) { invalid = a.attrName + ' 需为数字'; return; }
          if (t === 'INT' && !/^-?\d+$/.test(v)) { invalid = a.attrName + ' 需为整数'; return; }
          if (a.lengthLimit && v.length > a.lengthLimit) { invalid = a.attrName + ' 超出长度限制(' + a.lengthLimit + ')'; return; }
          data[a.attrCode] = v;
        }
      });
      if (invalid) { DN.toast('属性校验失败：' + invalid, 'err'); return; }
      if (missing) { DN.toast('必填属性未填写：' + missing, 'err'); return; }
      var payload = { id: row.id, entityId: _grEntity.id, dataJson: JSON.stringify(data), status: statusSel.value, sourceSystem: srcInput.value.trim() };
      foot.busy();
      DN.post('/api/mdm/golden/save', payload).then(function () { DN.toast('已保存', 'ok'); dr.close(); reloadGolden(); })
        .catch(function (e) { DN.toast(errMsg(e) || '保存失败', 'err'); foot.reset(); });
    };
    foot = DN.drawerFoot({ okText: isEdit ? '保存修改' : '创建记录', onOk: doSave, onCancel: function () { dr.close(); } });
    dr = DN.drawer((isEdit ? '编辑' : '新建') + '黄金记录', body, foot.el);
    DN.enterSubmit(body, doSave);
  }

  // #19: 已生效记录不可直改 — 发起 update 变更申请(预填当前字段值), 提交进入变更审批, 批准后自动应用
  function goldenChangeRequest(row) {
    if (!_grEntity) { DN.toast('请先选择实体', 'warn'); return; }
    if (!_grAttrs.length) { DN.toast('该实体尚无属性定义，请先在“域与实体建模”补充属性', 'warn'); return; }
    var cur = row.dataJson ? safeParse(row.dataJson, (row._vals || {})) : (row._vals || {});
    var inputs = {};
    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 12px', text: '已生效记录不可直接修改。请在下方调整字段值并提交变更申请，审批批准后自动应用到该记录。' }));
    var secA = DN.formSection('变更后属性值 · ' + (row.bizKey || ('#' + row.id)));
    _grAttrs.forEach(function (a) {
      var input = grInput(a, cur[a.attrCode]);
      inputs[a.attrCode] = input;
      var hint = readableType(a.dataType, a.lengthLimit) + (a.isKey === 1 ? ' · 关键字段(匹配去重用)' : '');
      secA.add(field(a.attrName, input, hint, a.required === 1));
    });
    body.appendChild(secA.el);
    var secR = DN.formSection('申请信息');
    var fReason = DN.h('textarea', { class: 'dn-form-input', style: 'width:100%;min-height:64px;resize:vertical', placeholder: '变更原因(必填)' });
    secR.add(field('变更原因', fReason, null, true));
    body.appendChild(secR.el);
    var dr, foot;
    var doSave = function () {
      if (foot.ok.disabled) return;
      var reason = fReason.value.trim();
      if (!reason) { DN.toast('请填写变更原因', 'err'); fReason.focus(); return; }
      // 与 goldenForm 同口径的必填/类型/长度校验; 仅把改动过的字段作为 patch 提交, 审批通过后合并进记录
      var patch = {}, missing = null, invalid = null, changed = 0;
      _grAttrs.forEach(function (a) {
        if (invalid) return;
        var el = inputs[a.attrCode];
        var v = (el && el.value != null ? String(el.value).trim() : '');
        if (a.required === 1 && !v && !missing) missing = a.attrName;
        if (v !== '') {
          var t = (a.dataType || 'STRING').toUpperCase();
          if ((t === 'INT' || t === 'DECIMAL') && isNaN(Number(v))) { invalid = a.attrName + ' 需为数字'; return; }
          if (t === 'INT' && !/^-?\d+$/.test(v)) { invalid = a.attrName + ' 需为整数'; return; }
          if (a.lengthLimit && v.length > a.lengthLimit) { invalid = a.attrName + ' 超出长度限制(' + a.lengthLimit + ')'; return; }
        }
        var old = (cur[a.attrCode] == null ? '' : String(cur[a.attrCode]));
        if (v !== old) { patch[a.attrCode] = v; changed++; }
      });
      if (invalid) { DN.toast('属性校验失败：' + invalid, 'err'); return; }
      if (missing) { DN.toast('必填属性未填写：' + missing, 'err'); return; }
      if (!changed) { DN.toast('未修改任何字段，无需提交变更申请', 'warn'); return; }
      var reqBody = { entityId: _grEntity.id, goldenRecordId: row.id, changeType: 'update', bizKey: row.bizKey || null, reason: reason, payloadJson: JSON.stringify(patch) };
      foot.busy();
      DN.post('/api/mdm/approval/submit', reqBody).then(function () {
        DN.toast('变更申请已提交，批准后生效（可在“变更审批”查看进度）', 'ok'); dr.close();
      }).catch(function (e) { DN.toast(errMsg(e) || '提交失败', 'err'); foot.reset(); });
    };
    foot = DN.drawerFoot({ okText: '提交变更申请', onOk: doSave, onCancel: function () { dr.close(); } });
    dr = DN.drawer('发起变更申请 · ' + (row.bizKey || ('#' + row.id)), body, foot.el);
    DN.enterSubmit(body, doSave);
  }

  // 可读类型提示(技术类型 → 人话)
  function readableType(t, len) {
    var m = { STRING: '文本', INT: '整数', DECIMAL: '小数', DATE: '日期', DATETIME: '日期时间', BOOLEAN: '是/否', ENUM: '枚举', REFERENCE: '引用' };
    var base = m[(t || 'STRING').toUpperCase()] || (t || '文本');
    return base + (len ? '，最长 ' + len + ' 字符' : '');
  }

  // ===================== 匹配去重（按关键/唯一属性检测重复 + 合并） =====================
  var _ddEntity = null, _ddAttrs = [];

  window.MDM_RENDERERS.dedup = function (c) {
    var ctx = window.__mdmCtx || {};
    var selWrap = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:14px' });
    selWrap.appendChild(DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '选择实体：' }));
    var entSel = DN.h('select', { class: 'dn-form-select', style: 'min-width:240px' });
    entSel.appendChild(DN.h('option', { value: '', text: '加载中…' }));
    selWrap.appendChild(entSel);
    selWrap.appendChild(DN.h('a', { class: 'btn btn-sm btn-ghost', href: 'javascript:void(0)', text: '存活策略配置', onclick: function () { window.mdmGoModule('survivorship'); } }));
    c.appendChild(selWrap);
    var box = DN.h('div', { id: 'ddBox' });
    c.appendChild(box);

    getEntities().then(function (ents) {
      ents = ents || [];
      entSel.innerHTML = '';
      if (!ents.length) { entSel.appendChild(DN.h('option', { value: '', text: '(暂无实体)' })); box.appendChild(DN.empty('请先创建实体与黄金记录', 'layers')); return; }
      entSel.appendChild(DN.h('option', { value: '', text: '(请选择实体)' }));
      ents.forEach(function (e) { entSel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + e.entityName })); });
      // 深链：ctx.entityId 指定则选中该实体，否则默认第一个
      var ctxEnt = ctx.entityId != null ? ents.filter(function (e) { return String(e.id) === String(ctx.entityId); })[0] : null;
      var initEnt = ctxEnt || ents[0];
      entSel.value = String(initEnt.id); _ddEntity = initEnt;
      loadDedup(box);
      entSel.onchange = function () { _ddEntity = ents.filter(function (e) { return String(e.id) === entSel.value; })[0] || null; box.innerHTML = ''; if (_ddEntity) loadDedup(box); else box.appendChild(DN.empty('请选择一个实体以检测重复', 'alert')); };
    }).catch(function (e) { entSel.innerHTML = ''; entSel.appendChild(DN.h('option', { value: '', text: '(加载失败)' })); box.appendChild(DN.errorBox('实体加载失败: ' + errMsg(e), function () { invalidateEntities(); c.innerHTML = ''; MDM_RENDERERS.dedup(c); })); });
  };

  function loadDedup(box) {
    if (!_ddEntity) return;
    box.innerHTML = ''; box.appendChild(DN.skeleton(3));
    Promise.all([
      DN.get('/api/mdm/attributes?entityId=' + encodeURIComponent(_ddEntity.id)),
      DN.get('/api/mdm/match/duplicates?entityId=' + encodeURIComponent(_ddEntity.id))
    ]).then(function (r) {
      _ddAttrs = r[0] || [];
      var d = r[1] || {};
      box.innerHTML = '';
      box.appendChild(DN.statRow([
        { icon: 'shield', label: '扫描记录', value: d.scannedCount || 0 },
        { icon: 'grid', label: '匹配键', value: d.keyAttrCount || 0, sub: '关键/唯一属性' },
        { icon: 'alert', label: '重复簇', value: d.clusterCount || 0, tone: (d.clusterCount ? 'warn' : 'ok') },
        { icon: 'list', label: '涉及记录', value: d.duplicateRecordCount || 0, tone: (d.duplicateRecordCount ? 'warn' : 'ok') }
      ]));
      var clusters = d.clusters || [];
      if (!(d.keyAttrCount > 0)) { box.appendChild(DN.empty('该实体未定义关键/唯一属性，无法匹配去重。请在“域与实体建模”将匹配字段标记为关键或唯一。', 'alert')); return; }
      if (!clusters.length) { box.appendChild(DN.empty('未检测到重复记录，主数据干净 ✓', 'check')); return; }
      clusters.forEach(function (cl, idx) { box.appendChild(buildClusterCard(cl, idx, box)); });
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.errorBox('检测失败: ' + errMsg(e), function () { loadDedup(box); })); });
  }

  function buildClusterCard(cl, idx, box) {
    cl = cl || {};
    var card = DN.card({ title: '重复簇 · ' + (cl.matchAttrName || '') + ' = ' + (cl.matchValue == null ? '' : cl.matchValue) + ' （' + (cl.size || 0) + ' 条）', icon: 'alert' });
    card.el.classList.add('primary');
    var recs = cl.records || [];
    recs.forEach(function (r) { r._vals = safeParse(r.dataJson, {}); });
    var showAttrs = (_ddAttrs.filter(function (a) { return a.isKey === 1 || a.isUnique === 1; }));
    if (!showAttrs.length) showAttrs = _ddAttrs.slice(0, 4);

    var radioName = 'survivor_' + idx;
    var tbl = DN.h('table', { class: 'gov-tbl', style: 'width:100%' });
    var thead = '<thead><tr><th>存活</th>' + showAttrs.map(function (a) { return '<th>' + DN.esc(a.attrName) + '</th>'; }).join('') + '<th>状态</th><th>来源</th><th>版本</th><th>更新</th><th>操作</th></tr></thead>';
    var tb = document.createElement('tbody');
    recs.forEach(function (r, i) {
      var tr = document.createElement('tr');
      var rd = document.createElement('td');
      var radio = DN.h('input', { type: 'radio', name: radioName, value: String(r.id) });
      if (i === 0) radio.checked = true;   // 默认首条为存活
      rd.appendChild(radio); tr.appendChild(rd);
      showAttrs.forEach(function (a) { var td = document.createElement('td'); var v = r._vals ? r._vals[a.attrCode] : null; var s = (v == null || v === '') ? '-' : String(v); if (s.length > 40) { td.textContent = s.slice(0, 40) + '…'; td.title = s; } else { td.textContent = s; } tr.appendChild(td); });
      var st = document.createElement('td'); st.appendChild(r.status === 'active' ? DN.pill('已生效', 'ok') : DN.pill('草稿', 'warn')); tr.appendChild(st);
      var sc = document.createElement('td'); sc.textContent = r.sourceSystem || '-'; tr.appendChild(sc);
      var ve = document.createElement('td'); ve.textContent = 'v' + (r.version || 1); tr.appendChild(ve);
      var up = document.createElement('td'); up.appendChild(DN.timeAgo(r.updatedAt)); tr.appendChild(up);
      // 下钻：查看该记录 → 黄金记录模块并自动打开其编辑（带选中实体）
      var opTd = document.createElement('td');
      opTd.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '查看记录', style: 'color:var(--primary)', title: '在黄金记录中打开该记录', onclick: (function (rec) { return function () { mdmGoModule('goldenrecord', { editId: rec.id, entityId: _ddEntity.id }); }; })(r) }));
      tr.appendChild(opTd);
      tb.appendChild(tr);
    });
    tbl.innerHTML = thead; tbl.appendChild(tb);
    var wrap = DN.h('div', { class: 'gov-tbl-wrap' }); wrap.appendChild(tbl);
    card.body.appendChild(wrap);

    var footer = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;margin-top:12px' });
    footer.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0;flex:1', text: '选定一条为存活记录，其余将被合并（置为停用）。' }));
    var mergeBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '合并该簇', 'data-perm': 'mdm:manage' });
    mergeBtn.onclick = function () {
      if (mergeBtn._busy) return;
      var checked = card.el.querySelector('input[name="' + radioName + '"]:checked');
      if (!checked) { DN.toast('请先选定存活记录', 'warn'); return; }
      var survivorId = Number(checked.value);
      var mergedIds = recs.map(function (r) { return r.id; }).filter(function (id) { return id !== survivorId; });
      if (!mergedIds.length) { DN.toast('该簇只有 1 条记录，无需合并', 'warn'); return; }
      DN.confirm('确认合并？保留 1 条存活记录，其余 ' + mergedIds.length + ' 条将置为停用。', { title: '合并确认', danger: true }).then(function (ok) {
        if (!ok) return;
        mergeBtn._busy = true; mergeBtn.textContent = '合并中...'; mergeBtn.style.pointerEvents = 'none';
        DN.post('/api/mdm/match/merge', { survivorId: survivorId, mergedIds: mergedIds }).then(function (res) {
          var ap = (res && res.survivorshipApplied) || [];
          DN.toast('已合并：保留 1 条，停用 ' + ((res && res.mergedCount) || mergedIds.length) + ' 条' + (ap.length ? '；存活性规则组合 ' + ap.length + ' 个字段：' + ap.join('，') : ''), 'ok');
          loadDedup(box);
        }).catch(function (e) { DN.toast(errMsg(e) || '合并失败', 'err'); mergeBtn._busy = false; mergeBtn.textContent = '合并该簇'; mergeBtn.style.pointerEvents = ''; });
      });
    };
    footer.appendChild(mergeBtn);
    card.body.appendChild(footer);
    return card.el;
  }

  // ===================== 数据管家工作台（待办聚合） =====================
  window.MDM_RENDERERS.steward = function (c) {
    var statBox = DN.h('div', { id: 'stwStats' }); statBox.appendChild(DN.skeleton(2)); c.appendChild(statBox);
    var card = DN.card({ title: '管家待办清单', icon: 'inbox', actions: DN.h('a', { class: 'btn btn-sm btn-ghost', href: 'javascript:void(0)', text: '质量监控', onclick: function () { window.mdmGoModule('quality'); } }) });
    card.body.appendChild(DN.skeleton(3));
    c.appendChild(card.el);

    DN.get('/api/mdm/steward/overview').then(function (d) {
      d = d || {};
      statBox.innerHTML = '';
      statBox.appendChild(DN.statRow([
        { icon: 'clock', label: '待复核草稿', value: d.totalDraft || 0, tone: (d.totalDraft ? 'warn' : 'ok'), title: '进入黄金记录处理', onClick: function () { mdmGoModule('goldenrecord'); } },
        { icon: 'alert', label: '待去重重复簇', value: d.totalDupClusters || 0, tone: (d.totalDupClusters ? 'warn' : 'ok'), title: '进入匹配去重处理', onClick: function () { mdmGoModule('dedup'); } },
        { icon: 'check', label: '已生效记录', value: d.totalActive || 0, tone: 'ok' },
        { icon: 'shield', label: '有待办实体', value: d.pendingEntityCount || 0, sub: '共 ' + (d.entityCount || 0) + ' 实体' }
      ]));
      card.body.innerHTML = '';
      var todos = d.todos || [];
      if (!todos.length) { card.body.appendChild(DN.empty('管家工作台清爽，暂无待处理事项 ✓', 'check')); return; }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'entityName', label: '实体', render: function (r) { return (r.domainName ? r.domainName + ' / ' : '') + r.entityName; } },
          { key: 'draftCount', label: '待复核草稿', align: 'right', sortable: true, render: function (r) {
              if (!r.draftCount) return DN.h('span', { text: '0', style: 'color:var(--text-muted)' });
              return DN.h('a', { href: 'javascript:void(0)', style: 'color:var(--primary);font-weight:600', text: String(r.draftCount) + ' 去复核', title: '前往黄金记录复核草稿', onclick: function () { mdmGoModule('goldenrecord', { entityId: r.entityId }); } });
            } },
          { key: 'dupClusterCount', label: '待去重重复簇', align: 'right', sortable: true, render: function (r) {
              if (!r.dupClusterCount) return DN.h('span', { text: '0', style: 'color:var(--text-muted)' });
              return DN.h('a', { href: 'javascript:void(0)', style: 'color:var(--primary);font-weight:600', text: r.dupClusterCount + ' 簇/' + r.dupRecordCount + ' 条 去去重', title: '前往匹配去重处理', onclick: function () { mdmGoModule('dedup', { entityId: r.entityId }); } });
            } },
          { key: 'activeCount', label: '已生效', align: 'right', sortable: true, render: function (r) { return String(r.activeCount); } },
          { key: 'pendingTotal', label: '待办合计', align: 'right', sortable: true, render: function (r) { return DN.pill(String(r.pendingTotal), r.pendingTotal > 0 ? 'warn' : 'ok'); } }
        ],
        rows: todos, pageSize: 20, searchKeys: ['entityName', 'domainName'], searchPlaceholder: '搜索实体/域', exportName: '管家待办'
      }));
    }).catch(function (e) {
      statBox.innerHTML = ''; card.body.innerHTML = '';
      card.body.appendChild(DN.errorBox('工作台加载失败: ' + errMsg(e), function () { c.innerHTML = ''; MDM_RENDERERS.steward(c); }));
    });
  };

  // ===================== 交叉引用(XREF) =====================
  var _xrEntity = null, _xrGoldens = [];

  window.MDM_RENDERERS.xref = function (c) {
    var ctx = window.__mdmCtx || {};
    var selWrap = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:14px' });
    selWrap.appendChild(DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '选择实体：' }));
    var entSel = DN.h('select', { class: 'dn-form-select', style: 'min-width:240px' });
    entSel.appendChild(DN.h('option', { value: '', text: '加载中…' }));
    selWrap.appendChild(entSel);
    c.appendChild(selWrap);
    var box = DN.h('div', { id: 'xrBox' });
    c.appendChild(box);

    getEntities().then(function (ents) {
      ents = ents || [];
      entSel.innerHTML = '';
      if (!ents.length) { entSel.appendChild(DN.h('option', { value: '', text: '(暂无实体)' })); box.appendChild(DN.empty('请先创建实体与黄金记录', 'layers')); return; }
      entSel.appendChild(DN.h('option', { value: '', text: '(请选择实体)' }));
      ents.forEach(function (e) { entSel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + e.entityName })); });
      // 深链：ctx.entityId 指定则选中该实体，否则默认第一个（与黄金记录/去重一致）
      var ctxEnt = ctx.entityId != null ? ents.filter(function (e) { return String(e.id) === String(ctx.entityId); })[0] : null;
      var initEnt = ctxEnt || ents[0];
      entSel.value = String(initEnt.id); _xrEntity = initEnt;
      loadXref(box);
      entSel.onchange = function () { _xrEntity = ents.filter(function (e) { return String(e.id) === entSel.value; })[0] || null; box.innerHTML = ''; if (_xrEntity) loadXref(box); else box.appendChild(DN.empty('请选择一个实体以管理交叉引用', 'list')); };
    }).catch(function (e) { entSel.innerHTML = ''; entSel.appendChild(DN.h('option', { value: '', text: '(加载失败)' })); box.appendChild(DN.errorBox('实体加载失败: ' + errMsg(e), function () { invalidateEntities(); c.innerHTML = ''; MDM_RENDERERS.xref(c); })); });
  };

  function loadXref(box) {
    if (!_xrEntity) return;
    box.innerHTML = ''; box.appendChild(DN.skeleton(3));
    Promise.all([
      DN.get('/api/mdm/xref/stats?entityId=' + encodeURIComponent(_xrEntity.id)),
      DN.get('/api/mdm/xref/by-entity?entityId=' + encodeURIComponent(_xrEntity.id)),
      DN.get('/api/mdm/golden/list?entityId=' + encodeURIComponent(_xrEntity.id))
    ]).then(function (r) {
      var stats = r[0] || {}, xrefs = r[1] || [];
      _xrGoldens = r[2] || [];
      box.innerHTML = '';
      box.appendChild(DN.statRow([
        { icon: 'list', label: '映射数', value: stats.xrefCount || 0 },
        { icon: 'grid', label: '源系统数', value: stats.sourceSystemCount || 0, sub: (stats.sourceSystems || []).join(' / ') },
        { icon: 'shield', label: '已映射黄金记录', value: stats.mappedGoldenCount || 0 }
      ]));

      // 反查工具
      var resolveCard = DN.card({ title: '按源系统ID反查黄金记录', icon: 'search' });
      var sysIn = DN.h('input', { class: 'dn-form-input', style: 'min-width:120px', placeholder: '源系统 如 CRM' });
      var idIn = DN.h('input', { class: 'dn-form-input', style: 'min-width:160px', placeholder: '源系统业务ID' });
      var rbtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '反查' });
      var rout = DN.h('div', { style: 'margin-top:10px' });
      rbtn.onclick = function () {
        if (rbtn._busy) return;
        if (!sysIn.value.trim() || !idIn.value.trim()) { DN.toast('请填写源系统与源ID', 'warn'); return; }
        rbtn._busy = true; rbtn.textContent = '反查中...'; rbtn.style.pointerEvents = 'none';
        rout.innerHTML = ''; rout.appendChild(DN.skeleton(1));
        DN.get('/api/mdm/xref/resolve?sourceSystem=' + encodeURIComponent(sysIn.value.trim()) + '&sourceId=' + encodeURIComponent(idIn.value.trim())).then(function (d) {
          rbtn._busy = false; rbtn.textContent = '反查'; rbtn.style.pointerEvents = '';
          rout.innerHTML = '';
          if (!d || !d.found) { rout.appendChild(DN.empty('未找到该源标识对应的黄金记录', 'alert')); return; }
          var dj = d.dataJson || '';
          var djShow = dj.length > 120 ? dj.slice(0, 120) + '…' : dj;
          rout.appendChild(DN.h('div', { style: 'padding:10px 12px;border-radius:var(--radius-lg);background:rgba(47,158,68,.08);border:1px solid rgba(47,158,68,.25);font-size:13px' }, [
            DN.h('div', { style: 'font-weight:600;margin-bottom:4px' }, [DN.h('span', { text: '✓ 命中黄金记录：' + (d.bizKey || ('#' + d.goldenRecordId)) }), DN.h('span', { style: 'margin-left:8px' }, [DN.pill(d.status === 'active' ? '已生效' : (d.status === 'inactive' ? '已停用' : '草稿'), d.status === 'active' ? 'ok' : 'warn')])]),
            DN.h('div', { class: 'gov-desc', style: 'margin:0', text: 'MDM ID: ' + d.goldenRecordId + ' · 属性: ' + djShow, title: dj })
          ]));
        }).catch(function (e) { rbtn._busy = false; rbtn.textContent = '反查'; rbtn.style.pointerEvents = ''; rout.innerHTML = ''; rout.appendChild(DN.errorBox('反查失败: ' + errMsg(e))); });
      };
      var rrow = DN.h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap;align-items:center' }, [sysIn, idIn, rbtn]);
      resolveCard.body.appendChild(rrow);
      resolveCard.body.appendChild(rout);
      box.appendChild(resolveCard.el);

      // 映射表
      var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增映射', 'data-perm': 'mdm:manage', onclick: function () { xrefForm(null, box); } });
      var card = DN.card({ title: '交叉引用映射 · ' + _xrEntity.entityName, icon: 'list', actions: addBtn });
      if (!xrefs.length) { card.body.appendChild(DN.empty('暂无交叉引用，点右上角“新增映射”为黄金记录关联源系统ID', 'list')); box.appendChild(card.el); return; }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'bizKey', label: '黄金记录', copyable: true, render: function (r) { return r.bizKey || ('#' + r.goldenRecordId); } },
          { key: 'sourceSystem', label: '源系统', render: function (r) { return DN.pill(r.sourceSystem, 'info'); } },
          { key: 'sourceId', label: '源系统ID', copyable: true, render: function (r) { return r.sourceId; } },
          { key: 'isPrimary', label: '主源', render: function (r) { return r.isPrimary === 1 ? DN.pill('主源', 'ok') : DN.h('span', { text: '-', style: 'color:var(--text-muted)' }); } },
          { key: 'matchScore', label: '置信度', align: 'right', render: function (r) { return r.matchScore == null ? '-' : (r.matchScore + '%'); } },
          { key: '_op', label: '操作', render: function (r) {
              var w = DN.h('span', { style: 'display:inline-flex;gap:9px' });
              w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary)', 'data-perm': 'mdm:manage', onclick: function () { xrefForm(r, box); } }));
              var delA = DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error)', 'data-perm': 'mdm:manage', onclick: function () { DN.confirm('删除映射 ' + r.sourceSystem + ':' + r.sourceId + '？', { title: '删除确认', danger: true }).then(function (ok) { if (!ok) return; busyLink(delA, '删除中...', function () { return DN.del('/api/mdm/xref/' + r.id).then(function () { DN.toast('已删除', 'ok'); loadXref(box); }).catch(function (e) { DN.toast(errMsg(e) || '删除失败', 'err'); }); }); }); } });
              w.appendChild(delA);
              return w;
            } }
        ],
        rows: xrefs, pageSize: 20, searchKeys: ['bizKey', 'sourceSystem', 'sourceId'], searchPlaceholder: '搜索黄金记录/源系统/源ID', exportName: '交叉引用_' + _xrEntity.entityCode
      }));
      box.appendChild(card.el);
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.errorBox('加载失败: ' + errMsg(e), function () { loadXref(box); })); });
  }

  function xrefForm(row, box) {
    if (!_xrGoldens.length) { DN.toast('该实体尚无黄金记录，请先创建黄金记录', 'warn'); return; }
    var isEdit = !!row; row = row || {};
    var gSel = DN.h('select', { class: 'dn-form-select', style: 'width:100%' });
    _xrGoldens.forEach(function (g) { var op = DN.h('option', { value: g.id, text: (g.bizKey || ('#' + g.id)) + ' (' + g.status + ')' }); if (String(row.goldenRecordId) === String(g.id)) op.selected = true; gSel.appendChild(op); });
    var fSys = inp(row.sourceSystem, '如 CRM / ERP');
    var fId = inp(row.sourceId, '源系统业务ID');
    var fScore = inp(row.matchScore, '置信度 0-100');
    var cPrimary = chk('设为主源', row.isPrimary === 1);
    var body = DN.h('div', {});
    body.appendChild(field('黄金记录', gSel));
    body.appendChild(field('源系统', fSys));
    body.appendChild(field('源系统业务ID', fId, '源系统+源ID 全局唯一'));
    body.appendChild(field('置信度(%)', fScore));
    body.appendChild(field('主源', cPrimary));
    var footer = DN.h('div', { style: 'text-align:right;margin-top:10px' });
    var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '保存', 'data-perm': 'mdm:manage' });
    footer.appendChild(save); body.appendChild(footer);
    var dr = DN.drawer((isEdit ? '编辑' : '新增') + '交叉引用', body);
    save.onclick = function () {
      if (save._busy) return;
      var goldenId = Number(gSel.value);
      if (!goldenId) { DN.toast('请选择黄金记录', 'err'); return; }
      var score = null;
      if (fScore.value.trim()) {
        score = parseFloat(fScore.value);
        if (isNaN(score) || score < 0 || score > 100) { DN.toast('置信度需为 0-100 的数字', 'err'); return; }
      }
      var payload = { id: row.id, goldenRecordId: goldenId, sourceSystem: fSys.value.trim(), sourceId: fId.value.trim(), matchScore: score, isPrimary: cPrimary._cb.checked ? 1 : 0 };
      if (!payload.sourceSystem) { DN.toast('请填写源系统', 'err'); return; }
      if (!payload.sourceId) { DN.toast('请填写源系统业务ID', 'err'); return; }
      save._busy = true; save.textContent = '保存中...'; save.style.pointerEvents = 'none';
      DN.post('/api/mdm/xref/save', payload).then(function () { DN.toast('已保存', 'ok'); dr.close(); loadXref(box); })
        .catch(function (e) { DN.toast(errMsg(e) || '保存失败', 'err'); save._busy = false; save.textContent = '保存'; save.style.pointerEvents = ''; });
    };
    DN.enterSubmit(body);
  }
})();
