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

  window.MDM_RENDERERS.survivorship = function (c) {
    c.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-bottom:14px', text: '存活性规则定义某实体每个属性在黄金记录合并时的存活策略，用于多源冲突时自动选最佳值。最新值/最完整/源系统优先三选一，源系统优先可指定优先级清单。' }));
    var selWrap = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:14px' });
    selWrap.appendChild(DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '选择实体：' }));
    var entSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:240px' });
    entSel.appendChild(DN.h('option', { value: '', text: '加载中…' }));
    selWrap.appendChild(entSel);
    c.appendChild(selWrap);
    var box = DN.h('div', { id: 'svBox' });
    c.appendChild(box);

    // 并行拉策略枚举（失败用兜底）与实体列表
    DN.get('/api/mdm/survivorship/strategies').then(function (s) { if (s && s.length) _strategies = s; }).catch(function () {});

    DN.get('/api/mdm/entities').then(function (ents) {
      ents = ents || [];
      entSel.innerHTML = '';
      if (!ents.length) {
        entSel.appendChild(DN.h('option', { value: '', text: '(暂无实体，请先在“域与实体建模”创建)' }));
        box.appendChild(DN.empty('请先在“域与实体建模”创建实体与属性', 'layers'));
        return;
      }
      entSel.appendChild(DN.h('option', { value: '', text: '(请选择实体)' }));
      ents.forEach(function (e) { entSel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + e.entityName })); });
      entSel.value = String(ents[0].id);
      _svEntity = ents[0];
      loadSurvivorship(box);
      entSel.onchange = function () {
        _svEntity = ents.filter(function (e) { return String(e.id) === entSel.value; })[0] || null;
        box.innerHTML = '';
        if (_svEntity) loadSurvivorship(box);
      };
    }).catch(function (e) {
      box.appendChild(DN.errorBox('实体加载失败: ' + e.message, function () { c.innerHTML = ''; MDM_RENDERERS.survivorship(c); }));
    });
  };

  function loadSurvivorship(box) {
    if (!_svEntity) return;
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    Promise.all([
      DN.get('/api/mdm/attributes?entityId=' + encodeURIComponent(_svEntity.id)),
      DN.get('/api/mdm/survivorship/list?entityId=' + encodeURIComponent(_svEntity.id))
    ]).then(function (r) {
      _svAttrs = r[0] || [];
      var rules = r[1] || [];
      _svRules = {};
      rules.forEach(function (ru) { _svRules[ru.attrCode] = ru; });
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
      box.appendChild(DN.errorBox('加载失败: ' + e.message, function () { loadSurvivorship(box); }));
    });
  }

  // ---- 属性列表 + 逐属性配置存活策略 ----
  function renderAttrConfigCard(box) {
    var card = DN.card({ title: '属性存活策略 · ' + _svEntity.entityName, icon: 'list' });
    if (!_svAttrs.length) {
      card.body.appendChild(DN.empty('该实体暂无属性，请先在“域与实体建模”补充属性', 'list'));
      box.appendChild(card.el);
      return;
    }
    card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 10px', text: '为每个属性选择合并时的存活策略，点“保存”落库；已配规则会回填。' }));
    card.body.appendChild(DN.table({
      columns: [
        { key: 'attrCode', label: '属性编码', copyable: true, render: function (r) { return r.attrCode; } },
        { key: 'attrName', label: '属性名称', render: function (r) { return r.attrName; } },
        { key: 'dataType', label: '类型', render: function (r) { return DN.pill(r.dataType || 'STRING', 'info'); } },
        { key: '_strategy', label: '存活策略', render: function (r) {
            var ru = _svRules[r.attrCode];
            return ru ? DN.pill(strategyLabel(ru.strategy), strategyTone(ru.strategy)) : DN.h('span', { text: '未配置', style: 'color:var(--text-muted)' });
          } },
        { key: '_op', label: '操作', render: function (r) {
            var w = DN.h('span', { style: 'display:inline-flex;gap:10px' });
            var ru = _svRules[r.attrCode];
            w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: ru ? '编辑' : '配置', style: 'color:var(--primary,#1890ff)', onclick: function () { ruleForm(r, ru, box); } }));
            if (ru) w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:#ff4d4f', onclick: function () { delRule(ru, box); } }));
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
        { key: 'attrCode', label: '属性编码', copyable: true, render: function (r) { return r.attrCode; } },
        { key: 'attrName', label: '属性名称', render: function (r) { return r.attrName || '-'; } },
        { key: 'strategy', label: '存活策略', render: function (r) { return DN.pill(strategyLabel(r.strategy), strategyTone(r.strategy)); } },
        { key: 'sourcePriority', label: '源系统优先级', render: function (r) { return r.sourcePriority ? r.sourcePriority : DN.h('span', { text: '-', style: 'color:var(--text-muted)' }); } },
        { key: '_op', label: '操作', render: function (r) {
            var w = DN.h('span', { style: 'display:inline-flex;gap:10px' });
            w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#1890ff)', onclick: function () {
                var attr = _svAttrs.filter(function (a) { return a.attrCode === r.attrCode; })[0] || { attrCode: r.attrCode, attrName: r.attrName };
                ruleForm(attr, r, box);
              } }));
            w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:#ff4d4f', onclick: function () { delRule(r, box); } }));
            return w;
          } }
      ],
      rows: rules, pageSize: 20, searchKeys: ['attrCode', 'attrName', 'strategy', 'sourcePriority'], searchPlaceholder: '搜索属性/策略/源系统', exportName: '存活规则_' + _svEntity.entityCode
    }));
    box.appendChild(card.el);
  }

  // ---- 表单（DN.drawer） ----
  function field(label, input, hint) {
    var row = DN.h('div', { style: 'display:flex;flex-direction:column;gap:4px;margin-bottom:12px' });
    row.appendChild(DN.h('label', { text: label, style: 'font-size:12px;color:var(--text-muted)' }));
    row.appendChild(input);
    if (hint) row.appendChild(DN.h('div', { style: 'font-size:11px;color:var(--text-muted)', text: hint }));
    return row;
  }
  function inp(val, ph) { return DN.h('input', { class: 'iw-form-select', style: 'width:100%', value: val == null ? '' : String(val), placeholder: ph || '' }); }

  function ruleForm(attr, rule, box) {
    var isEdit = !!rule; rule = rule || {};
    var stratSel = DN.h('select', { class: 'iw-form-select', style: 'width:100%' });
    _strategies.forEach(function (s) {
      var op = DN.h('option', { value: s.value, text: s.label });
      if (String(rule.strategy || 'latest') === s.value) op.selected = true;
      stratSel.appendChild(op);
    });
    var fSrc = inp(rule.sourcePriority, '如 CRM,ERP,MES（逗号分隔，优先级从高到低）');
    var fPriority = inp(rule.priority == null ? '' : rule.priority, '规则优先级，数字越小越优先');

    var body = DN.h('div', {});
    body.appendChild(field('所属实体', DN.h('input', { class: 'iw-form-select', style: 'width:100%', value: _svEntity.entityName, disabled: 'disabled' })));
    body.appendChild(field('属性', DN.h('input', { class: 'iw-form-select', style: 'width:100%', value: (attr.attrName || '') + ' (' + attr.attrCode + ')', disabled: 'disabled' })));
    var stratHint = DN.h('div', { style: 'font-size:11px;color:var(--text-muted)' });
    body.appendChild(field('存活策略', stratSel));
    body.appendChild(stratHint);
    var srcRow = field('源系统优先级清单', fSrc, '仅「源系统优先」策略需要，逗号分隔');
    body.appendChild(srcRow);
    body.appendChild(field('规则优先级', fPriority));

    function syncStrategyUI() {
      var v = stratSel.value;
      for (var i = 0; i < _strategies.length; i++) if (_strategies[i].value === v) stratHint.textContent = _strategies[i].desc || '';
      srcRow.style.display = (v === 'source_priority') ? '' : 'none';
    }
    syncStrategyUI();
    stratSel.onchange = syncStrategyUI;

    var footer = DN.h('div', { style: 'text-align:right;margin-top:10px' });
    var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '保存' });
    footer.appendChild(save); body.appendChild(footer);
    var dr = DN.drawer((isEdit ? '编辑' : '配置') + '存活规则', body);
    save.onclick = function () {
      var strategy = stratSel.value;
      var srcVal = fSrc.value.trim();
      if (strategy === 'source_priority' && !srcVal) { DN.toast('「源系统优先」策略需填写源系统优先级清单', 'err'); return; }
      var payload = {
        id: rule.id, entityId: _svEntity.id, attrCode: attr.attrCode, attrName: attr.attrName || rule.attrName,
        strategy: strategy, sourcePriority: srcVal,
        priority: fPriority.value.trim() ? parseInt(fPriority.value, 10) : 0
      };
      save.textContent = '保存中...'; save.style.pointerEvents = 'none';
      DN.post('/api/mdm/survivorship/save', payload).then(function () { DN.toast('已保存', 'ok'); dr.close(); loadSurvivorship(box); })
        .catch(function (e) { DN.toast(e.message || '保存失败', 'err'); save.textContent = '保存'; save.style.pointerEvents = ''; });
    };
    DN.enterSubmit(body);
  }

  function delRule(rule, box) {
    if (!window.confirm('删除属性「' + (rule.attrName || rule.attrCode) + '」的存活规则？')) return;
    DN.del('/api/mdm/survivorship/' + rule.id).then(function () { DN.toast('已删除', 'ok'); loadSurvivorship(box); })
      .catch(function (e) { DN.toast(e.message || '删除失败', 'err'); });
  }
})();
