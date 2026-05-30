/* 治理模块：数据质量（M5 阈值 + 调度 + 趋势评分） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  window.GOV_RENDERERS.quality = function (c) {
    var bar = DN.h('div', { class: 'gov-desc' });
    bar.appendChild(DN.h('a', { class: 'gov-btn', href: 'workspace.html#/quality',
      text: '前往工作台质量' }));
    c.appendChild(bar);

    c.appendChild(DN.h('div', { id: 'qScore', class: 'gov-desc', text: '整体质量分加载中…' }));
    c.appendChild(DN.h('div', { id: 'qTrend', class: 'gov-desc' }));
    c.appendChild(DN.h('div', { id: 'qRules' }));

    loadScore();
    loadRules();
  };

  function loadScore() {
    DN.get('/api/quality/score').then(function (d) {
      var box = document.getElementById('qScore');
      if (!box) return;
      var score = (d && d.score != null) ? d.score : 100;
      var n = (d && d.sampleRuns != null) ? d.sampleRuns : 0;
      box.textContent = '整体质量分: ' + score + ' / 100（近 7 天 ' + n + ' 次检查均值）';
    }).catch(function () {});
  }

  function loadRules() {
    DN.get('/api/quality/rules').then(function (rules) {
      var box = document.getElementById('qRules');
      if (!box) return;
      box.innerHTML = '';
      if (!rules || !rules.length) {
        box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无质量规则，请前往工作台创建' }));
        return;
      }
      var rows = rules.slice(0, 200).map(function (r) {
        var th = (r.passThreshold == null ? 100 : r.passThreshold);
        var strong = (r.blockDownstream === 1) ? '强' : '弱';
        return '<tr data-id="' + r.id + '" style="cursor:pointer">' +
          '<td>' + DN.esc(r.ruleName || '') + '</td>' +
          '<td>' + DN.esc(r.ruleType || '') + '</td>' +
          '<td>' + DN.esc(r.dimension || '-') + '</td>' +
          '<td style="text-align:right">' + th + '%</td>' +
          '<td>' + strong + '</td>' +
          '<td>' + DN.esc(r.scheduleCron || '-') + '</td>' +
          '<td>' + (r.status === 1 ? '启用' : '停用') + '</td></tr>';
      }).join('');
      box.innerHTML = '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
        '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        '<th style="padding:8px">规则</th><th style="padding:8px">类型</th><th style="padding:8px">维度</th>' +
        '<th style="padding:8px;text-align:right">阈值</th><th style="padding:8px">强/弱</th>' +
        '<th style="padding:8px">调度</th><th style="padding:8px">状态</th></tr></thead><tbody>' + rows + '</tbody></table>';
      box.querySelectorAll('td').forEach(function (td) { td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5'; });
      box.querySelectorAll('tr[data-id]').forEach(function (tr) {
        tr.addEventListener('click', function () { loadTrend(tr.getAttribute('data-id')); });
      });
    }).catch(function (e) {
      var box = document.getElementById('qRules');
      if (box) box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message }));
    });
  }

  function loadTrend(ruleId) {
    DN.get('/api/quality/trend?ruleId=' + encodeURIComponent(ruleId)).then(function (points) {
      var box = document.getElementById('qTrend');
      if (!box) return;
      if (!points || !points.length) { box.textContent = '该规则暂无执行记录'; return; }
      var parts = points.map(function (p) {
        var rate = (p.passRate == null ? '-' : p.passRate);
        return rate + '%(' + DN.esc(p.runStatus || '') + ')';
      });
      box.textContent = '规则 #' + ruleId + ' 近 ' + points.length + ' 次通过率: ' + parts.join(' → ');
    }).catch(function () {});
  }
})();
