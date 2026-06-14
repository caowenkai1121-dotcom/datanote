/* 治理模块：主题域管理（树展示 + 增删改，级联删除） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var API = '/api/subject';
  var LAYERS = ['DWD', 'DIM', 'DWS', 'ADS', 'ALL'];
  var NAME_MAX = 60;        // 主题名称长度上限(与后端/库字段对齐,前端先拦)
  var SORT_MIN = -9999, SORT_MAX = 9999;
  var TREE_PAGE = 200;      // 超长主题树时每页渲染上限,避免一次性渲染海量 DOM 卡顿
  var _reloading = false;   // reload 去重:防止快速连点(增/删/改后)触发并发重复请求

  window.GOV_RENDERERS.subject = function (c) {
    var card = DN.card({ title: '主题域', icon: 'layers' });
    c.appendChild(card.el);

    var form = buildForm(function () { reload(list, form); });
    card.body.appendChild(form.el);
    var ov = DN.h('div', { id: 'subjectOverview' });
    card.body.appendChild(ov);
    // 树过滤搜索框(去抖 250ms):仅在主题较多时辅助定位,空态/全量逻辑不变
    var filterBox = DN.h('div', { id: 'subjectFilterBox', style: 'display:none;margin:4px 0 10px' });
    card.body.appendChild(filterBox);
    var list = DN.h('div', { id: 'subjectList' });
    card.body.appendChild(list);
    list.appendChild(DN.skeleton(4));
    reload(list, form);
  };

  // ========== 新增表单（就近 .gov-form，父主题/分层下拉保留） ==========
  function buildForm(onSaved) {
    var box = DN.h('div', { class: 'gov-form', style: 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:16px' });
    var name = inp('主题名称');
    name.setAttribute('maxlength', String(NAME_MAX));     // 超长输入硬截断,避免提交超限名称
    var parent = DN.h('select', { class: 'dn-form-select', style: 'width:auto' });
    var layer = DN.h('select', { class: 'dn-form-select', style: 'width:auto' });
    LAYERS.forEach(function (l) { layer.appendChild(DN.h('option', { value: l, text: l })); });
    var sort = inp('排序');
    sort.style.width = '70px';
    sort.setAttribute('type', 'number');                  // 排序仅接受数字
    sort.setAttribute('min', String(SORT_MIN)); sort.setAttribute('max', String(SORT_MAX));

    var add = DN.h('a', {
      class: 'btn btn-primary', 'data-perm': 'governance:manage', href: 'javascript:void(0)', text: '新增主题', onclick: function () {
        var nm = name.value.trim();
        if (!nm) { DN.toast('主题名称必填', 'error'); name.focus(); return; }
        if (nm.length > NAME_MAX) { DN.toast('主题名称不能超过 ' + NAME_MAX + ' 个字符', 'error'); name.focus(); return; }
        var sortRaw = sort.value.trim();
        var sortNum = 0;
        if (sortRaw !== '') {
          sortNum = parseInt(sortRaw, 10);
          if (isNaN(sortNum)) { DN.toast('排序必须是整数', 'error'); sort.focus(); return; }
          if (sortNum < SORT_MIN || sortNum > SORT_MAX) { DN.toast('排序需在 ' + SORT_MIN + ' ~ ' + SORT_MAX + ' 之间', 'error'); sort.focus(); return; }
        }
        var payload = {
          name: nm,
          parentId: parent.value ? Number(parent.value) : null,
          layer: layer.value,
          sortOrder: sortNum
        };
        // 加载中禁用按钮防重复提交,完成/失败后恢复
        if (add.classList.contains('is-loading')) return;
        setBtnBusy(add, true, '新增中…');
        DN.post(API, payload).then(function () {
          DN.toast('已新增', 'success'); name.value = ''; sort.value = ''; onSaved();
        }).catch(function (e) { DN.toast(e && e.message ? e.message : '新增失败', 'error'); })
          .then(function () { setBtnBusy(add, false, '新增主题'); });
      }
    });
    box.appendChild(DN.h('span', { text: '父主题：', style: 'color:var(--text-muted);margin-right:4px' }));
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
    if (_reloading) return;                 // 去重:并发期间忽略重复触发
    _reloading = true;
    if (!list.firstChild) list.appendChild(DN.skeleton(4)); // 首次或重载时给加载骨架
    var reloadFn = function () { reload(list, form); };
    DN.get(API + '/list').then(function (data) {
      data = Array.isArray(data) ? data : [];
      fillParentOptions(form.parentSel, data);
      renderSubjectOverview(data);
      list.innerHTML = '';
      var fbox = document.getElementById('subjectFilterBox');
      if (!data.length) {
        if (fbox) fbox.style.display = 'none';
        list.appendChild(DN.empty('暂无主题域，请在上方表单新增', 'layers'));
        return;
      }
      buildSubtreeIndex(data);              // 一次性建子节点索引(O(n)),供树/概览复用,不再递归扫描
      // 主题较多时启用过滤搜索框(去抖),少量时隐藏避免干扰
      var curQ = '';
      if (fbox) curQ = setupFilter(fbox, data, list, reloadFn);
      renderTree(data, curQ, list, reloadFn); // 保留当前搜索词,reload 后视图不跳变
    }).catch(function (e) {
      list.innerHTML = '';
      list.appendChild(DN.errorBox('加载失败: ' + (e && e.message ? e.message : '网络异常'), reloadFn));
      var fbox2 = document.getElementById('subjectFilterBox'); if (fbox2) fbox2.style.display = 'none';
    }).then(function () { _reloading = false; });
  }

  // 子节点索引:childMap[parentId] = [子节点...]，避免 buildTree 的 O(n²) 递归与对源对象的污染
  var _childMap = null, _allNodes = null;
  function buildSubtreeIndex(data) {
    _allNodes = data;
    _childMap = {};
    data.forEach(function (s) {
      var pid = s.parentId == null ? '__root__' : s.parentId;
      (_childMap[pid] || (_childMap[pid] = [])).push(s);
    });
    Object.keys(_childMap).forEach(function (k) {
      _childMap[k].sort(function (a, b) { return (a.sortOrder || 0) - (b.sortOrder || 0) || (a.id || 0) - (b.id || 0); });
    });
  }

  // 渲染树:q 为过滤关键字(空=全量)。命中节点及其祖先链一并展开显示,边界(空结果)给具体空态。
  function renderTree(data, q, list, reloadFn) {
    list.innerHTML = '';
    q = (q || '').trim().toLowerCase();
    var keep = null;
    if (q) {
      keep = {};
      var byId = {}; data.forEach(function (s) { byId[s.id] = s; });
      data.forEach(function (s) {
        if (String(s.name || '').toLowerCase().indexOf(q) < 0) return;
        var cur = s, guard = 0;
        while (cur && guard++ < 50) { keep[cur.id] = 1; cur = cur.parentId == null ? null : byId[cur.parentId]; }
      });
      if (!Object.keys(keep).length) {
        list.appendChild(DN.empty('未找到匹配「' + q + '」的主题', 'search'));
        return;
      }
    }
    var ul = DN.h('div', {});
    var counter = { n: 0, truncated: false };
    renderNodes(_childMap['__root__'] || [], ul, 0, reloadFn, keep, counter);
    list.appendChild(ul);
    if (counter.truncated) {
      list.appendChild(DN.h('div', {
        style: 'padding:10px 12px;font-size:12px;color:var(--text-muted);text-align:center',
        text: '主题较多，已显示前 ' + TREE_PAGE + ' 个节点，请使用上方搜索精确定位。'
      }));
    }
  }

  // 过滤搜索框(去抖 250ms)。仅在节点数较多(>12)时展示,避免少量主题时多余 UI。
  // 返回当前搜索关键字,供 reload 重渲染时保持视图不跳变。
  function setupFilter(fbox, data, list, reloadFn) {
    if (data.length <= 12) { fbox.style.display = 'none'; fbox.innerHTML = ''; fbox.removeAttribute('data-bound'); return ''; }
    if (fbox.getAttribute('data-bound') === '1') {       // 监听去重:已绑定则复用,仅返回当前词
      fbox.style.display = '';
      var exist = fbox.querySelector('input');
      return exist ? exist.value : '';
    }
    fbox.style.display = '';
    fbox.innerHTML = '';
    var input = DN.h('input', { class: 'dn-form-input', placeholder: '搜索主题名称…', style: 'width:240px;max-width:100%' });
    var clr = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '清除', style: 'margin-left:8px;display:none', onclick: function () { input.value = ''; trigger(); input.focus(); } });
    var _t = null;
    function trigger() {
      clr.style.display = input.value ? '' : 'none';
      renderTree(_allNodes || data, input.value, list, reloadFn);
    }
    input.addEventListener('input', function () { clearTimeout(_t); _t = setTimeout(trigger, 250); });
    fbox.appendChild(DN.h('span', { html: DN.icon('search'), style: 'display:inline-flex;width:15px;height:15px;color:var(--text-muted);vertical-align:middle;margin-right:6px' }));
    fbox.appendChild(input);
    fbox.appendChild(clr);
    fbox.setAttribute('data-bound', '1');
    return '';
  }

  // 主题域分层统计概览(大功能): 总数/一级数/最大层级/叶子数 + 分层分布条
  function renderSubjectOverview(data) {
    var box = document.getElementById('subjectOverview'); if (!box) return;
    box.innerHTML = '';
    if (!Array.isArray(data) || !data.length) return;
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
    if (!Array.isArray(data) || !data.length) return;
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
    // tone 须用 DN.pill/statTile 约定的 warn/ok/err（warning/success/error 无对应 CSS，会失色）
    // 空置主题域 → 点击下钻到资产目录(暂不带 subjectId, 仅切模块查看全部资产以核对挂载情况)
    card.body.appendChild(DN.statRow([
      { icon: 'doc', label: '空置主题域', value: leafN, tone: leafN ? 'warn' : 'ok', sub: '叶子节点(可能未挂数据) 占比 ' + leafRate + '% · 点击查看资产', onClick: function () { if (window.govGoModule) govGoModule('assets', {}); }, title: '前往资产目录核对末级主题是否已挂载数据' },
      { icon: 'chart', label: '最大层级', value: maxDepth + ' 层', tone: maxDepth > 4 ? 'err' : 'ok', sub: maxDepth > 4 ? '超过 4 层建议下沉拆分' : '层级合理' },
      { icon: 'layers', label: '过深主题', value: deepN, tone: deepN ? 'err' : 'ok', sub: '层级 > 4 的主题数' },
      { icon: 'grid', label: '分层均衡分', value: balanceScore, tone: balanceScore >= 70 ? 'ok' : (balanceScore >= 40 ? 'warn' : 'err'), sub: '越高分层越均衡' }
    ]));

    // 各分层分布（均衡度可视化）
    if (layerKeys.length) {
      var maxCnt = Math.max.apply(null, counts) || 1;
      card.body.appendChild(DN.bars(layerKeys.map(function (k) {
        var v = byLayer[k];
        var over = v >= mean * 1.6, under = v <= mean * 0.4;
        var tone = over ? 'warn' : (under ? 'info' : 'ok');
        // WCAG1.4.1：偏多/偏少不能仅靠色区分，display 前加 ▲/▼ 非色符号
        var cue = over ? '▲ ' : (under ? '▼ ' : '');
        return { label: k, value: Math.round(v / maxCnt * 100), tone: tone, display: cue + v };
      })));
    }

    // 结构优化提示(每条 tip 可带 act 跳转, 消除纯展示死胡同 → 可点击去处理)
    var scrollToTree = function () { var el = document.getElementById('subjectList'); if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'start' }); };
    var tips = [];
    if (deepN > 0) tips.push({ t: '有 ' + deepN + ' 个主题层级超过 4 层，层级过深会增加治理与检索成本，建议拆分或下沉重组。', label: '查看主题树', go: scrollToTree });
    if (leafRate >= 60) tips.push({ t: '叶子主题占比 ' + leafRate + '%，存在较多末级主题域，请确认是否均已挂载数据资产，避免空置。', label: '去资产目录核对', go: function () { if (window.govGoModule) govGoModule('assets', {}); } });
    if (balanceScore < 40 && layerKeys.length > 1) tips.push({ t: '各分层主题数量差异较大（均衡分 ' + balanceScore + '），建议补全偏少分层（如 DWS/ADS）的主题规划。', label: '新增主题', go: scrollToTree });
    if (!tips.length) tips.push({ t: '主题域结构总体健康：层级适中、分层较均衡，继续保持。' });
    var tipBox = DN.h('div', { style: 'margin-top:12px;display:flex;flex-direction:column;gap:8px' });
    tips.forEach(function (tip) {
      var line = DN.h('span', { text: tip.t, style: 'flex:1' });
      if (typeof tip.go === 'function') {
        line.appendChild(DN.h('a', {
          href: 'javascript:void(0)', text: ' ' + tip.label + ' →',
          style: 'color:var(--primary);text-decoration:none;white-space:nowrap',
          onclick: tip.go
        }));
      }
      tipBox.appendChild(DN.h('div', {
        style: 'display:flex;align-items:flex-start;gap:8px;font-size:13px;color:var(--text-regular);line-height:1.5'
      }, [
        DN.h('span', { html: DN.icon('info'), style: 'display:inline-flex;width:15px;height:15px;color:var(--primary);margin-top:2px;flex:none' }),
        line
      ]));
    });
    card.body.appendChild(tipBox);
    box.appendChild(card.el);
  }

  function fillParentOptions(sel, data) {
    if (!sel) return;
    var cur = sel.value;
    sel.innerHTML = '';
    sel.appendChild(DN.h('option', { value: '', text: '（一级主题）' }));
    var exists = false;
    (Array.isArray(data) ? data : []).forEach(function (s) {
      if (String(s.id) === cur) exists = true;
      var nm = s.name == null ? '' : String(s.name);
      // 超长父主题名截断显示,完整名挂 title
      sel.appendChild(DN.h('option', { value: String(s.id), text: nm.length > 24 ? nm.slice(0, 24) + '…' : nm, title: nm }));
    });
    sel.value = exists ? cur : '';   // 选中项已被删除则回落到「一级主题」,避免悬空 value
  }

  // 用 _childMap 取直接子节点(已排序),不再依赖污染源对象的 n.children
  function childrenOf(n) { return (_childMap && _childMap[n.id]) || []; }

  // keep 为过滤命中保留集(null=全量);counter 累计已渲染节点数,超 TREE_PAGE 截断防卡顿
  function renderNodes(nodes, container, depth, reloadFn, keep, counter) {
    nodes.forEach(function (n) {
      if (keep && !keep[n.id]) return;            // 过滤:不在命中链上的跳过
      if (counter && counter.n >= TREE_PAGE) { counter.truncated = true; return; }
      if (counter) counter.n++;
      var kids = childrenOf(n);
      var hasChild = kids.length > 0;
      var row = DN.h('div', {
        style: 'display:flex;align-items:center;padding:9px 12px;border-bottom:1px solid var(--divider);' +
          'padding-left:' + (12 + depth * 22) + 'px'
      });
      // 层级图标：父节点用 layers，叶子用 doc
      row.appendChild(DN.h('span', {
        class: 'subj-ic', html: DN.icon(hasChild ? 'layers' : 'doc'),
        style: 'display:inline-flex;width:16px;height:16px;color:var(--primary);margin-right:8px;flex:none'
      }));
      // 主题名 → 下钻到资产目录按该主题域筛资产(gov-assets 收 ctx.subjectId), 消除纯展示死胡同
      // 超长名称用省略号+完整 title;无名兜底占位,避免链接空白不可点
      var nm = (n.name == null || n.name === '') ? '(未命名)' : String(n.name);
      var nameEl = DN.h('a', {
        href: 'javascript:void(0)', text: nm, title: nm + ' · 点击按此主题域查看资产',
        style: 'flex:1;min-width:0;font-size:13px;color:var(--text-regular);text-decoration:none;' +
          'overflow:hidden;text-overflow:ellipsis;white-space:nowrap',
        onclick: function () { if (window.govGoModule) govGoModule('assets', { subjectId: n.id }); }
      });
      row.appendChild(nameEl);
      var layerPill = DN.pill(n.layer || 'ALL', 'info');
      layerPill.style.flex = 'none';   // 防分层徽标在窄行被挤压换行(flex 行内 span 默认可收缩)
      row.appendChild(layerPill);
      var ops = DN.h('span', { style: 'margin-left:12px;flex:none' });
      ops.appendChild(DN.h('a', {
        href: 'javascript:void(0)', text: '编辑', 'data-perm': 'governance:manage', style: 'color:var(--primary);font-size:13px;margin-right:12px',
        onclick: function () { editNode(n, reloadFn); }
      }));
      var delLink = DN.h('a', {
        href: 'javascript:void(0)', text: '删除', 'data-perm': 'governance:manage', style: 'color:var(--error);font-size:13px',
        onclick: function () {
          if (delLink.getAttribute('data-busy') === '1') return;   // 删除中防重复触发
          var tip = hasChild
            ? '主题「' + nm + '」含 ' + kids.length + ' 个直接子主题，将连同其所有后代一并级联删除，且不可恢复，确认？'
            : '确认删除主题「' + nm + '」？此操作不可恢复。';
          confirmModal(tip, function () {
            delLink.setAttribute('data-busy', '1'); delLink.style.opacity = '0.5'; delLink.style.pointerEvents = 'none';
            DN.del(API + '/' + n.id).then(function () { DN.toast('已删除', 'success'); reloadFn(); })
              .catch(function (e) {
                DN.toast(e && e.message ? e.message : '删除失败', 'error');
                delLink.removeAttribute('data-busy'); delLink.style.opacity = ''; delLink.style.pointerEvents = '';
              });
          });
        }
      });
      ops.appendChild(delLink);
      row.appendChild(ops);
      container.appendChild(row);
      if (hasChild) renderNodes(kids, container, depth + 1, reloadFn, keep, counter);
    });
  }

  // ========== 编辑（弹窗改名 + 分层下拉） ==========
  function editNode(n, reloadFn) {
    var nameInput = DN.h('input', { class: 'dn-form-input', value: n.name || '', placeholder: '主题名称', maxlength: String(NAME_MAX) });
    var layerSel = DN.h('select', { class: 'dn-form-select' },
      LAYERS.map(function (l) { return DN.h('option', { value: l, text: l }); }));
    layerSel.value = LAYERS.indexOf(n.layer) >= 0 ? n.layer : 'ALL';  // 历史脏分层值回落 ALL,避免下拉空选
    modal('编辑主题', [formRow('主题名称', nameInput), formRow('适用分层', layerSel)], function (close, okBtn) {
      if (okBtn && okBtn.classList.contains('is-loading')) return;     // 提交中忽略重复点击
      var newName = nameInput.value.trim();
      if (!newName) { DN.toast('名称不能为空', 'error'); nameInput.focus(); return; }
      if (newName.length > NAME_MAX) { DN.toast('名称不能超过 ' + NAME_MAX + ' 个字符', 'error'); nameInput.focus(); return; }
      if (newName === (n.name || '') && layerSel.value === (n.layer || 'ALL')) { DN.toast('未修改任何内容'); close(); return; }
      setBtnBusy(okBtn, true, '保存中…');
      DN.api(API + '/' + n.id, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newName, parentId: n.parentId == null ? null : n.parentId, layer: layerSel.value, sortOrder: n.sortOrder })
      }).then(function () { DN.toast('已更新', 'success'); close(); reloadFn(); })
        .catch(function (e) { DN.toast(e && e.message ? e.message : '更新失败', 'error'); setBtnBusy(okBtn, false, '确定'); });
    });
  }

  // ========== 小工具 ==========
  function inp(ph) {
    return DN.h('input', { class: 'dn-form-input', placeholder: ph, style: 'width:auto' });
  }

  function formRow(label, control) {
    return DN.h('div', { class: 'ds-form-row', style: 'align-items:flex-start' }, [
      DN.h('label', { text: label }), control
    ]);
  }

  function confirmModal(text, onOk) {
    modal('确认操作', [DN.h('div', { style: 'font-size:13px;color:var(--text-regular)', text: text })],
      function (close) { close(); onOk(); });
  }

  function modal(title, bodyNodes, onOk) {
    var mask = DN.h('div', { style: 'position:fixed;inset:0;background:rgba(0,0,0,.35);z-index:9999;display:flex;align-items:center;justify-content:center' });
    var box = DN.h('div', { role: 'dialog', 'aria-modal': 'true', 'aria-label': title || '对话框', style: 'background:var(--bg-card);border-radius:var(--radius-lg);min-width:360px;max-width:90vw;max-height:80vh;overflow:auto;padding:20px;box-shadow:var(--shadow-md)' });
    box.appendChild(DN.h('div', { style: 'font-size:16px;font-weight:600;margin-bottom:16px', text: title }));
    bodyNodes.forEach(function (n) { box.appendChild(n); });
    var _closed = false;
    function close() { if (_closed) return; _closed = true; if (mask.parentNode) mask.parentNode.removeChild(mask); document.removeEventListener('keydown', onKey); }
    function onKey(e) { if (e.key === 'Escape') close(); }
    var okBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确定' });
    function fireOk() { onOk(close, okBtn); }   // 第二参回传 okBtn,供调用方禁用防重复提交
    okBtn.addEventListener('click', fireOk);
    var footer = DN.h('div', { style: 'text-align:right;margin-top:16px' });
    footer.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '取消', style: 'margin-right:8px', onclick: close }));
    footer.appendChild(okBtn);
    box.appendChild(footer);
    // 弹窗内输入框回车直接确定(textarea 除外),与表单交互一致
    box.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
      var tag = e.target && e.target.tagName;
      if (tag === 'TEXTAREA' || tag === 'A' || tag === 'BUTTON') return;
      if (okBtn.classList.contains('is-loading')) return;
      e.preventDefault(); fireOk();
    });
    mask.appendChild(box);
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(mask);
    // 打开后聚焦首个输入控件,便于直接键入
    var f = box.querySelector('input,select,textarea'); if (f) { try { f.focus(); } catch (e) {} }
  }

  // 按钮忙碌态:禁用并改文案,防重复提交;done 时恢复。统一 add/编辑确认按钮使用。
  function setBtnBusy(btn, busy, text) {
    if (!btn) return;
    if (busy) {
      btn.classList.add('is-loading');
      btn.setAttribute('aria-busy', 'true');
      btn.style.pointerEvents = 'none'; btn.style.opacity = '0.6';
      if (text != null) btn.textContent = text;
    } else {
      btn.classList.remove('is-loading');
      btn.removeAttribute('aria-busy');
      btn.style.pointerEvents = ''; btn.style.opacity = '';
      if (text != null) btn.textContent = text;
    }
  }
})();
