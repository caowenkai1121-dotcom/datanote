/* 主数据管理(MDM) 模块 —— 域/实体/属性建模 + 总览。
   复用 DN 共享套件(dn-common.js)与 gov-modern.css(:is(#govModuleContent,#mdmModuleContent) 作用域)，
   与数据治理模块保持完全一致的 UI 风格与交互。
   渲染器注册到 window.MDM_RENDERERS；侧栏/切换在 workspace.html 的 initMdmCenter/switchMdmModule。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  // ===================== 主数据总览 =====================
  window.MDM_RENDERERS.overview = function (c) {
    var statBox = DN.h('div', { id: 'mdmStats' });
    statBox.appendChild(DN.skeleton(2));
    c.appendChild(statBox);

    var grid = DN.h('div', { class: 'gov-grid' });
    var catCard = DN.card({ title: '主数据域分布（按业务类别）', icon: 'grid' });
    catCard.body.appendChild(DN.skeleton(2));
    var scaleCard = DN.card({ title: '各域建模规模（实体数）', icon: 'layers' });
    scaleCard.body.appendChild(DN.skeleton(2));
    grid.appendChild(catCard.el); grid.appendChild(scaleCard.el);
    c.appendChild(grid);

    DN.get('/api/mdm/overview').then(function (d) {
      d = d || {};
      statBox.innerHTML = '';
      statBox.appendChild(DN.statRow([
        { icon: 'grid', label: '主数据域', value: d.domainCount || 0, sub: '已启用 ' + (d.enabledDomainCount || 0), title: '进入域与实体建模', onClick: function () { mdmGoModule('modeling'); } },
        { icon: 'layers', label: '实体', value: d.entityCount || 0, title: '进入域与实体建模', onClick: function () { mdmGoModule('modeling'); } },
        { icon: 'list', label: '属性', value: d.attributeCount || 0 },
        { icon: 'shield', label: '黄金记录', value: d.goldenCount || 0, sub: '已生效 ' + (d.goldenActiveCount || 0), tone: (d.goldenCount ? 'ok' : 'muted'), title: '进入黄金记录', onClick: function () { mdmGoModule('goldenrecord'); } }
      ]));

      // 按类别分布
      catCard.body.innerHTML = '';
      var byCat = d.byCategory || {};
      var catKeys = Object.keys(byCat);
      if (!catKeys.length) { catCard.body.appendChild(DN.empty('暂无主数据域，请前往“域与实体建模”创建', 'grid')); }
      else {
        catCard.body.appendChild(DN.bars(catKeys.map(function (k) {
          return { label: k, value: Number(byCat[k]) || 0, tone: 'info', display: String(byCat[k]) };
        })));
      }

      // 各域规模
      scaleCard.body.innerHTML = '';
      var scale = d.domainScale || [];
      if (!scale.length) { scaleCard.body.appendChild(DN.empty('暂无数据', 'layers')); }
      else {
        scaleCard.body.appendChild(DN.table({
          columns: [
            { key: 'domainName', label: '主数据域', render: function (r) { return r.domainName || '-'; } },
            { key: 'category', label: '类别', render: function (r) { return r.category ? DN.pill(r.category, 'info') : '-'; } },
            { key: 'entityCount', label: '实体数', align: 'right', sortable: true, render: function (r) { return String(r.entityCount); } }
          ],
          rows: scale, pageSize: 8, search: false, empty: '暂无数据'
        }));
      }
    }).catch(function (e) {
      statBox.innerHTML = '';
      statBox.appendChild(DN.errorBox('总览加载失败: ' + (e && e.message ? e.message : e), function () {
        c.innerHTML = ''; MDM_RENDERERS.overview(c);
      }));
    });
  };

  // ===================== 域与实体建模（域 → 实体 → 属性 三级） =====================
  var _selDomain = null;   // 当前选中的域
  var _selEntity = null;   // 当前选中的实体

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
    return DN.h('a', { href: 'javascript:void(0)', text: text, style: 'color:var(--primary,#1890ff)', onclick: onclick });
  }

  // ---- 第1级：域管理 ----
  function renderDomainLevel(c) {
    var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建域', onclick: function () { domainForm(null, c); } });
    var card = DN.card({ title: '主数据域', icon: 'grid', actions: addBtn });
    card.body.appendChild(DN.skeleton(3));
    c.appendChild(card.el);
    DN.get('/api/mdm/domains').then(function (rows) {
      rows = rows || [];
      card.body.innerHTML = '';
      if (!rows.length) { card.body.appendChild(DN.empty('暂无主数据域，点右上角“新建域”开始建模', 'grid')); return; }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'domainCode', label: '域编码', copyable: true, render: function (r) { return r.domainCode; } },
          { key: 'domainName', label: '域名称', render: function (r) {
              return DN.h('a', { href: 'javascript:void(0)', text: r.domainName, style: 'color:var(--primary,#1890ff)', title: '管理该域下实体', onclick: function () { _selDomain = r; _selEntity = null; renderModeling(c); } });
            } },
          { key: 'category', label: '类别', render: function (r) { return r.category ? DN.pill(r.category, 'info') : '-'; } },
          { key: 'owner', label: '负责人', render: function (r) { return r.owner || '-'; } },
          { key: 'entityCount', label: '实体数', align: 'right', sortable: true, render: function (r) { return String(r.entityCount == null ? 0 : r.entityCount); } },
          { key: 'status', label: '状态', render: function (r) { return r.status === 0 ? DN.pill('停用', 'muted') : DN.pill('启用', 'ok'); } },
          { key: '_op', label: '操作', render: function (r) {
              var box = DN.h('span', { style: 'display:inline-flex;gap:10px' });
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '实体', style: 'color:var(--primary,#1890ff)', onclick: function () { _selDomain = r; _selEntity = null; renderModeling(c); } }));
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#1890ff)', onclick: function () { domainForm(r, c); } }));
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:#ff4d4f', onclick: function () { delConfirm('删除域「' + r.domainName + '」？将级联删除其下所有实体与属性。', '/api/mdm/domain/' + r.id, c); } }));
              return box;
            } }
        ],
        rows: rows, pageSize: 15, searchKeys: ['domainCode', 'domainName', 'category', 'owner'], searchPlaceholder: '搜索域编码/名称/类别/负责人', exportName: '主数据域'
      }));
    }).catch(function (e) {
      card.body.innerHTML = '';
      card.body.appendChild(DN.errorBox('加载失败: ' + e.message, function () { renderModeling(c); }));
    });
  }

  // ---- 第2级：实体管理 ----
  function renderEntityLevel(c) {
    var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建实体', onclick: function () { entityForm(null, c); } });
    var card = DN.card({ title: '实体 · ' + _selDomain.domainName, icon: 'layers', actions: addBtn });
    card.body.appendChild(DN.skeleton(3));
    c.appendChild(card.el);
    DN.get('/api/mdm/entities?domainId=' + encodeURIComponent(_selDomain.id)).then(function (rows) {
      rows = rows || [];
      card.body.innerHTML = '';
      if (!rows.length) { card.body.appendChild(DN.empty('该域下暂无实体，点右上角“新建实体”创建', 'layers')); return; }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'entityCode', label: '实体编码', copyable: true, render: function (r) { return r.entityCode; } },
          { key: 'entityName', label: '实体名称', render: function (r) {
              return DN.h('a', { href: 'javascript:void(0)', text: r.entityName, style: 'color:var(--primary,#1890ff)', title: '管理该实体的属性', onclick: function () { _selEntity = r; renderModeling(c); } });
            } },
          { key: 'attrCount', label: '属性数', align: 'right', sortable: true, render: function (r) { return String(r.attrCount == null ? 0 : r.attrCount); } },
          { key: 'description', label: '描述', render: function (r) { return r.description || '-'; } },
          { key: 'status', label: '状态', render: function (r) { return r.status === 0 ? DN.pill('停用', 'muted') : DN.pill('启用', 'ok'); } },
          { key: '_op', label: '操作', render: function (r) {
              var box = DN.h('span', { style: 'display:inline-flex;gap:10px' });
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '属性', style: 'color:var(--primary,#1890ff)', onclick: function () { _selEntity = r; renderModeling(c); } }));
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#1890ff)', onclick: function () { entityForm(r, c); } }));
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:#ff4d4f', onclick: function () { delConfirm('删除实体「' + r.entityName + '」？将级联删除其属性。', '/api/mdm/entity/' + r.id, c); } }));
              return box;
            } }
        ],
        rows: rows, pageSize: 15, searchKeys: ['entityCode', 'entityName', 'description'], searchPlaceholder: '搜索实体编码/名称/描述', exportName: '实体_' + _selDomain.domainCode
      }));
    }).catch(function (e) {
      card.body.innerHTML = '';
      card.body.appendChild(DN.errorBox('加载失败: ' + e.message, function () { renderModeling(c); }));
    });
  }

  // ---- 第3级：属性管理 ----
  var DATA_TYPES = ['STRING', 'INT', 'DECIMAL', 'DATE', 'BOOLEAN', 'ENUM', 'REFERENCE'];
  function renderAttributeLevel(c) {
    var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建属性', onclick: function () { attrForm(null, c); } });
    var card = DN.card({ title: '属性 · ' + _selEntity.entityName, icon: 'list', actions: addBtn });
    card.body.appendChild(DN.skeleton(3));
    c.appendChild(card.el);
    DN.get('/api/mdm/attributes?entityId=' + encodeURIComponent(_selEntity.id)).then(function (rows) {
      rows = rows || [];
      card.body.innerHTML = '';
      card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 10px', text: '关键字段(可用于匹配去重)、必填、唯一等属性元数据，是黄金记录生成的基础。' }));
      if (!rows.length) { card.body.appendChild(DN.empty('该实体下暂无属性，点右上角“新建属性”创建', 'list')); return; }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'attrCode', label: '属性编码', copyable: true, render: function (r) { return r.attrCode; } },
          { key: 'attrName', label: '属性名称', render: function (r) { return r.attrName; } },
          { key: 'dataType', label: '数据类型', render: function (r) { return DN.pill(r.dataType || 'STRING', 'info'); } },
          { key: 'lengthLimit', label: '长度', align: 'right', render: function (r) { return r.lengthLimit == null ? '-' : String(r.lengthLimit); } },
          { key: '_flags', label: '约束', render: function (r) {
              var box = DN.h('span', { style: 'display:inline-flex;gap:4px;flex-wrap:wrap' });
              if (r.isKey === 1) box.appendChild(DN.pill('关键', 'warn'));
              if (r.required === 1) box.appendChild(DN.pill('必填', 'err'));
              if (r.isUnique === 1) box.appendChild(DN.pill('唯一', 'info'));
              if (!box.childNodes.length) box.appendChild(DN.h('span', { text: '-', style: 'color:var(--text-muted)' }));
              return box;
            } },
          { key: 'description', label: '描述', render: function (r) { return r.description || '-'; } },
          { key: '_op', label: '操作', render: function (r) {
              var box = DN.h('span', { style: 'display:inline-flex;gap:10px' });
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#1890ff)', onclick: function () { attrForm(r, c); } }));
              box.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:#ff4d4f', onclick: function () { delConfirm('删除属性「' + r.attrName + '」？', '/api/mdm/attribute/' + r.id, c); } }));
              return box;
            } }
        ],
        rows: rows, pageSize: 20, searchKeys: ['attrCode', 'attrName', 'dataType', 'description'], searchPlaceholder: '搜索属性编码/名称/类型', exportName: '属性_' + _selEntity.entityCode
      }));
    }).catch(function (e) {
      card.body.innerHTML = '';
      card.body.appendChild(DN.errorBox('加载失败: ' + e.message, function () { renderModeling(c); }));
    });
  }

  // ===================== 表单（DN.drawer） =====================
  function field(label, input, hint) {
    var row = DN.h('div', { class: 'ds-form-row', style: 'display:flex;flex-direction:column;gap:4px;margin-bottom:12px' });
    row.appendChild(DN.h('label', { text: label, style: 'font-size:12px;color:var(--text-muted)' }));
    row.appendChild(input);
    if (hint) row.appendChild(DN.h('div', { style: 'font-size:11px;color:var(--text-muted)', text: hint }));
    return row;
  }
  function inp(val, ph) { return DN.h('input', { class: 'iw-form-select', style: 'width:100%', value: val == null ? '' : String(val), placeholder: ph || '' }); }
  function sel(opts, val) {
    var s = DN.h('select', { class: 'iw-form-select', style: 'width:100%' });
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
    body.appendChild(field('域编码', fCode, '唯一，建议大写英文'));
    body.appendChild(field('域名称', fName));
    body.appendChild(field('业务类别', fCat));
    body.appendChild(field('负责人', fOwner));
    body.appendChild(field('描述', fDesc));
    body.appendChild(field('状态', fStatus));
    var footer = DN.h('div', { style: 'text-align:right;margin-top:10px' });
    var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '保存' });
    footer.appendChild(save); body.appendChild(footer);
    var dr = DN.drawer((isEdit ? '编辑' : '新建') + '主数据域', body);
    save.onclick = function () {
      var payload = { id: row.id, domainCode: fCode.value.trim(), domainName: fName.value.trim(), category: fCat.value, owner: fOwner.value.trim(), description: fDesc.value.trim(), status: Number(fStatus.value) };
      if (!payload.domainCode) { DN.toast('请填写域编码', 'err'); return; }
      if (!payload.domainName) { DN.toast('请填写域名称', 'err'); return; }
      save.textContent = '保存中...'; save.style.pointerEvents = 'none';
      DN.post('/api/mdm/domain/save', payload).then(function () { DN.toast('已保存', 'ok'); dr.close(); renderModeling(c); })
        .catch(function (e) { DN.toast(e.message || '保存失败', 'err'); save.textContent = '保存'; save.style.pointerEvents = ''; });
    };
    DN.enterSubmit(body);
  }

  function entityForm(row, c) {
    var isEdit = !!row; row = row || {};
    var fCode = inp(row.entityCode, '如 customer');
    var fName = inp(row.entityName, '如 客户');
    var fDesc = inp(row.description, '描述');
    var fStatus = sel([[1, '启用'], [0, '停用']], row.status == null ? 1 : row.status);
    var body = DN.h('div', {});
    body.appendChild(field('所属域', DN.h('input', { class: 'iw-form-select', style: 'width:100%', value: _selDomain.domainName, disabled: 'disabled' })));
    body.appendChild(field('实体编码', fCode, '同域内唯一'));
    body.appendChild(field('实体名称', fName));
    body.appendChild(field('描述', fDesc));
    body.appendChild(field('状态', fStatus));
    var footer = DN.h('div', { style: 'text-align:right;margin-top:10px' });
    var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '保存' });
    footer.appendChild(save); body.appendChild(footer);
    var dr = DN.drawer((isEdit ? '编辑' : '新建') + '实体', body);
    save.onclick = function () {
      var payload = { id: row.id, domainId: _selDomain.id, entityCode: fCode.value.trim(), entityName: fName.value.trim(), description: fDesc.value.trim(), status: Number(fStatus.value) };
      if (!payload.entityCode) { DN.toast('请填写实体编码', 'err'); return; }
      if (!payload.entityName) { DN.toast('请填写实体名称', 'err'); return; }
      save.textContent = '保存中...'; save.style.pointerEvents = 'none';
      DN.post('/api/mdm/entity/save', payload).then(function () { DN.toast('已保存', 'ok'); dr.close(); renderModeling(c); })
        .catch(function (e) { DN.toast(e.message || '保存失败', 'err'); save.textContent = '保存'; save.style.pointerEvents = ''; });
    };
    DN.enterSubmit(body);
  }

  function attrForm(row, c) {
    var isEdit = !!row; row = row || {};
    var fCode = inp(row.attrCode, '如 cust_name');
    var fName = inp(row.attrName, '如 客户名称');
    var fType = sel(DATA_TYPES, row.dataType || 'STRING');
    var fLen = inp(row.lengthLimit, '如 128');
    var fEnum = inp(row.enumValues, 'ENUM 候选值，逗号分隔');
    var fDefault = inp(row.defaultValue, '默认值');
    var fDesc = inp(row.description, '描述');
    var fSort = inp(row.sortOrder == null ? '' : row.sortOrder, '排序');
    var cKey = chk('关键字段(匹配用)', row.isKey === 1);
    var cReq = chk('必填', row.required === 1);
    var cUniq = chk('唯一', row.isUnique === 1);
    var body = DN.h('div', {});
    body.appendChild(field('所属实体', DN.h('input', { class: 'iw-form-select', style: 'width:100%', value: _selEntity.entityName, disabled: 'disabled' })));
    body.appendChild(field('属性编码', fCode, '同实体内唯一'));
    body.appendChild(field('属性名称', fName));
    body.appendChild(field('数据类型', fType));
    body.appendChild(field('长度限制', fLen));
    body.appendChild(field('枚举候选值', fEnum, '仅 ENUM 类型时填写'));
    body.appendChild(field('默认值', fDefault));
    body.appendChild(field('约束', DN.h('div', { style: 'display:flex;flex-wrap:wrap;align-items:center' }, [cKey, cReq, cUniq])));
    body.appendChild(field('描述', fDesc));
    body.appendChild(field('排序', fSort));
    var footer = DN.h('div', { style: 'text-align:right;margin-top:10px' });
    var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '保存' });
    footer.appendChild(save); body.appendChild(footer);
    var dr = DN.drawer((isEdit ? '编辑' : '新建') + '属性', body);
    save.onclick = function () {
      var payload = {
        id: row.id, entityId: _selEntity.id, attrCode: fCode.value.trim(), attrName: fName.value.trim(),
        dataType: fType.value, lengthLimit: fLen.value.trim() ? parseInt(fLen.value, 10) : null,
        enumValues: fEnum.value.trim(), defaultValue: fDefault.value.trim(), description: fDesc.value.trim(),
        sortOrder: fSort.value.trim() ? parseInt(fSort.value, 10) : 0,
        isKey: cKey._cb.checked ? 1 : 0, required: cReq._cb.checked ? 1 : 0, isUnique: cUniq._cb.checked ? 1 : 0
      };
      if (!payload.attrCode) { DN.toast('请填写属性编码', 'err'); return; }
      if (!payload.attrName) { DN.toast('请填写属性名称', 'err'); return; }
      save.textContent = '保存中...'; save.style.pointerEvents = 'none';
      DN.post('/api/mdm/attribute/save', payload).then(function () { DN.toast('已保存', 'ok'); dr.close(); renderModeling(c); })
        .catch(function (e) { DN.toast(e.message || '保存失败', 'err'); save.textContent = '保存'; save.style.pointerEvents = ''; });
    };
    DN.enterSubmit(body);
  }

  function delConfirm(msg, url, c) {
    if (!window.confirm(msg)) return;
    DN.del(url).then(function () { DN.toast('已删除', 'ok'); renderModeling(c); })
      .catch(function (e) { DN.toast(e.message || '删除失败', 'err'); });
  }

  // ===================== 黄金记录（按实体 schema 动态生成表单） =====================
  var _grEntity = null;   // 当前选中实体
  var _grAttrs = [];      // 当前实体属性 schema
  var _grFilter = '';     // 状态筛选

  window.MDM_RENDERERS.goldenrecord = function (c) {
    var bar = DN.h('div', { class: 'gov-desc', style: 'margin-bottom:14px', text: '黄金记录是某实体的单一可信版本，属性按实体建模 schema 动态录入；支持草稿/发布状态流转。' });
    c.appendChild(bar);
    var selWrap = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:14px' });
    selWrap.appendChild(DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '选择实体：' }));
    var entSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:240px' });
    entSel.appendChild(DN.h('option', { value: '', text: '加载中…' }));
    selWrap.appendChild(entSel);
    c.appendChild(selWrap);
    var box = DN.h('div', { id: 'grBox' });
    c.appendChild(box);

    DN.get('/api/mdm/entities').then(function (ents) {
      ents = ents || [];
      entSel.innerHTML = '';
      if (!ents.length) { entSel.appendChild(DN.h('option', { value: '', text: '(暂无实体，请先在“域与实体建模”创建)' })); box.appendChild(DN.empty('请先在“域与实体建模”创建实体', 'layers')); return; }
      entSel.appendChild(DN.h('option', { value: '', text: '(请选择实体)' }));
      ents.forEach(function (e) { entSel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + e.entityName })); });
      // 默认选第一个
      entSel.value = String(ents[0].id);
      _grEntity = ents[0];
      loadGoldenForEntity(box);
      entSel.onchange = function () {
        _grEntity = ents.filter(function (e) { return String(e.id) === entSel.value; })[0] || null;
        box.innerHTML = '';
        if (_grEntity) loadGoldenForEntity(box);
      };
    }).catch(function (e) {
      box.appendChild(DN.errorBox('实体加载失败: ' + e.message, function () { c.innerHTML = ''; MDM_RENDERERS.goldenrecord(c); }));
    });
  };

  function loadGoldenForEntity(box) {
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
      var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建黄金记录', onclick: function () { goldenForm(null, box); } });
      var statusSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:120px' });
      [['', '全部状态'], ['active', '已生效'], ['draft', '草稿'], ['inactive', '已停用']].forEach(function (o) { statusSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
      statusSel.value = _grFilter;
      statusSel.onchange = function () { _grFilter = statusSel.value; loadGoldenList(listBox); };
      var card = DN.card({ title: '黄金记录 · ' + _grEntity.entityName, icon: 'shield', actions: addBtn });
      var listBox = DN.h('div', {});
      card.body.appendChild(DN.h('div', { style: 'margin-bottom:10px' }, [statusSel]));
      card.body.appendChild(listBox);
      box.appendChild(card.el);
      loadGoldenList(listBox);
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('加载失败: ' + e.message, function () { loadGoldenForEntity(box); }));
    });
  }

  function loadGoldenList(listBox) {
    listBox.innerHTML = ''; listBox.appendChild(DN.skeleton(3));
    var qs = '?entityId=' + encodeURIComponent(_grEntity.id) + (_grFilter ? '&status=' + _grFilter : '');
    DN.get('/api/mdm/golden/list' + qs).then(function (rows) {
      rows = rows || [];
      listBox.innerHTML = '';
      if (!rows.length) { listBox.appendChild(DN.empty('暂无黄金记录，点右上角“新建黄金记录”录入', 'shield')); return; }
      rows.forEach(function (r) { try { r._vals = JSON.parse(r.dataJson || '{}'); } catch (e) { r._vals = {}; } });
      // 动态列：业务主键 + 前若干关键/普通属性 + 状态 + 版本 + 操作
      var keyAttrs = _grAttrs.filter(function (a) { return a.isKey === 1 || a.isUnique === 1; });
      var showAttrs = (keyAttrs.length ? keyAttrs : _grAttrs).slice(0, 4);
      var cols = [];
      showAttrs.forEach(function (a) {
        cols.push({ key: 'a_' + a.attrCode, label: a.attrName, render: (function (code) { return function (r) { var v = r._vals[code]; return (v == null || v === '') ? '-' : String(v); }; })(a.attrCode) });
      });
      cols.push({ key: 'status', label: '状态', render: function (r) {
          var s = r.status; return s === 'active' ? DN.pill('已生效', 'ok') : s === 'inactive' ? DN.pill('已停用', 'muted') : DN.pill('草稿', 'warn');
        } });
      cols.push({ key: 'sourceSystem', label: '来源', render: function (r) { return r.sourceSystem ? DN.pill(r.sourceSystem, 'info') : '-'; } });
      cols.push({ key: 'version', label: '版本', align: 'right', render: function (r) { return 'v' + (r.version || 1); } });
      cols.push({ key: 'updatedAt', label: '更新', render: function (r) { return DN.timeAgo(r.updatedAt); } });
      cols.push({ key: '_op', label: '操作', render: function (r) {
          var w = DN.h('span', { style: 'display:inline-flex;gap:9px' });
          w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#1890ff)', onclick: function () { goldenForm(r, document.getElementById('grBox')); } }));
          if (r.status !== 'active') w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '发布', style: 'color:#389e0d', onclick: function () { goldenAction('/api/mdm/golden/' + r.id + '/publish', '已发布'); } }));
          if (r.status === 'active') w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '停用', style: 'color:#ad6800', onclick: function () { goldenAction('/api/mdm/golden/' + r.id + '/deactivate', '已停用'); } }));
          w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:#ff4d4f', onclick: function () { if (!window.confirm('删除黄金记录「' + (r.bizKey || r.id) + '」？')) return; DN.del('/api/mdm/golden/' + r.id).then(function () { DN.toast('已删除', 'ok'); reloadGolden(); }).catch(function (e) { DN.toast(e.message || '删除失败', 'err'); }); } }));
          return w;
        } });
      listBox.appendChild(DN.table({
        columns: cols, rows: rows, pageSize: 15,
        searchKeys: ['bizKey', 'sourceSystem'], searchPlaceholder: '搜索业务主键/来源', exportName: '黄金记录_' + _grEntity.entityCode,
        exportValue: function (r, k) { return r._vals && r._vals[k.replace('a_', '')] != null ? r._vals[k.replace('a_', '')] : undefined; }
      }));
    }).catch(function (e) {
      listBox.innerHTML = '';
      listBox.appendChild(DN.errorBox('加载失败: ' + e.message, function () { loadGoldenList(listBox); }));
    });
  }

  function reloadGolden() { var box = document.getElementById('grBox'); if (box) loadGoldenForEntity(box); }
  function goldenAction(url, okMsg) {
    DN.post(url).then(function () { DN.toast(okMsg, 'ok'); reloadGolden(); }).catch(function (e) { DN.toast(e.message || '操作失败', 'err'); });
  }

  // 按属性类型生成输入控件
  function grInput(attr, val) {
    var t = (attr.dataType || 'STRING').toUpperCase();
    if (t === 'ENUM') {
      var opts = (attr.enumValues || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean);
      return sel([['', '(请选择)']].concat(opts), val == null ? '' : val);
    }
    if (t === 'BOOLEAN') return sel([['', '(请选择)'], ['true', '是'], ['false', '否']], val == null ? '' : String(val));
    if (t === 'DATE') return DN.h('input', { type: 'date', class: 'iw-form-select', style: 'width:100%', value: val || '' });
    if (t === 'INT' || t === 'DECIMAL') return DN.h('input', { type: 'number', class: 'iw-form-select', style: 'width:100%', value: val == null ? '' : val, step: t === 'DECIMAL' ? 'any' : '1' });
    return inp(val, attr.description || '');
  }

  function goldenForm(row, box) {
    if (!_grAttrs.length) { DN.toast('该实体尚无属性定义，请先在“域与实体建模”补充属性', 'warn'); return; }
    var isEdit = !!row; row = row || {};
    var vals = {};
    try { vals = row.dataJson ? JSON.parse(row.dataJson) : (row._vals || {}); } catch (e) { vals = row._vals || {}; }
    var inputs = {};   // attrCode -> input element
    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 10px', text: '实体：' + _grEntity.entityName + '（按属性 schema 录入，标 * 为必填）' }));
    _grAttrs.forEach(function (a) {
      var input = grInput(a, vals[a.attrCode]);
      inputs[a.attrCode] = input;
      var label = a.attrName + (a.required === 1 ? ' *' : '') + (a.isKey === 1 ? '（关键）' : '');
      body.appendChild(field(label, input, a.dataType + (a.lengthLimit ? '(' + a.lengthLimit + ')' : '')));
    });
    var srcInput = inp(row.sourceSystem, '如 CRM / ERP');
    body.appendChild(field('来源系统', srcInput));
    var statusSel = sel([['draft', '草稿'], ['active', '已生效'], ['inactive', '已停用']], row.status || 'draft');
    body.appendChild(field('状态', statusSel));
    var footer = DN.h('div', { style: 'text-align:right;margin-top:10px' });
    var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '保存' });
    footer.appendChild(save); body.appendChild(footer);
    var dr = DN.drawer((isEdit ? '编辑' : '新建') + '黄金记录', body);
    save.onclick = function () {
      var data = {};
      var missing = null;
      _grAttrs.forEach(function (a) {
        var el = inputs[a.attrCode];
        var v = (el.value != null ? String(el.value).trim() : '');
        if (a.required === 1 && !v && !missing) missing = a.attrName;
        if (v !== '') data[a.attrCode] = v;
      });
      if (missing) { DN.toast('必填属性未填写：' + missing, 'err'); return; }
      var payload = { id: row.id, entityId: _grEntity.id, dataJson: JSON.stringify(data), status: statusSel.value, sourceSystem: srcInput.value.trim() };
      save.textContent = '保存中...'; save.style.pointerEvents = 'none';
      DN.post('/api/mdm/golden/save', payload).then(function () { DN.toast('已保存', 'ok'); dr.close(); reloadGolden(); })
        .catch(function (e) { DN.toast(e.message || '保存失败', 'err'); save.textContent = '保存'; save.style.pointerEvents = ''; });
    };
    DN.enterSubmit(body);
  }

  // ===================== 匹配去重（按关键/唯一属性检测重复 + 合并） =====================
  var _ddEntity = null, _ddAttrs = [];

  window.MDM_RENDERERS.dedup = function (c) {
    c.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-bottom:14px', text: '基于实体的关键/唯一属性检测重复黄金记录，聚合为重复簇，支持选定存活记录后合并（其余置为停用）。' }));
    var selWrap = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:14px' });
    selWrap.appendChild(DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '选择实体：' }));
    var entSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:240px' });
    entSel.appendChild(DN.h('option', { value: '', text: '加载中…' }));
    selWrap.appendChild(entSel);
    c.appendChild(selWrap);
    var box = DN.h('div', { id: 'ddBox' });
    c.appendChild(box);

    DN.get('/api/mdm/entities').then(function (ents) {
      ents = ents || [];
      entSel.innerHTML = '';
      if (!ents.length) { entSel.appendChild(DN.h('option', { value: '', text: '(暂无实体)' })); box.appendChild(DN.empty('请先创建实体与黄金记录', 'layers')); return; }
      entSel.appendChild(DN.h('option', { value: '', text: '(请选择实体)' }));
      ents.forEach(function (e) { entSel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + e.entityName })); });
      entSel.value = String(ents[0].id); _ddEntity = ents[0];
      loadDedup(box);
      entSel.onchange = function () { _ddEntity = ents.filter(function (e) { return String(e.id) === entSel.value; })[0] || null; box.innerHTML = ''; if (_ddEntity) loadDedup(box); };
    }).catch(function (e) { box.appendChild(DN.errorBox('实体加载失败: ' + e.message, function () { c.innerHTML = ''; MDM_RENDERERS.dedup(c); })); });
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
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.errorBox('检测失败: ' + e.message, function () { loadDedup(box); })); });
  }

  function buildClusterCard(cl, idx, box) {
    var card = DN.card({ title: '重复簇 · ' + cl.matchAttrName + ' = ' + cl.matchValue + ' （' + cl.size + ' 条）', icon: 'alert' });
    card.el.classList.add('primary');
    var recs = cl.records || [];
    recs.forEach(function (r) { try { r._vals = JSON.parse(r.dataJson || '{}'); } catch (e) { r._vals = {}; } });
    var showAttrs = (_ddAttrs.filter(function (a) { return a.isKey === 1 || a.isUnique === 1; }));
    if (!showAttrs.length) showAttrs = _ddAttrs.slice(0, 4);

    var radioName = 'survivor_' + idx;
    var tbl = DN.h('table', { class: 'gov-tbl', style: 'width:100%' });
    var thead = '<thead><tr><th>存活</th>' + showAttrs.map(function (a) { return '<th>' + DN.esc(a.attrName) + '</th>'; }).join('') + '<th>状态</th><th>来源</th><th>版本</th><th>更新</th></tr></thead>';
    var tb = document.createElement('tbody');
    recs.forEach(function (r, i) {
      var tr = document.createElement('tr');
      var rd = document.createElement('td');
      var radio = DN.h('input', { type: 'radio', name: radioName, value: String(r.id) });
      if (i === 0) radio.checked = true;   // 默认首条为存活
      rd.appendChild(radio); tr.appendChild(rd);
      showAttrs.forEach(function (a) { var td = document.createElement('td'); var v = r._vals[a.attrCode]; td.textContent = (v == null || v === '') ? '-' : String(v); tr.appendChild(td); });
      var st = document.createElement('td'); st.appendChild(r.status === 'active' ? DN.pill('已生效', 'ok') : DN.pill('草稿', 'warn')); tr.appendChild(st);
      var sc = document.createElement('td'); sc.textContent = r.sourceSystem || '-'; tr.appendChild(sc);
      var ve = document.createElement('td'); ve.textContent = 'v' + (r.version || 1); tr.appendChild(ve);
      var up = document.createElement('td'); up.appendChild(DN.timeAgo(r.updatedAt)); tr.appendChild(up);
      tb.appendChild(tr);
    });
    tbl.innerHTML = thead; tbl.appendChild(tb);
    var wrap = DN.h('div', { class: 'gov-tbl-wrap' }); wrap.appendChild(tbl);
    card.body.appendChild(wrap);

    var footer = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;margin-top:12px' });
    footer.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0;flex:1', text: '选定一条为存活记录，其余将被合并（置为停用）。' }));
    var mergeBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '合并该簇' });
    mergeBtn.onclick = function () {
      var checked = card.el.querySelector('input[name="' + radioName + '"]:checked');
      if (!checked) { DN.toast('请先选定存活记录', 'warn'); return; }
      var survivorId = Number(checked.value);
      var mergedIds = recs.map(function (r) { return r.id; }).filter(function (id) { return id !== survivorId; });
      if (!window.confirm('确认合并？保留 1 条存活记录，其余 ' + mergedIds.length + ' 条将置为停用。')) return;
      mergeBtn.textContent = '合并中...'; mergeBtn.style.pointerEvents = 'none';
      DN.post('/api/mdm/match/merge', { survivorId: survivorId, mergedIds: mergedIds }).then(function (res) {
        DN.toast('已合并：保留 1 条，停用 ' + ((res && res.mergedCount) || mergedIds.length) + ' 条', 'ok');
        loadDedup(box);
      }).catch(function (e) { DN.toast(e.message || '合并失败', 'err'); mergeBtn.textContent = '合并该簇'; mergeBtn.style.pointerEvents = ''; });
    };
    footer.appendChild(mergeBtn);
    card.body.appendChild(footer);
    return card.el;
  }
})();
