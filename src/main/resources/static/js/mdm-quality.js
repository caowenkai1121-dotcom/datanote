/* 主数据管理(MDM) · 质量监控(Quality) 渲染器（独立文件）。
   选择实体 → 校验该实体全部 active/draft 黄金记录是否符合属性约束(必填/枚举/唯一/类型)，
   展示合规率磁贴 + 问题分类磁贴 + 不合规记录表(业务主键 + 问题pill清单)。
   复用 DN 共享套件(dn-common.js)，与 gov/mdm 现有模块 UI 完全一致。
   注册到 window.MDM_RENDERERS.quality；script 引入与 MDM_MODS 条目由主线串行装配。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  var _qEntity = null;   // 当前选中实体

  // 问题类型 → pill 色调（与文案前缀匹配，用于不合规记录的问题展示）
  function issueTone(issue) {
    if (issue.indexOf('缺必填') === 0) return 'err';
    if (issue.indexOf('枚举越界') === 0) return 'warn';
    if (issue.indexOf('唯一冲突') === 0) return 'info';
    if (issue.indexOf('类型错误') === 0) return 'warn';
    return 'muted';
  }

  // 合规率 → 色调（gauge 式语义着色）
  function scoreTone(score) {
    if (score >= 95) return 'ok';
    if (score >= 80) return 'info';
    if (score >= 60) return 'warn';
    return 'err';
  }

  window.MDM_RENDERERS.quality = function (c) {
    c.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-bottom:14px', text: '质量监控逐条校验黄金记录是否符合其实体的属性约束：必填项是否填写、枚举值是否在候选范围、唯一属性是否重复、数据类型格式(DATE/INT/DECIMAL/BOOLEAN)是否合法。仅统计生效/草稿记录，输出整体合规率与不合规清单。' }));
    var selWrap = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:14px' });
    selWrap.appendChild(DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '选择实体：' }));
    var entSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:240px' });
    entSel.appendChild(DN.h('option', { value: '', text: '加载中…' }));
    selWrap.appendChild(entSel);
    c.appendChild(selWrap);
    var box = DN.h('div', { id: 'qBox' });
    c.appendChild(box);

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
      _qEntity = ents[0];
      loadQuality(box);
      entSel.onchange = function () {
        _qEntity = ents.filter(function (e) { return String(e.id) === entSel.value; })[0] || null;
        box.innerHTML = '';
        if (_qEntity) loadQuality(box);
      };
    }).catch(function (e) {
      box.appendChild(DN.errorBox('实体加载失败: ' + e.message, function () { c.innerHTML = ''; MDM_RENDERERS.quality(c); }));
    });
  };

  function loadQuality(box) {
    if (!_qEntity) return;
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    Promise.all([
      DN.get('/api/mdm/quality/overview?entityId=' + encodeURIComponent(_qEntity.id)),
      DN.get('/api/mdm/quality/check?entityId=' + encodeURIComponent(_qEntity.id))
    ]).then(function (r) {
      var ov = r[0] || {}, chk = r[1] || {};
      box.innerHTML = '';

      var score = ov.score != null ? ov.score : 100;
      // 合规率磁贴
      box.appendChild(DN.statRow([
        { icon: 'shield', label: '合规率', value: score + '%', tone: scoreTone(score), sub: ov.compliant + ' / ' + (ov.total || 0) + ' 条合规' },
        { icon: 'list', label: '校验记录', value: ov.total || 0, sub: '生效+草稿' },
        { icon: 'check', label: '合规记录', value: ov.compliant || 0, tone: 'ok' },
        { icon: 'alert', label: '不合规记录', value: ov.nonCompliant || 0, tone: (ov.nonCompliant ? 'err' : 'ok') }
      ]));

      // 问题分类磁贴
      box.appendChild(DN.statRow([
        { icon: 'alert', label: '缺必填', value: ov.missingRequired || 0, tone: (ov.missingRequired ? 'err' : 'muted') },
        { icon: 'tag', label: '枚举越界', value: ov.enumOut || 0, tone: (ov.enumOut ? 'warn' : 'muted') },
        { icon: 'layers', label: '唯一冲突', value: ov.uniqueDup || 0, tone: (ov.uniqueDup ? 'warn' : 'muted') },
        { icon: 'doc', label: '类型错误', value: ov.typeErr || 0, tone: (ov.typeErr ? 'warn' : 'muted') }
      ]));

      renderRecordTable(box, chk);
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('加载失败: ' + e.message, function () { loadQuality(box); }));
    });
  }

  function renderRecordTable(box, chk) {
    var records = (chk && chk.records) || [];
    var bad = records.filter(function (r) { return !r.compliant; });
    var refreshBtn = DN.h('a', { href: 'javascript:void(0)', class: 'btn', text: '重新校验', onclick: function () { loadQuality(box); } });
    var card = DN.card({ title: '不合规记录', icon: 'alert', actions: refreshBtn });
    box.appendChild(card.el);

    if (!records.length) { card.body.appendChild(DN.empty('该实体暂无可校验的黄金记录', 'inbox')); return; }
    if (!bad.length) { card.body.appendChild(DN.empty('全部 ' + records.length + ' 条记录均合规 🎉', 'check')); return; }

    card.body.appendChild(DN.table({
      columns: [
        { key: 'bizKey', label: '业务主键', copyable: true, render: function (r) { return r.bizKey || ('#' + r.id); } },
        { key: 'status', label: '状态', render: function (r) {
            return r.status === 'active' ? DN.pill('生效', 'ok') : r.status === 'draft' ? DN.pill('草稿', 'warn') : DN.pill(r.status || '-', 'muted');
          } },
        { key: 'issueCount', label: '问题数', render: function (r) { return DN.pill(String(r.issueCount || 0), 'err'); } },
        { key: 'issues', label: '问题清单', render: function (r) {
            var w = DN.h('span', { style: 'display:inline-flex;gap:6px;flex-wrap:wrap' });
            (r.issues || []).forEach(function (iss) { w.appendChild(DN.pill(iss, issueTone(iss))); });
            return w;
          } },
        { key: '_fix', label: '操作', render: function (r) {
            // R26 闭环：检测→修复。跳回黄金记录处理，直接打开该不合规记录编辑
            return DN.h('a', { href: 'javascript:void(0)', class: 'btn', text: '修复', title: '前往黄金记录处理并打开该记录修复', onclick: function () {
              mdmGoModule('goldenrecord', { editId: r.id, entityId: (_qEntity ? _qEntity.id : null) });
            } });
          } }
      ],
      rows: bad, pageSize: 15, searchKeys: ['bizKey'], searchPlaceholder: '搜索业务主键', exportName: '质量不合规记录'
    }));
  }
})();
