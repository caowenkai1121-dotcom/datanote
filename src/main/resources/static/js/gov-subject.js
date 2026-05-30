/* 治理模块：主题域管理（树展示 + 增删改，级联删除） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var API = '/api/subject';
  var LAYERS = ['DWD', 'DIM', 'DWS', 'ADS', 'ALL'];

  window.GOV_RENDERERS.subject = function (c) {
    var form = buildForm(function () { reload(list, form); });
    c.appendChild(form.el);
    var list = DN.h('div', { id: 'subjectList' });
    c.appendChild(list);
    reload(list, form);
  };

  // ========== 新增表单 ==========
  function buildForm(onSaved) {
    var box = DN.h('div', { class: 'gov-desc', style: 'display:flex;gap:8px;align-items:center;flex-wrap:wrap' });
    var name = inp('主题名称');
    var parent = DN.h('select', { class: 'iw-form-select', style: 'width:auto' });
    var layer = DN.h('select', { class: 'iw-form-select', style: 'width:auto' });
    LAYERS.forEach(function (l) { layer.appendChild(DN.h('option', { value: l, text: l })); });
    var sort = inp('排序');
    sort.style.width = '70px';

    var add = DN.h('a', {
      class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增主题', onclick: function () {
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
        href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#165dff);margin-right:10px',
        onclick: function () { editNode(n, reloadFn); }
      }));
      row.appendChild(DN.h('a', {
        href: 'javascript:void(0)', text: '删除', style: 'color:var(--error,#f53f3f)',
        onclick: function () {
          var tip = (n.children && n.children.length)
            ? '主题「' + n.name + '」含 ' + n.children.length + ' 个子主题，将一并级联删除，确认？'
            : '确认删除主题「' + n.name + '」？';
          confirmModal(tip, function () {
            DN.del(API + '/' + n.id).then(function () { DN.toast('已删除'); reloadFn(); })
              .catch(function (e) { DN.toast(e.message, 'error'); });
          });
        }
      }));
      container.appendChild(row);
      if (n.children && n.children.length) renderNodes(n.children, container, depth + 1, reloadFn);
    });
  }

  // ========== 编辑（弹窗改名 + 分层下拉） ==========
  function editNode(n, reloadFn) {
    var nameInput = DN.h('input', { class: 'iw-form-input', value: n.name || '', placeholder: '主题名称' });
    var layerSel = DN.h('select', { class: 'iw-form-select' },
      LAYERS.map(function (l) { return DN.h('option', { value: l, text: l }); }));
    layerSel.value = n.layer || 'ALL';
    modal('编辑主题', [formRow('主题名称', nameInput), formRow('适用分层', layerSel)], function (close) {
      var newName = nameInput.value.trim();
      if (!newName) { DN.toast('名称不能为空', 'error'); return; }
      DN.api(API + '/' + n.id, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newName, parentId: n.parentId == null ? null : n.parentId, layer: layerSel.value, sortOrder: n.sortOrder })
      }).then(function () { DN.toast('已更新'); close(); reloadFn(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  // ========== 小工具 ==========
  function inp(ph) {
    return DN.h('input', { class: 'iw-form-input', placeholder: ph, style: 'width:auto' });
  }

  function formRow(label, control) {
    return DN.h('div', { class: 'ds-form-row', style: 'align-items:flex-start' }, [
      DN.h('label', { text: label }), control
    ]);
  }

  function confirmModal(text, onOk) {
    modal('确认操作', [DN.h('div', { style: 'font-size:13px;color:var(--text-regular,#4e5969)', text: text })],
      function (close) { close(); onOk(); });
  }

  function modal(title, bodyNodes, onOk) {
    var mask = DN.h('div', { style: 'position:fixed;inset:0;background:rgba(0,0,0,.35);z-index:9999;display:flex;align-items:center;justify-content:center' });
    var box = DN.h('div', { style: 'background:var(--bg-card,#fff);border-radius:8px;min-width:360px;max-width:90vw;max-height:80vh;overflow:auto;padding:20px;box-shadow:0 8px 24px rgba(0,0,0,.2)' });
    box.appendChild(DN.h('div', { style: 'font-size:16px;font-weight:600;margin-bottom:16px', text: title }));
    bodyNodes.forEach(function (n) { box.appendChild(n); });
    function close() { if (mask.parentNode) mask.parentNode.removeChild(mask); }
    var footer = DN.h('div', { style: 'text-align:right;margin-top:16px' });
    footer.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '取消', style: 'margin-right:8px', onclick: close }));
    footer.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确定', onclick: function () { onOk(close); } }));
    box.appendChild(footer);
    mask.appendChild(box);
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    document.body.appendChild(mask);
  }
})();
