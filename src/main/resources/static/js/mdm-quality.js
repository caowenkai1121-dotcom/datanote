/* 主数据管理(MDM) · 质量监控(Quality) 渲染器（独立文件）。
   选择实体 → 校验该实体全部 active/draft 黄金记录是否符合属性约束(必填/枚举/唯一/类型)，
   展示合规率磁贴 + 问题分类磁贴 + 不合规记录表(业务主键 + 问题pill清单)。
   复用 DN 共享套件(dn-common.js)，与 gov/mdm 现有模块 UI 完全一致。
   注册到 window.MDM_RENDERERS.quality；script 引入与 MDM_MODS 条目由主线串行装配。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  var _qEntity = null;   // 当前选中实体
  var _qEntsCache = null; // 实体列表缓存（去重 fetch，切换实体不重复请求）
  var _qLoadToken = 0;    // 加载令牌：防止快速切换实体时旧请求覆盖新结果（竞态守卫）

  // 问题类型 → pill 色调（与文案前缀匹配，用于不合规记录的问题展示）
  // 注意：DN.pill 的 tone 枚举只认 ok/warn/err/info/muted。
  function issueTone(issue) {
    issue = String(issue == null ? '' : issue);
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
    _qEntsCache = null;   // 每次进模块清实体缓存, 防新增/删实体后列表陈旧(切换实体时仍走缓存)
    var ctx = window.__mdmCtx || {};   // R26/R37 深链：上游(govmap/工作台)可带 entityId 直达指定实体
    var selWrap = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:14px' });
    selWrap.appendChild(DN.h('span', { class: 'gov-desc', style: 'margin:0', text: '选择实体：' }));
    var entSel = DN.h('select', { class: 'dn-form-select', style: 'min-width:240px', disabled: 'disabled' });
    entSel.appendChild(DN.h('option', { value: '', text: '加载中…' }));
    selWrap.appendChild(entSel);
    c.appendChild(selWrap);
    var box = DN.h('div', { id: 'qBox' });
    box.appendChild(DN.skeleton(3));   // 实体列表请求期间先占位骨架，避免空白闪烁
    c.appendChild(box);

    function fillEntities(ents) {
      ents = Array.isArray(ents) ? ents : [];
      entSel.innerHTML = '';
      entSel.removeAttribute('disabled');
      box.innerHTML = '';
      if (!ents.length) {
        entSel.appendChild(DN.h('option', { value: '', text: '(暂无实体，请先在“域与实体建模”创建)' }));
        box.appendChild(DN.empty('请先在“域与实体建模”创建实体与属性', 'layers'));
        return;
      }
      entSel.appendChild(DN.h('option', { value: '', text: '(请选择实体)' }));
      ents.forEach(function (e) {
        if (!e) return;
        entSel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + (e.entityName || ('#' + e.id)) }));
      });
      // 深链命中则选中目标实体，否则默认第一个
      var ctxEnt = ctx.entityId != null ? ents.filter(function (e) { return e && String(e.id) === String(ctx.entityId); })[0] : null;
      var initEnt = ctxEnt || ents[0];
      entSel.value = String(initEnt.id);
      _qEntity = initEnt;
      loadQuality(box);
      entSel.onchange = function () {
        _qEntity = ents.filter(function (e) { return e && String(e.id) === entSel.value; })[0] || null;
        box.innerHTML = '';
        if (_qEntity) loadQuality(box);
        else box.appendChild(DN.empty('请先在上方选择一个实体后再校验', 'layers')); // 空实体选择兜底
      };
    }

    // 去重 fetch：实体列表全局缓存，切换/返回本模块复用，无需重复请求
    if (_qEntsCache) { fillEntities(_qEntsCache); return; }
    DN.get('/api/mdm/entities').then(function (ents) {
      _qEntsCache = Array.isArray(ents) ? ents : [];
      fillEntities(_qEntsCache);
    }).catch(function (e) {
      entSel.innerHTML = '';
      entSel.removeAttribute('disabled');
      entSel.appendChild(DN.h('option', { value: '', text: '(加载失败)' }));
      box.innerHTML = '';
      box.appendChild(DN.errorBox('实体加载失败: ' + (e && e.message ? e.message : e), function () { _qEntsCache = null; c.innerHTML = ''; MDM_RENDERERS.quality(c); }));
    });
  };

  // isRecheck=true 表示用户手动“重新校验”，完成后给出 toast 反馈
  function loadQuality(box, isRecheck) {
    if (!_qEntity || !box) { if (box) { box.innerHTML = ''; box.appendChild(DN.empty('请先在上方选择一个实体后再校验', 'layers')); } return; }
    var token = ++_qLoadToken;            // 抢占令牌：仅最新一次请求允许渲染
    var entId = _qEntity.id;
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    Promise.all([
      DN.get('/api/mdm/quality/overview?entityId=' + encodeURIComponent(entId)),
      DN.get('/api/mdm/quality/check?entityId=' + encodeURIComponent(entId))
    ]).then(function (r) {
      if (token !== _qLoadToken) return;  // 已被更新的切换抢占，丢弃旧结果(竞态守卫)
      var ov = (r && r[0]) || {}, chk = (r && r[1]) || {};
      box.innerHTML = '';

      var score = ov.score != null ? ov.score : 100;
      var compliant = ov.compliant || 0, total = ov.total || 0;
      // 合规率磁贴
      box.appendChild(DN.statRow([
        { icon: 'shield', label: '合规率', value: score + '%', tone: scoreTone(score), sub: compliant + ' / ' + total + ' 条合规' },
        { icon: 'list', label: '校验记录', value: total, sub: '生效+草稿' },
        { icon: 'check', label: '合规记录', value: compliant, tone: 'ok' },
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
      if (isRecheck) DN.toast('校验完成：合规率 ' + score + '%', (ov.nonCompliant ? 'warn' : 'ok'));
    }).catch(function (e) {
      if (token !== _qLoadToken) return;
      box.innerHTML = '';
      box.appendChild(DN.errorBox('加载失败: ' + (e && e.message ? e.message : e), function () { loadQuality(box, isRecheck); }));
      if (isRecheck) DN.toast('校验失败', 'err');
    });
  }

  var MAX_PILLS = 6;   // 单行最多展示的问题 pill 数，超出折叠为“+N”，避免超长清单撑破布局/卡顿

  function renderRecordTable(box, chk) {
    var records = (chk && Array.isArray(chk.records)) ? chk.records : [];
    var bad = records.filter(function (r) { return r && !r.compliant; });
    // 重新校验：点击后禁用按钮防重复提交，完成/失败后自动恢复
    var refreshBtn = DN.h('a', { href: 'javascript:void(0)', class: 'btn', text: '重新校验', onclick: function () {
      if (refreshBtn.classList.contains('is-disabled')) return;
      refreshBtn.classList.add('is-disabled'); refreshBtn.style.pointerEvents = 'none'; refreshBtn.style.opacity = '0.6'; refreshBtn.textContent = '校验中…';
      loadQuality(box, true);   // box 内容会被整体重绘，按钮随之重建，无需手动复位
    } });
    var card = DN.card({ title: '不合规记录', icon: 'alert', actions: refreshBtn });
    box.appendChild(card.el);

    if (!records.length) { card.body.appendChild(DN.empty('该实体暂无可校验的黄金记录', 'inbox')); return; }
    if (!bad.length) { card.body.appendChild(DN.empty('全部 ' + records.length + ' 条记录均合规', 'check')); return; }

    card.body.appendChild(DN.table({
      columns: [
        { key: 'bizKey', label: '业务主键', copyable: true,
          exportValue: function (r) { return r.bizKey || ('#' + r.id); },
          render: function (r) {
            var raw = r.bizKey || ('#' + r.id);
            var s = String(raw);
            // 超长业务主键截断展示，title 悬浮看全文
            return s.length > 40 ? DN.h('span', { title: s, text: s.slice(0, 40) + '…', style: 'cursor:help' }) : DN.h('span', { text: s });
          } },
        { key: 'status', label: '状态', render: function (r) {
            return r.status === 'active' ? DN.pill('生效', 'ok') : r.status === 'draft' ? DN.pill('草稿', 'warn') : DN.pill(r.status || '-', 'muted');
          } },
        { key: 'issueCount', label: '问题数', align: 'right', sortable: true, render: function (r) { return DN.pill(String(r.issueCount || 0), 'err'); } },
        { key: 'issues', label: '问题清单', render: function (r) {
            var w = DN.h('span', { style: 'display:inline-flex;gap:6px;flex-wrap:wrap' });
            var issues = Array.isArray(r.issues) ? r.issues : [];
            issues.slice(0, MAX_PILLS).forEach(function (iss) {
              var t = String(iss == null ? '' : iss);
              // 单条问题文案过长也截断 + title，保持单元格整洁
              var pill = DN.pill(t.length > 36 ? t.slice(0, 36) + '…' : t, issueTone(t));
              if (t.length > 36) pill.title = t;
              w.appendChild(pill);
            });
            if (issues.length > MAX_PILLS) {
              var more = DN.pill('+' + (issues.length - MAX_PILLS), 'muted');
              more.title = issues.slice(MAX_PILLS).join('\n'); // 折叠项悬浮可见全文
              w.appendChild(more);
            }
            return w;
          } },
        { key: '_fix', label: '操作', render: function (r) {
            var w = DN.h('span', { style: 'display:inline-flex;gap:8px;align-items:center' });
            // R26 闭环：检测→修复。跳回黄金记录处理，直接打开该不合规记录编辑
            w.appendChild(DN.h('a', { href: 'javascript:void(0)', class: 'btn', text: '修复', title: '前往黄金记录处理并打开该记录修复', onclick: function () {
              if (!window.mdmGoModule) { DN.toast('暂不支持跳转', 'err'); return; }
              mdmGoModule('goldenrecord', { editId: r.id, entityId: (_qEntity ? _qEntity.id : null) });
            } }));
            // R123 联动: 把主数据不合规记录【升级为治理工单】, 纳入全局治理工单闭环(分配/流转/关单), 破 MDM↔治理 隔离
            w.appendChild(DN.h('a', { href: 'javascript:void(0)', class: 'btn', 'data-perm': 'governance:issue', text: '升级工单', title: '生成治理工单纳入统一闭环跟踪', onclick: function () {
              var bk = r.bizKey || ('#' + r.id);
              DN.confirm('为不合规记录「' + bk + '」生成治理工单(纳入治理工单闭环跟踪)？', { title: '升级工单确认' }).then(function (ok) {
                if (!ok) return;
                var ent = _qEntity || {}, issues = Array.isArray(r.issues) ? r.issues : [];
                DN.post('/api/gov/health/issues', {
                  issueType: 'MDM', dimension: '主数据质量',
                  severity: ((r.issueCount || issues.length) > 2 ? 'HIGH' : 'MEDIUM'),
                  title: '[主数据质量] ' + (ent.entityName || ('实体#' + (ent.id || ''))) + ' 记录 ' + bk + ' 不合规(' + (r.issueCount || issues.length) + '项)',
                  description: issues.join('; ') + '\n来源: 主数据质量监控\n实体: ' + (ent.entityName || '') + ' (#' + (ent.id || '') + ') 记录 #' + r.id,
                  objectRef: 'mdm:' + (ent.id || '') + ':' + r.id
                }).then(function (res) {
                  DN.toast('已生成治理工单' + (res && res.id ? ' #' + res.id : '') + '，可在 治理→治理健康分→工单 跟踪', 'ok');
                }).catch(function (e) { DN.toast('生成失败: ' + (e && e.message ? e.message : '请稍后重试'), 'err'); });
              });
            } }));
            return w;
          } }
      ],
      rows: bad, pageSize: 15, searchKeys: ['bizKey'], searchPlaceholder: '搜索业务主键', exportName: '质量不合规记录'
    }));
  }
})();
