/* 治理模块：数据血缘（M3 字段映射血缘查询 / M4 SQL 解析血缘扩展） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  window.GOV_RENDERERS.lineage = function (c) {
    var bar = DN.h('div', { class: 'gov-desc' });
    bar.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '从同步任务重建血缘',
      onclick: function () {
        DN.post('/api/lineage/rebuild-edges').then(function (r) {
          DN.toast('已重建 ' + (r && r.edgeCount != null ? r.edgeCount : 0) + ' 条血缘边');
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      } }));
    bar.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '解析脚本SQL血缘',
      style: 'margin-left:8px',
      onclick: function () {
        DN.post('/api/lineage/parse-scripts').then(function (r) {
          DN.toast('SQL血缘已解析 ' + (r && r.edgeCount != null ? r.edgeCount : 0) + ' 条边');
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      } }));
    c.appendChild(bar);

    var q = DN.h('div', { class: 'gov-desc' });
    var inDb = DN.h('input', { id: 'lnDb', placeholder: '库名(如 ods)',
      style: 'padding:6px 10px;border:1px solid #d4d7de;border-radius:6px;margin-right:8px' });
    var inTab = DN.h('input', { id: 'lnTable', placeholder: '表名',
      style: 'padding:6px 10px;border:1px solid #d4d7de;border-radius:6px;margin-right:8px' });
    var btn = DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '查询血缘',
      onclick: queryLineage, style: 'margin-top:0' });
    q.appendChild(inDb); q.appendChild(inTab); q.appendChild(btn);
    c.appendChild(q);

    c.appendChild(DN.h('div', { id: 'lnResult' }));

    // 影响/溯源查询区（基于 dn_lineage_edge 表级 BFS）
    var iq = DN.h('div', { class: 'gov-desc', style: 'margin-top:16px;border-top:1px solid #e5e6eb;padding-top:12px' });
    var iDb = DN.h('input', { id: 'impDb', placeholder: '库名',
      style: 'padding:6px 10px;border:1px solid #d4d7de;border-radius:6px;margin-right:8px' });
    var iTab = DN.h('input', { id: 'impTable', placeholder: '表名',
      style: 'padding:6px 10px;border:1px solid #d4d7de;border-radius:6px;margin-right:8px' });
    iq.appendChild(iDb); iq.appendChild(iTab);
    iq.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '下游影响',
      onclick: function () { queryFlow('impact'); }, style: 'margin-top:0' }));
    iq.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '上游溯源',
      onclick: function () { queryFlow('trace'); }, style: 'margin-top:0;margin-left:8px' }));
    c.appendChild(iq);
    c.appendChild(DN.h('div', { id: 'impResult' }));
  };

  function queryFlow(kind) {
    var db = document.getElementById('impDb').value.trim();
    var table = document.getElementById('impTable').value.trim();
    if (!db || !table) { DN.toast('请输入库名与表名', 'error'); return; }
    var box = document.getElementById('impResult');
    box.innerHTML = '加载中...';
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
    DN.get('/api/lineage/' + kind + qs).then(function (list) {
      list = list || [];
      var title = kind === 'impact' ? '下游影响' : '上游溯源';
      if (!list.length) { box.innerHTML = '<div class="gov-placeholder">无' + title + '（先解析脚本SQL血缘或重建）</div>'; return; }
      box.innerHTML = '<div class="gov-desc"><b>' + title + '（共 ' + list.length + ' 个表）:</b></div>' +
        '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px"><thead>' +
        '<tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        '<th style="padding:8px">表</th><th style="padding:8px">层级</th><th style="padding:8px">来源</th></tr></thead><tbody>' +
        list.map(function (n) {
          return '<tr><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(n.db + '.' + n.table) +
            '</td><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(String(n.depth)) +
            '</td><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(n.source || '') + '</td></tr>';
        }).join('') + '</tbody></table>';
    }).catch(function (e) { box.innerHTML = '<div class="gov-placeholder">查询失败: ' + DN.esc(e.message) + '</div>'; });
  }

  function queryLineage() {
    var db = document.getElementById('lnDb').value.trim();
    var table = document.getElementById('lnTable').value.trim();
    if (!db || !table) { DN.toast('请输入库名与表名', 'error'); return; }
    var box = document.getElementById('lnResult');
    box.innerHTML = '加载中...';
    var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
    Promise.all([
      DN.get('/api/lineage/table-edges' + qs),
      DN.get('/api/lineage/column-edges' + qs)
    ]).then(function (res) {
      var nb = res[0] || {}, cols = res[1] || [];
      var up = (nb.upstream || []).map(function (e) { return e.srcDb + '.' + e.srcTable; });
      var down = (nb.downstream || []).map(function (e) { return e.dstDb + '.' + e.dstTable; });
      var html = '<div class="gov-desc"><b>上游表:</b> ' + (up.length ? up.map(DN.esc).join(', ') : '无') + '</div>' +
        '<div class="gov-desc"><b>下游表:</b> ' + (down.length ? down.map(DN.esc).join(', ') : '无') + '</div>';
      if (cols.length) {
        html += '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px"><thead>' +
          '<tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
          '<th style="padding:8px">目标列</th><th style="padding:8px">来源</th><th style="padding:8px">变换</th></tr></thead><tbody>' +
          cols.map(function (e) {
            return '<tr><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(e.dstColumn) +
              '</td><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(e.srcDb + '.' + e.srcTable + '.' + e.srcColumn) +
              '</td><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(e.transformType || '') + '</td></tr>';
          }).join('') + '</tbody></table>';
      } else {
        html += '<div class="gov-placeholder">无字段级血缘（先点上方重建，或该表非同步目标）</div>';
      }
      box.innerHTML = html;
    }).catch(function (e) { box.innerHTML = '<div class="gov-placeholder">查询失败: ' + DN.esc(e.message) + '</div>'; });
  }
})();
