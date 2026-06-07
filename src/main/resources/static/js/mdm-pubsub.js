/* 主数据管理(MDM) · 发布订阅(Pub/Sub) 渲染器（独立文件）。
   黄金记录变更向订阅系统发布：订阅方按实体+变更类型订阅，模拟发布时对匹配订阅写发布日志。
   统计磁贴 + 订阅表 + 新建订阅抽屉 + 模拟发布抽屉 + 发布日志表。
   复用 DN 共享套件(dn-common.js)，与 gov/mdm 现有模块 UI 完全一致。
   注册到 window.MDM_RENDERERS.pubsub；script 引入与 MDM_MODS 条目由主线串行装配。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  var _entities = [];          // 实体列表（下拉用）
  var _subs = [];              // 订阅列表缓存（用于新建时本地查重）
  var _logSubId = '';          // 发布日志当前订阅筛选

  var TYPE_LABEL = { create: '新增', update: '修改', delete: '删除' };
  var TYPE_TONE = { create: 'ok', update: 'info', delete: 'err' };
  var _goldenCache = {};       // 模拟发布抽屉黄金记录缓存（按实体ID，避免重复 fetch）

  // 错误信息归一化：e 可能是 Error/字符串/对象，统一取可读文案
  function errMsg(e, fallback) { return (e && e.message) ? e.message : (typeof e === 'string' && e ? e : (fallback || '操作失败')); }

  // 文本截断 + title 悬停看全文（超长推送地址/说明/业务主键不撑破表格）
  function truncCell(text, max) {
    var s = text == null ? '' : String(text);
    if (!s) return DN.h('span', { text: '-', style: 'color:var(--text-muted)' });
    max = max || 48;
    if (s.length <= max) return DN.h('span', { text: s });
    return DN.h('span', { text: s.slice(0, max) + '…', title: s, style: 'cursor:help' });
  }

  // 提交按钮置忙/复位：禁用点击 + 键盘提交(DN.enterSubmit 据 .disabled 跳过)，杜绝重复提交
  function setBusy(btn, busyText) {
    btn._idleText = btn._idleText || btn.textContent;
    btn.textContent = busyText || '处理中...';
    btn.classList.add('disabled'); btn.setAttribute('aria-disabled', 'true');
    btn.style.pointerEvents = 'none'; btn.style.opacity = '0.6';
  }
  function setIdle(btn) {
    if (btn._idleText != null) btn.textContent = btn._idleText;
    btn.classList.remove('disabled'); btn.removeAttribute('aria-disabled');
    btn.style.pointerEvents = ''; btn.style.opacity = '';
  }

  window.MDM_RENDERERS.pubsub = function (c) {
    _logSubId = '';            // 每次进入模块重置日志筛选，避免上次的“按订阅筛选”状态残留
    _goldenCache = {};         // 重置黄金记录缓存，确保取最新数据
    c.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-bottom:14px', text: '发布订阅将黄金记录变更（新增/修改/删除）向下游订阅系统推送。订阅方按实体与变更类型订阅，模拟发布时对匹配的启用订阅写入发布日志，可追溯每次推送结果。' }));
    var statBox = DN.h('div', { id: 'psStats' });
    statBox.appendChild(DN.skeleton(2));
    c.appendChild(statBox);
    var box = DN.h('div', { id: 'psBox' });
    c.appendChild(box);
    var logBox = DN.h('div', { id: 'psLogBox', style: 'margin-top:16px' });
    c.appendChild(logBox);
    // 先拉实体（下拉用），再加载
    DN.get('/api/mdm/entities').then(function (ents) {
      _entities = Array.isArray(ents) ? ents : [];
      loadAll(statBox, box, logBox);
    }).catch(function () {
      _entities = [];
      loadAll(statBox, box, logBox);
    });
  };

  function loadAll(statBox, box, logBox) {
    loadStats(statBox);
    loadSubscriptions(box, statBox, logBox);
    loadLogs(logBox);
  }

  function loadStats(statBox) {
    statBox.innerHTML = ''; statBox.appendChild(DN.skeleton(2));
    DN.get('/api/mdm/pubsub/stats').then(function (s) {
      s = s || {};
      statBox.innerHTML = '';
      statBox.appendChild(DN.statRow([
        { icon: 'tag', label: '订阅数', value: s.subscriptionCount || 0, sub: '启用 ' + (s.activeSubscriptionCount || 0), tone: 'info' },
        { icon: 'lineage', label: '发布次数', value: s.publishCount || 0, sub: '成功 ' + (s.successCount || 0) },
        { icon: 'check', label: '成功率', value: (s.successRate != null ? s.successRate : 100) + '%', tone: ((s.successRate != null ? s.successRate : 100) >= 99 ? 'ok' : 'warn') }
      ]));
    }).catch(function (e) {
      statBox.innerHTML = '';
      statBox.appendChild(DN.errorBox('统计加载失败: ' + (e && e.message ? e.message : e), function () { loadStats(statBox); }));
    });
  }

  function entityName(id) {
    for (var i = 0; i < _entities.length; i++) if (String(_entities[i].id) === String(id)) return _entities[i].entityName;
    return '#' + id;
  }

  // ---- 订阅表 ----
  function loadSubscriptions(box, statBox, logBox) {
    box.innerHTML = ''; box.appendChild(DN.skeleton(3));
    DN.get('/api/mdm/pubsub/subscriptions').then(function (rows) {
      rows = Array.isArray(rows) ? rows : [];
      _subs = rows;
      var addBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '+ 新建订阅', onclick: function () { subDrawer(null, box, statBox, logBox); } });
      var pubBtn = DN.h('a', { class: 'btn', href: 'javascript:void(0)', style: 'margin-left:8px', text: '模拟发布', onclick: function () { publishDrawer(statBox, logBox); } });
      var actions = DN.h('span', { style: 'display:inline-flex;align-items:center' }, [addBtn, pubBtn]);
      var card = DN.card({ title: '订阅管理', icon: 'tag', actions: actions });
      box.innerHTML = '';
      if (!rows.length) { card.body.appendChild(DN.empty('暂无订阅，点击右上角“新建订阅”', 'tag')); box.appendChild(card.el); return; }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'subscriberSystem', label: '订阅方', copyable: true, render: function (r) { return truncCell(r.subscriberSystem, 36); }, exportValue: function (r) { return r.subscriberSystem || ''; } },
          { key: 'entityName', label: '实体', render: function (r) { return r.entityName || ('#' + r.entityId); } },
          { key: 'changeTypes', label: '变更类型', render: function (r) {
              var w = DN.h('span', { style: 'display:inline-flex;gap:4px;flex-wrap:wrap' });
              (r.changeTypes ? r.changeTypes.split(',') : []).forEach(function (t) {
                if (t) w.appendChild(DN.pill(TYPE_LABEL[t] || t, TYPE_TONE[t] || 'info'));
              });
              return w.childNodes.length ? w : DN.h('span', { text: '-', style: 'color:var(--text-muted)' });
            } },
          { key: 'endpoint', label: '推送地址', copyable: true, render: function (r) { return truncCell(r.endpoint, 44); }, exportValue: function (r) { return r.endpoint || ''; } },
          { key: 'status', label: '状态', render: function (r) { return (r.status === 1 || r.status === '1') ? DN.pill('启用', 'ok') : DN.pill('停用', 'muted'); } },
          { key: 'updatedAt', label: '更新', render: function (r) { return DN.timeAgo(r.updatedAt); } },
          { key: '_op', label: '操作', render: function (r) {
              var w = DN.h('span', { style: 'display:inline-flex;gap:10px' });
              w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '日志', style: 'color:var(--primary,#1890ff)', onclick: function () { _logSubId = String(r.id); loadLogs(logBox); logBox.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); } }));
              w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '编辑', style: 'color:var(--primary,#1890ff)', onclick: function () { subDrawer(r, box, statBox, logBox); } }));
              w.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:#ff4d4f', onclick: function () { delSub(r, box, statBox, logBox); } }));
              return w;
            } }
        ],
        rows: rows, pageSize: 12, searchKeys: ['subscriberSystem', 'entityName', 'endpoint', 'changeTypes'], searchPlaceholder: '搜索订阅方/实体/推送地址/变更类型', exportName: '主数据订阅'
      }));
      box.appendChild(card.el);
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.errorBox('订阅加载失败: ' + (e && e.message ? e.message : e), function () { loadSubscriptions(box, statBox, logBox); }));
    });
  }

  function delSub(r, box, statBox, logBox) {
    if (!window.confirm('确认删除订阅「' + (r.subscriberSystem || ('#' + r.id)) + '」？发布日志将保留。')) return;
    DN.del('/api/mdm/pubsub/subscription/' + r.id).then(function () {
      DN.toast('已删除', 'ok'); loadSubscriptions(box, statBox, logBox); loadStats(statBox);
    }).catch(function (e) { DN.toast(errMsg(e, '删除失败'), 'err'); });
  }

  // ---- 新建/编辑 订阅抽屉 ----
  function subDrawer(r, box, statBox, logBox) {
    var isEdit = !!r;
    var body = DN.h('div', {});
    var fSubscriber = DN.h('input', { class: 'iw-form-select', style: 'width:100%', placeholder: '订阅方系统名称', maxlength: '64' });
    if (isEdit) fSubscriber.value = r.subscriberSystem || '';
    body.appendChild(field('订阅方系统', fSubscriber));

    var entSel = DN.h('select', { class: 'iw-form-select', style: 'width:100%' });
    if (!_entities.length) {
      entSel.appendChild(DN.h('option', { value: '', text: '(暂无实体，请先在“域与实体建模”创建)' }));
    } else {
      entSel.appendChild(DN.h('option', { value: '', text: '(请选择实体)' }));
      _entities.forEach(function (e) { entSel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + e.entityName })); });
    }
    if (isEdit) entSel.value = String(r.entityId);
    body.appendChild(field('订阅实体', entSel));

    var typesWrap = DN.h('div', { style: 'display:flex;gap:14px;flex-wrap:wrap' });
    var typeChecks = {};
    var existing = isEdit && r.changeTypes ? r.changeTypes.split(',') : ['create', 'update', 'delete'];
    ['create', 'update', 'delete'].forEach(function (t) {
      var cb = DN.h('input', { type: 'checkbox', value: t });
      if (existing.indexOf(t) >= 0) cb.checked = true;
      typeChecks[t] = cb;
      var lab = DN.h('label', { style: 'display:inline-flex;align-items:center;gap:6px;font-size:13px;cursor:pointer' }, [cb, DN.h('span', { text: TYPE_LABEL[t] })]);
      typesWrap.appendChild(lab);
    });
    body.appendChild(field('订阅变更类型', typesWrap));

    var fEndpoint = DN.h('input', { class: 'iw-form-select', style: 'width:100%', placeholder: 'http(s):// 推送地址', maxlength: '512' });
    if (isEdit) fEndpoint.value = r.endpoint || '';
    body.appendChild(field('推送地址', fEndpoint));

    var statusSel = DN.h('select', { class: 'iw-form-select', style: 'width:100%' });
    [['1', '启用'], ['0', '停用']].forEach(function (o) { statusSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
    statusSel.value = isEdit ? String(r.status) : '1';
    body.appendChild(field('状态', statusSel));

    var fDesc = DN.h('textarea', { class: 'iw-form-select', style: 'width:100%;min-height:64px;resize:vertical', placeholder: '订阅说明（可选）', maxlength: '500' });
    if (isEdit) fDesc.value = r.description || '';
    body.appendChild(field('描述', fDesc));

    var footer = DN.h('div', { style: 'text-align:right;margin-top:10px' });
    var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '保存' });
    footer.appendChild(save); body.appendChild(footer);
    var dr = DN.drawer(isEdit ? '编辑订阅' : '新建订阅', body);

    save.onclick = function () {
      if (save._busy) return;                 // 防重复提交（点击/回车均拦截）
      var subscriber = fSubscriber.value.trim();
      if (!subscriber) { DN.toast('请填写订阅方系统', 'err'); fSubscriber.focus(); return; }
      if (subscriber.length > 64) { DN.toast('订阅方系统名称不能超过 64 个字符', 'err'); fSubscriber.focus(); return; }
      if (!entSel.value) { DN.toast(_entities.length ? '请选择订阅实体' : '暂无实体，请先在“域与实体建模”创建', 'err'); entSel.focus(); return; }
      var types = ['create', 'update', 'delete'].filter(function (t) { return typeChecks[t].checked; });
      if (!types.length) { DN.toast('请至少订阅一种变更类型', 'err'); return; }
      var endpoint = fEndpoint.value.trim();
      if (!endpoint) { DN.toast('请填写推送地址', 'err'); fEndpoint.focus(); return; }
      if (!/^https?:\/\/.+/i.test(endpoint)) { DN.toast('推送地址须以 http:// 或 https:// 开头', 'err'); fEndpoint.focus(); return; }
      if (endpoint.length > 512) { DN.toast('推送地址不能超过 512 个字符', 'err'); fEndpoint.focus(); return; }
      // 本地查重：同订阅方 + 同实体 视为重复（编辑自身除外）
      var dup = _subs.filter(function (s) {
        return String(s.entityId) === entSel.value
          && String(s.subscriberSystem || '').trim().toLowerCase() === subscriber.toLowerCase()
          && (!isEdit || String(s.id) !== String(r.id));
      })[0];
      if (dup) { DN.toast('该订阅方对此实体已存在订阅，请勿重复创建', 'err'); fSubscriber.focus(); return; }
      if (fDesc.value.trim().length > 500) { DN.toast('描述不能超过 500 个字符', 'err'); fDesc.focus(); return; }
      var payload = {
        subscriberSystem: subscriber, entityId: Number(entSel.value), changeTypes: types.join(','),
        endpoint: endpoint, status: Number(statusSel.value), description: fDesc.value.trim()
      };
      if (isEdit) payload.id = r.id;
      save._busy = true; setBusy(save, '保存中...');
      DN.post('/api/mdm/pubsub/subscription/save', payload).then(function () {
        DN.toast('已保存', 'ok'); dr.close(); loadSubscriptions(box, statBox, logBox); loadStats(statBox);
      }).catch(function (e) { DN.toast(errMsg(e, '保存失败'), 'err'); save._busy = false; setIdle(save); });
    };
    DN.enterSubmit(body);
  }

  // ---- 模拟发布抽屉 ----
  function publishDrawer(statBox, logBox) {
    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 12px', text: '选择实体与一条黄金记录，模拟一次变更发布。系统将对该实体下启用且订阅了对应变更类型的订阅写入发布日志。' }));

    var entSel = DN.h('select', { class: 'iw-form-select', style: 'width:100%' });
    entSel.appendChild(DN.h('option', { value: '', text: _entities.length ? '(请选择实体)' : '(暂无实体)' }));
    _entities.forEach(function (e) { entSel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + e.entityName })); });
    body.appendChild(field('实体', entSel));

    var recSel = DN.h('select', { class: 'iw-form-select', style: 'width:100%' });
    recSel.appendChild(DN.h('option', { value: '', text: '(先选择实体)' }));
    body.appendChild(field('黄金记录', recSel));

    function fillRecs(recs) {
      recs = Array.isArray(recs) ? recs : [];
      recSel.innerHTML = '';
      if (!recs.length) { recSel.appendChild(DN.h('option', { value: '', text: '(该实体暂无黄金记录)' })); return; }
      recSel.appendChild(DN.h('option', { value: '', text: '(请选择黄金记录)' }));
      recs.forEach(function (g) {
        if (!g || g.id == null) return;
        recSel.appendChild(DN.h('option', { value: g.id, text: (g.bizKey || ('#' + g.id)) + ' [' + (g.status || '') + ']' }));
      });
    }
    entSel.onchange = function () {
      var eid = entSel.value;
      if (!eid) { recSel.innerHTML = ''; recSel.appendChild(DN.h('option', { value: '', text: '(先选择实体)' })); return; }
      if (_goldenCache[eid]) { fillRecs(_goldenCache[eid]); return; }   // 命中缓存，免重复 fetch
      recSel.innerHTML = ''; recSel.appendChild(DN.h('option', { value: '', text: '加载中…' }));
      DN.get('/api/mdm/golden/list?entityId=' + encodeURIComponent(eid)).then(function (recs) {
        if (entSel.value !== eid) return;        // 期间已切换实体，丢弃过期响应
        _goldenCache[eid] = Array.isArray(recs) ? recs : [];
        fillRecs(_goldenCache[eid]);
      }).catch(function (e) {
        if (entSel.value !== eid) return;
        recSel.innerHTML = ''; recSel.appendChild(DN.h('option', { value: '', text: '(加载失败：' + errMsg(e, '请重试') + ')' }));
      });
    };

    var typeSel = DN.h('select', { class: 'iw-form-select', style: 'width:100%' });
    [['update', '修改'], ['create', '新增'], ['delete', '删除']].forEach(function (o) { typeSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
    body.appendChild(field('变更类型', typeSel));

    var footer = DN.h('div', { style: 'text-align:right;margin-top:10px' });
    var save = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确认发布' });
    footer.appendChild(save); body.appendChild(footer);
    var dr = DN.drawer('模拟发布', body);

    save.onclick = function () {
      if (save._busy) return;                  // 防重复提交
      if (!entSel.value) { DN.toast('请先选择实体', 'err'); entSel.focus(); return; }
      if (!recSel.value) { DN.toast('请选择黄金记录', 'err'); recSel.focus(); return; }
      save._busy = true; setBusy(save, '发布中...');
      DN.post('/api/mdm/pubsub/publish', { goldenRecordId: Number(recSel.value), changeType: typeSel.value }).then(function (res) {
        var n = res && res.matchedSubscriptions != null ? res.matchedSubscriptions : 0;
        DN.toast(n > 0 ? ('已发布至 ' + n + ' 个订阅') : '无匹配的启用订阅', n > 0 ? 'ok' : 'info');
        _goldenCache = {};   // 黄金记录可能因本次变更失效，清缓存以便下次取最新
        dr.close(); loadStats(statBox); loadLogs(logBox);
      }).catch(function (e) { DN.toast(errMsg(e, '发布失败'), 'err'); save._busy = false; setIdle(save); });
    };
    DN.enterSubmit(body);
  }

  // ---- 发布日志表 ----
  function loadLogs(logBox) {
    logBox.innerHTML = ''; logBox.appendChild(DN.skeleton(2));
    var url = '/api/mdm/pubsub/logs' + (_logSubId ? '?subscriptionId=' + encodeURIComponent(_logSubId) : '');
    DN.get(url).then(function (rows) {
      rows = Array.isArray(rows) ? rows : [];
      var actions = DN.h('span', { style: 'display:inline-flex;align-items:center;gap:8px' });
      if (_logSubId) actions.appendChild(DN.h('a', { href: 'javascript:void(0)', class: 'btn', text: '清除筛选', onclick: function () { _logSubId = ''; loadLogs(logBox); } }));
      var card = DN.card({ title: '发布日志' + (_logSubId ? '（按订阅 #' + _logSubId + ' 筛选）' : ''), icon: 'list', actions: actions });
      logBox.innerHTML = '';
      if (!rows.length) { card.body.appendChild(DN.empty(_logSubId ? '该订阅暂无发布记录' : '暂无发布记录，点击“模拟发布”', 'list')); logBox.appendChild(card.el); return; }
      card.body.appendChild(DN.table({
        columns: [
          { key: 'subscriberSystem', label: '订阅方', render: function (r) { return truncCell(r.subscriberSystem || ('#' + r.subscriptionId), 36); }, exportValue: function (r) { return r.subscriberSystem || ('#' + r.subscriptionId); } },
          { key: 'bizKey', label: '业务主键', copyable: true, render: function (r) { return truncCell(r.bizKey || ('#' + r.goldenRecordId), 40); }, exportValue: function (r) { return r.bizKey || ('#' + r.goldenRecordId); } },
          { key: 'changeType', label: '变更类型', render: function (r) { return DN.pill(TYPE_LABEL[r.changeType] || r.changeType || '-', TYPE_TONE[r.changeType] || 'info'); } },
          { key: 'status', label: '结果', render: function (r) { return r.status === 'success' ? DN.pill('成功', 'ok') : DN.pill('失败', 'err'); } },
          { key: 'message', label: '说明', render: function (r) { return truncCell(r.message, 56); }, exportValue: function (r) { return r.message || ''; } },
          { key: 'publishedAt', label: '发布时间', render: function (r) { return DN.timeAgo(r.publishedAt); } }
        ],
        rows: rows, pageSize: 12, searchKeys: ['subscriberSystem', 'bizKey', 'message'], searchPlaceholder: '搜索订阅方/业务主键/说明', exportName: '主数据发布日志'
      }));
      logBox.appendChild(card.el);
    }).catch(function (e) {
      logBox.innerHTML = '';
      logBox.appendChild(DN.errorBox('日志加载失败: ' + (e && e.message ? e.message : e), function () { loadLogs(logBox); }));
    });
  }

  function field(label, input) {
    var row = DN.h('div', { style: 'display:flex;flex-direction:column;gap:4px;margin-bottom:12px' });
    row.appendChild(DN.h('label', { text: label, style: 'font-size:12px;color:var(--text-muted)' }));
    row.appendChild(input);
    return row;
  }
})();
