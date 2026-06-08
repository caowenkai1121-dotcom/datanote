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
    c.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-bottom:14px', text: '对黄金记录的变更（新增/修改/删除）发起审批请求；审批人可批准或驳回并填写意见，保证主数据变更可追溯、可管控。' }));
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

      var statusSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:120px' });
      [['', '全部状态'], ['pending', '待审批'], ['approved', '已批准'], ['rejected', '已驳回']].forEach(function (o) { statusSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
      statusSel.value = _filter;
      statusSel.onchange = function () { _filter = statusSel.value; loadApproval(statBox, box); };
      var card = DN.card({ title: '变更请求', icon: 'inbox', actions: statusSel });
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
              w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '详情', style: 'color:var(--primary,#3457d5)', onclick: function () { detailDrawer(r); } }));
              if (r.status === 'pending') {
                w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '批准', style: 'color:#389e0d', onclick: function () { reviewDrawer(r, 'approve', statBox, box); } }));
                w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '驳回', style: 'color:#e03131', onclick: function () { reviewDrawer(r, 'reject', statBox, box); } }));
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
    var pre = DN.h('pre', { style: 'margin:0;padding:10px 12px;border-radius:8px;background:var(--bg-soft,rgba(0,0,0,.04));font-size:12px;white-space:pre-wrap;word-break:break-all;max-height:320px;overflow:auto' });
    pre.textContent = prettyJson(r.payloadJson);
    body.appendChild(pre);
    DN.drawer('变更请求详情', body);
  }

  function kv(label, valNode) {
    var row = DN.h('div', { style: 'display:flex;gap:10px;margin-bottom:8px;font-size:13px' });
    row.appendChild(DN.h('span', { text: label, style: 'min-width:72px;color:var(--text-muted)' }));
    row.appendChild(valNode);
    return row;
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
    var fReviewer = DN.h('input', { class: 'iw-form-select', style: 'width:100%', placeholder: '审批人姓名/工号' });
    body.appendChild(field('审批人', fReviewer));
    var fComment = DN.h('textarea', { class: 'iw-form-select', style: 'width:100%;min-height:72px;resize:vertical', placeholder: isApprove ? '批准意见（可选）' : '驳回原因（建议填写）' });
    body.appendChild(field('审批意见', fComment));
    var footer = DN.h('div', { style: 'text-align:right;margin-top:10px' });
    var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: submitText });
    footer.appendChild(save); body.appendChild(footer);
    var dr = DN.drawer((isApprove ? '批准' : '驳回') + '变更请求', body);
    var submitting = false;          // 防重复提交（按钮再入守卫）
    save.onclick = function () {
      if (submitting) return;
      var reviewer = fReviewer.value.trim();
      if (!reviewer) { DN.toast('请填写审批人', 'err'); fReviewer.focus(); return; }
      // 破坏性/不可逆操作（审批结果落库）二次确认
      if (!window.confirm((isApprove ? '确认批准' : '确认驳回') + '该变更请求？\n' + bizLabel + '（' + typeLabel + '）\n审批人：' + reviewer)) return;
      var payload = { reviewer: reviewer, reviewComment: fComment.value.trim() };
      submitting = true;
      save.textContent = '提交中...'; save.style.pointerEvents = 'none'; save.classList.add('is-disabled');
      DN.post('/api/mdm/approval/' + encodeURIComponent(r.id) + '/' + action, payload).then(function () {
        DN.toast(isApprove ? '已批准' : '已驳回', 'ok'); dr.close(); loadApproval(statBox, box);
      }).catch(function (e) {
        submitting = false;
        DN.toast((e && e.message) || '操作失败', 'err');
        save.textContent = submitText; save.style.pointerEvents = ''; save.classList.remove('is-disabled');
      });
    };
    DN.enterSubmit(body);
  }

  function field(label, input) {
    var row = DN.h('div', { style: 'display:flex;flex-direction:column;gap:4px;margin-bottom:12px' });
    row.appendChild(DN.h('label', { text: label, style: 'font-size:12px;color:var(--text-muted)' }));
    row.appendChild(input);
    return row;
  }
})();
