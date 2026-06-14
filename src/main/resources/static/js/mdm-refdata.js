/* 参考数据/码表(Reference Data) 模块 —— 系统级枚举与码表(国家/地区/行业分类等)，支持树形 parent_code。
   独立渲染器，注册到 window.MDM_RENDERERS.refdata；复用 DN 共享套件，与 gov/mdm 现有模块 UI 完全一致。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  var _selCategory = '';   // 当前选中的码表类别
  var _cats = [];          // 所有类别 [{category,itemCount,enabledCount}]
  var _rows = [];          // 当前类别下的码值（缓存，供表单本地校验，免重复 fetch）

  window.MDM_RENDERERS.refdata = function (c) {
    // R26 深链：mdmGoModule('refdata', {category}) 可定位到指定码表类别
    var ctx = window.__mdmCtx || {};
    _selCategory = ctx.category ? String(ctx.category) : '';
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
      var addCatBtn = DN.h('a', { class: 'btn btn-primary', 'data-perm': 'mdm:manage', href: 'javascript:void(0)', text: '新建码值', onclick: function () { codeForm(null, tileBox, box); } });
      var card = DN.card({ title: '码表类别', icon: 'grid', actions: addCatBtn });
      if (!_cats.length) {
        card.body.appendChild(DN.empty('暂无参考数据，点右上角“新建码值”创建首个码表类别', 'grid'));
        box.innerHTML = '';
        tileBox.appendChild(card.el);
        return;
      }
      // 深链 ctx.category 若指向不存在的类别，回退到首个，避免选中空类别白屏
      if (_selCategory && !_cats.some(function (ct) { return String(ct.category) === String(_selCategory); })) {
        _selCategory = '';
      }
      // 顶部类别磁贴：点击切换当前类别（tone 只用合法枚举 ok/warn/err，选中态用 ok 高亮）
      var statRow = DN.statRow(_cats.map(function (ct, i) {
        var isSel = String(ct.category) === String(_selCategory);
        return {
          icon: 'list', label: ct.category || '(未命名)', value: ct.itemCount || 0,
          sub: '启用 ' + (ct.enabledCount || 0), tone: (isSel ? 'ok' : undefined),
          title: isSel ? '当前查看的类别' : '查看「' + (ct.category || '(未命名)') + '」码值',
          onClick: function () {
            if (String(_selCategory) === String(ct.category)) return; // 已选中同类别，免重复加载
            _selCategory = ct.category;
            // 本地切换选中态（增删 tone-ok class），仅重拉列表，避免额外请求类别
            var tiles = statRow.children;
            for (var k = 0; k < tiles.length; k++) { tiles[k].classList.toggle('tone-ok', k === i); }
            loadList(box, tileBox);
          }
        };
      }));
      card.body.appendChild(statRow);
      tileBox.appendChild(card.el);
      // 默认选中第一个类别
      if (!_selCategory) _selCategory = _cats[0] && _cats[0].category || '';
      loadList(box, tileBox);
    }).catch(function (e) {
      tileBox.innerHTML = ''; box.innerHTML = ''; // 同时清掉列表区残留骨架，避免错误态下半屏空转
      tileBox.appendChild(DN.errorBox('类别加载失败: ' + (e && e.message ? e.message : e), function () { loadCategories(tileBox, box); }));
    });
  }

  function loadList(box, tileBox) {
    if (!_selCategory) { box.innerHTML = ''; return; }
    box.innerHTML = ''; box.appendChild(DN.skeleton(3));
    var reqCat = _selCategory; // 捕获本次请求的类别，避免快速切换时旧响应覆盖新列表（竞态）
    DN.get('/api/mdm/refdata/list?category=' + encodeURIComponent(reqCat)).then(function (rows) {
      if (reqCat !== _selCategory) return; // 用户已切到别的类别，丢弃过期响应
      rows = Array.isArray(rows) ? rows : [];
      _rows = rows; // 缓存当前类别码值，供表单做本地校验（父级存在/码值唯一），免额外 fetch
      box.innerHTML = '';
      // 父级码值 → 名称映射（树形展示用）
      var nameByCode = {};
      rows.forEach(function (r) { if (r && r.code != null) nameByCode[r.code] = r.name; });
      // 头部动作：在建模中引用（指引到属性建模，复制 REFERENCE 引用令牌）+ 新建码值
      var refBtn = DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '在建模中引用', title: '在主数据建模中将属性设为 REFERENCE 并引用该码表类别', onclick: function () { refInModeling(_selCategory); } });
      var actBar = DN.h('span', { style: 'display:inline-flex;gap:8px' }, [refBtn, DN.h('a', { class: 'btn btn-primary', 'data-perm': 'mdm:manage', href: 'javascript:void(0)', text: '新建码值', onclick: function () { codeForm(null, tileBox, box); } })]);
      var card = DN.card({ title: '码表 · ' + _selCategory, icon: 'list', actions: actBar });
      // 引用指引：码表类别即为建模属性的引用令牌
      card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 10px', text: '在「域与实体建模」中将属性数据类型设为 REFERENCE、引用值填「' + _selCategory + '」，即可复用本类别码值，保证取值一致。' }));
      if (!rows.length) {
        card.body.appendChild(DN.empty('该类别下暂无码值，点右上角“新建码值”创建', 'list'));
        box.appendChild(card.el);
        return;
      }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'code', label: '码值', copyable: true, sortable: true, render: function (r) { return r.code == null ? '-' : String(r.code); } },
          { key: 'name', label: '名称', sortable: true, render: function (r) {
              if (r.name == null) return '-';
              var nm = String(r.name);
              // 超长名称截断 + title 悬停看全文，避免撑破表格（与描述列口径一致）
              return DN.h('span', { text: nm.length > 30 ? nm.slice(0, 30) + '…' : nm, title: nm.length > 30 ? nm : '' });
            } },
          { key: 'parentCode', label: '父级', render: function (r) {
              if (!r.parentCode) return DN.h('span', { text: '-', style: 'color:var(--text-muted)' });
              var pn = nameByCode[r.parentCode];
              return DN.h('span', { text: r.parentCode + (pn ? '（' + pn + '）' : '') });
            } },
          { key: 'sortOrder', label: '排序', align: 'right', sortable: true, render: function (r) { return String(r.sortOrder == null ? 0 : r.sortOrder); } },
          { key: 'status', label: '状态', render: function (r) { return r.status === 0 ? DN.pill('停用', 'muted') : DN.pill('启用', 'ok'); } },
          { key: 'description', label: '描述', render: function (r) {
              var d = r.description == null ? '' : String(r.description);
              if (!d) return DN.h('span', { text: '-', style: 'color:var(--text-muted)' });
              // 超长描述截断 + title 悬停看全文，避免撑破表格
              var short = d.length > 40 ? d.slice(0, 40) + '…' : d;
              return DN.h('span', { text: short, title: d.length > 40 ? d : '' });
            } },
          { key: '_op', label: '操作', render: function (r) {
              var w = DN.h('span', { style: 'display:inline-flex;gap:10px' });
              w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '明细', style: 'color:var(--primary)', onclick: function () { detailDrawer(r, nameByCode); } }));
              w.appendChild(DN.h('a', { 'data-perm': 'mdm:manage', href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary)', onclick: function () { codeForm(r, tileBox, box); } }));
              var delLink = DN.h('a', { 'data-perm': 'mdm:manage', href: 'javascript:void(0)', text: '删除', style: 'color:var(--error)' });
              delLink.onclick = function () {
                if (delLink._busy) return; // 防重复点击（删除请求在途时忽略再次点击）
                // 本地预检：存在子级码值则先提示，避免无意义的破坏性请求（与后端规则一致）
                var hasChild = (_rows || []).some(function (x) { return x && x.parentCode === r.code; });
                if (hasChild) { DN.toast('该码值存在子级，请先删除其下级码值', 'warn'); return; }
                DN.confirm('确认删除码值「' + (r.name || '') + '（' + (r.code || '') + '）」？此操作不可恢复。', { title: '删除确认', danger: true }).then(function (ok) {
                  if (!ok) return;
                  delLink._busy = true; delLink.style.opacity = '0.5'; delLink.style.pointerEvents = 'none';
                  DN.del('/api/mdm/refdata/' + r.id).then(function () { DN.toast('已删除', 'ok'); loadCategories(tileBox, box); })
                    .catch(function (e) {
                      DN.toast(e && e.message ? e.message : '删除失败', 'err');
                      delLink._busy = false; delLink.style.opacity = ''; delLink.style.pointerEvents = '';
                    });
                });
              };
              w.appendChild(delLink);
              return w;
            } }
        ],
        rows: rows, pageSize: 20, searchKeys: ['code', 'name', 'parentCode', 'description'], searchPlaceholder: '搜索码值/名称/父级/描述', exportName: '码表_' + _selCategory
      }));
      box.appendChild(card.el);
    }).catch(function (e) {
      if (reqCat !== _selCategory) return; // 过期请求的错误不污染当前视图
      box.innerHTML = '';
      box.appendChild(DN.errorBox('码值加载失败: ' + (e && e.message ? e.message : e), function () { loadList(box, tileBox); }));
    });
  }

  // ===================== 表单（DN.drawer） =====================
  function inp(val, ph) { return DN.h('input', { class: 'dn-form-input', style: 'width:100%', value: val == null ? '' : String(val), placeholder: ph || '' }); }
  function sel(opts, val) {
    var s = DN.h('select', { class: 'dn-form-select', style: 'width:100%' });
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
    var sec = DN.formSection('码值信息');
    sec.add(DN.formGrid2([
      DN.field('码表类别', fCat, { required: true, hint: isEdit ? '编辑时不可修改' : '唯一，建议大写英文，复用已有类别即归入该类别' }),
      DN.field('码值', fCode, { required: true, hint: isEdit ? '编辑时不可修改' : '同类别内唯一' })
    ]));
    sec.add(DN.formGrid2([
      DN.field('名称', fName, { required: true }),
      DN.field('父级码值', fParent, { hint: '树形结构，留空为顶级' })
    ]));
    sec.add(DN.formGrid2([
      DN.field('排序', fSort),
      DN.field('状态', fStatus)
    ]));
    sec.add(DN.field('描述', fDesc));
    body.appendChild(sec.el);

    var dr, foot;
    var doSave = function () {
      if (foot.ok.disabled) return; // 防重复提交（保存在途时忽略再次点击 / 回车）
      var sortRaw = fSort.value.trim();
      var payload = {
        id: row.id,
        category: fCat.value.trim(),
        code: fCode.value.trim(),
        name: fName.value.trim(),
        parentCode: fParent.value.trim(),
        sortOrder: sortRaw ? parseInt(sortRaw, 10) : 0,
        status: Number(fStatus.value),
        description: fDesc.value.trim()
      };
      // 必填校验
      if (!payload.category) { DN.toast('请填写码表类别', 'err'); return; }
      if (!payload.code) { DN.toast('请填写码值', 'err'); return; }
      if (!payload.name) { DN.toast('请填写名称', 'err'); return; }
      // 排序需为合法整数（避免输入字母导致 NaN 透传）
      if (sortRaw && (!/^-?\d+$/.test(sortRaw) || isNaN(payload.sortOrder))) {
        DN.toast('排序必须为整数', 'err'); return;
      }
      // 父级自引用 / 父级须同类别内存在（与后端规则一致，本地先拦截，少一次失败往返）
      if (payload.parentCode) {
        if (payload.parentCode === payload.code) { DN.toast('父级码值不能指向自身', 'err'); return; }
        // 仅当编辑或新建到当前已选类别时，可用本地缓存做父级存在性预检
        if (String(payload.category) === String(_selCategory) &&
            !(_rows || []).some(function (x) { return x && x.code === payload.parentCode; })) {
          DN.toast('父级码值在本类别下不存在：' + payload.parentCode, 'err'); return;
        }
      }
      // 新建到当前类别时，本地预检码值唯一（与后端 category+code 唯一一致）
      if (!isEdit && String(payload.category) === String(_selCategory) &&
          (_rows || []).some(function (x) { return x && x.code === payload.code; })) {
        DN.toast('该类别下码值已存在：' + payload.code, 'err'); return;
      }
      foot.busy('保存中…');
      DN.post('/api/mdm/refdata/save', payload).then(function () {
        DN.toast('已保存', 'ok'); dr.close();
        _selCategory = payload.category;
        loadCategories(tileBox, box);
      }).catch(function (e) {
        DN.toast(e && e.message ? e.message : '保存失败', 'err');
        foot.reset('保存');
      });
    };
    foot = DN.drawerFoot({ okText: '保存', onOk: doSave, onCancel: function () { dr.close(); } });
    dr = DN.drawer((isEdit ? '编辑' : '新建') + '码值', body, foot.el);
    DN.enterSubmit(body, doSave);
  }

  // R26: 「在建模中引用」—— 跳到域与实体建模, 提示用 REFERENCE 引用该码表类别
  function refInModeling(category) {
    var cat = category || _selCategory;
    if (!cat) { DN.toast('请先选择码表类别', 'warn'); return; }
    if (window.mdmGoModule) {
      mdmGoModule('modeling', { refCategory: cat });
      DN.toast('在建模中将属性数据类型设为 REFERENCE, 引用值填「' + cat + '」', 'info');
    } else {
      // 无法跳转时如实告知，并把引用令牌复制到剪贴板，用户可手动粘贴
      DN.copy(cat);
      DN.toast('已复制引用令牌「' + cat + '」, 请在建模中将属性设为 REFERENCE 并粘贴', 'info');
    }
  }

  // R26: 码值明细抽屉(含父级名称)
  function detailDrawer(r, nameByCode) {
    if (!r) { DN.toast('记录不存在', 'err'); return; }
    var body = DN.h('div');
    function row(k, v) { return DN.h('div', { class: 'dn-kv' }, [
      DN.h('span', { class: 'k', text: k }), DN.h('span', { class: 'v', text: (v == null || v === '') ? '-' : String(v) }) ]); }
    body.appendChild(row('类别', r.category));
    body.appendChild(row('码值', r.code));
    body.appendChild(row('名称', r.name));
    body.appendChild(row('父级', r.parentCode ? (r.parentCode + (nameByCode && nameByCode[r.parentCode] ? '（' + nameByCode[r.parentCode] + '）' : '')) : '-'));
    body.appendChild(row('排序', r.sortOrder == null ? 0 : r.sortOrder));
    body.appendChild(row('状态', r.status === 0 ? '停用' : '启用')); // 与列表口径一致：0=停用，其余=启用
    body.appendChild(row('描述', r.description));
    DN.drawer('码值明细 · ' + (r.name || r.code), body);
  }
})();
