/* 治理模块：治理健康分 + 工单闭环 + DCMM 成熟度自评（M11）
   五维雷达 SVG 自绘 + 健康分大屏 + 工单列表与状态流转 + 成熟度雷达。零图表库依赖。 */
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
  var STATUS_COLOR = {
    OPEN: '#f53f3f', FIXING: '#ff7d00', RESOLVED: '#165dff',
    VERIFIED: '#00b42a', CLOSED: '#86909c'
  };

  window.GOV_RENDERERS.health = function (c) {
    // 健康分大屏
    c.appendChild(DN.h('h3', { text: '治理健康分', style: 'margin:6px 0 8px' }));
    var top = DN.h('div', { id: 'hsTop',
      style: 'display:flex;gap:24px;align-items:center;flex-wrap:wrap;background:#fff;border:1px solid #e5e6eb;border-radius:8px;padding:16px' });
    c.appendChild(top);
    var bar = DN.h('div', { class: 'gov-desc' });
    bar.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '重算并快照',
      onclick: refreshScore, style: 'margin-top:0' }));
    c.appendChild(bar);
    c.appendChild(DN.h('div', { id: 'hsTrend', class: 'gov-desc' }));

    // 工单
    c.appendChild(DN.h('h3', { text: '治理工单', style: 'margin:18px 0 8px' }));
    var ibar = DN.h('div', { class: 'gov-desc' });
    ibar.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '新建工单',
      onclick: addIssue, style: 'margin-top:0' }));
    var fsel = DN.h('select', { id: 'hsIssueFilter',
      style: 'padding:6px 10px;border:1px solid #d4d7de;border-radius:6px;margin-left:8px' });
    fsel.appendChild(DN.h('option', { value: '', text: '全部状态' }));
    ISSUE_STATUS.forEach(function (s) { fsel.appendChild(DN.h('option', { value: s, text: s })); });
    fsel.addEventListener('change', loadIssues);
    ibar.appendChild(fsel);
    c.appendChild(ibar);
    c.appendChild(DN.h('div', { id: 'hsIssues' }));
    c.appendChild(DN.h('h4', { text: '工单排行榜(按负责人)', style: 'margin:14px 0 6px' }));
    c.appendChild(DN.h('div', { id: 'hsBoard' }));

    // 成熟度
    c.appendChild(DN.h('h3', { text: 'DCMM 八大域成熟度自评', style: 'margin:18px 0 8px' }));
    var mbar = DN.h('div', { class: 'gov-desc' });
    mbar.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '录入自评',
      onclick: addMaturity, style: 'margin-top:0' }));
    c.appendChild(mbar);
    var mwrap = DN.h('div', { style: 'display:flex;gap:24px;flex-wrap:wrap;align-items:flex-start' });
    mwrap.appendChild(DN.h('div', { id: 'hsMaturityRadar' }));
    mwrap.appendChild(DN.h('div', { id: 'hsMaturityTable', style: 'flex:1;min-width:280px' }));
    c.appendChild(mwrap);

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
    // 总分仪表
    var color = total >= 85 ? '#00b42a' : (total >= 70 ? '#165dff' : (total >= 60 ? '#ff7d00' : '#f53f3f'));
    var scoreBox = '<div style="text-align:center;min-width:140px">' +
      '<div style="font-size:48px;font-weight:700;color:' + color + ';line-height:1">' + total + '</div>' +
      '<div style="color:#86909c;font-size:13px;margin-top:4px">治理健康总分</div></div>';
    // 五维雷达
    var vals = DIMS.map(function (d) {
      var dd = dims[d]; return dd ? (dd.score || 0) : 0;
    });
    var radar = drawRadar(DIMS, vals, 260, color);
    // 明细表
    var rows = DIMS.map(function (d) {
      var dd = dims[d] || {};
      return '<tr><td>' + DN.esc(d) + '</td><td><b>' + (dd.score != null ? dd.score : '-') + '</b></td>' +
        '<td>' + (weights[d] != null ? weights[d] : '-') + '</td>' +
        '<td style="color:#86909c">' + DN.esc(dd.source || '') + '</td></tr>';
    }).join('');
    var table = '<table style="border-collapse:collapse;font-size:13px;min-width:320px">' +
      '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
      '<th style="padding:6px">维度</th><th style="padding:6px">得分</th><th style="padding:6px">权重</th><th style="padding:6px">数据源</th></tr></thead>' +
      '<tbody>' + rows + '</tbody></table>';
    top.innerHTML = scoreBox + '<div>' + radar + '</div><div style="flex:1;min-width:320px">' + table + '</div>';
    top.querySelectorAll('td').forEach(function (td) { td.style.padding = '6px'; td.style.borderBottom = '1px solid #f2f3f5'; });
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
        box.innerHTML = '<span class="gov-placeholder">趋势数据不足(点击"重算并快照"积累时序)</span>';
        return;
      }
      var vals = rows.map(function (x) { return Number(x.totalScore) || 0; });
      box.innerHTML = '<b style="font-size:13px;color:#86909c">近30天趋势: </b>' + drawSparkline(vals, 320, 60);
    }).catch(function () {});
  }

  // ========== 工单 ==========

  function loadIssues() {
    var status = (document.getElementById('hsIssueFilter') || {}).value || '';
    DN.get('/api/gov/health/issues' + (status ? '?status=' + encodeURIComponent(status) : '')).then(function (rows) {
      var box = document.getElementById('hsIssues');
      if (!box) return;
      if (!rows || !rows.length) { box.innerHTML = '<div class="gov-placeholder">暂无工单</div>'; return; }
      var body = rows.map(function (it) {
        var nexts = FLOW[it.status] || [];
        var ops = nexts.map(function (ns) {
          return '<a href="javascript:void(0)" data-tr="' + it.id + '" data-to="' + ns + '" style="margin-right:8px">' + ns + '</a>';
        }).join('');
        return '<tr><td>' + it.id + '</td><td>' + DN.esc(it.title) + '</td>' +
          '<td>' + DN.esc(it.dimension || '-') + '</td><td>' + DN.esc(it.severity || '') + '</td>' +
          '<td>' + DN.esc(it.owner || '未分配') + '</td>' +
          '<td><span style="color:#fff;background:' + (STATUS_COLOR[it.status] || '#86909c') +
          ';padding:2px 8px;border-radius:10px;font-size:12px">' + DN.esc(it.status) + '</span></td>' +
          '<td>' + ops + '<a href="javascript:void(0)" data-asg="' + it.id + '" style="margin-right:8px">指派</a>' +
          '<a href="javascript:void(0)" data-del="' + it.id + '" style="color:#f53f3f">删除</a></td></tr>';
      }).join('');
      box.innerHTML = '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
        '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        '<th>ID</th><th>标题</th><th>维度</th><th>级别</th><th>负责人</th><th>状态</th><th>操作</th></tr></thead>' +
        '<tbody>' + body + '</tbody></table>';
      box.querySelectorAll('th,td').forEach(function (td) { td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5'; });
      box.querySelectorAll('[data-tr]').forEach(function (a) {
        a.onclick = function () {
          DN.post('/api/gov/health/issues/' + a.getAttribute('data-tr') + '/transition',
            { status: a.getAttribute('data-to') })
            .then(function () { DN.toast('已流转'); loadIssues(); loadBoard(); })
            .catch(function (e) { DN.toast(e.message, 'error'); });
        };
      });
      box.querySelectorAll('[data-asg]').forEach(function (a) {
        a.onclick = function () {
          var owner = prompt('指派负责人'); if (owner == null) return;
          DN.post('/api/gov/health/issues/' + a.getAttribute('data-asg') + '/assign', { owner: owner })
            .then(function () { DN.toast('已指派'); loadIssues(); loadBoard(); })
            .catch(function (e) { DN.toast(e.message, 'error'); });
        };
      });
      box.querySelectorAll('[data-del]').forEach(function (a) {
        a.onclick = function () {
          DN.del('/api/gov/health/issues/' + a.getAttribute('data-del'))
            .then(function () { DN.toast('已删除'); loadIssues(); loadBoard(); })
            .catch(function (e) { DN.toast(e.message, 'error'); });
        };
      });
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function addIssue() {
    var title = prompt('工单标题'); if (!title) return;
    var type = prompt('问题类型 STANDARD/QUALITY/SECURITY/LINEAGE/LIFECYCLE/OTHER', 'OTHER'); if (!type) return;
    var dimension = prompt('所属维度(规范/质量/安全/生命周期/血缘,可空)') || '';
    var severity = prompt('级别 HIGH/MEDIUM/LOW', 'MEDIUM') || 'MEDIUM';
    var owner = prompt('负责人(可空)') || '';
    var desc = prompt('描述(可空)') || '';
    DN.post('/api/gov/health/issues', {
      title: title, issueType: type.trim().toUpperCase(), dimension: dimension,
      severity: severity.trim().toUpperCase(), owner: owner, description: desc
    }).then(function () { DN.toast('已新建'); loadIssues(); loadBoard(); })
      .catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function loadBoard() {
    DN.get('/api/gov/health/issues/leaderboard').then(function (rows) {
      var box = document.getElementById('hsBoard');
      if (!box) return;
      if (!rows || !rows.length) { box.innerHTML = '<div class="gov-placeholder">暂无数据</div>'; return; }
      var body = rows.map(function (r) {
        return '<tr><td>' + DN.esc(r.owner) + '</td><td>' + r.total + '</td>' +
          '<td style="color:#f53f3f">' + r.open + '</td><td style="color:#00b42a">' + r.closed + '</td></tr>';
      }).join('');
      box.innerHTML = '<table style="border-collapse:collapse;background:#fff;font-size:13px;min-width:360px">' +
        '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        '<th>负责人</th><th>总数</th><th>未关单</th><th>已关单</th></tr></thead><tbody>' + body + '</tbody></table>';
      box.querySelectorAll('th,td').forEach(function (td) { td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5'; });
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
        if (!rows || !rows.length) {
          tableBox.innerHTML = '<div class="gov-placeholder">暂无自评，点击"录入自评"</div>';
          return;
        }
        var body = domains.map(function (d) {
          var r = byDomain[d];
          return '<tr><td>' + DN.esc(d) + '</td><td>' + (r ? r.score : '-') + '</td>' +
            '<td>L' + (r ? r.level : '-') + '</td><td style="color:#86909c">' + DN.esc(r && r.note ? r.note : '') + '</td></tr>';
        }).join('');
        tableBox.innerHTML = '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
          '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
          '<th>能力域</th><th>得分</th><th>等级</th><th>备注</th></tr></thead><tbody>' + body + '</tbody></table>';
        tableBox.querySelectorAll('th,td').forEach(function (td) { td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5'; });
      });
    }).catch(function (e) { DN.toast(e.message, 'error'); });
  }

  function addMaturity() {
    DN.get('/api/gov/health/maturity/domains').then(function (domains) {
      var domain = prompt('能力域:\n' + (domains || []).join(' / '), (domains || [])[0] || '');
      if (!domain) return;
      var score = prompt('自评分 0-100', '60'); if (score == null) return;
      var level = prompt('成熟度等级 1-5', '3'); if (level == null) return;
      var note = prompt('备注(可空)') || '';
      DN.post('/api/gov/health/maturity', {
        domain: domain.trim(), score: Number(score), level: Number(level), note: note
      }).then(function () { DN.toast('已录入'); loadMaturity(); })
        .catch(function (e) { DN.toast(e.message, 'error'); });
    });
  }

  // ========== SVG 自绘 ==========

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
})();
