/* 治理模块：治理健康分 + 工单闭环 + DCMM 成熟度自评（M11）
   健康分大环形 + 五维明细表 + 工单现代表格(状态药丸/流转) + 排行榜条形 + DCMM 雷达卡片。
   抽屉内表单录入，状态机与后端 IssueService 一致。零图表库依赖。 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var DIMS = ['规范', '质量', '安全', '生命周期', '血缘'];
  var ISSUE_STATUS = ['OPEN', 'FIXING', 'RESOLVED', 'VERIFIED', 'CLOSED'];
  // 状态机（与后端 IssueService 一致），供给前端流转按钮
  var FLOW = {
    OPEN: ['FIXING', 'CLOSED'],
    FIXING: ['RESOLVED', 'OPEN'],
    RESOLVED: ['VERIFIED', 'FIXING'],
    VERIFIED: ['CLOSED', 'FIXING'],
    CLOSED: ['OPEN']
  };
  // 工单状态 → 药丸色调
  var STATUS_TONE = {
    OPEN: 'info', FIXING: 'warn', RESOLVED: 'ok', VERIFIED: 'ok', CLOSED: 'muted'
  };

  var issueTable = null; // DN.table 句柄，便于 reload
  var selectedIssues = {}; // 批量流转选中：id -> 当前状态
  var boardOwnerFilter = ''; // 排行榜点击联动：按负责人客户端过滤工单

  window.GOV_RENDERERS.health = function (c) {
    // ---- 健康分 ----
    var hc = DN.card({
      title: '治理健康分', icon: 'shield',
      actions: [DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '重算并快照', onclick: refreshScore })]
    });
    hc.el.classList.add('primary');
    hc.body.appendChild(DN.h('div', { id: 'hsTop' }, DN.skeleton(3)));
    hc.body.appendChild(DN.h('div', { id: 'hsTrend', style: 'margin-top:14px' }));
    c.appendChild(hc.el);

    // ---- 工单 ----
    var fsel = DN.h('select', { id: 'hsIssueFilter', class: 'iw-form-select', style: 'width:auto' });
    fsel.appendChild(DN.h('option', { value: '', text: '全部状态' }));
    ISSUE_STATUS.forEach(function (s) { fsel.appendChild(DN.h('option', { value: s, text: s })); });
    fsel.addEventListener('change', loadIssues);
    var batchSel = DN.h('select', { id: 'hsBatchTo', class: 'iw-form-select', style: 'width:auto' });
    batchSel.appendChild(DN.h('option', { value: '', text: '批量流转到…' }));
    ISSUE_STATUS.forEach(function (s) { batchSel.appendChild(DN.h('option', { value: s, text: s })); });
    var icCard = DN.card({
      title: '治理工单', icon: 'inbox',
      actions: [fsel, batchSel,
        DN.h('a', { class: 'btn btn-ghost', href: 'javascript:void(0)', text: '应用批量', onclick: batchTransition }),
        DN.h('a', { class: 'btn btn-ghost', href: 'javascript:void(0)', text: '批量指派', onclick: batchAssign }),
        DN.h('a', { class: 'btn btn-danger', href: 'javascript:void(0)', text: '批量删除', onclick: batchDelete }),
        DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新建工单', onclick: addIssue })]
    });
    icCard.body.appendChild(DN.h('div', { id: 'hsIssues' }, DN.skeleton(4)));
    c.appendChild(icCard.el);

    // ---- 排行榜 ----
    var bc = DN.card({ title: '工单排行榜（按负责人）', icon: 'user' });
    bc.body.appendChild(DN.h('div', { id: 'hsBoard' }, DN.skeleton(3)));
    c.appendChild(bc.el);

    // ---- 成熟度 ----
    var mc = DN.card({
      title: 'DCMM 八大域成熟度自评', icon: 'layers',
      actions: [DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '录入自评', onclick: addMaturity })]
    });
    var mwrap = DN.h('div', { style: 'display:flex;gap:24px;flex-wrap:wrap;align-items:flex-start' });
    mwrap.appendChild(DN.h('div', { id: 'hsMaturityRadar' }));
    mwrap.appendChild(DN.h('div', { id: 'hsMaturityTable', style: 'flex:1;min-width:300px' }, DN.skeleton(4)));
    mc.body.appendChild(mwrap);
    c.appendChild(mc.el);

    loadScore();
    loadIssues();
    loadBoard();
    loadMaturity();
  };

  // ========== 健康分 ==========

  function loadScore() {
    DN.get('/api/gov/health/dimensions').then(function (r) {
      renderTop(r);
    }).catch(function (e) { DN.toast(e.message, 'error'); });
    loadTrend();
  }

  function renderTop(r) {
    var top = document.getElementById('hsTop');
    if (!top) return;
    var total = r.totalScore != null ? r.totalScore : 0;
    var dims = r.dimensions || {};
    var weights = r.weights || {};
    top.innerHTML = '';
    var row = DN.h('div', { style: 'display:flex;gap:28px;align-items:center;flex-wrap:wrap' });
    // 总分大环形
    row.appendChild(DN.gauge(total, { label: '治理健康总分', decimals: total % 1 ? 1 : 0 }));
    // 五维雷达
    row.appendChild(DN.radar(DIMS.map(function (d) { return { label: d, value: Number((dims[d] || {}).score) || 0 }; }), { color: '#1890ff' }));
    // 瓶颈维度提示（最低两维）
    var dimVals = DIMS.map(function (d) { return { dim: d, v: Number((dims[d] || {}).score) || 0 }; }).sort(function (a, b) { return a.v - b.v; });
    var weak = dimVals.slice(0, 2).filter(function (x) { return x.v < 85; });
    if (weak.length) {
      var bt = DN.h('div', { style: 'flex-basis:100%;display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
      bt.appendChild(DN.h('span', { style: 'font-size:12px;color:var(--text-muted)', text: '⚠ 拉低健康分的薄弱维度：' }));
      weak.forEach(function (x) { bt.appendChild(DN.pill(x.dim + ' ' + (Math.round(x.v * 10) / 10), scoreTone(x.v))); });
      row.appendChild(bt);
    }
    // 五维明细表
    var tblBox = DN.h('div', { style: 'flex:1;min-width:300px' });
    tblBox.appendChild(DN.table({
      search: false,
      columns: [
        { key: 'dim', label: '维度' },
        { key: 'score', label: '得分', align: 'right', render: function (x) { return DN.pill(x.score, scoreTone(x.scoreNum)); } },
        { key: 'weight', label: '权重', align: 'right' },
        { key: 'source', label: '数据源' }
      ],
      rows: DIMS.map(function (d) {
        var dd = dims[d] || {};
        return {
          dim: d,
          score: dd.score != null ? String(dd.score) : '-',
          scoreNum: Number(dd.score) || 0,
          weight: weights[d] != null ? weights[d] : '-',
          source: dd.source || '-'
        };
      })
    }));
    row.appendChild(tblBox);
    top.appendChild(row);
    // 创新功能：短板维度改进优先级（复用维度分，前端升序排优先级）
    top.appendChild(buildWeakPriority(dims));
  }

  // 各维度改进方向提示（按已加载维度数据，不调新 API）
  var DIM_TIPS = {
    '规范': '完善命名/标准落地，提升标准覆盖率',
    '质量': '补充质量规则与稽核，处置异常坏行',
    '安全': '梳理敏感分级与脱敏授权，收敛越权访问',
    '生命周期': '建立归档/冷热分层与留存策略',
    '血缘': '补全采集链路，提升血缘完整度'
  };

  /** 短板维度改进优先级：得分越低优先级越高，DN.bars 升序低分 warn/err 色 */
  function buildWeakPriority(dims) {
    var card = DN.card({ title: '短板维度改进优先级', icon: 'alert' });
    card.body.style.marginTop = '14px';
    var arr = DIMS.map(function (d) {
      var dd = dims[d] || {};
      return { dim: d, v: Number(dd.score) || 0, has: dd.score != null };
    }).filter(function (x) { return x.has; });
    if (!arr.length) {
      card.body.appendChild(DN.empty('暂无维度健康分数据', 'alert'));
      return card.el;
    }
    arr.sort(function (a, b) { return a.v - b.v; }); // 分越低越靠前 = 优先级越高
    card.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 10px',
      text: '按维度健康分升序排定改进优先级，得分越低越应优先治理。' }));
    card.body.appendChild(DN.bars(arr.map(function (x, i) {
      var tone = scoreTone(x.v); // <60 err / <85 warn / 否则 ok
      var tip = DIM_TIPS[x.dim] || '针对性补齐该维度治理短板';
      return {
        label: 'P' + (i + 1) + ' · ' + x.dim,
        value: x.v,
        tone: tone,
        display: (Math.round(x.v * 10) / 10) + ' 分 · ' + tip
      };
    })));
    return card.el;
  }

  function refreshScore() {
    DN.post('/api/gov/health/score/refresh').then(function (r) {
      DN.toast('已重算并快照');
      renderTop(r);
      loadTrend();
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function loadTrend() {
    DN.get('/api/gov/health/score/trend?days=30').then(function (rows) {
      var box = document.getElementById('hsTrend');
      if (!box) return;
      if (!rows || rows.length < 2) {
        box.innerHTML = '';
        box.appendChild(DN.empty('趋势数据不足（点击“重算并快照”积累时序）', 'clock'));
        return;
      }
      var vals = rows.map(function (x) { return Number(x.totalScore) || 0; });
      box.innerHTML = '';
      box.appendChild(DN.sectionTitle('近 30 天健康分趋势'));
      box.appendChild(DN.line(vals, { height: 80, max: 100, min: 0 }));
    }).catch(function () {});
  }

  // ========== 工单 ==========

  function loadIssues() {
    var status = (document.getElementById('hsIssueFilter') || {}).value || '';
    DN.get('/api/gov/health/issues' + (status ? '?status=' + encodeURIComponent(status) : '')).then(function (rows) {
      renderIssues(rows || []);
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  // 工单分析概览(大功能): 状态分布环图 + 级别分布 + 维度Top
  function buildIssueAnalytics(rows) {
    var stMeta = { OPEN: ['待处理', '#ff4d4f'], FIXING: ['处理中', '#faad14'], RESOLVED: ['已解决', '#1890ff'], VERIFIED: ['已验证', '#13c2c2'], CLOSED: ['已关闭', '#52c41a'] };
    var stCnt = {}, sevCnt = {}, dimCnt = {};
    rows.forEach(function (r) {
      var s = r.status || 'OPEN'; stCnt[s] = (stCnt[s] || 0) + 1;
      var sv = r.severity || 'LOW'; sevCnt[sv] = (sevCnt[sv] || 0) + 1;
      var d = r.dimension || '未分类'; dimCnt[d] = (dimCnt[d] || 0) + 1;
    });
    var segs = Object.keys(stCnt).map(function (s) { return { label: (stMeta[s] || [s])[0], value: stCnt[s], color: (stMeta[s] || [s, '#8c8c8c'])[1] }; });
    var grid = DN.h('div', { class: 'gov-grid', style: 'margin-bottom:12px' });
    // 状态环图
    var c1 = DN.card({ title: '工单状态分布', icon: 'inbox' });
    c1.body.appendChild(DN.donut(segs, { size: 110, stroke: 15, centerLabel: rows.length, centerSub: '工单', legend: true }));
    grid.appendChild(c1.el);
    // 级别分布 bars
    var c2 = DN.card({ title: '严重级别分布', icon: 'shield' });
    var sevMeta = { HIGH: ['高', 'err'], MEDIUM: ['中', 'warn'], LOW: ['低', 'info'] };
    c2.body.appendChild(DN.bars(['HIGH', 'MEDIUM', 'LOW'].filter(function (k) { return sevCnt[k]; }).map(function (k) {
      return { label: (sevMeta[k] || [k])[0], value: sevCnt[k], tone: (sevMeta[k] || [k, 'info'])[1], display: String(sevCnt[k]) };
    })));
    grid.appendChild(c2.el);
    // 维度 Top bars
    var c3 = DN.card({ title: '问题维度 Top', icon: 'grid' });
    var dimArr = Object.keys(dimCnt).map(function (k) { return { label: k, value: dimCnt[k] }; }).sort(function (a, b) { return b.value - a.value; }).slice(0, 6);
    c3.body.appendChild(DN.bars(dimArr.map(function (d) { return { label: d.label, value: d.value, tone: 'info', display: String(d.value) }; })));
    grid.appendChild(c3.el);
    return grid;
  }

  function renderIssues(rows) {
    var box = document.getElementById('hsIssues');
    if (!box) return;
    selectedIssues = {};
    if (boardOwnerFilter) rows = (rows || []).filter(function (it) { return (it.owner || '未分配') === boardOwnerFilter; });
    box.innerHTML = '';
    if (boardOwnerFilter) {
      box.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 8px;display:flex;align-items:center;gap:8px' }, [
        DN.h('span', { text: '已按负责人「' + boardOwnerFilter + '」筛选' }),
        DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '清除', onclick: function () { boardOwnerFilter = ''; loadIssues(); } })
      ]));
    }
    if (rows && rows.length) box.appendChild(buildIssueAnalytics(rows));
    issueTable = DN.table({
      rows: rows,
      pageSize: 10,
      searchKeys: ['title', 'dimension', 'owner', 'status'],
      searchPlaceholder: '搜索标题/维度/负责人/状态',
      empty: '暂无工单', emptyIcon: 'inbox',
      columns: [
        { key: '_sel', label: '', render: function (it) { var cb = DN.h('input', { type: 'checkbox', 'aria-label': '选择工单 ' + (it.title || it.id) }); cb.checked = !!selectedIssues[it.id]; cb.onchange = function () { if (cb.checked) selectedIssues[it.id] = it.status; else delete selectedIssues[it.id]; }; return cb; } },
        { key: 'id', label: 'ID', align: 'right' },
        { key: 'title', label: '标题' },
        { key: 'dimension', label: '维度', render: function (it) { return it.dimension || '-'; } },
        { key: 'severity', label: '级别', render: function (it) { return severityPill(it.severity); } },
        { key: 'owner', label: '负责人', render: function (it) { return it.owner || '未分配'; } },
        { key: 'status', label: '状态', render: function (it) { return DN.pill(it.status, STATUS_TONE[it.status] || 'muted'); } },
        { key: 'ops', label: '操作', render: function (it) { return issueOps(it); } }
      ]
    });
    box.appendChild(issueTable);
  }

  function severityPill(sev) {
    if (!sev) return '-';
    var t = sev === 'HIGH' ? 'err' : (sev === 'MEDIUM' ? 'warn' : 'info');
    return DN.pill(sev, t);
  }

  function issueOps(it) {
    var wrap = DN.h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap;align-items:center' });
    (FLOW[it.status] || []).forEach(function (ns) {
      wrap.appendChild(DN.h('a', {
        href: 'javascript:void(0)', text: ns,
        onclick: function () { transition(it.id, ns); }
      }));
    });
    wrap.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '指派', onclick: function () { assignIssue(it.id); } }));
    wrap.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '删除', style: 'color:var(--error,#f53f3f)', onclick: function () { delIssue(it.id); } }));
    return wrap;
  }

  function transition(id, to) {
    DN.post('/api/gov/health/issues/' + id + '/transition', { status: to })
      .then(function () { DN.toast('已流转'); loadIssues(); loadBoard(); })
      .catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function batchAssign() {
    var ids = Object.keys(selectedIssues);
    if (!ids.length) { DN.toast('请先勾选工单', 'error'); return; }
    var ownerInput = DN.h('input', { class: 'iw-form-input', placeholder: '负责人' });
    drawerForm('批量指派 ' + ids.length + ' 个工单', [formRow('负责人', ownerInput)], function (close) {
      var owner = ownerInput.value.trim();
      if (!owner) { DN.toast('请填写负责人', 'error'); return; }
      Promise.all(ids.map(function (id) { return DN.post('/api/gov/health/issues/' + id + '/assign', { owner: owner }).then(function () { return true; }).catch(function () { return false; }); }))
        .then(function (res) { DN.toast('已指派 ' + res.filter(Boolean).length + '/' + ids.length + ' 项'); close(); loadIssues(); loadBoard(); });
    });
  }
  function batchDelete() {
    var ids = Object.keys(selectedIssues);
    if (!ids.length) { DN.toast('请先勾选工单', 'error'); return; }
    if (!confirm('确认批量删除选中的 ' + ids.length + ' 个工单？')) return;
    Promise.all(ids.map(function (id) { return DN.del('/api/gov/health/issues/' + id).then(function () { return true; }).catch(function () { return false; }); }))
      .then(function (res) { DN.toast('已删除 ' + res.filter(Boolean).length + '/' + ids.length + ' 项'); loadIssues(); loadBoard(); });
  }
  function batchTransition() {
    var to = (document.getElementById('hsBatchTo') || {}).value || '';
    if (!to) { DN.toast('请选择目标状态', 'error'); return; }
    var ids = Object.keys(selectedIssues);
    if (!ids.length) { DN.toast('请先勾选工单', 'error'); return; }
    // 仅对状态机允许的工单流转
    var valid = ids.filter(function (id) { return (FLOW[selectedIssues[id]] || []).indexOf(to) >= 0; });
    if (!valid.length) { DN.toast('所选工单当前状态均不能流转到 ' + to, 'error'); return; }
    Promise.all(valid.map(function (id) {
      return DN.post('/api/gov/health/issues/' + id + '/transition', { status: to }).then(function () { return true; }).catch(function () { return false; });
    })).then(function (res) {
      var ok = res.filter(Boolean).length;
      DN.toast('已批量流转 ' + ok + '/' + ids.length + ' 项' + (valid.length < ids.length ? '（' + (ids.length - valid.length) + ' 项状态不允许已跳过）' : ''));
      loadIssues(); loadBoard();
    });
  }

  function delIssue(id) {
    DN.del('/api/gov/health/issues/' + id)
      .then(function () { DN.toast('已删除'); loadIssues(); loadBoard(); })
      .catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function assignIssue(id) {
    var ownerInput = DN.h('input', { class: 'iw-form-input', placeholder: '负责人' });
    drawerForm('指派工单 #' + id, [formRow('负责人', ownerInput)], function (close) {
      DN.post('/api/gov/health/issues/' + id + '/assign', { owner: ownerInput.value.trim() })
        .then(function () { DN.toast('已指派'); close(); loadIssues(); loadBoard(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  function addIssue() {
    var titleInput = DN.h('input', { class: 'iw-form-input', placeholder: '工单标题' });
    var typeSel = selectOf(['STANDARD', 'QUALITY', 'SECURITY', 'LINEAGE', 'LIFECYCLE', 'OTHER'], 'OTHER');
    var dimSel = selectOf(DIMS, '', '（不限维度）');
    var sevSel = selectOf(['HIGH', 'MEDIUM', 'LOW'], 'MEDIUM');
    var ownerInput = DN.h('input', { class: 'iw-form-input', placeholder: '负责人（可空）' });
    var descInput = DN.h('input', { class: 'iw-form-input', placeholder: '描述（可空）' });
    drawerForm('新建工单', [
      formRow('标题', titleInput), formRow('问题类型', typeSel), formRow('所属维度', dimSel),
      formRow('级别', sevSel), formRow('负责人', ownerInput), formRow('描述', descInput)
    ], function (close) {
      var title = titleInput.value.trim();
      if (!title) { DN.toast('标题不能为空', 'error'); return; }
      DN.post('/api/gov/health/issues', {
        title: title, issueType: typeSel.value, dimension: dimSel.value,
        severity: sevSel.value, owner: ownerInput.value.trim(), description: descInput.value.trim()
      }).then(function () { DN.toast('已新建'); close(); loadIssues(); loadBoard(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  function loadBoard() {
    DN.get('/api/gov/health/issues/leaderboard').then(function (rows) {
      var box = document.getElementById('hsBoard');
      if (!box) return;
      box.innerHTML = '';
      if (!rows || !rows.length) { box.appendChild(DN.empty('暂无数据', 'user')); return; }
      box.appendChild(DN.bars(rows.map(function (r) {
        return {
          label: r.owner, value: Number(r.total) || 0, tone: 'info',
          display: '总' + (r.total || 0) + ' · 未关' + (r.open || 0) + ' · 已关' + (r.closed || 0),
          onClick: function () { boardOwnerFilter = (boardOwnerFilter === r.owner) ? '' : r.owner; loadIssues(); DN.toast(boardOwnerFilter ? ('仅看负责人 ' + r.owner) : '显示全部工单', 'info'); }
        };
      })));
    }).catch(function () {});
  }

  // ========== 成熟度 ==========

  function loadMaturity() {
    DN.get('/api/gov/health/maturity').then(function (rows) {
      var radarBox = document.getElementById('hsMaturityRadar');
      var tableBox = document.getElementById('hsMaturityTable');
      if (!radarBox || !tableBox) return;
      var byDomain = {};
      (rows || []).forEach(function (r) { byDomain[r.domain] = r; });
      DN.get('/api/gov/health/maturity/domains').then(function (domains) {
        domains = domains || [];
        var vals = domains.map(function (d) { return byDomain[d] ? Number(byDomain[d].score) || 0 : 0; });
        radarBox.innerHTML = drawRadar(domains, vals, 280, '#722ed1');
        tableBox.innerHTML = '';
        if (!rows || !rows.length) {
          tableBox.appendChild(DN.empty('暂无自评，点击“录入自评”', 'layers'));
          return;
        }
        // 等级分布环图 + 平均成熟度
        var lvCnt = {}, sum = 0; rows.forEach(function (r) { var l = 'L' + (r.level || 0); lvCnt[l] = (lvCnt[l] || 0) + 1; sum += Number(r.score) || 0; });
        var avg = rows.length ? (sum / rows.length) : 0;
        var lvColor = { L1: '#ff4d4f', L2: '#fa8c16', L3: '#faad14', L4: '#1890ff', L5: '#52c41a' };
        var segs = Object.keys(lvCnt).sort().map(function (l) { return { label: l, value: lvCnt[l], color: lvColor[l] || '#8c8c8c' }; });
        var dwrap = DN.h('div', { style: 'display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin-bottom:12px' });
        dwrap.appendChild(DN.donut(segs, { size: 100, stroke: 13, centerLabel: avg.toFixed(0), centerSub: '平均分' }));
        var lg = DN.h('div', { class: 'gov-legend' });
        segs.forEach(function (s) { lg.appendChild(DN.h('span', {}, [DN.h('i', { style: 'background:' + s.color }), DN.h('span', { text: s.label + ' ' + s.value + ' 域' })])); });
        dwrap.appendChild(lg);
        tableBox.appendChild(dwrap);
        tableBox.appendChild(DN.table({
          search: false,
          columns: [
            { key: 'domain', label: '能力域' },
            { key: 'score', label: '得分', align: 'right', render: function (x) { return x.r ? DN.pill(String(x.r.score), scoreTone(Number(x.r.score) || 0)) : '-'; } },
            { key: 'level', label: '等级', align: 'right', render: function (x) { return x.r ? 'L' + x.r.level : '-'; } },
            { key: 'note', label: '备注', render: function (x) { return (x.r && x.r.note) ? x.r.note : '-'; } }
          ],
          rows: domains.map(function (d) { return { domain: d, r: byDomain[d] || null }; })
        }));
      });
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function addMaturity() {
    DN.get('/api/gov/health/maturity/domains').then(function (domains) {
      var domainSel = selectOf(domains || [], (domains || [])[0] || '');
      var scoreInput = DN.h('input', { class: 'iw-form-input', type: 'number', min: '0', max: '100', value: '60' });
      var levelSel = selectOf(['1', '2', '3', '4', '5'], '3');
      var noteInput = DN.h('input', { class: 'iw-form-input', placeholder: '备注（可空）' });
      drawerForm('录入 DCMM 自评', [
        formRow('能力域', domainSel), formRow('自评分', scoreInput),
        formRow('成熟度等级', levelSel), formRow('备注', noteInput)
      ], function (close) {
        if (!domainSel.value) { DN.toast('请选择能力域', 'error'); return; }
        DN.post('/api/gov/health/maturity', {
          domain: domainSel.value, score: Number(scoreInput.value),
          level: Number(levelSel.value), note: noteInput.value.trim()
        }).then(function () { DN.toast('已录入'); close(); loadMaturity(); })
          .catch(function (e) { DN.toast(e.message, 'error'); });
      });
    });
  }

  // ========== SVG 自绘（DCMM 雷达 + 趋势折线保留） ==========

  /** 雷达图：axes 维度名数组，vals 0-100 数组，size 画布边长，color 主色 */
  function drawRadar(axes, vals, size, color) {
    var n = axes.length;
    if (!n) return '';
    var cx = size / 2, cy = size / 2, R = size / 2 - 40;
    var parts = [];
    // 网格圈（4 层）
    for (var g = 1; g <= 4; g++) {
      var rr = R * g / 4;
      var pts = [];
      for (var i = 0; i < n; i++) {
        var p = polar(cx, cy, rr, angle(i, n));
        pts.push(p.x.toFixed(1) + ',' + p.y.toFixed(1));
      }
      parts.push('<polygon points="' + pts.join(' ') + '" fill="none" stroke="#e5e6eb" stroke-width="1"/>');
    }
    // 轴线 + 标签
    for (var j = 0; j < n; j++) {
      var a = angle(j, n);
      var edge = polar(cx, cy, R, a);
      parts.push('<line x1="' + cx + '" y1="' + cy + '" x2="' + edge.x.toFixed(1) + '" y2="' + edge.y.toFixed(1) + '" stroke="#e5e6eb" stroke-width="1"/>');
      var lp = polar(cx, cy, R + 16, a);
      var anchor = Math.abs(lp.x - cx) < 4 ? 'middle' : (lp.x > cx ? 'start' : 'end');
      parts.push('<text x="' + lp.x.toFixed(1) + '" y="' + (lp.y + 4).toFixed(1) + '" font-size="11" fill="#4e5969" text-anchor="' + anchor + '">' + esc(axes[j]) + '</text>');
    }
    // 数据多边形
    var dpts = [];
    for (var k = 0; k < n; k++) {
      var v = Math.max(0, Math.min(100, vals[k] || 0));
      var pp = polar(cx, cy, R * v / 100, angle(k, n));
      dpts.push(pp.x.toFixed(1) + ',' + pp.y.toFixed(1));
    }
    parts.push('<polygon points="' + dpts.join(' ') + '" fill="' + color + '33" stroke="' + color + '" stroke-width="2"/>');
    for (var m = 0; m < n; m++) {
      var c = dpts[m].split(',');
      parts.push('<circle cx="' + c[0] + '" cy="' + c[1] + '" r="3" fill="' + color + '"/>');
    }
    return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '">' + parts.join('') + '</svg>';
  }

  /** 迷你折线 */
  function drawSparkline(vals, w, h) {
    var n = vals.length;
    if (n < 2) return '';
    var max = Math.max.apply(null, vals), min = Math.min.apply(null, vals);
    var range = max - min || 1, pad = 6;
    var pts = vals.map(function (v, i) {
      var x = pad + (w - 2 * pad) * i / (n - 1);
      var y = pad + (h - 2 * pad) * (1 - (v - min) / range);
      return x.toFixed(1) + ',' + y.toFixed(1);
    });
    return '<svg width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '" style="vertical-align:middle">' +
      '<polyline points="' + pts.join(' ') + '" fill="none" stroke="#165dff" stroke-width="2"/>' +
      pts.map(function (p) { var c = p.split(','); return '<circle cx="' + c[0] + '" cy="' + c[1] + '" r="2" fill="#165dff"/>'; }).join('') +
      '</svg>';
  }

  function angle(i, n) { return -Math.PI / 2 + 2 * Math.PI * i / n; }
  function polar(cx, cy, r, a) { return { x: cx + r * Math.cos(a), y: cy + r * Math.sin(a) }; }
  function esc(s) { return DN.esc(s); }
  // 分值 → 药丸色调
  function scoreTone(v) { return v >= 85 ? 'ok' : (v >= 60 ? 'warn' : 'err'); }

  // ========== 抽屉内表单 UI ==========

  /** 构造下拉：items 选项数组，sel 默认值，emptyText 传入则首项为空选项 */
  function selectOf(items, sel, emptyText) {
    var opts = (emptyText != null) ? [DN.h('option', { value: '', text: emptyText })] : [];
    (items || []).forEach(function (v) { opts.push(DN.h('option', { value: v, text: v })); });
    var s = DN.h('select', { class: 'iw-form-select' }, opts);
    if (sel != null) s.value = sel;
    return s;
  }

  function formRow(label, control) {
    return DN.h('div', { class: 'ds-form-row', style: 'align-items:flex-start' }, [
      DN.h('label', { text: label }), control
    ]);
  }

  /** 在右侧抽屉里渲染表单 + 操作按钮，onOk(close) */
  function drawerForm(title, bodyNodes, onOk) {
    var form = DN.h('div', { class: 'gov-form' });
    bodyNodes.forEach(function (n) { form.appendChild(n); });
    var dr = DN.drawer(title, form);
    var footer = DN.h('div', { style: 'text-align:right;margin-top:16px' });
    footer.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '取消', style: 'margin-right:8px', onclick: dr.close }));
    footer.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确定', onclick: function () { onOk(dr.close); } }));
    form.appendChild(footer);
  }
})();
