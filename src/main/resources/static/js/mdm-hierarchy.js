/* 主数据管理(MDM) · 层级管理 渲染器 —— 维护黄金记录间的树形层级
   (组织架构/地区/产品分类),实体+类型选择→树形展示+新增关系抽屉。
   复用 DN 共享套件(dn-common.js) 与 mdm/gov 同款 UI 风格。
   独立文件,注册到 window.MDM_RENDERERS.hierarchy；主线负责 script 引入与 MDM_MODS 条目。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  var _ent = null;       // 当前实体
  var _type = 'org';     // 当前层级类型
  var _records = [];     // 该实体的黄金记录(供父/子选择)
  var _recCache = null;  // 已拉取的黄金记录缓存(按实体复用,跨层级类型切换不重复请求)
  var _recCacheKey = null; // 缓存对应的实体 id

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

    // 切换层级类型同实体时复用已缓存的黄金记录,避免重复拉取(见 loadHierarchy 的 _recCache)
    typeSel.onchange = function () { _type = typeSel.value; if (_ent) loadHierarchy(box); };

    // 实体下拉加载期间先给骨架,避免空白闪烁
    box.appendChild(DN.skeleton(2));
    DN.get('/api/mdm/entities').then(function (ents) {
      ents = Array.isArray(ents) ? ents : [];
      box.innerHTML = '';
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
      _recCache = null; _recCacheKey = null; // 实体首次确定,清空旧实体的黄金记录缓存
      loadHierarchy(box);
      entSel.onchange = function () {
        _ent = ents.filter(function (e) { return String(e.id) === entSel.value; })[0] || null;
        _recCache = null; _recCacheKey = null; // 切换实体作废缓存,避免父/子选项串实体
        box.innerHTML = '';
        if (_ent) loadHierarchy(box);
        else box.appendChild(DN.empty('请先选择一个实体', 'layers'));
      };
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('实体加载失败: ' + (e && e.message ? e.message : e), function () { c.innerHTML = ''; MDM_RENDERERS.hierarchy(c); }));
    });
  };

  function loadHierarchy(box) {
    if (!_ent || _ent.id == null) { box.innerHTML = ''; box.appendChild(DN.empty('请先选择一个实体', 'layers')); return; }
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    var eid = encodeURIComponent(_ent.id), t = encodeURIComponent(_type);
    // 黄金记录按实体复用缓存:同实体切层级类型时不再重复请求 golden/list
    var goldenP = (_recCache && _recCacheKey === String(_ent.id))
      ? Promise.resolve(_recCache)
      : DN.get('/api/mdm/golden/list?entityId=' + eid);
    Promise.all([
      DN.get('/api/mdm/hierarchy/list?entityId=' + eid + '&hierarchyType=' + t),
      DN.get('/api/mdm/hierarchy/tree?entityId=' + eid + '&hierarchyType=' + t),
      goldenP
    ]).then(function (r) {
      var rows = Array.isArray(r[0]) ? r[0] : [], tree = Array.isArray(r[1]) ? r[1] : [];
      _records = Array.isArray(r[2]) ? r[2] : [];
      _recCache = _records; _recCacheKey = String(_ent.id); // 回填缓存
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
        tree.forEach(function (node) { renderNode(treeWrap, node, 0, box, {}); });
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
                w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '查看子记录', style: 'color:var(--primary,#3457d5)', title: '在黄金记录中打开子节点记录', onclick: function () { goGolden(r.childRecordId); } }));
                if (r.parentRecordId != null) {
                  w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '查看父记录', style: 'color:var(--primary,#3457d5)', title: '在黄金记录中打开父节点记录', onclick: function () { goGolden(r.parentRecordId); } }));
                }
                w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#3457d5)', onclick: function () { saveDrawer(r, box); } }));
                w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:#e03131', onclick: function () { delRel(r, box); } }));
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

  var MAX_TREE_DEPTH = 64; // 兜底:防止后端数据成环时无限递归(正常层级远小于此)

  function maxDepth(nodes, seen, depth) {
    if (depth == null) depth = 0;
    if (depth > MAX_TREE_DEPTH) return 0; // 触底即停,避免栈溢出
    seen = seen || {};
    var d = 0;
    (Array.isArray(nodes) ? nodes : []).forEach(function (n) {
      if (!n) return;
      var key = n.childRecordId;
      if (key != null) { if (seen[key]) return; seen[key] = 1; } // 已访问节点跳过,断环
      d = Math.max(d, 1 + maxDepth(n.children, seen, depth + 1));
      if (key != null) delete seen[key]; // 回溯:允许同一节点出现在不同分支(DAG),仅断真正的环
    });
    return d;
  }

  // 业务主键超长时截断展示,完整值挂 title(避免长文本撑破布局)
  function truncLabel(s) {
    s = String(s == null ? '' : s);
    return s.length > 48 ? s.slice(0, 48) + '…' : s;
  }

  // ---- 递归渲染树节点(缩进+连接符);seen 断环,depth 兜底防栈溢出 ----
  function renderNode(wrap, node, depth, box, seen) {
    if (!node) return;
    seen = seen || {};
    if (depth > MAX_TREE_DEPTH) return; // 触底即停
    var key = node.childRecordId;
    if (key != null && seen[key]) return; // 数据成环:已渲染过的节点不再展开
    var row = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:6px 4px;font-size:13px;border-bottom:1px solid var(--border,rgba(0,0,0,.06))' });
    if (depth > 0) row.style.paddingLeft = (depth * 22 + 4) + 'px';
    var hasChildren = node.children && node.children.length;
    row.appendChild(DN.h('span', { text: hasChildren ? '▸' : '·', style: 'color:var(--text-muted);width:12px;display:inline-block;text-align:center' }));
    var label = node.bizKey || node.childBizKey || ('#' + node.childRecordId);
    row.appendChild(DN.h('span', { text: truncLabel(label), title: String(label), style: 'font-weight:500' }));
    if (hasChildren) row.appendChild(DN.pill(node.children.length + ' 子', 'info'));
    var ops = DN.h('span', { style: 'margin-left:auto;display:inline-flex;gap:12px;align-items:center' });
    if (node.childRecordId != null) {
      ops.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '查看黄金记录', style: 'color:var(--primary,#3457d5);font-size:12px', title: '在黄金记录中打开该节点对应记录', onclick: function () { goGolden(node.childRecordId); } }));
    }
    ops.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:#e03131;font-size:12px', onclick: function () { delRel(node, box); } }));
    row.appendChild(ops);
    wrap.appendChild(row);
    if (key != null) seen[key] = 1;
    (Array.isArray(node.children) ? node.children : []).forEach(function (ch) { renderNode(wrap, ch, depth + 1, box, seen); });
    if (key != null) delete seen[key]; // 回溯,允许 DAG 复用节点
  }

  // ---- 下钻：跳转黄金记录(自动打开该记录编辑 + 选中当前实体) ----
  function goGolden(recordId) {
    if (recordId == null) { DN.toast('该节点无对应黄金记录', 'err'); return; }
    if (!window.mdmGoModule) { DN.toast('暂不支持跳转', 'err'); return; }
    mdmGoModule('goldenrecord', { editId: recordId, entityId: (_ent ? _ent.id : null) });
  }

  var _deleting = false; // 防删除并发(避免快速双击发两次 DELETE)
  function delRel(r, box) {
    if (!r || r.id == null) { DN.toast('该关系缺少标识,无法删除', 'err'); return; }
    if (_deleting) return;
    if (!window.confirm('确定删除该层级关系？\n（仅解除父子关系，不删除黄金记录本身）')) return;
    _deleting = true;
    DN.del('/api/mdm/hierarchy/' + encodeURIComponent(r.id)).then(function () {
      DN.toast('已删除', 'ok'); loadHierarchy(box);
    }).catch(function (e) { DN.toast(e && e.message ? e.message : '删除失败', 'err'); })
      .then(function () { _deleting = false; });
  }

  // 黄金记录下拉文案:超长业务主键截断,避免下拉项过宽撑破抽屉布局
  function recOption(g) {
    var raw = (g.bizKey || ('#' + g.id)) + (g.status === 'active' ? '' : ' (' + (g.status || 'draft') + ')');
    return { value: g.id, label: truncLabel(raw) };
  }

  // ---- 新增/编辑关系抽屉(选父+子黄金记录) ----
  function saveDrawer(rec, box) {
    if (!_ent || _ent.id == null) { DN.toast('请先选择实体', 'err'); return; } // 空实体守卫
    var isEdit = !!(rec && rec.id);
    var noRecords = !_records.length;
    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 12px', text: (isEdit ? '编辑' : '新增') + (_ent ? '实体「' + _ent.entityName + '」' : '') + ' 的 ' + typeLabel(_type) + ' 层级关系。父节点可空(表示根节点)。' }));

    // 该实体下没有黄金记录时,无法构建关系:给出明确引导并禁用保存
    if (noRecords) {
      body.appendChild(DN.empty('该实体下暂无黄金记录,请先在「黄金记录」中创建后再建立层级关系', 'inbox'));
    }

    // 父黄金记录(可空)
    var pSel = DN.h('select', { class: 'iw-form-select', style: 'width:100%' });
    pSel.appendChild(DN.h('option', { value: '', text: '(无 / 作为根节点)' }));
    // 子黄金记录(必填)
    var cSel = DN.h('select', { class: 'iw-form-select', style: 'width:100%' });
    cSel.appendChild(DN.h('option', { value: '', text: '(请选择子黄金记录)' }));
    _records.forEach(function (g) {
      if (!g || g.id == null) return;
      var o = recOption(g);
      pSel.appendChild(DN.h('option', { value: o.value, text: o.label }));
      cSel.appendChild(DN.h('option', { value: o.value, text: o.label }));
    });
    if (isEdit) {
      pSel.value = rec.parentRecordId == null ? '' : String(rec.parentRecordId);
      cSel.value = String(rec.childRecordId);
    }
    if (noRecords) { pSel.disabled = true; cSel.disabled = true; }

    var fSort = DN.h('input', { class: 'iw-form-select', style: 'width:100%', type: 'number', step: '1', placeholder: '同级排序(默认0)', value: (isEdit && rec.sortOrder != null) ? String(rec.sortOrder) : '0' });
    if (noRecords) fSort.disabled = true;

    var sec = DN.formSection('关系配置');
    sec.add(DN.formGrid2([
      DN.field('父黄金记录', pSel),
      DN.field('子黄金记录', cSel, { required: true })
    ]));
    sec.add(DN.field('排序', fSort));
    body.appendChild(sec.el);

    var dr, foot;
    var doSave = function () {
      if (noRecords || foot.ok.disabled) return;
      var childId = cSel.value;
      if (!childId) { DN.toast('请选择子黄金记录', 'err'); return; }
      var parentId = pSel.value;
      if (parentId && parentId === childId) { DN.toast('父子不能为同一黄金记录', 'err'); return; }
      // 排序须为整数(后端字段为整型),非法值明确提示而非静默归零
      var sortRaw = String(fSort.value || '').trim();
      var sortNum = sortRaw === '' ? 0 : Number(sortRaw);
      if (!isFinite(sortNum) || Math.floor(sortNum) !== sortNum) { DN.toast('排序需为整数', 'err'); return; }
      var payload = {
        entityId: _ent.id,
        hierarchyType: _type,
        parentRecordId: parentId ? Number(parentId) : null,
        childRecordId: Number(childId),
        sortOrder: sortNum
      };
      if (isEdit) payload.id = rec.id;
      foot.busy('提交中…');
      DN.post('/api/mdm/hierarchy/save', payload).then(function () {
        DN.toast(isEdit ? '已保存' : '已新增', 'ok'); dr.close(); loadHierarchy(box);
      }).catch(function (e) {
        DN.toast(e && e.message ? e.message : '保存失败', 'err');
        foot.reset(isEdit ? '保存修改' : '确认新增');
      });
    };
    foot = DN.drawerFoot({ okText: isEdit ? '保存修改' : '确认新增', onOk: doSave, onCancel: function () { dr.close(); } });
    if (noRecords) { foot.ok.disabled = true; foot.ok.title = '请先创建黄金记录'; }
    dr = DN.drawer((isEdit ? '编辑' : '新增') + '层级关系', body, foot.el);
    DN.enterSubmit(body, doSave);
  }
})();
