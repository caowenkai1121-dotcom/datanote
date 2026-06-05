/* 治理模块：主题域管理（树展示 + 增删改，级联删除） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var API = '/api/subject';
  var LAYERS = ['DWD', 'DIM', 'DWS', 'ADS', 'ALL'];

  window.GOV_RENDERERS.subject = function (c) {
    var card = DN.card({ title: '主题域', icon: 'layers' });
    c.appendChild(card.el);

    var form = buildForm(function () { reload(list, form); });
    card.body.appendChild(form.el);
    var ov = DN.h('div', { id: 'subjectOverview' });
    card.body.appendChild(ov);
    var list = DN.h('div', { id: 'subjectList' });
    card.body.appendChild(list);
    list.appendChild(DN.skeleton(4));
    reload(list, form);
  };

  // ========== 新增表单（就近 .gov-form，父主题/分层下拉保留） ==========
  function buildForm(onSaved) {
    var box = DN.h('div', { class: 'gov-form', style: 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:16px' });
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
    box.appendChild(DN.h('span', { text: '父主题：', style: 'color:var(--text-muted,#86909c);margin-right:4px' }));
    box.appendChild(parent);
    box.appendChild(name);
    box.appendChild(layer);
    box.appendChild(sort);
    box.appendChild(add);
    DN.enterSubmit(box);
    return { el: box, parentSel: parent };
  }

  // ========== 加载并渲染树 ==========
  function reload(list, form) {
    DN.get(API + '/list').then(function (data) {
      data = data || [];
      fillParentOptions(form.parentSel, data);
      renderSubjectOverview(data);
      list.innerHTML = '';
      if (!data.length) { list.appendChild(DN.empty('暂无主题域', 'layers')); return; }
      var tree = buildTree(data, null);
      var ul = DN.h('div', {});
      renderNodes(tree, ul, 0, function () { reload(list, form); });
      list.appendChild(ul);
    }).catch(function (e) {
      list.innerHTML = '';
      list.appendChild(DN.errorBox('加载失败: ' + e.message, function () { reload(list, form); }));
    });
  }

  // 主题域分层统计概览(大功能): 总数/一级数/最大层级/叶子数 + 分层分布条
  function renderSubjectOverview(data) {
    var box = document.getElementById('subjectOverview'); if (!box) return;
    box.innerHTML = '';
    if (!data.length) return;
    var byId = {}; data.forEach(function (s) { byId[s.id] = s; });
    var depthOf = function (s) { var d = 1, cur = s, guard = 0; while (cur && cur.parentId != null && byId[cur.parentId] && guard++ < 20) { d++; cur = byId[cur.parentId]; } return d; };
    var rootN = data.filter(function (s) { return s.parentId == null; }).length;
    var childIds = {}; data.forEach(function (s) { if (s.parentId != null) childIds[s.parentId] = 1; });
    var leafN = data.filter(function (s) { return !childIds[s.id]; }).length;
    var maxDepth = data.reduce(function (m, s) { return Math.max(m, depthOf(s)); }, 1);
    box.appendChild(DN.statRow([
      { icon: 'layers', label: '主题总数', value: data.length },
      { icon: 'grid', label: '一级主题', value: rootN },
      { icon: 'chart', label: '最大层级', value: maxDepth + ' 层' },
      { icon: 'list', label: '叶子主题', value: leafN }
    ]));
    var byLayer = {}; data.forEach(function (s) { var l = s.layer || '未分层'; byLayer[l] = (byLayer[l] || 0) + 1; });
    var keys = Object.keys(byLayer);
    if (keys.length) {
      var card = DN.card({ title: '分层分布', icon: 'chart' });
      card.body.appendChild(DN.bars(keys.map(function (k) { return { label: k, value: byLayer[k], tone: 'info', display: String(byLayer[k]) }; })));
      box.appendChild(card.el);
    }
    renderSubjectHealth(data, byId, depthOf, byLayer);
  }

  // 创新功能：主题域结构健康（复用树数据前端计算，不调新 API）
  // 指标：空置主题域(叶子,可能未挂数据) / 层级是否过深(>4层) / 各分层分布是否均衡，并给出优化提示
  function renderSubjectHealth(data, byId, depthOf, byLayer) {
    var box = document.getElementById('subjectOverview'); if (!box) return;
    if (!data.length) return;
    // 叶子（空置）主题域：无任何子主题，可能尚未挂载数据
    var childIds = {}; data.forEach(function (s) { if (s.parentId != null) childIds[s.parentId] = 1; });
    var leaves = data.filter(function (s) { return !childIds[s.id]; });
    var leafN = leaves.length;
    var leafRate = Math.round(leafN / (data.length || 1) * 100);
    // 层级过深（>4 层）的主题域数量
    var maxDepth = data.reduce(function (m, s) { return Math.max(m, depthOf(s)); }, 1);
    var deepN = data.filter(function (s) { return depthOf(s) > 4; }).length;
    // 分层分布均衡度：变异系数越小越均衡，转为 0-100 的均衡分（仅统计已分层项）
    var layerKeys = Object.keys(byLayer);
    var counts = layerKeys.map(function (k) { return byLayer[k]; });
    var sum = counts.reduce(function (a, b) { return a + b; }, 0);
    var mean = sum / (counts.length || 1);
    var variance = counts.reduce(function (a, v) { return a + Math.pow(v - mean, 2); }, 0) / (counts.length || 1);
    var cv = mean > 0 ? Math.sqrt(variance) / mean : 0; // 变异系数
    var balanceScore = Math.max(0, Math.min(100, Math.round((1 - cv) * 100)));

    var card = DN.card({ title: '主题域结构健康', icon: 'shield' });
    card.body.appendChild(DN.statRow([
      { icon: 'doc', label: '空置主题域', value: leafN, tone: leafN ? 'warning' : 'success', sub: '叶子节点(可能未挂数据) 占比 ' + leafRate + '%' },
      { icon: 'chart', label: '最大层级', value: maxDepth + ' 层', tone: maxDepth > 4 ? 'error' : 'success', sub: maxDepth > 4 ? '超过 4 层建议下沉拆分' : '层级合理' },
      { icon: 'layers', label: '过深主题', value: deepN, tone: deepN ? 'error' : 'success', sub: '层级 > 4 的主题数' },
      { icon: 'grid', label: '分层均衡分', value: balanceScore, tone: balanceScore >= 70 ? 'success' : (balanceScore >= 40 ? 'warning' : 'error'), sub: '越高分层越均衡' }
    ]));

    // 各分层分布（均衡度可视化）
    if (layerKeys.length) {
      var maxCnt = Math.max.apply(null, counts) || 1;
      card.body.appendChild(DN.bars(layerKeys.map(function (k) {
        var v = byLayer[k];
        var over = v >= mean * 1.6, under = v <= mean * 0.4;
        var tone = over ? 'warning' : (under ? 'info' : 'success');
        // WCAG1.4.1：偏多/偏少不能仅靠色区分，display 前加 ▲/▼ 非色符号
        var cue = over ? '▲ ' : (under ? '▼ ' : '');
        return { label: k, value: Math.round(v / maxCnt * 100), tone: tone, display: cue + v };
      })));
    }

    // 结构优化提示
    var tips = [];
    if (deepN > 0) tips.push('有 ' + deepN + ' 个主题层级超过 4 层，层级过深会增加治理与检索成本，建议拆分或下沉重组。');
    if (leafRate >= 60) tips.push('叶子主题占比 ' + leafRate + '%，存在较多末级主题域，请确认是否均已挂载数据资产，避免空置。');
    if (balanceScore < 40 && layerKeys.length > 1) tips.push('各分层主题数量差异较大（均衡分 ' + balanceScore + '），建议补全偏少分层（如 DWS/ADS）的主题规划。');
    if (!tips.length) tips.push('主题域结构总体健康：层级适中、分层较均衡，继续保持。');
    var tipBox = DN.h('div', { style: 'margin-top:12px;display:flex;flex-direction:column;gap:8px' });
    tips.forEach(function (t) {
      tipBox.appendChild(DN.h('div', {
        style: 'display:flex;align-items:flex-start;gap:8px;font-size:13px;color:var(--text-regular,#4e5969);line-height:1.5'
      }, [
        DN.h('span', { html: DN.icon('info'), style: 'display:inline-flex;width:15px;height:15px;color:var(--primary,#1890ff);margin-top:2px;flex:none' }),
        DN.h('span', { text: t, style: 'flex:1' })
      ]));
    });
    card.body.appendChild(tipBox);
    box.appendChild(card.el);
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
      var hasChild = !!(n.children && n.children.length);
      var row = DN.h('div', {
        style: 'display:flex;align-items:center;padding:9px 12px;border-bottom:1px solid var(--divider,#f3f4f6);' +
          'padding-left:' + (12 + depth * 22) + 'px'
      });
      // 层级图标：父节点用 layers，叶子用 doc
      row.appendChild(DN.h('span', {
        class: 'subj-ic', html: DN.icon(hasChild ? 'layers' : 'doc'),
        style: 'display:inline-flex;width:16px;height:16px;color:var(--primary,#1890ff);margin-right:8px;flex:none'
      }));
      row.appendChild(DN.h('span', {
        text: n.name, style: 'flex:1;font-size:13px;color:var(--text-regular,#1f2329)'
      }));
      row.appendChild(DN.pill(n.layer || 'ALL', 'info'));
      var ops = DN.h('span', { style: 'margin-left:12px;flex:none' });
      ops.appendChild(DN.h('a', {
        href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#1890ff);font-size:13px;margin-right:12px',
        onclick: function () { editNode(n, reloadFn); }
      }));
      ops.appendChild(DN.h('a', {
        href: 'javascript:void(0)', text: '删除', style: 'color:var(--error,#ff4d4f);font-size:13px',
        onclick: function () {
          var tip = hasChild
            ? '主题「' + n.name + '」含 ' + n.children.length + ' 个子主题，将一并级联删除，确认？'
            : '确认删除主题「' + n.name + '」？';
          confirmModal(tip, function () {
            DN.del(API + '/' + n.id).then(function () { DN.toast('已删除'); reloadFn(); })
              .catch(function (e) { DN.toast(e.message, 'error'); });
          });
        }
      }));
      row.appendChild(ops);
      container.appendChild(row);
      if (hasChild) renderNodes(n.children, container, depth + 1, reloadFn);
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
    function close() { if (mask.parentNode) mask.parentNode.removeChild(mask); document.removeEventListener('keydown', onKey); }
    function onKey(e) { if (e.key === 'Escape') close(); }
    var footer = DN.h('div', { style: 'text-align:right;margin-top:16px' });
    footer.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '取消', style: 'margin-right:8px', onclick: close }));
    footer.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确定', onclick: function () { onOk(close); } }));
    box.appendChild(footer);
    mask.appendChild(box);
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(mask);
  }
})();
