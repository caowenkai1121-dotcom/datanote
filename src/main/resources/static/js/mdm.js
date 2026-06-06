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
        { icon: 'shield', label: '黄金记录', value: '—', sub: '建设中', tone: 'muted' }
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
})();
