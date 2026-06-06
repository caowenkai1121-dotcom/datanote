/* 参考数据/码表(Reference Data) 模块 —— 系统级枚举与码表(国家/地区/行业分类等)，支持树形 parent_code。
   独立渲染器，注册到 window.MDM_RENDERERS.refdata；复用 DN 共享套件，与 gov/mdm 现有模块 UI 完全一致。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  var _selCategory = '';   // 当前选中的码表类别
  var _cats = [];          // 所有类别 [{category,itemCount,enabledCount}]

  window.MDM_RENDERERS.refdata = function (c) {
    _selCategory = '';
    c.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-bottom:14px', text: '参考数据是系统级枚举与码表（客户类型/地区/行业分类等），支持 parent_code 树形结构，供主数据属性引用以保证取值一致。' }));
    var tileBox = DN.h('div', { id: 'rdTiles' });
    tileBox.appendChild(DN.skeleton(1));
    c.appendChild(tileBox);
    var box = DN.h('div', { id: 'rdBox', style: 'margin-top:14px' });
    c.appendChild(box);
    loadCategories(tileBox, box);
  };

  function loadCategories(tileBox, box) {
    tileBox.innerHTML = ''; tileBox.appendChild(DN.skeleton(1));
    DN.get('/api/mdm/refdata/categories').then(function (cats) {
      _cats = cats || [];
      tileBox.innerHTML = '';
      var addCatBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建码值', onclick: function () { codeForm(null, tileBox, box); } });
      var card = DN.card({ title: '码表类别', icon: 'grid', actions: addCatBtn });
      if (!_cats.length) {
        card.body.appendChild(DN.empty('暂无参考数据，点右上角“新建码值”创建首个码表类别', 'grid'));
        box.innerHTML = '';
        tileBox.appendChild(card.el);
        return;
      }
      // 顶部类别磁贴：点击切换当前类别
      card.body.appendChild(DN.statRow(_cats.map(function (ct) {
        return {
          icon: 'list', label: ct.category || '(未命名)', value: ct.itemCount || 0,
          sub: '启用 ' + (ct.enabledCount || 0), tone: (String(ct.category) === String(_selCategory) ? 'info' : undefined),
          title: '查看该类别码值', onClick: function () { _selCategory = ct.category; loadList(box, tileBox); }
        };
      })));
      tileBox.appendChild(card.el);
      // 默认选中第一个类别
      if (!_selCategory) _selCategory = _cats[0].category;
      loadList(box, tileBox);
    }).catch(function (e) {
      tileBox.innerHTML = '';
      tileBox.appendChild(DN.errorBox('类别加载失败: ' + (e && e.message ? e.message : e), function () { loadCategories(tileBox, box); }));
    });
  }

  function loadList(box, tileBox) {
    if (!_selCategory) { box.innerHTML = ''; return; }
    box.innerHTML = ''; box.appendChild(DN.skeleton(3));
    DN.get('/api/mdm/refdata/list?category=' + encodeURIComponent(_selCategory)).then(function (rows) {
      rows = rows || [];
      box.innerHTML = '';
      // 父级码值 → 名称映射（树形展示用）
      var nameByCode = {};
      rows.forEach(function (r) { nameByCode[r.code] = r.name; });
      var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建码值', onclick: function () { codeForm(null, tileBox, box); } });
      var card = DN.card({ title: '码表 · ' + _selCategory, icon: 'list', actions: addBtn });
      if (!rows.length) {
        card.body.appendChild(DN.empty('该类别下暂无码值，点右上角“新建码值”创建', 'list'));
        box.appendChild(card.el);
        return;
      }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'code', label: '码值', copyable: true, render: function (r) { return r.code; } },
          { key: 'name', label: '名称', render: function (r) { return r.name; } },
          { key: 'parentCode', label: '父级', render: function (r) {
              if (!r.parentCode) return DN.h('span', { text: '-', style: 'color:var(--text-muted)' });
              var pn = nameByCode[r.parentCode];
              return DN.h('span', { text: r.parentCode + (pn ? '（' + pn + '）' : '') });
            } },
          { key: 'sortOrder', label: '排序', align: 'right', sortable: true, render: function (r) { return String(r.sortOrder == null ? 0 : r.sortOrder); } },
          { key: 'status', label: '状态', render: function (r) { return r.status === 0 ? DN.pill('停用', 'muted') : DN.pill('启用', 'ok'); } },
          { key: 'description', label: '描述', render: function (r) { return r.description || '-'; } },
          { key: '_op', label: '操作', render: function (r) {
              var w = DN.h('span', { style: 'display:inline-flex;gap:10px' });
              w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#1890ff)', onclick: function () { codeForm(r, tileBox, box); } }));
              w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:#ff4d4f', onclick: function () {
                  if (!window.confirm('删除码值「' + r.name + '（' + r.code + '）」？')) return;
                  DN.del('/api/mdm/refdata/' + r.id).then(function () { DN.toast('已删除', 'ok'); loadCategories(tileBox, box); })
                    .catch(function (e) { DN.toast(e.message || '删除失败', 'err'); });
                } }));
              return w;
            } }
        ],
        rows: rows, pageSize: 20, searchKeys: ['code', 'name', 'parentCode', 'description'], searchPlaceholder: '搜索码值/名称/父级/描述', exportName: '码表_' + _selCategory
      }));
      box.appendChild(card.el);
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('码值加载失败: ' + (e && e.message ? e.message : e), function () { loadList(box, tileBox); }));
    });
  }

  // ===================== 表单（DN.drawer） =====================
  function field(label, input, hint) {
    var row = DN.h('div', { style: 'display:flex;flex-direction:column;gap:4px;margin-bottom:12px' });
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

  function codeForm(row, tileBox, box) {
    var isEdit = !!row; row = row || {};
    // 类别：编辑时锁定；新建时可选已有类别或输入新类别
    var catVal = row.category || _selCategory || '';
    var fCat = inp(catVal, '如 CUSTOMER_TYPE / REGION');
    if (isEdit) { fCat.disabled = 'disabled'; }
    var fCode = inp(row.code, '如 ENTERPRISE / 110000');
    if (isEdit) { fCode.disabled = 'disabled'; }
    var fName = inp(row.name, '如 企业 / 北京市');
    var fParent = inp(row.parentCode, '父级码值(可空,同类别内)');
    var fSort = inp(row.sortOrder == null ? '' : row.sortOrder, '排序，越小越靠前');
    var fStatus = sel([[1, '启用'], [0, '停用']], row.status == null ? 1 : row.status);
    var fDesc = inp(row.description, '描述');
    var body = DN.h('div', {});
    body.appendChild(field('码表类别', fCat, isEdit ? '编辑时不可修改' : '唯一，建议大写英文，复用已有类别即归入该类别'));
    body.appendChild(field('码值', fCode, isEdit ? '编辑时不可修改' : '同类别内唯一'));
    body.appendChild(field('名称', fName));
    body.appendChild(field('父级码值', fParent, '树形结构，留空为顶级'));
    body.appendChild(field('排序', fSort));
    body.appendChild(field('状态', fStatus));
    body.appendChild(field('描述', fDesc));
    var footer = DN.h('div', { style: 'text-align:right;margin-top:10px' });
    var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '保存' });
    footer.appendChild(save); body.appendChild(footer);
    var dr = DN.drawer((isEdit ? '编辑' : '新建') + '码值', body);
    save.onclick = function () {
      var payload = {
        id: row.id,
        category: fCat.value.trim(),
        code: fCode.value.trim(),
        name: fName.value.trim(),
        parentCode: fParent.value.trim(),
        sortOrder: fSort.value.trim() ? parseInt(fSort.value, 10) : 0,
        status: Number(fStatus.value),
        description: fDesc.value.trim()
      };
      if (!payload.category) { DN.toast('请填写码表类别', 'err'); return; }
      if (!payload.code) { DN.toast('请填写码值', 'err'); return; }
      if (!payload.name) { DN.toast('请填写名称', 'err'); return; }
      save.textContent = '保存中...'; save.style.pointerEvents = 'none';
      DN.post('/api/mdm/refdata/save', payload).then(function () {
        DN.toast('已保存', 'ok'); dr.close();
        _selCategory = payload.category;
        loadCategories(tileBox, box);
      }).catch(function (e) { DN.toast(e.message || '保存失败', 'err'); save.textContent = '保存'; save.style.pointerEvents = ''; });
    };
    DN.enterSubmit(body);
  }
})();
