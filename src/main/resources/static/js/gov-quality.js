/* 治理模块：数据质量（M5 阈值 + 调度 + 趋势评分） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var allRules = [];          // 全量规则
  var filterStatus = '';      // ''=全部 / '1'=启用 / '0'=停用
  var tableEl = null;         // DN.table 实例（供筛选 reload）
  var trendBody = null;       // 趋势卡片 body

  window.GOV_RENDERERS.quality = function (c) {
    // 顶部：质量分 gauge + 趋势 bars 两张卡片
    var grid = DN.h('div', { class: 'gov-grid' });
    var scoreCard = DN.card({ title: '整体质量分', icon: 'check' });
    var trendCard = DN.card({ title: '规则通过率趋势', icon: 'chart' });
    trendBody = trendCard.body;
    scoreCard.body.appendChild(DN.skeleton(2));
    trendBody.appendChild(DN.empty('点击下方规则查看其通过率趋势', 'chart'));
    grid.appendChild(scoreCard.el);
    grid.appendChild(trendCard.el);
    c.appendChild(grid);

    // 规则列表卡片
    var actions = DN.h('a', { class: 'btn btn-primary', href: 'workspace.html#/quality', text: '前往工作台质量' });
    var rulesCard = DN.card({ title: '质量规则', icon: 'list', actions: actions });
    var rulesBody = rulesCard.body;
    rulesBody.appendChild(DN.skeleton(4));
    c.appendChild(rulesCard.el);

    loadScore(scoreCard.body);
    loadRules(rulesBody);
  };

  function loadScore(box) {
    DN.get('/api/quality/score').then(function (d) {
      var score = (d && d.score != null) ? Number(d.score) : 100;
      var n = (d && d.sampleRuns != null) ? d.sampleRuns : 0;
      box.innerHTML = '';
      var wrap = DN.h('div', { style: 'display:flex;align-items:center;gap:18px;flex-wrap:wrap' });
      wrap.appendChild(DN.gauge(score, { label: '满分 100', decimals: 1 }));
      wrap.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0',
        text: '近 7 天 ' + n + ' 次检查均值' }));
      box.appendChild(wrap);
    }).catch(function () {
      box.innerHTML = '';
      box.appendChild(DN.empty('质量分加载失败', 'alert'));
    });
  }

  function loadRules(box) {
    DN.get('/api/quality/rules').then(function (rules) {
      allRules = rules || [];
      box.innerHTML = '';
      box.appendChild(buildToolbarAndTable());
    }).catch(function (e) {
      box.innerHTML = '';
      box.appendChild(DN.empty('加载失败: ' + e.message, 'alert'));
    });
  }

  function filtered() {
    return allRules.filter(function (r) {
      if (filterStatus !== '' && String(r.status == null ? 1 : r.status) !== filterStatus) return false;
      return true;
    });
  }

  function buildToolbarAndTable() {
    // 状态筛选下拉（放进 DN.table 工具条）
    var statusSel = DN.h('select', { class: 'iw-form-select', style: 'min-width:120px' });
    [['', '全部状态'], ['1', '启用'], ['0', '停用']].forEach(function (o) {
      statusSel.appendChild(DN.h('option', { value: o[0], text: o[1] }));
    });
    statusSel.value = filterStatus;
    statusSel.onchange = function () { filterStatus = statusSel.value; if (tableEl) tableEl.reload(filtered()); };

    tableEl = DN.table({
      columns: [
        { key: 'ruleName', label: '规则', render: function (r) {
            return DN.h('a', { href: 'javascript:void(0)', text: r.ruleName || '-',
              style: 'color:var(--primary,#1890ff)', onclick: function () { loadTrend(r.id); } });
          } },
        { key: 'ruleType', label: '类型', render: function (r) { return r.ruleType || '-'; } },
        { key: 'dimension', label: '维度', render: function (r) { return r.dimension || '-'; } },
        { key: 'target', label: '目标库表', render: function (r) {
            var t = [r.dbName, r.tableName].filter(Boolean).join('.');
            if (r.columnName) t += '.' + r.columnName;
            return t || '-';
          } },
        { key: 'severity', label: '严重级', render: function (r) {
            var on = (r.blockDownstream === 1);
            return DN.pill(on ? '强阻断' : '弱提醒', on ? 'err' : 'info');
          } },
        { key: 'passThreshold', label: '阈值', align: 'right', render: function (r) {
            return (r.passThreshold == null ? 100 : r.passThreshold) + '%';
          } },
        { key: 'scheduleCron', label: '调度', render: function (r) { return r.scheduleCron || '-'; } },
        { key: 'status', label: '状态', render: function (r) {
            return r.status === 1 ? DN.pill('启用', 'ok') : DN.pill('停用', 'muted');
          } }
      ],
      rows: filtered(),
      pageSize: 20,
      searchKeys: ['ruleName', 'ruleType', 'dimension'],
      searchPlaceholder: '搜索规则名/类型/维度',
      toolbar: statusSel,
      empty: '暂无质量规则，请前往工作台创建',
      emptyIcon: 'check'
    });

    return tableEl;
  }

  function loadTrend(ruleId) {
    if (!trendBody) return;
    trendBody.innerHTML = '';
    trendBody.appendChild(DN.skeleton(3));
    DN.get('/api/quality/trend?ruleId=' + encodeURIComponent(ruleId)).then(function (points) {
      trendBody.innerHTML = '';
      trendBody.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-bottom:10px',
        text: '规则 #' + ruleId + ' · 近 ' + (points ? points.length : 0) + ' 次通过率' }));
      if (!points || !points.length) {
        trendBody.appendChild(DN.empty('该规则暂无执行记录', 'clock'));
        return;
      }
      var bars = points.map(function (p, i) {
        var rate = (p.passRate == null ? 0 : Number(p.passRate));
        var ok = String(p.runStatus || '').toUpperCase();
        var tone = rate >= 80 ? 'ok' : (rate >= 60 ? 'warn' : 'err');
        return { label: '#' + (i + 1) + (ok ? ' ' + ok : ''), value: rate, max: 100, display: rate + '%', tone: tone };
      });
      trendBody.appendChild(DN.bars(bars));
    }).catch(function () {
      trendBody.innerHTML = '';
      trendBody.appendChild(DN.empty('趋势加载失败', 'alert'));
    });
  }
})();
