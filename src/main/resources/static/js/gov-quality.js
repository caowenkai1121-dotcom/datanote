/* 治理模块：数据质量（M5 阈值 + 调度 + 趋势评分） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var PAGE_SIZE = 20;
  var allRules = [];          // 全量规则
  var filterStatus = '';      // ''=全部 / '1'=启用 / '0'=停用
  var keyword = '';           // 搜索关键词
  var page = 1;

  window.GOV_RENDERERS.quality = function (c) {
    var bar = DN.h('div', { class: 'gov-desc' });
    bar.appendChild(DN.h('a', { class: 'btn btn-primary', href: 'workspace.html#/quality',
      text: '前往工作台质量' }));
    c.appendChild(bar);

    // 质量分 + 趋势：系统卡片网格
    var grid = DN.h('div', { id: 'qCards',
      style: 'display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px;margin-bottom:16px' });
    grid.appendChild(DN.h('div', { id: 'qScore' }));
    grid.appendChild(DN.h('div', { id: 'qTrend' }));
    c.appendChild(grid);

    c.appendChild(DN.h('div', { id: 'qRules' }));

    loadScore();
    loadRules();
  };

  // ===== 系统卡片 =====
  function card(title) {
    var el = DN.h('div', {
      style: 'background:var(--bg-card,#fff);border:1px solid var(--border,#e5e6eb);border-radius:8px;padding:16px;display:flex;flex-direction:column'
    });
    el.appendChild(DN.h('div', { text: title,
      style: 'font-size:14px;font-weight:600;color:var(--text-regular,#1f2329);margin-bottom:12px' }));
    return el;
  }
  function scoreColor(v) {
    return v >= 85 ? '#00b42a' : (v >= 70 ? '#165dff' : (v >= 60 ? '#ff7d00' : '#f53f3f'));
  }

  function loadScore() {
    DN.get('/api/quality/score').then(function (d) {
      var box = document.getElementById('qScore');
      if (!box) return;
      var score = (d && d.score != null) ? d.score : 100;
      var n = (d && d.sampleRuns != null) ? d.sampleRuns : 0;
      box.innerHTML = '';
      var el = card('整体质量分');
      var color = scoreColor(Number(score) || 0);
      el.innerHTML += '<div style="display:flex;align-items:baseline;gap:6px">' +
        '<div style="font-size:42px;font-weight:700;color:' + color + ';line-height:1">' + DN.esc(score) + '</div>' +
        '<div style="color:var(--text-muted,#86909c);font-size:13px">/ 100</div></div>' +
        '<div style="color:var(--text-muted,#86909c);font-size:12px;margin-top:8px">近 7 天 ' + n + ' 次检查均值</div>';
      box.appendChild(el);
    }).catch(function () {});
  }

  function loadRules() {
    DN.get('/api/quality/rules').then(function (rules) {
      allRules = rules || [];
      page = 1;
      renderRules();
    }).catch(function (e) {
      var box = document.getElementById('qRules');
      if (box) box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message }));
    });
  }

  function filtered() {
    return allRules.filter(function (r) {
      if (filterStatus !== '' && String(r.status == null ? 1 : r.status) !== filterStatus) return false;
      if (keyword) {
        var hay = ((r.ruleName || '') + ' ' + (r.ruleType || '') + ' ' + (r.dimension || '')).toLowerCase();
        if (hay.indexOf(keyword.toLowerCase()) < 0) return false;
      }
      return true;
    });
  }

  function renderRules() {
    var box = document.getElementById('qRules');
    if (!box) return;
    box.innerHTML = '';

    // 工具条：搜索 + 状态筛选下拉
    var toolbar = DN.h('div', { style: 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:12px' });
    var search = DN.h('input', { class: 'iw-form-input', placeholder: '搜索规则名/类型/维度',
      value: keyword, style: 'width:220px' });
    search.oninput = function () { keyword = search.value.trim(); page = 1; renderRules(); search.focus(); };
    var statusSel = DN.h('select', { class: 'iw-form-select', style: 'width:120px' });
    [['', '全部状态'], ['1', '启用'], ['0', '停用']].forEach(function (o) {
      statusSel.appendChild(DN.h('option', { value: o[0], text: o[1] }));
    });
    statusSel.value = filterStatus;
    statusSel.onchange = function () { filterStatus = statusSel.value; page = 1; renderRules(); };
    toolbar.appendChild(search);
    toolbar.appendChild(statusSel);
    box.appendChild(toolbar);

    var rows = filtered();
    if (!allRules.length) {
      box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无质量规则，请前往工作台创建' }));
      return;
    }
    if (!rows.length) {
      box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '无匹配规则' }));
      return;
    }

    var totalPages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
    if (page > totalPages) page = totalPages;
    var pageRows = rows.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

    var trHtml = pageRows.map(function (r) {
      var th = (r.passThreshold == null ? 100 : r.passThreshold);
      var strong = (r.blockDownstream === 1) ? '强' : '弱';
      var on = (r.status === 1);
      var badge = '<span class="badge" style="color:' + (on ? '#00b42a' : '#86909c') +
        ';background:' + (on ? 'rgba(0,180,42,.12)' : 'rgba(134,144,156,.12)') + '">' + (on ? '启用' : '停用') + '</span>';
      return '<tr data-id="' + r.id + '" style="cursor:pointer">' +
        '<td>' + DN.esc(r.ruleName || '') + '</td>' +
        '<td>' + DN.esc(r.ruleType || '') + '</td>' +
        '<td>' + DN.esc(r.dimension || '-') + '</td>' +
        '<td style="text-align:right">' + th + '%</td>' +
        '<td>' + strong + '</td>' +
        '<td>' + DN.esc(r.scheduleCron || '-') + '</td>' +
        '<td>' + badge + '</td></tr>';
    }).join('');

    var t = tableCard(['规则', '类型', '维度', { label: '阈值', right: true }, '强/弱', '调度', '状态'], trHtml);
    t.querySelectorAll('tr[data-id]').forEach(function (tr) {
      tr.addEventListener('click', function () { loadTrend(tr.getAttribute('data-id')); });
    });
    box.appendChild(t);

    box.appendChild(pager(rows.length, totalPages));
  }

  // ===== 系统表格卡片 =====
  function tableCard(headers, rowsHtml) {
    var ths = headers.map(function (h) {
      var label = (typeof h === 'string') ? h : h.label;
      var right = (typeof h === 'object') && h.right;
      return '<th style="padding:8px;' + (right ? 'text-align:right' : 'text-align:left') + '">' + DN.esc(label) + '</th>';
    }).join('');
    var box = DN.h('div', { html:
      '<table style="width:100%;border-collapse:collapse;background:var(--bg-card,#fff);font-size:13px">' +
      '<thead><tr style="color:var(--text-muted,#86909c);border-bottom:1px solid var(--border,#e5e6eb)">' + ths +
      '</tr></thead><tbody>' + rowsHtml + '</tbody></table>' });
    box.querySelectorAll('td').forEach(function (td) {
      td.style.padding = '8px'; td.style.borderBottom = '1px solid var(--divider,#f2f3f5)';
    });
    return box;
  }

  // ===== 客户端分页条 =====
  function pager(total, totalPages) {
    var bar = DN.h('div', { style: 'display:flex;justify-content:flex-end;align-items:center;gap:8px;margin-top:12px;font-size:13px;color:var(--text-muted,#86909c)' });
    bar.appendChild(DN.h('span', { text: '共 ' + total + ' 条 · ' + page + '/' + totalPages }));
    var prev = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '上一页' });
    var next = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '下一页' });
    prev.onclick = function () { if (page > 1) { page--; renderRules(); } };
    next.onclick = function () { if (page < totalPages) { page++; renderRules(); } };
    bar.appendChild(prev);
    bar.appendChild(next);
    return bar;
  }

  function loadTrend(ruleId) {
    DN.get('/api/quality/trend?ruleId=' + encodeURIComponent(ruleId)).then(function (points) {
      var box = document.getElementById('qTrend');
      if (!box) return;
      box.innerHTML = '';
      var el = card('规则 #' + ruleId + ' 通过率趋势');
      if (!points || !points.length) {
        el.appendChild(DN.h('div', { style: 'color:var(--text-muted,#86909c);font-size:13px', text: '该规则暂无执行记录' }));
        box.appendChild(el);
        return;
      }
      var parts = points.map(function (p) {
        var rate = (p.passRate == null ? '-' : p.passRate);
        return rate + '%(' + DN.esc(p.runStatus || '') + ')';
      });
      el.appendChild(DN.h('div', { style: 'font-size:13px;color:var(--text-regular,#1f2329);line-height:1.8',
        text: '近 ' + points.length + ' 次: ' + parts.join(' → ') }));
      box.appendChild(el);
    }).catch(function () {});
  }
})();
