/* 治理模块：主题域管理（树展示 + 增删改，级联删除） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var API = '/api/subject';
  var LAYERS = ['ALL', 'DWD', 'DIM', 'DWS', 'ADS'];

  window.GOV_RENDERERS.subject = function (c) {
    var form = buildForm(function () { reload(list, form); });
    c.appendChild(form.el);
    var list = DN.h('div', { id: 'subjectList' });
    c.appendChild(list);
    reload(list, form);
  };

  // ========== 新增表单 ==========
  function buildForm(onSaved) {
    var box = DN.h('div', { class: 'gov-desc' });
    var name = inp('主题名称');
    var parent = DN.h('select', { style: selStyle() });
    var layer = DN.h('select', { style: selStyle() });
    LAYERS.forEach(function (l) { layer.appendChild(DN.h('option', { value: l, text: l })); });
    var sort = inp('排序');
    sort.style.width = '70px';

    var add = DN.h('a', {
      class: 'gov-btn', href: 'javascript:void(0)', text: '新增主题', onclick: function () {
        var payload = {
          name: name.value.trim(),
          parentId: parent.value ? Number(parent.value) : null,
          layer: layer.value,
          sortOrder: parseInt(sort.value, 10) || 0
        };
        if (!payload.name) { DN.toast('主题名称必填', 'error'); return; }
        DN.post(API, payload).then(function () {
          DN.toast('已新增'); name.value = ''; sort.value = ''; onSaved();
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      }
    });
    box.appendChild(DN.h('span', { text: '父主题：', style: 'color:#86909c;margin-right:4px' }));
    box.appendChild(parent);
    box.appendChild(name);
    box.appendChild(layer);
    box.appendChild(sort);
    box.appendChild(add);
    return { el: box, parentSel: parent };
  }

  // ========== 加载并渲染树 ==========
  function reload(list, form) {
    DN.get(API + '/list').then(function (data) {
      data = data || [];
      fillParentOptions(form.parentSel, data);
      list.innerHTML = '';
      if (!data.length) { list.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无主题域' })); return; }
      var tree = buildTree(data, null);
      var ul = DN.h('div', { style: 'margin-top:8px' });
      renderNodes(tree, ul, 0, function () { reload(list, form); });
      list.appendChild(ul);
    }).catch(function (e) {
      list.innerHTML = '';
      list.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message }));
    });
  }

  function fillParentOptions(sel, data) {
    var cur = sel.value;
    sel.innerHTML = '';
    sel.appendChild(DN.h('option', { value: '', text: '（一级主题）' }));
    data.forEach(function (s) {
      sel.appendChild(DN.h('option', { value: String(s.id), text: s.name }));
    });
    sel.value = cur;
  }

  function buildTree(list, parentId) {
    var nodes = [];
    list.forEach(function (s) {
      var sp = s.parentId == null ? null : s.parentId;
      if (sp === parentId) {
        s.children = buildTree(list, s.id);
        nodes.push(s);
      }
    });
    nodes.sort(function (a, b) { return (a.sortOrder || 0) - (b.sortOrder || 0) || a.id - b.id; });
    return nodes;
  }

  function renderNodes(nodes, container, depth, reloadFn) {
    nodes.forEach(function (n) {
      var row = DN.h('div', {
        style: 'display:flex;align-items:center;padding:6px 8px;border-bottom:1px solid #f2f3f5;' +
          'padding-left:' + (8 + depth * 22) + 'px'
      });
      row.appendChild(DN.h('span', {
        text: (n.children && n.children.length ? '▸ ' : '· ') + n.name,
        style: 'flex:1;font-size:13px'
      }));
      row.appendChild(DN.h('span', { text: n.layer || 'ALL', style: 'color:#86909c;font-size:12px;margin-right:12px' }));
      row.appendChild(DN.h('a', {
        href: 'javascript:void(0)', text: '编辑', style: 'color:#165dff;margin-right:10px',
        onclick: function () { editNode(n, reloadFn); }
      }));
      row.appendChild(DN.h('a', {
        href: 'javascript:void(0)', text: '删除', style: 'color:#f53f3f',
        onclick: function () {
          var tip = (n.children && n.children.length)
            ? '该主题含 ' + n.children.length + ' 个子主题，将一并级联删除，确认？'
            : '确认删除？';
          if (!confirm(tip)) return;
          DN.del(API + '/' + n.id).then(function () { DN.toast('已删除'); reloadFn(); })
            .catch(function (e) { DN.toast(e.message, 'error'); });
        }
      }));
      container.appendChild(row);
      if (n.children && n.children.length) renderNodes(n.children, container, depth + 1, reloadFn);
    });
  }

  // ========== 编辑（行内改名 + 分层） ==========
  function editNode(n, reloadFn) {
    var newName = prompt('修改主题名称：', n.name);
    if (newName == null) return;
    newName = newName.trim();
    if (!newName) { DN.toast('名称不能为空', 'error'); return; }
    var newLayer = prompt('适用分层（ALL/DWD/DIM/DWS/ADS）：', n.layer || 'ALL');
    if (newLayer == null) return;
    DN.api(API + '/' + n.id, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: newName, parentId: n.parentId == null ? null : n.parentId, layer: newLayer.trim() || 'ALL', sortOrder: n.sortOrder })
    }).then(function () { DN.toast('已更新'); reloadFn(); })
      .catch(function (e) { DN.toast(e.message, 'error'); });
  }

  // ========== 小工具 ==========
  function inp(ph) {
    return DN.h('input', { placeholder: ph, style: 'margin:0 6px 6px 0;padding:4px 6px;border:1px solid #ddd;border-radius:4px' });
  }
  function selStyle() {
    return 'margin:0 6px 6px 0;padding:4px 6px;border:1px solid #ddd;border-radius:4px';
  }
})();
