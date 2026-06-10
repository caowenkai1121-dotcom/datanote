/* 主数据管理(MDM) — 存活性规则(Survivorship) 渲染器（独立文件）。
   选择实体 → 列出该实体属性，逐属性配置存活策略(latest/most_complete/source_priority)并保存；
   表格展示已配规则。复用 DN 共享套件(dn-common.js)，与 gov/mdm 现有模块 UI 完全一致。
   注册到 window.MDM_RENDERERS.survivorship；script 引入与 MDM_MODS 条目由主线串行装配。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  var _svEntity = null;       // 当前选中实体
  var _svAttrs = [];          // 当前实体属性 schema
  var _svRules = {};          // attrCode -> rule（已配规则索引）
  var _stratLoaded = false;   // 策略枚举是否已成功拉取（避免重复 fetch，该枚举为静态字典）
  var _strategies = [         // 策略枚举（来自后端，含兜底默认）
    { value: 'latest', label: '最新值', desc: '取更新时间最新的来源值' },
    { value: 'most_complete', label: '最完整', desc: '取非空且长度最长（信息最完整）的值' },
    { value: 'source_priority', label: '源系统优先', desc: '按指定源系统优先级清单依次取值' }
  ];

  function strategyLabel(v) {
    for (var i = 0; i < _strategies.length; i++) if (_strategies[i].value === v) return _strategies[i].label;
    return v || '-';
  }
  function strategyTone(v) {
    return v === 'source_priority' ? 'warn' : (v === 'most_complete' ? 'info' : 'ok');
  }
  // 超长文本截断展示：超过 max 字符截断并以 title 挂全文（避免表格被撑爆）
  function truncCell(text, max) {
    var s = text == null ? '' : String(text);
    max = max || 40;
    if (s.length <= max) return DN.h('span', { text: s });
    return DN.h('span', { text: s.slice(0, max) + '…', title: s });
  }

  window.MDM_RENDERERS.survivorship = function (c) {
    // 深链上下文：mdmGoModule('survivorship', { entityId }) 进入时自动选中该实体（与其他 MDM 模块一致）
    var ctx = window.__mdmCtx || {};
    var wantEntityId = ctx.entityId != null ? String(ctx.entityId) : '';
    var selWrap = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:14px' });
    selWrap.appendChild(DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '选择实体：' }));
    var entSel = DN.h('select', { class: 'dn-form-select', style: 'min-width:240px', disabled: 'disabled' });
    entSel.appendChild(DN.h('option', { value: '', text: '加载中…' }));
    selWrap.appendChild(entSel);
    c.appendChild(selWrap);
    var box = DN.h('div', { id: 'svBox' });
    box.appendChild(DN.skeleton(3));
    c.appendChild(box);

    // 策略枚举：仅首次拉取（失败用兜底），避免每次进模块重复 fetch
    if (!_stratLoaded) {
      DN.get('/api/mdm/survivorship/strategies').then(function (s) {
        if (s && s.length) { _strategies = s; _stratLoaded = true; }
      }).catch(function () {});
    }

    // 实体列表：拉一次后在本次渲染生命周期内复用（onchange/重试不再打接口，避免重复 fetch）；
    // 每次重新进入模块都会重新执行本渲染函数并刷新列表，规避在建模处新建实体后的列表过期。
    function applyEntities(ents) {
      ents = ents || [];
      entSel.innerHTML = '';
      entSel.disabled = false;
      if (!ents.length) {
        entSel.appendChild(DN.h('option', { value: '', text: '(暂无实体，请先在“域与实体建模”创建)' }));
        entSel.disabled = true;
        box.innerHTML = '';
        box.appendChild(DN.empty('请先在“域与实体建模”创建实体与属性', 'layers'));
        return;
      }
      entSel.appendChild(DN.h('option', { value: '', text: '(请选择实体)' }));
      ents.forEach(function (e) { entSel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + e.entityName })); });
      // 优先按深链 entityId 选中，命中则用之，否则回退第一个
      var initial = (wantEntityId && ents.filter(function (e) { return String(e.id) === wantEntityId; })[0]) || ents[0];
      entSel.value = String(initial.id);
      _svEntity = initial;
      loadSurvivorship(box);
      entSel.onchange = function () {
        _svEntity = ents.filter(function (e) { return String(e.id) === entSel.value; })[0] || null;
        box.innerHTML = '';
        if (_svEntity) loadSurvivorship(box);
        else box.appendChild(DN.empty('请先在上方选择一个实体', 'layers'));  // 空选择明确提示
      };
    }

    DN.get('/api/mdm/entities').then(applyEntities).catch(function (e) {
      entSel.innerHTML = ''; entSel.appendChild(DN.h('option', { value: '', text: '(加载失败)' }));
      box.innerHTML = '';
      box.appendChild(DN.errorBox('实体加载失败: ' + ((e && e.message) || e), function () { c.innerHTML = ''; MDM_RENDERERS.survivorship(c); }));
    });
  };

  function loadSurvivorship(box) {
    if (!_svEntity || _svEntity.id == null) { box.innerHTML = ''; box.appendChild(DN.empty('请先在上方选择一个实体', 'layers')); return; }
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    Promise.all([
      DN.get('/api/mdm/attributes?entityId=' + encodeURIComponent(_svEntity.id)),
      DN.get('/api/mdm/survivorship/list?entityId=' + encodeURIComponent(_svEntity.id))
    ]).then(function (r) {
      r = r || [];
      _svAttrs = Array.isArray(r[0]) ? r[0] : [];
      var rules = Array.isArray(r[1]) ? r[1] : [];
      _svRules = {};
      rules.forEach(function (ru) { if (ru && ru.attrCode != null) _svRules[ru.attrCode] = ru; });
      box.innerHTML = '';

      var configured = rules.length;
      var total = _svAttrs.length;
      box.appendChild(DN.statRow([
        { icon: 'list', label: '实体属性', value: total },
        { icon: 'check', label: '已配规则', value: configured, tone: (configured ? 'ok' : 'muted') },
        { icon: 'alert', label: '未配属性', value: Math.max(0, total - configured), tone: ((total - configured) > 0 ? 'warn' : 'ok') },
        { icon: 'shield', label: '源优先规则', value: rules.filter(function (ru) { return ru.strategy === 'source_priority'; }).length, sub: '需源系统清单' }
      ]));

      renderAttrConfigCard(box);
      renderRuleTableCard(box, rules);
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('加载失败: ' + ((e && e.message) || e), function () { loadSurvivorship(box); }));
    });
  }

  // ---- 属性列表 + 逐属性配置存活策略 ----
  function renderAttrConfigCard(box) {
    // R37 深链：存活规则按实体配置 → 直达该实体黄金记录（合并后存活策略的最终落地处）
    var goGolden = DN.h('a', { href: 'javascript:void(0)', class: 'btn', text: '查看该实体黄金记录', title: '前往黄金记录并选中该实体', onclick: function () {
      if (!window.mdmGoModule) { DN.toast('暂不支持跳转', 'err'); return; }
      mdmGoModule('goldenrecord', { entityId: _svEntity.id });
    } });
    var card = DN.card({ title: '属性存活策略 · ' + _svEntity.entityName, icon: 'list', actions: goGolden });
    if (!_svAttrs.length) {
      card.body.appendChild(DN.empty('该实体暂无属性，请先在“域与实体建模”补充属性', 'list'));
      box.appendChild(card.el);
      return;
    }
    card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 10px', text: '为每个属性选择合并时的存活策略，点“保存”落库；已配规则会回填。' }));
    card.body.appendChild(DN.table({
      columns: [
        { key: 'attrCode', label: '属性编码', copyable: true, render: function (r) { return r.attrCode || '-'; } },
        { key: 'attrName', label: '属性名称', render: function (r) { return r.attrName || '-'; } },
        { key: 'dataType', label: '类型', render: function (r) { return DN.pill(r.dataType || 'STRING', 'info'); } },
        { key: '_strategy', label: '存活策略', render: function (r) {
            var ru = _svRules[r.attrCode];
            return ru ? DN.pill(strategyLabel(ru.strategy), strategyTone(ru.strategy)) : DN.h('span', { text: '未配置', style: 'color:var(--text-muted)' });
          } },
        { key: '_op', label: '操作', render: function (r) {
            var w = DN.h('span', { style: 'display:inline-flex;gap:10px' });
            var ru = _svRules[r.attrCode];
            w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: ru ? '编辑' : '配置', style: 'color:var(--primary)', onclick: function () { ruleForm(r, ru, box); } }));
            if (ru) w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error)', onclick: function () { delRule(ru, box); } }));
            return w;
          } }
      ],
      rows: _svAttrs, pageSize: 20, searchKeys: ['attrCode', 'attrName', 'dataType'], searchPlaceholder: '搜索属性编码/名称/类型'
    }));
    box.appendChild(card.el);
  }

  // ---- 已配规则一览表 ----
  function renderRuleTableCard(box, rules) {
    var card = DN.card({ title: '已配存活规则', icon: 'shield' });
    if (!rules.length) {
      card.body.appendChild(DN.empty('该实体暂无存活规则，在上方属性列表为属性配置策略', 'shield'));
      box.appendChild(card.el);
      return;
    }
    card.body.appendChild(DN.table({
      columns: [
        { key: 'priority', label: '优先级', align: 'right', sortable: true, render: function (r) { return String(r.priority == null ? 0 : r.priority); } },
        { key: 'attrCode', label: '属性编码', copyable: true, render: function (r) { return r.attrCode || '-'; } },
        { key: 'attrName', label: '属性名称', render: function (r) { return r.attrName || '-'; } },
        { key: 'strategy', label: '存活策略', render: function (r) { return DN.pill(strategyLabel(r.strategy), strategyTone(r.strategy)); } },
        { key: 'sourcePriority', label: '源系统优先级', render: function (r) { return r.sourcePriority ? truncCell(r.sourcePriority, 48) : DN.h('span', { text: '-', style: 'color:var(--text-muted)' }); } },
        { key: '_op', label: '操作', render: function (r) {
            var w = DN.h('span', { style: 'display:inline-flex;gap:10px' });
            w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary)', onclick: function () {
                var attr = _svAttrs.filter(function (a) { return a.attrCode === r.attrCode; })[0] || { attrCode: r.attrCode, attrName: r.attrName };
                ruleForm(attr, r, box);
              } }));
            w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error)', onclick: function () { delRule(r, box); } }));
            return w;
          } }
      ],
      rows: rules, pageSize: 20, searchKeys: ['attrCode', 'attrName', 'strategy', 'sourcePriority'], searchPlaceholder: '搜索属性/策略/源系统', exportName: '存活规则_' + (_svEntity.entityCode || _svEntity.entityName || _svEntity.id)
    }));
    box.appendChild(card.el);
  }

  function inp(val, ph) { return DN.h('input', { class: 'dn-form-input', style: 'width:100%', value: val == null ? '' : String(val), placeholder: ph || '' }); }

  function ruleForm(attr, rule, box) {
    attr = attr || {};
    if (!_svEntity || _svEntity.id == null) { DN.toast('请先选择实体', 'err'); return; }
    if (!attr.attrCode) { DN.toast('该属性缺少编码，无法配置规则', 'err'); return; }
    var isEdit = !!rule; rule = rule || {};
    var stratSel = DN.h('select', { class: 'dn-form-select', style: 'width:100%' });
    _strategies.forEach(function (s) {
      var op = DN.h('option', { value: s.value, text: s.label });
      if (String(rule.strategy || 'latest') === s.value) op.selected = true;
      stratSel.appendChild(op);
    });
    var fSrc = inp(rule.sourcePriority, '如 CRM,ERP,MES（逗号分隔，优先级从高到低）');
    var fPriority = inp(rule.priority == null ? '' : rule.priority, '规则优先级，数字越小越优先');

    var body = DN.h('div', {});

    var secInfo = DN.formSection('所属信息');
    secInfo.add(DN.formGrid2([
      DN.field('所属实体', DN.h('input', { class: 'dn-form-input', style: 'width:100%', value: _svEntity.entityName, disabled: 'disabled' })),
      DN.field('属性', DN.h('input', { class: 'dn-form-input', style: 'width:100%', value: (attr.attrName || '') + ' (' + attr.attrCode + ')', disabled: 'disabled' }))
    ]));
    body.appendChild(secInfo.el);

    var secStrat = DN.formSection('策略配置');
    var stratHint = DN.h('div', { style: 'font-size:var(--fs-xs);color:var(--text-muted);margin-top:-6px;margin-bottom:8px' });
    var srcRow = DN.field('源系统优先级清单', fSrc, { hint: '仅「源系统优先」策略需要，逗号分隔' });
    secStrat.add(DN.field('存活策略', stratSel, { required: true }));
    secStrat.add(stratHint);
    secStrat.add(srcRow);
    secStrat.add(DN.field('规则优先级', fPriority, { hint: '数字越小越优先，留空默认为 0' }));
    body.appendChild(secStrat.el);

    function syncStrategyUI() {
      var v = stratSel.value;
      for (var i = 0; i < _strategies.length; i++) if (_strategies[i].value === v) stratHint.textContent = _strategies[i].desc || '';
      srcRow.style.display = (v === 'source_priority') ? '' : 'none';
    }
    syncStrategyUI();
    stratSel.onchange = syncStrategyUI;

    var dr, foot;
    var doSave = function () {
      if (foot.ok.disabled) return;
      var strategy = stratSel.value;
      // 校验：策略必须为已知枚举值（防脏数据落库）
      var known = _strategies.some(function (s) { return s.value === strategy; });
      if (!strategy || !known) { DN.toast('请选择有效的存活策略', 'err'); return; }
      var srcVal = fSrc.value.trim();
      if (strategy === 'source_priority') {
        if (!srcVal) { DN.toast('「源系统优先」策略需填写源系统优先级清单', 'err'); return; }
        // 规整清单：去空段、去首尾空格，按逗号回拼
        var srcList = srcVal.split(',').map(function (x) { return x.trim(); }).filter(function (x) { return x; });
        if (!srcList.length) { DN.toast('源系统优先级清单无有效项', 'err'); return; }
        srcVal = srcList.join(',');
      } else {
        srcVal = '';                                          // 非源优先策略不应携带清单
      }
      // 校验：规则优先级须为非负整数
      var prTxt = fPriority.value.trim(), priority = 0;
      if (prTxt) {
        if (!/^\d+$/.test(prTxt)) { DN.toast('规则优先级须为非负整数', 'err'); return; }
        priority = parseInt(prTxt, 10);
      }
      var payload = {
        id: rule.id, entityId: _svEntity.id, attrCode: attr.attrCode, attrName: attr.attrName || rule.attrName,
        strategy: strategy, sourcePriority: srcVal,
        priority: priority
      };
      foot.busy('保存中...');
      DN.post('/api/mdm/survivorship/save', payload).then(function () { DN.toast('已保存', 'ok'); dr.close(); loadSurvivorship(box); })
        .catch(function (e) { DN.toast((e && e.message) || '保存失败', 'err'); foot.reset('保存'); });
    };
    foot = DN.drawerFoot({ okText: '保存', onOk: doSave, onCancel: function () { dr.close(); } });
    dr = DN.drawer((isEdit ? '编辑' : '配置') + '存活规则', body, foot.el);
    DN.enterSubmit(body, doSave);
  }

  var _delBusy = false;       // 删除中互斥，防同一规则被重复提交
  function delRule(rule, box) {
    if (!rule || rule.id == null) { DN.toast('该规则缺少 id，无法删除', 'err'); return; }
    if (_delBusy) return;
    DN.confirm('删除属性「' + (rule.attrName || rule.attrCode) + '」的存活规则？', { title: '删除确认', danger: true }).then(function (ok) {
      if (!ok) return;
      _delBusy = true;
      DN.del('/api/mdm/survivorship/' + encodeURIComponent(rule.id)).then(function () { _delBusy = false; DN.toast('已删除', 'ok'); loadSurvivorship(box); })
        .catch(function (e) { _delBusy = false; DN.toast((e && e.message) || '删除失败', 'err'); });
    });
  }
})();
