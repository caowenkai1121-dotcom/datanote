/* 主数据管理(MDM) · 变更审批 渲染器 —— 对黄金记录的变更走审批流。
   复用 DN 共享套件(dn-common.js) 与 mdm/gov 同款 UI 风格。
   独立文件，注册到 window.MDM_RENDERERS.approval；主线负责 script 引入与 MDM_MODS 条目。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  var _filter = '';   // 状态筛选
  var _loading = false; // 加载中标记，防并发重复请求

  var TYPE_LABEL = { create: '新增', update: '修改', delete: '删除' };
  var TYPE_TONE = { create: 'ok', update: 'info', delete: 'err' };

  // 超长文本截断（列表内展示），完整内容挂 title
  function truncate(s, n) {
    s = (s == null ? '' : String(s));
    n = n || 40;
    return s.length > n ? s.slice(0, n) + '…' : s;
  }

  // 业务主键展示：优先 bizKey，回退黄金记录ID/请求ID，全空则 '-'（避免 #undefined）
  function bizKeyOf(r) {
    if (!r) return '-';
    if (r.bizKey) return r.bizKey;
    var fb = (r.goldenRecordId != null ? r.goldenRecordId : r.id);
    return fb != null ? '#' + fb : '-';
  }

  window.MDM_RENDERERS.approval = function (c) {
    if (!c) return;
    _loading = false; // 重新进入模块时清掉可能残留的加载锁（上次加载被切走未完成）
    var statBox = DN.h('div', { id: 'apStats' });
    statBox.appendChild(DN.skeleton(2));
    c.appendChild(statBox);
    var box = DN.h('div', { id: 'apBox' });
    c.appendChild(box);
    loadApproval(statBox, box);
  };

  function loadApproval(statBox, box) {
    if (!statBox || !box) return;
    if (_loading) return;            // 防并发：上一次请求未完成时忽略重复触发
    _loading = true;
    statBox.innerHTML = ''; statBox.appendChild(DN.skeleton(2));
    box.innerHTML = ''; box.appendChild(DN.skeleton(3));
    Promise.all([
      DN.get('/api/mdm/approval/stats'),
      DN.get('/api/mdm/approval/list' + (_filter ? '?status=' + encodeURIComponent(_filter) : ''))
    ]).then(function (r) {
      _loading = false;
      r = r || [];
      var stats = r[0] || {}, rows = Array.isArray(r[1]) ? r[1] : [];
      statBox.innerHTML = '';
      statBox.appendChild(DN.statRow([
        { icon: 'clock', label: '待审批', value: stats.pending || 0, tone: (stats.pending ? 'warn' : 'ok') },
        { icon: 'check', label: '已批准', value: stats.approved || 0, tone: 'ok' },
        { icon: 'alert', label: '已驳回', value: stats.rejected || 0, tone: (stats.rejected ? 'err' : 'muted') },
        { icon: 'list', label: '变更总数', value: stats.total || 0 }
      ]));

      var statusSel = DN.h('select', { class: 'dn-form-select', style: 'min-width:120px' });
      [['', '全部状态'], ['pending', '待审批'], ['approved', '已批准'], ['rejected', '已驳回']].forEach(function (o) { statusSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
      statusSel.value = _filter;
      statusSel.onchange = function () { _filter = statusSel.value; loadApproval(statBox, box); };
      var newBtn = DN.h('a', { class: 'btn btn-sm btn-primary', href: 'javascript:void(0)', text: '＋ 新建变更申请', onclick: function () { submitDrawer(statBox, box); } });
      var acts = DN.h('span', { style: 'display:inline-flex;gap:10px;align-items:center' }, [newBtn, statusSel]);
      var card = DN.card({ title: '变更请求', icon: 'inbox', actions: acts });
      box.innerHTML = '';
      if (!rows.length) { card.body.appendChild(DN.empty(_filter ? '该状态下暂无变更请求' : '暂无变更请求', 'inbox')); box.appendChild(card.el); return; }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'changeType', label: '类型', render: function (r) { return DN.pill(TYPE_LABEL[r.changeType] || r.changeType || '-', TYPE_TONE[r.changeType] || 'info'); } },
          { key: 'entityName', label: '实体', render: function (r) { return r.entityName || (r.entityId != null ? '#' + r.entityId : '-'); } },
          { key: 'bizKey', label: '业务主键', copyable: true, exportValue: bizKeyOf, render: function (r) { return bizKeyOf(r); } },
          { key: 'reason', label: '变更原因', render: function (r) {
              var txt = r.reason || '-';
              var span = DN.h('span', { text: truncate(txt, 40) });
              if (txt !== '-' && txt.length > 40) span.title = txt; // 超长原因截断+悬停看全文
              return span;
            } },
          { key: 'status', label: '状态', render: function (r) {
              var s = r.status;
              return s === 'approved' ? DN.pill('已批准', 'ok') : s === 'rejected' ? DN.pill('已驳回', 'err') : DN.pill('待审批', 'warn');
            } },
          { key: 'requestedBy', label: '申请人', render: function (r) { return r.requestedBy || '-'; } },
          { key: 'reviewer', label: '审批人', render: function (r) {
              if (!r.reviewer) return DN.h('span', { text: '-', style: 'color:var(--text-muted)' });
              var w = DN.h('span', {}, [DN.h('span', { text: r.reviewer })]);
              if (r.reviewComment) w.title = '审批意见：' + truncate(r.reviewComment, 200);
              return w;
            } },
          { key: 'updatedAt', label: '更新', render: function (r) { return DN.timeAgo(r.updatedAt); } },
          { key: '_op', label: '操作', render: function (r) {
              var w = DN.h('span', { style: 'display:inline-flex;gap:10px' });
              w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '详情', style: 'color:var(--primary)', onclick: function () { detailDrawer(r); } }));
              if (r.status === 'pending') {
                w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '批准', style: 'color:var(--success)', onclick: function () { reviewDrawer(r, 'approve', statBox, box); } }));
                w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '驳回', style: 'color:var(--error)', onclick: function () { reviewDrawer(r, 'reject', statBox, box); } }));
              }
              // R128 联动: 已关联黄金记录的请求可直跳该记录的变更历史(diff)
              if (r.goldenRecordId != null && window.mdmGoModule) {
                w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '记录历史', style: 'color:var(--primary)', title: '查看该黄金记录的变更历史与版本diff', onclick: function () { window.mdmGoModule('goldenrecord', { entityId: r.entityId, historyId: r.goldenRecordId }); } }));
              }
              return w;
            } }
        ],
        rows: rows, pageSize: 15, searchKeys: ['bizKey', 'reason', 'requestedBy', 'reviewer', 'entityName'], searchPlaceholder: '搜索业务主键/原因/申请人/审批人/实体', exportName: '变更审批'
      }));
      box.appendChild(card.el);
    }).catch(function (e) {
      _loading = false;
      statBox.innerHTML = ''; box.innerHTML = '';
      box.appendChild(DN.errorBox('加载失败: ' + (e && e.message ? e.message : e), function () { loadApproval(statBox, box); }));
    });
  }

  // ---- 新建变更申请抽屉(补齐审批闭环的"发起"端) ----
  function submitDrawer(statBox, box) {
    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 12px', text: '对某实体的黄金记录发起变更申请(新增/修改/删除), 提交后进入待审批, 由审批人批准或驳回。' }));
    var sec = DN.formSection('申请信息');
    var fEntity = DN.h('select', { class: 'dn-form-select', style: 'width:100%' });
    fEntity.appendChild(DN.h('option', { value: '', text: '加载实体中…' }));
    var fType = DN.h('select', { class: 'dn-form-select', style: 'width:100%' });
    [['create', '新增'], ['update', '修改'], ['delete', '删除']].forEach(function (o) { fType.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
    var fBizKey = DN.h('input', { class: 'dn-form-input', style: 'width:100%', placeholder: '业务主键(如客户编码, 修改/删除时填)' });
    var fReason = DN.h('textarea', { class: 'dn-form-input', style: 'width:100%;min-height:64px;resize:vertical', placeholder: '变更原因(必填)' });
    var fBy = DN.h('input', { class: 'dn-form-input', style: 'width:100%', placeholder: '申请人姓名/工号' });
    var fPayload = DN.h('textarea', { class: 'dn-form-input', style: 'width:100%;min-height:72px;resize:vertical;font-family:ui-monospace,Menlo,Consolas,monospace', placeholder: '变更内容(JSON, 可选; 如 {"客户名":"新值"})' });
    sec.add(DN.field('所属实体', fEntity, { required: true }));
    sec.add(DN.field('变更类型', fType, { required: true }));
    sec.add(DN.field('业务主键', fBizKey));
    sec.add(DN.field('变更原因', fReason, { required: true }));
    sec.add(DN.field('申请人', fBy));
    sec.add(DN.field('变更内容', fPayload));
    body.appendChild(sec.el);
    // 拉实体下拉
    DN.get('/api/mdm/entities').then(function (list) {
      fEntity.innerHTML = '';
      var arr = Array.isArray(list) ? list : [];
      if (!arr.length) { fEntity.appendChild(DN.h('option', { value: '', text: '(暂无实体, 请先在域与实体建模中创建)' })); return; }
      fEntity.appendChild(DN.h('option', { value: '', text: '请选择实体' }));
      arr.forEach(function (e) { fEntity.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + (e.entityName || ('#' + e.id)) })); });
    }).catch(function () { fEntity.innerHTML = ''; fEntity.appendChild(DN.h('option', { value: '', text: '(实体加载失败)' })); });

    var dr, foot;
    var doSave = function () {
      if (foot.ok.disabled) return;
      var entityId = fEntity.value;
      var reason = fReason.value.trim();
      if (!entityId) { DN.toast('请选择所属实体', 'err'); fEntity.focus(); return; }
      if (!reason) { DN.toast('请填写变更原因', 'err'); fReason.focus(); return; }
      var payload = fPayload.value.trim();
      if (payload) { try { JSON.parse(payload); } catch (e) { DN.toast('变更内容不是合法 JSON', 'err'); fPayload.focus(); return; } }
      var reqBody = { entityId: Number(entityId), changeType: fType.value, bizKey: fBizKey.value.trim() || null, reason: reason, requestedBy: fBy.value.trim() || null, payloadJson: payload || null };
      foot.busy('提交中...');
      DN.post('/api/mdm/approval/submit', reqBody).then(function () {
        DN.toast('变更申请已提交, 进入待审批', 'ok'); dr.close(); loadApproval(statBox, box);
      }).catch(function (e) { DN.toast((e && e.message) || '提交失败', 'err'); foot.reset('提交申请'); });
    };
    foot = DN.drawerFoot({ okText: '提交申请', onOk: doSave, onCancel: function () { dr.close(); } });
    dr = DN.drawer('新建变更申请', body, foot.el);
    DN.enterSubmit(body, doSave);
  }

  // ---- 变更详情抽屉 ----
  function detailDrawer(r) {
    if (!r) { DN.toast('记录数据缺失，无法查看详情', 'err'); return; }
    var body = DN.h('div', {});
    body.appendChild(kv('变更类型', DN.pill(TYPE_LABEL[r.changeType] || r.changeType || '-', TYPE_TONE[r.changeType] || 'info')));
    body.appendChild(kv('实体', DN.h('span', { text: r.entityName || (r.entityId != null ? '#' + r.entityId : '-') })));
    body.appendChild(kv('业务主键', DN.h('span', { text: bizKeyOf(r) })));
    body.appendChild(kv('变更原因', DN.h('span', { text: r.reason || '-' })));
    body.appendChild(kv('申请人', DN.h('span', { text: r.requestedBy || '-' })));
    var st = r.status === 'approved' ? DN.pill('已批准', 'ok') : r.status === 'rejected' ? DN.pill('已驳回', 'err') : DN.pill('待审批', 'warn');
    body.appendChild(kv('状态', st));
    if (r.reviewer) body.appendChild(kv('审批人', DN.h('span', { text: r.reviewer })));
    if (r.reviewComment) body.appendChild(kv('审批意见', DN.h('span', { text: r.reviewComment })));
    body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:14px 0 6px', text: '变更内容(JSON)：' }));
    var pre = DN.h('pre', { style: 'margin:0;padding:10px 12px;border-radius:var(--radius-lg);background:var(--bg-soft);font-size:12px;white-space:pre-wrap;word-break:break-all;max-height:320px;overflow:auto' });
    pre.textContent = prettyJson(r.payloadJson);
    body.appendChild(pre);
    // R128 联动: 已关联黄金记录 → 直达变更历史; 待审批的 update → 预览「当前值→新值」diff
    if (r.goldenRecordId != null && window.mdmGoModule) {
      body.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '查看该黄金记录的变更历史 →', style: 'display:inline-block;margin-top:12px;color:var(--primary);font-size:13px', onclick: function () { window.mdmGoModule('goldenrecord', { entityId: r.entityId, historyId: r.goldenRecordId }); } }));
    }
    if (r.status === 'pending' && r.changeType === 'update' && r.payloadJson) {
      var diffBox = DN.h('div', { style: 'margin-top:14px' });
      body.appendChild(diffBox);
      previewDiff(r, diffBox);
    }
    DN.drawer('变更请求详情', body);
  }

  // R128: 待审批 update 请求的变更预览 — 拉当前黄金记录, 按 payload 键展示「当前值→新值」
  function previewDiff(r, box) {
    var patch;
    try { patch = JSON.parse(r.payloadJson); } catch (e) { return; }
    if (!patch || typeof patch !== 'object') return;
    DN.get('/api/mdm/golden/list?entityId=' + encodeURIComponent(r.entityId)).then(function (rows) {
      rows = rows || [];
      var rec = null;
      if (r.goldenRecordId != null) rec = rows.filter(function (g) { return String(g.id) === String(r.goldenRecordId); })[0];
      if (!rec && r.bizKey) rec = rows.filter(function (g) { return g.bizKey === r.bizKey && g.status !== 'inactive'; })[0];
      if (!rec) return;   // 找不到目标记录则不展示预览(审批时后端会再校验)
      var cur;
      try { cur = rec.dataJson ? JSON.parse(rec.dataJson) : {}; } catch (e) { cur = {}; }
      box.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 6px', text: '变更预览（批准后将应用，当前值 → 新值）：' }));
      Object.keys(patch).forEach(function (k) {
        var a = cur[k] == null ? '' : String(cur[k]);
        var b = patch[k] == null ? '' : String(patch[k]);
        var same = a === b;
        box.appendChild(DN.h('div', { class: 'dn-diff-row' }, [
          DN.h('span', { class: 'dk', text: k }),
          DN.h('span', same ? { style: 'color:var(--text-muted)', text: a === '' ? '(空)' : a } : { class: 'da', text: a === '' ? '(空)' : a }),
          DN.h('span', { text: '→' }),
          DN.h('span', same ? { style: 'color:var(--text-muted)', text: (b === '' ? '(空)' : b) + '（无变化）' } : { class: 'db', text: b === '' ? '(空)' : b })
        ]));
      });
    }).catch(function () { /* 预览失败静默, 不影响详情主体 */ });
  }

  function kv(label, valNode) {
    return DN.h('div', { class: 'dn-kv' }, [
      DN.h('span', { class: 'k', text: label }),
      DN.h('span', { class: 'v' }, [valNode])
    ]);
  }

  function prettyJson(s) {
    if (!s) return '(无)';
    try { return JSON.stringify(JSON.parse(s), null, 2); } catch (e) { return s; }
  }

  // ---- 审批抽屉（批准/驳回，填写审批人 + 意见） ----
  function reviewDrawer(r, action, statBox, box) {
    if (!r || r.id == null) { DN.toast('记录数据缺失，无法审批', 'err'); return; }
    if (r.status !== 'pending') { DN.toast('仅待审批的请求可被审批', 'warn'); return; }
    var isApprove = action === 'approve';
    var bizLabel = bizKeyOf(r);
    var typeLabel = TYPE_LABEL[r.changeType] || r.changeType || '-';
    var submitText = isApprove ? '确认批准' : '确认驳回';

    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 12px', text: (isApprove ? '批准' : '驳回') + '变更请求：' + bizLabel + '（' + typeLabel + '）' }));

    var sec = DN.formSection('审批信息');
    var fReviewer = DN.h('input', { class: 'dn-form-input', style: 'width:100%', placeholder: '审批人姓名/工号' });
    var fComment = DN.h('textarea', { class: 'dn-form-input', style: 'width:100%;min-height:72px;resize:vertical', placeholder: isApprove ? '批准意见（可选）' : '驳回原因（建议填写）' });
    sec.add(DN.field('审批人', fReviewer, { required: true }));
    sec.add(DN.field('审批意见', fComment));
    body.appendChild(sec.el);

    var dr, foot;
    var doSave = function () {
      if (foot.ok.disabled) return;
      var reviewer = fReviewer.value.trim();
      if (!reviewer) { DN.toast('请填写审批人', 'err'); fReviewer.focus(); return; }
      var payload = { reviewer: reviewer, reviewComment: fComment.value.trim() };
      foot.busy(submitText === '确认批准' ? '批准中...' : '驳回中...');
      DN.post('/api/mdm/approval/' + encodeURIComponent(r.id) + '/' + action, payload).then(function () {
        DN.toast(isApprove ? '已批准' : '已驳回', 'ok'); dr.close(); loadApproval(statBox, box);
      }).catch(function (e) {
        DN.toast((e && e.message) || '操作失败', 'err');
        foot.reset(submitText);
      });
    };
    foot = DN.drawerFoot({ okText: submitText, onOk: doSave, onCancel: function () { dr.close(); } });
    dr = DN.drawer((isApprove ? '批准' : '驳回') + '变更请求', body, foot.el);
    DN.enterSubmit(body, doSave);
  }
})();
