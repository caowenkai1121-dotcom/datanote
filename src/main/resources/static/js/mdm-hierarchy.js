/* 主数据管理(MDM) · 层级管理 渲染器 —— 维护黄金记录间的树形层级
   (组织架构/地区/产品分类),实体+类型选择→树形展示+新增关系抽屉。
   复用 DN 共享套件(dn-common.js) 与 mdm/gov 同款 UI 风格。
   独立文件,注册到 window.MDM_RENDERERS.hierarchy；主线负责 script 引入与 MDM_MODS 条目。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  var _ent = null;     // 当前实体
  var _type = 'org';   // 当前层级类型
  var _records = [];   // 该实体的黄金记录(供父/子选择)

  // 内置常用层级类型(允许自定义,save 时按文本提交)
  var TYPES = [
    { code: 'org', label: '组织架构' },
    { code: 'region', label: '地区' },
    { code: 'category', label: '产品分类' }
  ];
  function typeLabel(code) {
    for (var i = 0; i < TYPES.length; i++) if (TYPES[i].code === code) return TYPES[i].label;
    return code;
  }

  window.MDM_RENDERERS.hierarchy = function (c) {
    c.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-bottom:14px', text: '层级管理维护黄金记录之间的树形父子关系(如组织架构、地区、产品分类)。父子均为同一实体下的黄金记录,根节点父可空。下方以树形展示层级,并可新增父→子关系。' }));

    var selWrap = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:14px' });
    selWrap.appendChild(DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '选择实体：' }));
    var entSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:240px' });
    entSel.appendChild(DN.h('option', { value: '', text: '加载中…' }));
    selWrap.appendChild(entSel);
    selWrap.appendChild(DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '层级类型：' }));
    var typeSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:140px' });
    TYPES.forEach(function (t) { typeSel.appendChild(DN.h('option', { value: t.code, text: t.label })); });
    typeSel.value = _type;
    selWrap.appendChild(typeSel);
    c.appendChild(selWrap);

    var box = DN.h('div', { id: 'hierBox' });
    c.appendChild(box);

    typeSel.onchange = function () { _type = typeSel.value; if (_ent) loadHierarchy(box); };

    DN.get('/api/mdm/entities').then(function (ents) {
      ents = ents || [];
      entSel.innerHTML = '';
      if (!ents.length) {
        entSel.appendChild(DN.h('option', { value: '', text: '(暂无实体，请先在“域与实体建模”创建)' }));
        box.appendChild(DN.empty('请先在“域与实体建模”创建实体与黄金记录', 'layers'));
        return;
      }
      entSel.appendChild(DN.h('option', { value: '', text: '(请选择实体)' }));
      ents.forEach(function (e) { entSel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + e.entityName })); });
      entSel.value = String(ents[0].id);
      _ent = ents[0];
      loadHierarchy(box);
      entSel.onchange = function () {
        _ent = ents.filter(function (e) { return String(e.id) === entSel.value; })[0] || null;
        box.innerHTML = '';
        if (_ent) loadHierarchy(box);
      };
    }).catch(function (e) {
      box.appendChild(DN.errorBox('实体加载失败: ' + (e && e.message ? e.message : e), function () { c.innerHTML = ''; MDM_RENDERERS.hierarchy(c); }));
    });
  };

  function loadHierarchy(box) {
    if (!_ent) return;
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    var eid = encodeURIComponent(_ent.id), t = encodeURIComponent(_type);
    Promise.all([
      DN.get('/api/mdm/hierarchy/list?entityId=' + eid + '&hierarchyType=' + t),
      DN.get('/api/mdm/hierarchy/tree?entityId=' + eid + '&hierarchyType=' + t),
      DN.get('/api/mdm/golden/list?entityId=' + eid)
    ]).then(function (r) {
      var rows = r[0] || [], tree = r[1] || [];
      _records = r[2] || [];
      box.innerHTML = '';

      // 统计：关系数 / 根节点数 / 涉及黄金记录数 / 最大深度
      var involved = {};
      rows.forEach(function (h) {
        if (h.parentRecordId != null) involved[h.parentRecordId] = 1;
        involved[h.childRecordId] = 1;
      });
      box.appendChild(DN.statRow([
        { icon: 'git-branch', label: '层级关系', value: rows.length },
        { icon: 'folder', label: '根节点', value: tree.length, tone: (tree.length ? 'ok' : 'muted') },
        { icon: 'list', label: '涉及黄金记录', value: Object.keys(involved).length },
        { icon: 'layers', label: '最大深度', value: maxDepth(tree), sub: typeLabel(_type) }
      ]));

      var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '+ 新增关系', onclick: function () { saveDrawer(null, box); } });
      var card = DN.card({ title: typeLabel(_type) + ' · 树形层级', icon: 'git-branch', actions: addBtn });

      if (!tree.length) {
        card.body.appendChild(DN.empty('该层级类型下暂无关系，点击“新增关系”构建父→子层级', 'git-branch'));
      } else {
        var treeWrap = DN.h('div', { style: 'padding:4px 2px' });
        tree.forEach(function (node) { renderNode(treeWrap, node, 0, box); });
        card.body.appendChild(treeWrap);
      }
      box.appendChild(card.el);

      // 关系明细表(便于编辑/删除)
      if (rows.length) {
        var tcard = DN.card({ title: '关系明细', icon: 'list' });
        tcard.body.appendChild(DN.table({
          columns: [
            { key: 'parentBizKey', label: '父节点', copyable: true, render: function (r) {
                return r.parentRecordId == null ? DN.pill('根节点', 'info') : DN.h('span', { text: r.parentBizKey || ('#' + r.parentRecordId) });
              } },
            { key: 'childBizKey', label: '子节点', copyable: true, render: function (r) { return r.childBizKey || ('#' + r.childRecordId); } },
            { key: 'sortOrder', label: '排序', render: function (r) { return String(r.sortOrder == null ? 0 : r.sortOrder); } },
            { key: 'updatedAt', label: '更新', render: function (r) { return DN.timeAgo(r.updatedAt); } },
            { key: '_op', label: '操作', render: function (r) {
                var w = DN.h('span', { style: 'display:inline-flex;gap:10px' });
                w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#1890ff)', onclick: function () { saveDrawer(r, box); } }));
                w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:#ff4d4f', onclick: function () { delRel(r, box); } }));
                return w;
              } }
          ],
          rows: rows, pageSize: 15, searchKeys: ['parentBizKey', 'childBizKey'], searchPlaceholder: '搜索父/子节点业务主键', exportName: '层级关系'
        }));
        box.appendChild(tcard.el);
      }
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('加载失败: ' + (e && e.message ? e.message : e), function () { loadHierarchy(box); }));
    });
  }

  function maxDepth(nodes) {
    var d = 0;
    (nodes || []).forEach(function (n) { d = Math.max(d, 1 + maxDepth(n.children)); });
    return d;
  }

  // ---- 递归渲染树节点(缩进+连接符) ----
  function renderNode(wrap, node, depth, box) {
    var row = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:6px 4px;font-size:13px;border-bottom:1px solid var(--border,rgba(0,0,0,.06))' });
    if (depth > 0) row.style.paddingLeft = (depth * 22 + 4) + 'px';
    var hasChildren = node.children && node.children.length;
    row.appendChild(DN.h('span', { text: hasChildren ? '▸' : '·', style: 'color:var(--text-muted);width:12px;display:inline-block;text-align:center' }));
    row.appendChild(DN.h('span', { text: node.bizKey || node.childBizKey || ('#' + node.childRecordId), style: 'font-weight:500' }));
    if (hasChildren) row.appendChild(DN.pill(node.children.length + ' 子', 'info'));
    var del = DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'margin-left:auto;color:#ff4d4f;font-size:12px', onclick: function () { delRel(node, box); } });
    row.appendChild(del);
    wrap.appendChild(row);
    (node.children || []).forEach(function (ch) { renderNode(wrap, ch, depth + 1, box); });
  }

  function delRel(r, box) {
    if (!window.confirm('确定删除该层级关系？\n（仅解除父子关系，不删除黄金记录本身）')) return;
    DN.del('/api/mdm/hierarchy/' + r.id).then(function () {
      DN.toast('已删除', 'ok'); loadHierarchy(box);
    }).catch(function (e) { DN.toast(e && e.message ? e.message : '删除失败', 'err'); });
  }

  // ---- 新增/编辑关系抽屉(选父+子黄金记录) ----
  function saveDrawer(rec, box) {
    var isEdit = !!(rec && rec.id);
    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 12px', text: (isEdit ? '编辑' : '新增') + (_ent ? '实体「' + _ent.entityName + '」' : '') + ' 的 ' + typeLabel(_type) + ' 层级关系。父节点可空(表示根节点)。' }));

    // 父黄金记录(可空)
    var pSel = DN.h('select', { class: 'iw-form-select', style: 'width:100%' });
    pSel.appendChild(DN.h('option', { value: '', text: '(无 / 作为根节点)' }));
    // 子黄金记录(必填)
    var cSel = DN.h('select', { class: 'iw-form-select', style: 'width:100%' });
    cSel.appendChild(DN.h('option', { value: '', text: '(请选择子黄金记录)' }));
    _records.forEach(function (g) {
      var label = (g.bizKey || ('#' + g.id)) + (g.status === 'active' ? '' : ' (' + (g.status || 'draft') + ')');
      pSel.appendChild(DN.h('option', { value: g.id, text: label }));
      cSel.appendChild(DN.h('option', { value: g.id, text: label }));
    });
    if (isEdit) {
      pSel.value = rec.parentRecordId == null ? '' : String(rec.parentRecordId);
      cSel.value = String(rec.childRecordId);
    }
    body.appendChild(field('父黄金记录', pSel));
    body.appendChild(field('子黄金记录', cSel));

    var fSort = DN.h('input', { class: 'iw-form-select', style: 'width:100%', type: 'number', placeholder: '同级排序(默认0)', value: (isEdit && rec.sortOrder != null) ? String(rec.sortOrder) : '0' });
    body.appendChild(field('排序', fSort));

    var footer = DN.h('div', { style: 'text-align:right;margin-top:10px' });
    var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: isEdit ? '保存修改' : '确认新增' });
    footer.appendChild(save); body.appendChild(footer);
    var dr = DN.drawer((isEdit ? '编辑' : '新增') + '层级关系', body);

    save.onclick = function () {
      var childId = cSel.value;
      if (!childId) { DN.toast('请选择子黄金记录', 'err'); return; }
      var parentId = pSel.value;
      if (parentId && parentId === childId) { DN.toast('父子不能为同一黄金记录', 'err'); return; }
      var payload = {
        entityId: _ent.id,
        hierarchyType: _type,
        parentRecordId: parentId ? Number(parentId) : null,
        childRecordId: Number(childId),
        sortOrder: Number(fSort.value) || 0
      };
      if (isEdit) payload.id = rec.id;
      save.textContent = '提交中...'; save.style.pointerEvents = 'none';
      DN.post('/api/mdm/hierarchy/save', payload).then(function () {
        DN.toast(isEdit ? '已保存' : '已新增', 'ok'); dr.close(); loadHierarchy(box);
      }).catch(function (e) {
        DN.toast(e && e.message ? e.message : '保存失败', 'err');
        save.textContent = isEdit ? '保存修改' : '确认新增'; save.style.pointerEvents = '';
      });
    };
    DN.enterSubmit(body);
  }

  function field(label, input) {
    var row = DN.h('div', { style: 'display:flex;flex-direction:column;gap:4px;margin-bottom:12px' });
    row.appendChild(DN.h('label', { text: label, style: 'font-size:12px;color:var(--text-muted)' }));
    row.appendChild(input);
    return row;
  }
})();
