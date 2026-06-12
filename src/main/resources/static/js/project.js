/* 项目管理模块(P1~P7 全量逻辑) — 由 workspace.html 内联段搬迁(39 纯工程波, 零行为变化)。
   依赖 workspace.html 全局: api/apiPost/escapeHtml/showToast/msgConfirm/msgPrompt/projShowModalBox/
   wsSkeleton/wsLocalDate/wsFmtAgo/csvCell/navigateTo/DN/_handleResponse/projDetailTab(markup)。
   全部声明保持顶层全局(非 IIFE), 供 onclick 与跨模块调用。 */
/* ============================================================
 * 项目管理 — PM-M1 项目空间(CRUD)
 * ============================================================ */
var _projList = [];
// 项目快速切换命令面板(大功能, Ctrl/Cmd+K): 输入即筛 + 键盘上下选 + Enter打开
var _projQsIdx = 0, _projQsRows = [];
window.projQuickSwitch = function() {
  var old = document.getElementById('projQsOverlay'); if (old) { old.remove(); return; }
  var ov = document.createElement('div');
  ov.id = 'projQsOverlay';
  ov.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.32);z-index:var(--z-spotlight);display:flex;align-items:flex-start;justify-content:center;padding-top:12vh;';
  ov.onclick = function(e) { if (e.target === ov) ov.remove(); };
  ov.innerHTML = '<div style="width:560px;max-width:92vw;background:var(--bg-card);border-radius:var(--radius-lg);box-shadow:var(--shadow-lg);overflow:hidden;">'
    + '<input id="projQsInput" placeholder="搜索项目名 / 编码 / 负责人，↑↓ 选择，Enter 打开，Esc 关闭" autocomplete="off" style="width:100%;box-sizing:border-box;padding:14px 16px;border:0;border-bottom:1px solid var(--divider);font-size:14px;outline:none;background:transparent;color:var(--text-regular);">'
    + '<div id="projQsList" style="max-height:50vh;overflow:auto;"></div></div>';
  document.body.appendChild(ov);
  var input = document.getElementById('projQsInput');
  _projQsIdx = 0;
  projQsRender('');
  input.oninput = function() { _projQsIdx = 0; projQsRender(input.value); };
  input.onkeydown = function(e) {
    if (e.key === 'ArrowDown') { e.preventDefault(); _projQsIdx = Math.min(_projQsRows.length - 1, _projQsIdx + 1); projQsHighlight(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); _projQsIdx = Math.max(0, _projQsIdx - 1); projQsHighlight(); }
    else if (e.key === 'Enter') { e.preventDefault(); var r = _projQsRows[_projQsIdx]; if (r) { ov.remove(); projOpenDetailById(r.id); } }
    else if (e.key === 'Escape') { ov.remove(); }
  };
  setTimeout(function() { input.focus(); }, 30);
};
function projQsRender(kw) {
  kw = (kw || '').trim().toLowerCase();
  var list = (_projList || []).filter(function(p) {
    if (!kw) return true;
    return ((p.projectName || '') + ' ' + (p.projectCode || '') + ' ' + (p.owner || '')).toLowerCase().indexOf(kw) >= 0;
  }).slice(0, 30);
  _projQsRows = list;
  var box = document.getElementById('projQsList'); if (!box) return;
  if (!list.length) { box.innerHTML = '<div style="padding:20px;text-align:center;color:var(--text-muted);font-size:13px;">' + (kw ? '未找到匹配「' + escapeHtml(kw) + '」的项目' : '暂无项目') + '</div>'; return; }
  box.innerHTML = list.map(function(p, i) {
    var st = PROJ_STATUS[p.status] || { t: p.status, c: '' };
    return '<div class="proj-qs-row" data-i="' + i + '" onclick="(function(){document.getElementById(\'projQsOverlay\').remove();projOpenDetailById(' + p.id + ');})()" style="display:flex;align-items:center;gap:10px;padding:10px 16px;cursor:pointer;border-bottom:1px solid var(--divider);">'
      + '<span style="font-weight:500;font-size:13px;">' + escapeHtml(p.projectName || '-') + '</span>'
      + '<span style="font-family:monospace;font-size:var(--fs-xs);color:var(--text-muted);">' + escapeHtml(p.projectCode || '') + '</span>'
      + '<span style="font-size:var(--fs-xs);color:' + st.c + ';margin-left:auto;">' + escapeHtml(st.t) + '</span>'
      + '<span style="font-size:var(--fs-xs);color:var(--text-muted);">' + escapeHtml(p.owner || '') + '</span></div>';
  }).join('');
  projQsHighlight();
}
function projQsHighlight() {
  Array.prototype.slice.call(document.querySelectorAll('#projQsList .proj-qs-row')).forEach(function(r, i) {
    var on = i === _projQsIdx;
    r.style.background = on ? 'var(--primary-bg)' : '';
    r.style.borderLeft = on ? '3px solid var(--primary)' : '3px solid transparent'; // 高对比度选中标识(兼容浅色主题)
    if (on) r.scrollIntoView({ block: 'nearest' });
  });
}
document.addEventListener('keydown', function(e) {
  if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
    var tag = (document.activeElement && document.activeElement.tagName) || '';
    // 仅在项目空间视图激活时拦截, 避免全局误触
    var pv = document.getElementById('viewProject');
    var active = pv && pv.offsetParent !== null;
    if (active) { e.preventDefault(); projQuickSwitch(); }
    else if (tag !== 'INPUT' && tag !== 'TEXTAREA' && tag !== 'SELECT') {
      // 全局唤起 AI 助手(天工司辰): 非项目视图、无输入焦点时
      e.preventDefault();
      if (window.openAiLauncher) openAiLauncher();
    }
  }
  // ESC 关闭动态覆盖层(对比/模板/快速切换), 提升键盘可达性
  if (e.key === 'Escape') {
    var ids = ['projQsOverlay', 'dbsyncCompareOverlay', 'dbsyncTplOverlay'];
    for (var i = 0; i < ids.length; i++) { var el = document.getElementById(ids[i]); if (el) { el.remove(); break; } }
  }
}, true);
var PROJ_TYPE_LABEL = { GENERAL: '通用', DATASYNC: '数据同步', DEVSQL: 'SQL开发', HYBRID: '混合', GOVERNANCE: '数据治理', AI: 'AI智能', TEST: '测试' };
var PROJ_ENV_LABEL = { DEV: '开发', PROD: '生产', MIXED: '混合', TEST: '测试' };
var PROJ_STATUS = { ACTIVE: { t: '活跃', c: 'var(--success)' }, ARCHIVED: { t: '已归档', c: 'var(--text-faint)' }, DELETED: { t: '已删除', c: 'var(--error)' } };

var _projTags = [], _projTagMap = {}, _projFav = {}, _projRecent = {};
function _pdata(res) { return (res && res.code === 0) ? (res.data || []) : (Array.isArray(res) ? res : []); }
window.loadProjectSpace = function() {
  var box = document.getElementById('projListBox');
  if (!box) return;
  Promise.all([
    api('/api/project/list'), api('/api/project/tags'), api('/api/project/tag-mappings'),
    api('/api/project/favorites'), api('/api/project/recent?limit=50')
  ]).then(function(a) {
    _projList = _pdata(a[0]);
    _projTags = _pdata(a[1]);
    _projTagMap = {};
    _pdata(a[2]).forEach(function(m) { (_projTagMap[m.projectId] = _projTagMap[m.projectId] || []).push(m.tagId); });
    _projFav = {};
    _pdata(a[3]).forEach(function(f) { _projFav[f.projectId] = { fav: true, pinned: f.pinned === 1 }; });
    _projRecent = {};
    _pdata(a[4]).forEach(function(r) { _projRecent[r.projectId] = r.accessAt; });
    projRenderKpi();
    projFillTagFilter();
    if (window.projRestoreFilters) projRestoreFilters();
    projApplyFilter();
    // N7: 健康分批量异步补充(不阻塞列表首屏), 到位后填徽标
    api('/api/project/health/batch').then(function(res) {
      _projHealth = (res && res.code === 0) ? (res.data || {}) : {};
      projFillHealthBadges();
    }).catch(function() {});
  }).catch(function() {
    box.innerHTML = '<div style="padding:24px;text-align:center;color:var(--error);">项目列表加载失败 <a href="#" onclick="loadProjectSpace();return false;" style="color:var(--primary);">重试</a></div>';
  });
};
var _projHealth = {};   // N7: projectId → {total, level, dims}
function projHealthBadgeHtml(h, projectId) {
  if (!h || h.total == null) return '';
  var c = h.total >= 80 ? 'var(--success)' : (h.total >= 60 ? 'var(--primary)' : (h.total >= 40 ? 'var(--warning)' : 'var(--error)'));
  var clk = projectId ? ' onclick="event.stopPropagation();projOpenDetailById(' + projectId + ');" ' : ' ';
  return ' <span' + clk + 'title="健康分 ' + h.total + ' · ' + escapeHtml(h.level || '') + '(运行/质量/任务/发布/协作五维' + (projectId ? ', 点击看详情' : '') + ')" style="font-size:var(--fs-xs);font-weight:600;color:' + c + ';border:1px solid ' + c + ';padding:0 6px;border-radius:var(--radius-lg);white-space:nowrap;' + (projectId ? 'cursor:pointer;' : '') + '">' + h.total + '分</span>';
}
function projFillHealthBadges() {
  Array.prototype.slice.call(document.querySelectorAll('#projListBox .dnph')).forEach(function(el) {
    var pid = el.getAttribute('data-id');
    var h = _projHealth[pid];
    if (h) el.innerHTML = projHealthBadgeHtml(h, parseInt(pid));
  });
}
function _projTagById(id) { for (var i = 0; i < _projTags.length; i++) if (_projTags[i].id === id) return _projTags[i]; return null; }
function projFillTagFilter() {
  var sel = document.getElementById('projFilterTag'); if (!sel) return;
  var cur = sel.value;
  sel.innerHTML = '<option value="">全部标签</option>' + _projTags.map(function(t) { return '<option value="' + t.id + '">' + escapeHtml(t.tagName) + '</option>'; }).join('');
  sel.value = cur;
}

function projTile(label, value, color, onclick) {
  // 不能给同一 div 拼两个 style 属性(HTML 只认第一个), 全部样式合并进一个 style
  var clk = onclick ? ' title="点击筛选" onclick="' + onclick + '"' : '';
  return '<div' + clk + ' class="proj-kpi-tile" style="background:var(--bg-card);border:1px solid var(--border);border-radius:var(--radius-lg);box-shadow:var(--shadow-sm);padding:14px 18px;' + (onclick ? 'cursor:pointer;' : '') + '">'
    + '<div class="num" style="font-size:24px;font-weight:700;line-height:1.2;' + (color ? 'color:' + color + ';' : 'color:var(--text-primary);') + '">' + value + '</div>'
    + '<div style="font-size:12px;color:var(--text-muted);margin-top:3px;">' + label + '</div></div>';
}
function projRenderKpi() {
  var box = document.getElementById('projKpiRow');
  if (!box) return;
  var total = _projList.length;
  var active = _projList.filter(function(p) { return p.status === 'ACTIVE'; }).length;
  var archived = _projList.filter(function(p) { return p.status === 'ARCHIVED'; }).length;
  var ym = new Date().toISOString().slice(0, 7);
  var month = _projList.filter(function(p) { return (p.createdAt || '').slice(0, 7) === ym; }).length;
  box.innerHTML = projTile('项目总数', total, '', "document.getElementById('projFilterStatus').value='';projApplyFilter()")
    + projTile('活跃', active, 'var(--success)', "document.getElementById('projFilterStatus').value='ACTIVE';projApplyFilter()")
    + projTile('已归档', archived, 'var(--text-faint)', "document.getElementById('projFilterStatus').value='ARCHIVED';projApplyFilter()")
    + projTile('本月新增', month, 'var(--primary)', "document.getElementById('projFilterStatus').value='';document.getElementById('projSort').value='created_desc';projApplyFilter()");
}
var _projSearchTimer = null;
window.projSearchDebounced = function() { clearTimeout(_projSearchTimer); _projSearchTimer = setTimeout(function() { projApplyFilter(); }, 220); };
window.projApplyFilter = function() {
  var q = (document.getElementById('projSearch') || {}).value || '';
  q = q.trim().toLowerCase();
  var ft = (document.getElementById('projFilterType') || {}).value || '';
  var fs = (document.getElementById('projFilterStatus') || {}).value || '';
  var fe = (document.getElementById('projFilterEnv') || {}).value || '';
  var sort = (document.getElementById('projSort') || {}).value || 'created_desc';
  var ftag = (document.getElementById('projFilterTag') || {}).value || '';
  var onlyFav = (document.getElementById('projOnlyFav') || {}).checked;
  var list = _projList.filter(function(p) {
    if (ft && p.projectType !== ft) return false;
    if (fs && p.status !== fs) return false;
    if (fe && p.env !== fe) return false;
    if (ftag && (_projTagMap[p.id] || []).indexOf(parseInt(ftag)) < 0) return false;
    if (onlyFav && !(_projFav[p.id] && _projFav[p.id].fav)) return false;
    if (q) {
      var hay = ((p.projectName || '') + ' ' + (p.projectCode || '') + ' ' + (p.owner || '')).toLowerCase();
      if (hay.indexOf(q) < 0) return false;
    }
    return true;
  });
  list.sort(function(a, b) {
    if (sort === 'health') {
      var ha = _projHealth[a.id] ? _projHealth[a.id].total : -1, hb = _projHealth[b.id] ? _projHealth[b.id].total : -1;
      if (ha !== hb) return ha - hb; // 低分在前: 先看需要救的项目
      return (b.id || 0) - (a.id || 0);
    }
    if (sort === 'name') return (a.projectName || '').localeCompare(b.projectName || '', 'zh');
    if (sort === 'created_asc') return String(a.createdAt || '').localeCompare(String(b.createdAt || '')) || ((a.id || 0) - (b.id || 0));
    if (sort === 'recent') { var ra = _projRecent[a.id] || '', rb = _projRecent[b.id] || ''; if (ra === rb) return 0; if (!ra) return 1; if (!rb) return -1; return String(rb).localeCompare(String(ra)); }
    if (sort === 'fav') {
      var pa = (_projFav[a.id] && _projFav[a.id].pinned) ? 2 : ((_projFav[a.id] && _projFav[a.id].fav) ? 1 : 0);
      var pb = (_projFav[b.id] && _projFav[b.id].pinned) ? 2 : ((_projFav[b.id] && _projFav[b.id].fav) ? 1 : 0);
      if (pa !== pb) return pb - pa;
      return (b.id || 0) - (a.id || 0);
    }
    return (b.id || 0) - (a.id || 0);
  });
  var cnt = document.getElementById('projFilterCount');
  if (cnt) cnt.textContent = '共 ' + list.length + ' / ' + _projList.length;
  _projFiltered = list;
  try { localStorage.setItem('projFilters', JSON.stringify({ q: (document.getElementById('projSearch') || {}).value || '', ft: ft, fs: fs, fe: fe, sort: sort, ftag: ftag, onlyFav: onlyFav })); } catch (e) {}
  projRenderList(list);
};
var _projFiltered = [];
window.projResetFilters = function() {
  ['projSearch', 'projFilterType', 'projFilterStatus', 'projFilterEnv', 'projFilterTag'].forEach(function(id) { var el = document.getElementById(id); if (el) el.value = ''; });
  var so = document.getElementById('projSort'); if (so) so.value = 'created_desc';
  var of = document.getElementById('projOnlyFav'); if (of) of.checked = false;
  try { localStorage.removeItem('projFilters'); } catch (e) {}
  projApplyFilter();
};
window.projRestoreFilters = function() {
  try {
    var f = JSON.parse(localStorage.getItem('projFilters') || 'null'); if (!f) return;
    var set = function(id, v) { var el = document.getElementById(id); if (el && v != null) el.value = v; };
    set('projSearch', f.q); set('projFilterType', f.ft); set('projFilterStatus', f.fs); set('projFilterEnv', f.fe); set('projSort', f.sort); set('projFilterTag', f.ftag);
    var of = document.getElementById('projOnlyFav'); if (of) of.checked = !!f.onlyFav;
  } catch (e) {}
};
window.projExportCsv = function() {
  var rows = _projFiltered && _projFiltered.length ? _projFiltered : _projList;
  var cell = csvCell; // 复用全局加固版(防公式注入+去换行)
  var head = ['项目名', '编码', '类型', '环境', '负责人', '状态', '标签', '描述', '创建时间'];
  var lines = [head.map(cell).join(',')];
  rows.forEach(function(p) {
    var tags = (_projTagMap[p.id] || []).map(function(t) { var o = _projTagById(t); return o ? o.tagName : ''; }).filter(Boolean).join(' ');
    lines.push([p.projectName, p.projectCode, PROJ_TYPE_LABEL[p.projectType] || p.projectType, PROJ_ENV_LABEL[p.env] || p.env, p.owner, (PROJ_STATUS[p.status] || {}).t || p.status, tags, p.description || '', (p.createdAt || '').replace('T', ' ').slice(0, 16)].map(cell).join(','));
  });
  var blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
  var url = URL.createObjectURL(blob), a = document.createElement('a');
  a.href = url; a.download = 'projects_' + new Date().toISOString().slice(0, 10) + '.csv';
  document.body.appendChild(a); a.click(); document.body.removeChild(a); setTimeout(function() { URL.revokeObjectURL(url); }, 1000);
};
var _projListView = (function() { try { return localStorage.getItem('proj.listView') || 'table'; } catch (e) { return 'table'; } })();
window.projSetListView = function(v) { _projListView = v; try { localStorage.setItem('proj.listView', v); } catch (e) {} projRenderList(); };
// 项目卡片视图(大功能): 网格卡片
function projListCards(rows) {
  var h = '<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:12px;">';
  rows.forEach(function(p) {
    var st = PROJ_STATUS[p.status] || { t: p.status, c: 'var(--text-faint)' };
    var fav = _projFav[p.id] && _projFav[p.id].fav;
    var pinned = _projFav[p.id] && _projFav[p.id].pinned;
    var tagChips = (_projTagMap[p.id] || []).map(function(tid) { var t = _projTagById(tid); if (!t) return ''; return '<span style="display:inline-block;font-size:var(--fs-xs);padding:0 6px;border-radius:var(--radius-lg);margin:2px 3px;word-break:break-word;background:' + (t.tagColor || 'var(--primary)') + '22;color:' + (t.tagColor || 'var(--primary)') + ';">' + escapeHtml(t.tagName) + '</span>'; }).join('');
    h += '<div style="border:1px solid var(--border);border-top:3px solid ' + st.c + ';border-radius:var(--radius-lg);padding:12px;background:var(--bg-card);cursor:pointer;transition:box-shadow var(--dur),transform var(--dur);" onclick="projOpenDetail(' + p.id + ')" onmouseover="this.style.boxShadow=\'0 4px 14px rgba(0,0,0,.1)\';this.style.transform=\'translateY(-2px)\'" onmouseout="this.style.boxShadow=\'\';this.style.transform=\'\'">'
      + '<div style="display:flex;align-items:center;gap:6px;margin-bottom:6px;">'
      + (pinned ? '<span style="color:var(--warning);display:inline-flex;vertical-align:-2px;">' + DN.icon('pin') + '</span>' : '')
      + '<span style="font-weight:600;font-size:14px;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;" title="' + escapeHtml(p.projectName || '') + '">' + escapeHtml(p.projectName || '-') + '<span class="dnph" data-id="' + p.id + '"></span></span>'
      + '<a href="#" onclick="event.stopPropagation();projToggleFav(' + p.id + ');return false;" title="收藏" style="text-decoration:none;font-size:15px;color:' + (fav ? 'var(--warning)' : 'var(--text-muted)') + ';">' + (fav ? '★' : '☆') + '</a></div>'
      + '<div style="font-family:monospace;font-size:var(--fs-xs);color:var(--text-muted);margin-bottom:6px;">' + escapeHtml(p.projectCode || '-') + '</div>'
      + (p.description ? '<div style="font-size:12px;color:var(--text-regular);margin-bottom:8px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;">' + escapeHtml(p.description) + '</div>' : '')
      + (tagChips ? '<div style="margin-bottom:8px;">' + tagChips + '</div>' : '')
      + '<div style="display:flex;align-items:center;gap:6px;font-size:var(--fs-xs);color:var(--text-muted);flex-wrap:wrap;">'
      + '<span style="padding:0 6px;border-radius:var(--radius-lg);background:var(--bg-hover);">' + escapeHtml(PROJ_TYPE_LABEL[p.projectType] || p.projectType || '-') + '</span>'
      + '<span style="padding:0 6px;border-radius:var(--radius-lg);background:var(--bg-hover);">' + escapeHtml(PROJ_ENV_LABEL[p.env] || p.env || '-') + '</span>'
      + '<span style="padding:0 7px;border-radius:var(--radius-lg);border:1px solid ' + st.c + ';color:' + st.c + ';font-weight:500;">' + escapeHtml(st.t) + '</span>'
      + '<span style="margin-left:auto;">' + escapeHtml(p.owner || '') + '</span></div>'
      + '</div>';
  });
  return h + '</div>';
}
function projRenderList(list) {
  var box = document.getElementById('projListBox');
  if (!box) return;
  var rows = list || _projFiltered || _projList;
  if (!rows.length) {
    box.innerHTML = '<div style="padding:32px;text-align:center;color:var(--text-muted);">' + (_projList.length ? '无匹配项目' : '暂无项目，点击右上角「新建项目」开始') + '</div>';
    return;
  }
  // (统计概览已由页面级 projTile 横幅提供, 此处不再重复)
  var lvTog = '<div style="display:flex;justify-content:flex-end;margin-bottom:8px;"><span style="display:inline-flex;border:1px solid var(--border);border-radius:var(--radius);overflow:hidden;">'
    + '<a href="#" onclick="projSetListView(\'table\');return false;" style="padding:3px 10px;font-size:12px;text-decoration:none;' + (_projListView === 'table' ? 'background:var(--primary);color:var(--text-inverse);' : 'color:var(--text-regular);') + '">表格</a>'
    + '<a href="#" onclick="projSetListView(\'card\');return false;" style="padding:3px 10px;font-size:12px;text-decoration:none;border-left:1px solid var(--border);' + (_projListView === 'card' ? 'background:var(--primary);color:var(--text-inverse);' : 'color:var(--text-regular);') + '">卡片</a></span></div>';
  if (_projListView === 'card') { box.innerHTML = lvTog + projListCards(rows); projFillHealthBadges(); return; }
  var h = lvTog + '<table class="dbsync-exec-table" style="width:100%;"><thead><tr>'
    + '<th style="width:30px;"></th><th>项目名</th><th>编码</th><th>类型</th><th>环境</th><th>标签</th><th>负责人</th><th>状态</th><th>创建时间</th><th style="width:210px;">操作</th>'
    + '</tr></thead><tbody>';
  rows.forEach(function(p) {
    var st = PROJ_STATUS[p.status] || { t: p.status, c: '' };
    var fav = _projFav[p.id] && _projFav[p.id].fav;
    var pinned = _projFav[p.id] && _projFav[p.id].pinned;
    var tagChips = (_projTagMap[p.id] || []).map(function(tid) {
      var t = _projTagById(tid); if (!t) return '';
      return '<span style="display:inline-block;font-size:var(--fs-xs);padding:0 6px;border-radius:var(--radius-lg);margin:1px;background:' + (t.tagColor || 'var(--primary)') + '22;color:' + (t.tagColor || 'var(--primary)') + ';">' + escapeHtml(t.tagName) + '</span>';
    }).join('');
    // 创建表单填写的自由标签(dn_project.tags) 一并以灰色 chip 展示，避免“填了却不显示”
    tagChips += (p.tags || '').split(/[,，]/).map(function(s) {
      s = s.trim(); if (!s) return '';
      return '<span style="display:inline-block;font-size:var(--fs-xs);padding:0 6px;border-radius:var(--radius-lg);margin:1px;background:var(--bg-hover);color:var(--text-muted);">' + escapeHtml(s) + '</span>';
    }).join('');
    h += '<tr>'
      + '<td><a href="#" onclick="projToggleFav(' + p.id + ');return false;" title="收藏" style="text-decoration:none;font-size:15px;color:' + (fav ? 'var(--warning)' : 'var(--text-muted)') + ';">' + (fav ? '★' : '☆') + '</a></td>'
      + '<td ondblclick="projOpenDetail(' + p.id + ')" title="双击打开详情" style="cursor:pointer;"><b>' + escapeHtml(p.projectName || '-') + '</b><span class="dnph" data-id="' + p.id + '"></span>' + (pinned ? ' <span style="font-size:var(--fs-xs);color:var(--warning);">置顶</span>' : '') + (p.description ? '<div style="font-size:var(--fs-xs);color:var(--text-muted);">' + escapeHtml(p.description) + '</div>' : '') + '</td>'
      + '<td style="font-family:monospace;">' + escapeHtml(p.projectCode || '-') + '</td>'
      + '<td>' + escapeHtml(PROJ_TYPE_LABEL[p.projectType] || p.projectType || '-') + '</td>'
      + '<td>' + escapeHtml(PROJ_ENV_LABEL[p.env] || p.env || '-') + '</td>'
      + '<td>' + (tagChips || '<span style="color:var(--text-muted);">-</span>') + '</td>'
      + '<td>' + escapeHtml(p.owner || '-') + '</td>'
      + '<td><span class="gov-pill ' + (p.status === 'ACTIVE' ? 'is-ok' : p.status === 'ARCHIVED' ? 'is-muted' : 'is-info') + '">' + escapeHtml(st.t) + '</span></td>'
      + '<td style="white-space:nowrap;">' + escapeHtml((p.createdAt || '').replace('T', ' ').slice(0, 16)) + '</td>'
      + '<td><a href="#" onclick="projOpenDetail(' + p.id + ');return false;" style="color:var(--primary);margin-right:8px;">详情</a>'
      + '<a href="#" data-perm="project:manage" onclick="projSetTagsModal(' + p.id + ');return false;" style="color:var(--primary);margin-right:8px;">标签</a>'
      + '<a href="#" data-perm="project:manage" onclick="projTogglePin(' + p.id + ');return false;" style="color:var(--primary);margin-right:8px;">' + (pinned ? '取消置顶' : '置顶') + '</a>'
      + '<a href="#" data-perm="project:manage" onclick="projOpenEdit(' + p.id + ');return false;" style="color:var(--primary);margin-right:8px;">编辑</a>'
      + (p.status === 'ACTIVE' ? '<a href="#" data-perm="project:manage" onclick="projArchive(' + p.id + ');return false;" style="color:var(--primary);margin-right:8px;">归档</a>' : '')
      + '<a href="#" data-perm="project:manage" onclick="projDelete(' + p.id + ',\'' + escapeHtml(p.projectName || '') + '\');return false;" style="color:var(--error);">删除</a></td>'
      + '</tr>';
  });
  h += '</tbody></table>';
  box.innerHTML = h;
  projFillHealthBadges();
}

/* ===== PM2-M1：收藏/置顶/标签 ===== */
window.projToggleFav = function(id) {
  apiPost('/api/project/' + id + '/favorite', {}).then(function(res) {
    var f = (res && res.code === 0) ? res.data.favorited : false;
    if (f) { _projFav[id] = _projFav[id] || {}; _projFav[id].fav = true; }
    else if (_projFav[id]) { _projFav[id].fav = false; _projFav[id].pinned = false; }
    showToast(f ? '已收藏' : '已取消收藏', 'success');
    projApplyFilter();
  }).catch(function() { showToast('操作失败', 'error'); });
};
window.projTogglePin = function(id) {
  var cur = _projFav[id] && _projFav[id].pinned;
  apiPost('/api/project/' + id + '/pin', { pinned: !cur }).then(function() {
    _projFav[id] = _projFav[id] || {}; _projFav[id].fav = true; _projFav[id].pinned = !cur;
    showToast(!cur ? '已置顶' : '已取消置顶', 'success'); projApplyFilter();
  }).catch(function() { showToast('操作失败', 'error'); });
};
window.projManageTags = function() {
  var h = '<div style="display:flex;gap:6px;margin-bottom:10px;"><input id="ptNewName" class="dbsync-form-input" style="width:140px;" placeholder="标签名"><input id="ptNewColor" type="color" value="var(--primary)" style="width:40px;height:32px;padding:0;border:1px solid var(--border);border-radius:var(--radius);"><button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projCreateTag()">新建</button></div><div id="ptList"></div>';
  projShowModalBox('标签管理', h);
  projRenderTagList();
};
function projRenderTagList() {
  var box = document.getElementById('ptList'); if (!box) return;
  if (!_projTags.length) { box.innerHTML = '<div style="color:var(--text-muted);">暂无标签</div>'; return; }
  box.innerHTML = _projTags.map(function(t) { return '<div style="display:flex;align-items:center;gap:8px;padding:4px 0;"><span style="display:inline-block;width:12px;height:12px;border-radius:var(--radius-sm);background:' + (t.tagColor || 'var(--primary)') + ';"></span><span style="flex:1;">' + escapeHtml(t.tagName) + '</span><a href="#" data-perm="project:manage" onclick="projDeleteTag(' + t.id + ');return false;" style="color:var(--error);">删除</a></div>'; }).join('');
}
window.projCreateTag = function() {
  var name = (document.getElementById('ptNewName').value || '').trim();
  if (!name) { showToast('请填写标签名', 'error'); return; }
  apiPost('/api/project/tags', { tagName: name, tagColor: document.getElementById('ptNewColor').value || 'var(--primary)' }).then(function(res) {
    if (res && res.code === 0) { _projTags.push(res.data); document.getElementById('ptNewName').value = ''; projRenderTagList(); projFillTagFilter(); }
    else showToast((res && res.msg) || '新建失败', 'error');
  }).catch(function() { showToast('新建失败', 'error'); });
};
window.projDeleteTag = function(tagId) {
  msgConfirm('删除标签', '删除标签将移除其在所有项目的关联，确认？', '确定删除').then(function(ok) {
    if (!ok) return;
    fetch('/api/project/tags/' + tagId, { method: 'DELETE' }).then(_handleResponse).then(function() {
      _projTags = _projTags.filter(function(t) { return t.id !== tagId; });
      Object.keys(_projTagMap).forEach(function(pid) { _projTagMap[pid] = (_projTagMap[pid] || []).filter(function(x) { return x !== tagId; }); });
      projRenderTagList(); projFillTagFilter(); projApplyFilter();
    }).catch(function() { showToast('删除失败', 'error'); });
  });
};
window.projSetTagsModal = function(pid) {
  if (!_projTags.length) { showToast('请先在「标签管理」新建标签', 'info'); return; }
  var cur = _projTagMap[pid] || [];
  var h = _projTags.map(function(t) { var on = cur.indexOf(t.id) >= 0; return '<label style="display:block;padding:3px 0;cursor:pointer;"><input type="checkbox" class="ptSetChk" value="' + t.id + '"' + (on ? ' checked' : '') + '> <span style="color:' + (t.tagColor || 'var(--primary)') + ';">' + escapeHtml(t.tagName) + '</span></label>'; }).join('');
  h += '<div style="margin-top:10px;text-align:right;"><button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projSaveTags(' + pid + ', this)">保存</button></div>';
  projShowModalBox('设置项目标签', h);
};
window.projSaveTags = function(pid, btn) {
  var ids = []; document.querySelectorAll('.ptSetChk:checked').forEach(function(c) { ids.push(parseInt(c.value)); });
  var _t, _r = function() { if (btn) { btn.disabled = false; btn.textContent = _t; } };
  if (btn) { _t = btn.textContent; btn.disabled = true; btn.textContent = '保存中...'; }
  apiPost('/api/project/' + pid + '/tags', { tagIds: ids }).then(function(res) {
    if (res && res.code === 0) { _projTagMap[pid] = ids; showToast('已保存', 'success'); projCloseModalBox(); projApplyFilter(); }
    else { showToast((res && res.msg) || '保存失败', 'error'); _r(); }
  }).catch(function() { showToast('保存失败', 'error'); _r(); });
};
function projShowModalBox(title, inner) {
  var old = document.getElementById('projModalBox'); if (old) old.remove();
  var ov = document.createElement('div'); ov.id = 'projModalBox';
  ov.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.35);z-index:var(--z-modal);display:flex;align-items:center;justify-content:center;';
  ov.onclick = function(e) { if (e.target === ov) ov.remove(); };
  var box = document.createElement('div');
  box.setAttribute('role', 'dialog'); box.setAttribute('aria-modal', 'true'); box.setAttribute('aria-label', title || '对话框');
  box.style.cssText = 'background:var(--bg-card);border-radius:var(--radius-lg);min-width:min(340px,92vw);max-width:min(90vw,640px);max-height:85vh;overflow:auto;box-shadow:var(--shadow-lg);';
  box.innerHTML = '<div style="display:flex;justify-content:space-between;align-items:center;padding:12px 16px;border-bottom:1px solid var(--border-light,var(--divider));"><b>' + escapeHtml(title) + '</b><span role="button" tabindex="0" aria-label="关闭" style="cursor:pointer;font-size:20px;color:var(--text-muted);line-height:1;padding:0 4px;" onclick="projCloseModalBox()">&times;</span></div><div style="padding:14px 16px;">' + inner + '</div>';
  // 关闭按钮键盘可操作 + Esc 关闭(模态 a11y)
  var _xb = box.querySelector('span[role="button"]');
  if (_xb) _xb.addEventListener('keydown', function(e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); projCloseModalBox(); } });
  ov.addEventListener('keydown', function(e) { if (e.key === 'Escape') projCloseModalBox(); });
  // 表单回车提交:输入框按 Enter 点击首个主按钮(textarea/Shift+Enter 不触发,避免误提交)
  box.addEventListener('keydown', function(e) {
    if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
    var t = e.target, tag = t && t.tagName;
    if (tag === 'TEXTAREA' || tag === 'BUTTON' || tag === 'A') return;
    var btn = box.querySelector('.btn-primary');
    if (btn && !btn.disabled) { e.preventDefault(); btn.click(); }
  });
  ov.appendChild(box); document.body.appendChild(ov);
}
window.projCloseModalBox = function() { var o = document.getElementById('projModalBox'); if (o) o.remove(); };
// Esc 关闭项目模块弹窗(动态 projModalBox 优先,其次两个静态弹窗),提升键盘可达性
if (!window._projEscBound) {
  window._projEscBound = true;
  document.addEventListener('keydown', function(e) {
    if (e.key !== 'Escape') return;
    var box = document.getElementById('projModalBox');
    if (box) { box.remove(); return; }
    var pm = document.getElementById('projModal');
    if (pm && pm.classList.contains('show')) { projCloseModal(); return; }
    var rm = document.getElementById('projReleaseModal');
    if (rm && rm.classList.contains('show')) { projCloseReleaseModal(); return; }
    // 统一: 弹窗都关掉后, Esc 关闭项目详情抽屉(与 DN.drawer / 同步详情抽屉行为一致)
    var pd = document.getElementById('projDetailDrawer');
    if (pd && pd.classList.contains('show')) { projCloseDetail(); return; }
  });
}

// II-5: 模板下拉与模板管理同源(删硬编码三预设); 选中按模板 cfg 预填类型/环境/标签
var _projTplCache = [];
window.projFillTemplateSelect = function() {
  var sel = document.getElementById('projTemplate'); if (!sel) return;
  api('/api/project/templates').then(function(res) {
    _projTplCache = _pdata(res);
    sel.innerHTML = '<option value="">空白项目</option>' + _projTplCache.map(function(t) {
      return '<option value="' + t.id + '">' + escapeHtml(t.templateName) + (t.templateType ? '（' + escapeHtml(PROJ_TYPE_LABEL[t.templateType] || t.templateType) + '）' : '') + '</option>';
    }).join('');
  }).catch(function() {});
};
window.projApplyTemplate = function() {
  var tid = document.getElementById('projTemplate').value;
  var t = _projTplCache.filter(function(x) { return String(x.id) === tid; })[0];
  if (!t) return;
  var cfg = {}; try { cfg = JSON.parse(t.configJson || '{}'); } catch (e) {}
  if (cfg.projectType) document.getElementById('projType').value = cfg.projectType;
  if (cfg.env) document.getElementById('projEnv').value = cfg.env;
  if (cfg.tags && !(document.getElementById('projTags').value || '').trim()) document.getElementById('projTags').value = cfg.tags;
};
window.projOpenCreate = function() {
  document.getElementById('projModalTitle').textContent = '新建项目';
  document.getElementById('projTemplateRow').style.display = '';
  projFillTemplateSelect();
  document.getElementById('projTemplate').value = '';
  document.getElementById('projId').value = '';
  document.getElementById('projName').value = '';
  document.getElementById('projCode').value = '';
  document.getElementById('projType').value = 'GENERAL';
  document.getElementById('projEnv').value = 'DEV';
  document.getElementById('projOwner').value = '';
  document.getElementById('projSensitivity').value = 'NORMAL';
  document.getElementById('projTags').value = '';
  document.getElementById('projDesc').value = '';
  document.getElementById('projModal').classList.add('show');
};
window.projOpenEdit = function(id) {
  var p = _projList.find(function(x) { return x.id === id; });
  if (!p) return;
  document.getElementById('projModalTitle').textContent = '编辑项目';
  document.getElementById('projTemplateRow').style.display = 'none';
  document.getElementById('projId').value = p.id;
  document.getElementById('projName').value = p.projectName || '';
  document.getElementById('projCode').value = p.projectCode || '';
  document.getElementById('projType').value = p.projectType || 'GENERAL';
  document.getElementById('projEnv').value = p.env || 'DEV';
  document.getElementById('projOwner').value = p.owner || '';
  document.getElementById('projSensitivity').value = p.sensitivity || 'NORMAL';
  document.getElementById('projTags').value = p.tags || '';
  document.getElementById('projDesc').value = p.description || '';
  document.getElementById('projModal').classList.add('show');
};
window.projCloseModal = function() { document.getElementById('projModal').classList.remove('show'); };
window.projSave = function(btn) {
  var name = (document.getElementById('projName').value || '').trim();
  if (!name) { showToast('请填写项目名称', 'error'); return; }
  var id = document.getElementById('projId').value;
  var body = {
    projectName: name,
    projectCode: (document.getElementById('projCode').value || '').trim(),
    projectType: document.getElementById('projType').value,
    env: document.getElementById('projEnv').value,
    owner: (document.getElementById('projOwner').value || '').trim(),
    sensitivity: document.getElementById('projSensitivity').value,
    tags: (document.getElementById('projTags').value || '').trim(),
    description: (document.getElementById('projDesc').value || '').trim()
  };
  if (id) body.id = parseInt(id);
  var _t; if (btn) { _t = btn.textContent; btn.disabled = true; btn.textContent = '保存中...'; }
  var _r = function() { if (btn) { btn.disabled = false; btn.textContent = _t; } };
  // II-5: 新建且选了模板 → 走模板创建(成员/任务骨架同建), 再用表单字段补丁; 否则普通 save
  var tplSel = document.getElementById('projTemplate');
  var tid = (!id && tplSel) ? tplSel.value : '';
  var done = function(res, newId) {
    if (res && res.code === 0) {
      showToast('已保存', 'success'); projCloseModal(); loadProjectSpace();
      // II-5: 新建直落详情, 顺动线配齐资产/成员/任务(编辑保存不跳)
      if (!id && newId) setTimeout(function() { projOpenDetailById(newId); }, 300);
    }
    else { showToast((res && res.msg) || '保存失败', 'error'); _r(); }
  };
  if (tid) {
    apiPost('/api/project/templates/' + tid + '/create', { projectName: body.projectName }).then(function(res) {
      if (!(res && res.code === 0 && res.data && res.data.id)) { done(res, null); return; }
      var newId = res.data.id;
      body.id = newId;
      apiPost('/api/project/save', body).then(function(res2) { done(res2, newId); })
        .catch(function() { done({ code: 0 }, newId); });   // 补丁失败不阻断, 项目已建成
    }).catch(function() { showToast('模板创建失败', 'error'); _r(); });
    return;
  }
  apiPost('/api/project/save', body).then(function(res) {
    done(res, res && res.data ? res.data.id : null);
  }).catch(function() { showToast('保存失败', 'error'); _r(); });
};
window.projArchive = function(id) {
  // III-2: 归档前列出影响面(绑定资产/启用同步任务), 建议先停用/移交
  api('/api/project/' + id + '/assets').catch(function() { return null; }).then(function(res) {
    var as = (res && res.code === 0) ? (res.data || []) : [];
    var jobN = as.filter(function(a) { return a.assetType === 'SYNC_JOB'; }).length;
    var tip = '归档后项目只读: 不能提交发布/建任务/绑资产, 可在状态筛选查看。'
      + (as.length ? '<br><b style="color:var(--warning-text);">当前绑定 ' + as.length + ' 个资产' + (jobN ? '(含 ' + jobN + ' 个同步任务, 调度不会自动停止)' : '') + ', 建议先停用或移交。</b>' : '')
      + '<br>确认归档？';
    msgConfirm('归档项目', tip, '确定归档').then(function(ok) {
      if (!ok) return;
      apiPost('/api/project/' + id + '/archive', {}).then(function() { showToast('已归档', 'success'); projCloseDetail(); loadProjectSpace(); })
        .catch(function() { showToast('归档失败', 'error'); });
    });
  });
};
window.projDelete = function(id, name) {
  msgConfirm('删除项目', '确认删除项目 [' + escapeHtml(name) + ']？(软删除，资产关联与成员将不再展示)', '确定删除').then(function(ok) {
    if (!ok) return;
    fetch('/api/project/' + id, { method: 'DELETE' }).then(_handleResponse).then(function() { showToast('已删除', 'success'); projCloseDetail(); loadProjectSpace(); })
      .catch(function() { showToast('删除失败', 'error'); });
  });
};

/* ===== PM-M2：项目详情抽屉 + 成员 + 设置 ===== */
var _projDetail = null;
var PROJ_ALL_TABS = ['overview', 'asset', 'member', 'task', 'release', 'activity', 'wiki', 'setting'];

// 项目报告生成(大功能): 汇编项目信息/任务/里程碑/成员为可打印报告
window.projGenReport = function() {
  if (!_projDetail) { showToast('请先打开项目', 'error'); return; }
  var pid = _projDetail.id;
  showToast('正在生成报告…', 'info');
  Promise.all([
    api('/api/project/' + pid + '/tasks').then(_pdata).catch(function() { return []; }),
    api('/api/project/' + pid + '/milestones').then(_pdata).catch(function() { return []; }),
    api('/api/project/' + pid + '/members').then(function(r) { return (r && r.code === 0) ? (r.data || []) : []; }).catch(function() { return []; })
  ]).then(function(arr) {
    var tasks = arr[0] || [], ms = arr[1] || [], members = arr[2] || [];
    var p = _projDetail, esc = escapeHtml;
    var today = wsLocalDate();
    var doneN = tasks.filter(function(t) { return t.status === 'DONE'; }).length;
    var doingN = tasks.filter(function(t) { return t.status === 'DOING'; }).length;
    var todoN = tasks.filter(function(t) { return t.status === 'TODO'; }).length;
    var overdueN = tasks.filter(function(t) { return t.dueDate && t.status !== 'DONE' && t.dueDate < today; }).length;
    var donePct = tasks.length ? Math.round(doneN * 100 / tasks.length) : 0;
    var now = new Date();
    var pad = function(n) { return (n < 10 ? '0' : '') + n; };
    var ts = now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()) + ' ' + pad(now.getHours()) + ':' + pad(now.getMinutes());
    var msRows = ms.length ? ms.map(function(m) {
      var mt = tasks.filter(function(t) { return t.milestoneId === m.id; });
      var dn = mt.filter(function(t) { return t.status === 'DONE'; }).length;
      var pg = mt.length ? Math.round(dn * 100 / mt.length) : 0;
      return '<tr><td>' + esc(m.name || '') + '</td><td>' + esc(m.startDate || '-') + ' ~ ' + esc(m.endDate || '-') + '</td><td>' + dn + '/' + mt.length + '</td><td>' + pg + '%</td></tr>';
    }).join('') : '<tr><td colspan="4" style="color:var(--text-muted)">无里程碑</td></tr>';
    var memRows = members.length ? members.map(function(m) { return '<tr><td>' + esc(m.username || '') + '</td><td>' + esc(projRoleLabel ? projRoleLabel(m.projectRole) : (m.projectRole || '')) + '</td></tr>'; }).join('') : '<tr><td colspan="2" style="color:var(--text-muted)">无成员</td></tr>';
    var html = '<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8"><title>项目报告 · ' + esc(p.projectName || '') + '</title>'
      // 打印窗是独立文档不加载站内 CSS——必须内嵌一份 v3 令牌, 否则 var() 全失效(按钮隐形/进度条透明)
      + '<style>:root{--primary:#4051d3;--text-primary:#10162b;--text-muted:#646d87;--text-inverse:#fff;--border:#e2e6ef;--divider:#edf0f6;--bg-main:#f6f8fb;--success:#2b8a4b;--radius:6px;--radius-xs:4px}'
      + 'body{font-family:-apple-system,"Microsoft YaHei",sans-serif;color:var(--text-primary);max-width:820px;margin:24px auto;padding:0 20px;line-height:1.7}'
      + 'h1{font-size:24px;border-bottom:3px solid var(--primary);padding-bottom:10px}h2{font-size:17px;margin-top:24px;color:var(--primary);border-left:4px solid var(--primary);padding-left:8px}'
      + 'table{width:100%;border-collapse:collapse;margin:10px 0;font-size:13px}th,td{border:1px solid var(--border);padding:7px 10px;text-align:left}th{background:var(--bg-main)}'
      + '.kpi{display:flex;gap:22px;flex-wrap:wrap;margin:12px 0}.kpi div{font-size:13px;color:var(--text-muted)}.kpi b{display:block;font-size:22px;color:var(--text-primary)}'
      + '.bar{height:10px;background:var(--divider);border-radius:var(--radius-xs);overflow:hidden;margin-top:4px}.bar>i{display:block;height:100%;background:var(--success)}'
      + '.muted{color:var(--text-muted);font-size:12px}@media print{.noprint{display:none}body{margin:0}}</style></head><body>'
      + '<div class="noprint" style="text-align:right;margin-bottom:10px"><button onclick="window.print()" style="padding:6px 16px;background:var(--primary);color:var(--text-inverse);border:0;border-radius:var(--radius);cursor:pointer">打印 / 另存为PDF</button></div>'
      + '<h1>项目报告 · ' + esc(p.projectName || '') + '</h1><div class="muted">生成时间：' + ts + '　|　编码 ' + esc(p.projectCode || '-') + '</div>'
      + '<h2>一、基本信息</h2><table>'
      + '<tr><th>类型</th><td>' + esc(PROJ_TYPE_LABEL[p.projectType] || p.projectType || '-') + '</td><th>环境</th><td>' + esc(PROJ_ENV_LABEL[p.env] || p.env || '-') + '</td></tr>'
      + '<tr><th>负责人</th><td>' + esc(p.owner || '-') + '</td><th>状态</th><td>' + esc((PROJ_STATUS[p.status] || {}).t || p.status || '-') + '</td></tr>'
      + '<tr><th>描述</th><td colspan="3">' + esc(p.description || '-') + '</td></tr></table>'
      + '<h2>二、任务进度</h2><div class="kpi"><div><b>' + tasks.length + '</b>任务总数</div><div><b style="color:var(--success)">' + doneN + '</b>已完成</div><div><b style="color:var(--primary)">' + doingN + '</b>进行中</div><div><b style="color:var(--text-muted)">' + todoN + '</b>待办</div><div><b style="color:' + (overdueN ? 'var(--error)' : 'var(--success)') + '">' + overdueN + '</b>超期</div></div>'
      + '<div>完成率 ' + donePct + '%<div class="bar"><i style="width:' + donePct + '%"></i></div></div>'
      + '<h2>三、里程碑（' + ms.length + '）</h2><table><tr><th>名称</th><th>周期</th><th>完成</th><th>进度</th></tr>' + msRows + '</table>'
      + '<h2>四、项目成员（' + members.length + '）</h2><table><tr><th>用户名</th><th>角色</th></tr>' + memRows + '</table>'
      + '<div class="muted" style="margin-top:30px;border-top:1px solid var(--border);padding-top:10px">本报告由系统依据项目实时数据自动生成。</div></body></html>';
    var w = window.open('', '_blank');
    if (!w) { showToast('弹窗被拦截, 请允许后重试', 'warning'); return; }
    w.document.open(); w.document.write(html); w.document.close();
  });
};
window.projOpenDetail = function(id) {
  var p = _projList.find(function(x) { return x.id === id; });
  if (!p) { if (window.projOpenDetailById) projOpenDetailById(id); return; } // 列表无此项(新建/过期)→拉取自愈
  _projDetail = p;
  apiPost('/api/project/' + id + '/visit', {}).catch(function() {});
  projRenderDetailHead(p);
  document.querySelectorAll('#projDetailTabs button').forEach(function(b) { b.classList.toggle('active', b.getAttribute('data-ptab') === 'overview'); });
  PROJ_ALL_TABS.forEach(function(t) { var el = document.getElementById('projPane_' + t); if (el) el.style.display = (t === 'overview') ? '' : 'none'; });
  document.getElementById('projDetailMask').classList.add('show');
  var d = document.getElementById('projDetailDrawer');
  d.classList.add('show'); d.setAttribute('aria-hidden', 'false');
  d.setAttribute('role', 'complementary'); d.setAttribute('aria-label', '项目详情');
  projLoadPane('overview');
};
window.projCloseDetail = function() {
  _projDetail = null;
  var d = document.getElementById('projDetailDrawer'), m = document.getElementById('projDetailMask');
  if (d) { d.classList.remove('show'); d.setAttribute('aria-hidden', 'true'); }
  if (m) m.classList.remove('show');
};
window.projDetailTab = function(btn, tab) {
  document.querySelectorAll('#projDetailTabs button').forEach(function(b) { b.classList.remove('active'); });
  btn.classList.add('active');
  PROJ_ALL_TABS.forEach(function(t) { var el = document.getElementById('projPane_' + t); if (el) el.style.display = (t === tab) ? '' : 'none'; });
  projLoadPane(tab);
};
function projLoadPane(tab) {
  if (_projDetail == null) return;
  if (tab === 'member') projLoadMembers();
  else if (tab === 'setting') projLoadSetting();
  else if (tab === 'asset' && window.projLoadAssets) projLoadAssets();
  else if (tab === 'overview' && window.projLoadOverview) projLoadOverview();
  else if (tab === 'release' && window.projLoadReleases) projLoadReleases();
  else if (tab === 'activity' && window.projLoadActivities) projLoadActivities();
  else if (tab === 'task' && window.projLoadTasks) projLoadTasks();
  else if (tab === 'wiki' && window.projLoadWiki) projLoadWiki();
  else { var el = document.getElementById('projPane_' + tab); if (el) el.innerHTML = '<div style="padding:24px;color:var(--text-muted);">即将完善</div>'; }
}
function projRenderDetailHead(p) {
  document.getElementById('projDetailName').textContent = p.projectName || ('项目#' + p.id);
  var st = PROJ_STATUS[p.status] || { t: p.status, c: '' };
  document.getElementById('projDetailStatus').innerHTML = '<span style="font-size:12px;padding:1px 8px;border-radius:var(--radius-lg);background:rgba(140,140,140,.12);color:' + st.c + ';">' + escapeHtml(st.t) + '</span>';
  document.getElementById('projDetailSub').textContent = (p.projectCode || '') + ' · ' + (PROJ_TYPE_LABEL[p.projectType] || p.projectType || '') + ' · ' + (PROJ_ENV_LABEL[p.env] || p.env || '') + ' · 负责人 ' + (p.owner || '-');
}
function projRoleOptions(sel) {
  return [['OWNER', '负责人'], ['ADMIN', '管理员'], ['DEVELOPER', '开发'], ['OPS', '运维'], ['VIEWER', '访客']]
    .map(function(r) { return '<option value="' + r[0] + '"' + (r[0] === sel ? ' selected' : '') + '>' + r[1] + '</option>'; }).join('');
}
window.projLoadMembers = function() {
  var pane = document.getElementById('projPane_member'); if (!pane || !_projDetail) return;
  pane.innerHTML = wsSkeleton(4);
  api('/api/project/' + _projDetail.id + '/members').then(function(res) {
    var ms = (res && res.code === 0) ? (res.data || []) : [];
    var h = '<div style="font-size:var(--fs-xs);color:var(--text-muted);margin-bottom:8px;">角色权限: 负责人/管理员=全部含审批发布 · 开发=资产与提交发布 · 运维=运行 · 访客=只读</div>'
      + '<div style="display:flex;gap:6px;margin-bottom:10px;align-items:center;flex-wrap:wrap;">'
      + '<input id="projMemberUser" class="dbsync-form-input" style="width:160px;" placeholder="用户名">'
      + '<select id="projMemberRole" class="dbsync-form-select" style="width:110px;">' + projRoleOptions('DEVELOPER') + '</select>'
      + '<button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projAddMember()">添加成员</button>'
      + '<a href="#" data-perm="project:manage" onclick="projPickUser();return false;" style="font-size:12px;color:var(--primary);margin-left:4px;">从用户列表选</a></div>';
    if (!ms.length) h += '<div style="padding:20px;text-align:center;color:var(--text-muted);">暂无成员</div>';
    else {
      // 角色分布徽标 + 搜索 + 导出
      var roleCnt = {}; ms.forEach(function(m) { var r = m.projectRole || ''; roleCnt[r] = (roleCnt[r] || 0) + 1; });
      h += '<div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center;margin-bottom:8px;">';
      Object.keys(roleCnt).forEach(function(r) { var on = _projMemberRoleFilter === r; h += '<span onclick="projToggleRoleFilter(\'' + escapeHtml(r) + '\')" title="点击筛选该角色" style="cursor:pointer;font-size:var(--fs-xs);padding:1px 8px;border-radius:var(--radius-lg);background:' + (on ? 'var(--primary)' : 'var(--bg-hover)') + ';color:' + (on ? 'var(--text-inverse)' : 'var(--text-regular)') + ';">' + escapeHtml(projRoleLabel(r)) + ' ' + roleCnt[r] + '</span>'; });
      h += '<input id="projMemberSearch" class="dbsync-form-input" style="width:140px;margin-left:auto;" placeholder="搜索成员" oninput="projFilterMembers()">';
      h += '<button class="btn btn-sm" onclick="projExportMembers()">导出CSV</button></div>';
      h += '<table class="dbsync-exec-table" id="projMemberTbl" style="width:100%;"><thead><tr><th>用户名</th><th>项目角色</th><th>加入时间</th><th style="width:80px;">操作</th></tr></thead><tbody>';
      ms.forEach(function(m) {
        h += '<tr class="proj-member-row" data-uname="' + escapeHtml((m.username || '').toLowerCase()) + '" data-role="' + escapeHtml(m.projectRole || '') + '"><td>' + escapeHtml(m.username) + '</td>'
          + '<td><select class="dbsync-form-select" style="width:110px;" onchange="projChangeRole(' + m.id + ',this.value)">' + projRoleOptions(m.projectRole) + '</select></td>'
          + '<td style="white-space:nowrap;">' + escapeHtml((m.createdAt || '').replace('T', ' ').slice(0, 16)) + '</td>'
          + '<td><a href="#" data-perm="project:manage" onclick="projRemoveMember(' + m.id + ');return false;" style="color:var(--error);">移除</a></td></tr>';
      });
      h += '</tbody></table>';
    }
    pane.innerHTML = h;
    _projMembers = ms;
    if (_projMemberRoleFilter) projFilterMembers();
  }).catch(function() { pane.innerHTML = '<div style="color:var(--error);">加载失败</div>'; });
};
var _projMembers = [];
var _projMemberRoleFilter = '';
window.projToggleRoleFilter = function(r) {
  _projMemberRoleFilter = (_projMemberRoleFilter === r) ? '' : r;
  projLoadMembers();
};
window.projFilterMembers = function() {
  var kw = (document.getElementById('projMemberSearch') || {}).value || ''; kw = kw.trim().toLowerCase();
  var rf = _projMemberRoleFilter;
  Array.prototype.slice.call(document.querySelectorAll('#projMemberTbl .proj-member-row')).forEach(function(tr) {
    var okKw = !kw || (tr.getAttribute('data-uname') || '').indexOf(kw) >= 0;
    var okRole = !rf || (tr.getAttribute('data-role') || '') === rf;
    tr.style.display = (okKw && okRole) ? '' : 'none';
  });
};
window.projExportMembers = function() {
  var ms = _projMembers || []; if (!ms.length) { showToast('暂无成员', 'info'); return; }
  var cell = csvCell; // 复用全局加固版(防公式注入+去换行)
  var lines = [['用户名', '角色', '加入时间'].map(cell).join(',')];
  ms.forEach(function(m) { lines.push([m.username, projRoleLabel(m.projectRole), (m.createdAt || '').replace('T', ' ').slice(0, 16)].map(cell).join(',')); });
  var blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
  var url = URL.createObjectURL(blob), a = document.createElement('a');
  a.href = url; a.download = 'members_' + (_projDetail ? _projDetail.id : '') + '.csv';
  document.body.appendChild(a); a.click(); document.body.removeChild(a); setTimeout(function() { URL.revokeObjectURL(url); }, 1000);
};
function projRoleLabel(r) { return ({ OWNER: '负责人', ADMIN: '管理员', DEVELOPER: '开发', OPS: '运维', VIEWER: '访客' })[r] || r; }
window.projAddMember = function() {
  var u = (document.getElementById('projMemberUser').value || '').trim();
  if (!u) { showToast('请填写用户名', 'error'); return; }
  var role = document.getElementById('projMemberRole').value;
  apiPost('/api/project/' + _projDetail.id + '/members', { username: u, role: role }).then(function(res) {
    if (res && res.code === 0) { showToast('已添加', 'success'); projLoadMembers(); }
    else showToast((res && res.msg) || '添加失败', 'error');
  }).catch(function() { showToast('添加失败', 'error'); });
};
window.projChangeRole = function(memberId, role) {
  fetch('/api/project/' + _projDetail.id + '/members/' + memberId, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role: role }) })
    .then(_handleResponse).then(function(res) {
      if (res && res.code === 0) showToast('角色已更新', 'success');
      else { showToast((res && res.msg) || '更新失败', 'error'); projLoadMembers(); }
    }).catch(function() { showToast('更新失败', 'error'); projLoadMembers(); });
};
window.projRemoveMember = function(memberId) {
  msgConfirm('移除成员', '确认移除该成员？', '确定移除').then(function(ok) {
    if (!ok) return;
  fetch('/api/project/' + _projDetail.id + '/members/' + memberId, { method: 'DELETE' }).then(_handleResponse).then(function(res) {
    if (res && res.code === 0) { showToast('已移除', 'success'); projLoadMembers(); } else showToast((res && res.msg) || '移除失败', 'error');
  }).catch(function() { showToast('移除失败', 'error'); });
  });
};
window.projPickUser = function() {
  // 轻量用户名端点(登录即可), 不调 /users(需 settings:user, 普通负责人会 403)
  api('/api/rbac/usernames').then(function(res) {
    var users = (res && res.code === 0) ? (res.data || []) : [];
    if (!users.length) { showToast('无可选用户', 'info'); return; }
    var rows = function(kw) {
      kw = (kw || '').toLowerCase();
      return users.filter(function(u) { return !kw || (u.username || '').toLowerCase().indexOf(kw) >= 0 || (u.nickname || '').toLowerCase().indexOf(kw) >= 0; })
        .map(function(u) { return '<div class="proj-pick-row" data-u="' + escapeHtml(u.username) + '" style="padding:7px 10px;cursor:pointer;border-radius:var(--radius);">' + escapeHtml(u.username) + (u.nickname ? ' <span style="color:var(--text-muted);font-size:12px;">' + escapeHtml(u.nickname) + '</span>' : '') + '</div>'; }).join('') || '<div style="padding:10px;color:var(--text-muted);">无匹配</div>';
    };
    var h = '<input id="projPickSearch" class="dbsync-form-input" style="width:100%;margin-bottom:8px;" placeholder="搜索用户名/昵称">'
      + '<div id="projPickList" style="max-height:280px;overflow:auto;">' + rows('') + '</div>';
    projShowModalBox('选择用户', h);
    var si = document.getElementById('projPickSearch'); if (si) { si.focus(); si.oninput = function() { document.getElementById('projPickList').innerHTML = rows(si.value); bind(); }; }
    function bind() {
      Array.prototype.slice.call(document.querySelectorAll('#projPickList .proj-pick-row')).forEach(function(el) {
        el.onclick = function() { document.getElementById('projMemberUser').value = el.getAttribute('data-u'); projCloseModalBox(); };
        el.onmouseenter = function() { el.style.background = 'var(--bg-hover,var(--bg-sunken))'; };
        el.onmouseleave = function() { el.style.background = ''; };
      });
    }
    bind();
  }).catch(function() { showToast('用户列表加载失败', 'error'); });
};
window.projLoadSetting = function() {
  var pane = document.getElementById('projPane_setting'); if (!pane || !_projDetail) return;
  var p = _projDetail;
  pane.innerHTML = '<div class="dbsync-detail-card" style="border:1px solid var(--divider);border-radius:var(--radius-lg);padding:14px;">'
    + '<div style="font-size:13px;line-height:2;">'
    + '<div><b>名称</b>：' + escapeHtml(p.projectName || '-') + '</div>'
    + '<div><b>编码</b>：<span style="font-family:monospace;">' + escapeHtml(p.projectCode || '-') + '</span></div>'
    + '<div><b>类型</b>：' + escapeHtml(PROJ_TYPE_LABEL[p.projectType] || p.projectType || '-') + '　<b>环境</b>：' + escapeHtml(PROJ_ENV_LABEL[p.env] || p.env || '-') + '</div>'
    + '<div><b>负责人</b>：' + escapeHtml(p.owner || '-') + '　<b>敏感度</b>：' + escapeHtml(p.sensitivity === 'SENSITIVE' ? '敏感' : '普通') + '</div>'
    + '<div><b>标签</b>：' + escapeHtml(p.tags || '-') + '</div>'
    + '<div><b>描述</b>：' + escapeHtml(p.description || '-') + '</div></div>'
    + '<div style="margin-top:14px;display:flex;gap:8px;">'
    + '<button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projOpenEdit(' + p.id + ')">编辑</button>'
    + '<button class="btn btn-sm" data-perm="project:manage" onclick="projSaveAsTemplate(' + p.id + ')">存为模板</button>'
    + (p.status === 'ACTIVE' ? '<button class="btn btn-sm" data-perm="project:manage" onclick="projArchive(' + p.id + ')">归档</button>' : '')
    + '<button class="btn btn-sm" style="color:var(--error);border-color:var(--error);" data-perm="project:manage" onclick="projDelete(' + p.id + ',\'' + escapeHtml(p.projectName || '') + '\')">删除</button>'
    + '</div></div>';
};
/* ===== PM-M3：资产纳管 ===== */
var PROJ_ASSET_TYPE = { SYNC_JOB: '同步任务', SCRIPT: '脚本', DATASOURCE: '数据源', QUALITY_RULE: '质量规则', METRIC: '指标' };
// R27 联动: 项目绑定资产 → 跳到该资产所在本体模块
/* ===== P1 资产归属闭环: 各模块表单"所属项目"共用工具(同步任务/脚本/质量规则) ===== */
// 填充下拉: 活跃项目列表 + 回显当前归属(取第一条绑定, 多项目绑定仍由项目资产页管理)
window.dnAssetProjFill = function(selId, type, assetId) {
  var sel = document.getElementById(selId); if (!sel) return Promise.resolve([]);
  sel.innerHTML = '<option value="">（不归属项目）</option>';
  // B3: 反查未完成前禁用下拉+哨兵值, 防"半加载状态保存"产生重复绑定/误解绑
  sel.setAttribute('data-old', assetId ? '__loading__' : '');
  sel.disabled = true;
  var pCur = assetId ? api('/api/project/asset-projects?type=' + type + '&assetId=' + assetId) : Promise.resolve(null);
  return Promise.all([api('/api/project/list?status=ACTIVE'), pCur]).then(function(arr) {
    var ps = (arr[0] && arr[0].code === 0) ? (arr[0].data || []) : [];
    var bound = (arr[1] && arr[1].code === 0) ? (arr[1].data || []) : [];
    sel.innerHTML = '<option value="">（不归属项目）</option>' + ps.map(function(p) { return '<option value="' + p.id + '">' + escapeHtml(p.projectName) + '</option>'; }).join('');
    if (bound.length) {
      sel.value = String(bound[0].projectId);
      // 归属项目不在活跃列表(已归档等)时补一项保证回显, 防止保存被误判为"改为不归属"而解绑
      if (sel.value !== String(bound[0].projectId)) {
        sel.insertAdjacentHTML('beforeend', '<option value="' + bound[0].projectId + '">' + escapeHtml(bound[0].projectName || ('#' + bound[0].projectId)) + '（非活跃）</option>');
        sel.value = String(bound[0].projectId);
      }
    }
    sel.setAttribute('data-old', bound.length ? JSON.stringify(bound[0]) : '');
    sel.disabled = false;
    return bound;
  }).catch(function() {
    sel.setAttribute('data-old', assetId ? '__unknown__' : '');
    sel.disabled = false;
    return [];
  });
};
// B2/B3: 保存后应用归属变更——未变不动; 改选→先解绑旧的这一条(失败即中止, 防双归属), 再绑新;
// 选空→只解绑表单展示的那条; data-old 哨兵(__loading__/__unknown__)时先实时反查再换绑, 不盲写。
// 失败向调用方 reject(调用方单独 toast"项目归属保存失败", 与主体保存成功分开提示)。
window.dnAssetProjApply = function(selId, type, assetId, assetName) {
  var sel = document.getElementById(selId); if (!sel || !assetId) return Promise.resolve();
  var raw = sel.getAttribute('data-old') || '';
  var pid = sel.value;
  var resolveOld;
  if (raw === '__loading__' || raw === '__unknown__') {
    resolveOld = api('/api/project/asset-projects?type=' + type + '&assetId=' + assetId)
      .then(function(res) { var b = (res && res.code === 0) ? (res.data || []) : null; if (b === null) throw new Error('归属反查失败'); return b.length ? b[0] : null; });
  } else {
    var old = null; try { old = raw ? JSON.parse(raw) : null; } catch (e) {}
    resolveOld = Promise.resolve(old);
  }
  return resolveOld.then(function(old) {
    if ((old ? String(old.projectId) : '') === (pid || '')) return;
    var chain = Promise.resolve();
    if (old && old.bindingId) {
      chain = fetch('/api/project/' + old.projectId + '/assets/' + old.bindingId, { method: 'DELETE' })
        .then(_handleResponse).then(function(res) {
          if (!res || res.code !== 0) throw new Error((res && res.msg) || '解绑原项目失败');
        });
    }
    if (pid) {
      chain = chain.then(function() {
        return apiPost('/api/project/' + pid + '/assets', { assetType: type, assetId: assetId, assetName: assetName || '' })
          .then(function(res) { if (!res || res.code !== 0) throw new Error((res && res.msg) || '绑定项目失败'); });
      });
    }
    return chain;
  });
};
// 列表页归属徽标: 批量反查 + 渲染小徽标(点击进项目空间)
window.dnAssetProjBadges = function(type, ids) {
  if (!ids || !ids.length) return Promise.resolve({});
  return api('/api/project/asset-projects/batch?type=' + type + '&ids=' + ids.join(','))
    .then(function(res) { return (res && res.code === 0) ? (res.data || {}) : {}; })
    .catch(function() { return {}; });
};
// N5: 资产侧"任务×N"徽标——该资产有项目任务在跟进, 点击直达项目任务页
window.dnAssetTaskBadges = function(refType, ids) {
  if (!ids || !ids.length) return Promise.resolve({});
  return api('/api/project/task-refs/batch?refType=' + refType + '&ids=' + ids.join(','))
    .then(function(res) { return (res && res.code === 0) ? (res.data || {}) : {}; })
    .catch(function() { return {}; });
};
window.dnAssetTaskBadgeHtml = function(tlist) {
  if (!tlist || !tlist.length) return '';
  var t = tlist[0];
  var undone = tlist.filter(function(x) { return x.status !== 'DONE'; }).length;
  return ' <a href="#" onclick="event.stopPropagation();navigateTo(\'project\');setTimeout(function(){if(window.projOpenDetailById)projOpenDetailById(' + t.projectId + ',\'task\');},800);return false;" title="' + escapeHtml(tlist.map(function(x) { return '#' + x.taskId + ' ' + (x.title || '') + '(' + x.status + ')'; }).join('、')) + '" style="font-size:var(--fs-xs);color:' + (undone ? 'var(--warning-text)' : 'var(--success-text)') + ';background:var(--bg-hover);padding:0 6px;border-radius:var(--radius-lg);white-space:nowrap;text-decoration:none;">任务×' + tlist.length + (undone ? '' : '✓') + '</a>';
};
window.dnAssetProjBadgeHtml = function(plist) {
  if (!plist || !plist.length) return '';
  var p = plist[0];
  var more = plist.length > 1 ? ' +' + (plist.length - 1) : '';
  return ' <a href="#" onclick="event.stopPropagation();navigateTo(\'project\');return false;" title="归属项目: ' + escapeHtml(plist.map(function(x) { return x.projectName; }).join('、')) + '" style="font-size:var(--fs-xs);color:var(--primary);background:var(--bg-hover);padding:0 6px;border-radius:var(--radius-lg);white-space:nowrap;text-decoration:none;">' + escapeHtml(p.projectName) + more + '</a>';
};
// II-5: 批量绑定——当前类型候选复选框(预滤已绑)+搜索+逐条容错一次刷新
window.projBatchBindModal = function() {
  var type = (document.getElementById('projAssetType') || {}).value;
  if (!type || !_projDetail) return;
  api('/api/project/' + _projDetail.id + '/asset-candidates?type=' + type).then(function(res) {
    var cands = ((res && res.code === 0) ? (res.data || []) : []).filter(function(c) { return !c.bound; });
    if (!cands.length) { showToast('该类型暂无未绑定的候选资产', 'info'); return; }
    var rows = cands.map(function(c) {
      return '<label class="pbb-row" data-k="' + escapeHtml(String(c.name || c.id).toLowerCase()) + '" style="display:block;padding:3px 0;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;"><input type="checkbox" class="pbbChk" value="' + c.id + '" data-name="' + escapeHtml(c.name || '') + '"> ' + escapeHtml(c.name || ('#' + c.id)) + '</label>';
    }).join('');
    var h = '<input id="pbbSearch" class="dbsync-form-input" style="width:100%;margin-bottom:8px;" placeholder="搜索名称" oninput="Array.prototype.slice.call(document.querySelectorAll(\'.pbb-row\')).forEach(function(r){r.style.display=(r.getAttribute(\'data-k\')||\'\').indexOf(this.value.trim().toLowerCase())>=0?\'\':\'none\'},this)">'
      + '<label style="display:block;padding:3px 0;font-size:12px;color:var(--text-muted);cursor:pointer;"><input type="checkbox" onchange="Array.prototype.slice.call(document.querySelectorAll(\'.pbbChk\')).forEach(function(c){if(c.closest(\'label\').style.display!==\'none\')c.checked=this.checked},this)"> 全选(可见项)</label>'
      + '<div style="max-height:280px;overflow:auto;">' + rows + '</div>'
      + '<div style="margin-top:10px;text-align:right;"><button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projBatchBindDo(this)">绑定选中</button></div>';
    projShowModalBox('批量绑定 · ' + (PROJ_ASSET_TYPE[type] || type), h);
  }).catch(function() { showToast('候选加载失败', 'error'); });
};
window.projBatchBindDo = function(btn) {
  var type = (document.getElementById('projAssetType') || {}).value;
  var picks = Array.prototype.slice.call(document.querySelectorAll('.pbbChk:checked'));
  if (!picks.length) { showToast('请勾选资产', 'error'); return; }
  if (btn) { btn.disabled = true; btn.textContent = '绑定中...'; }
  var okN = 0, failN = 0;
  var next = function(i) {
    if (i >= picks.length) {
      showToast('批量绑定完成: 成功 ' + okN + ' 条' + (failN ? ' / 失败 ' + failN + ' 条' : ''), failN ? 'warning' : 'success');
      projCloseModalBox(); projLoadAssets();
      return;
    }
    apiPost('/api/project/' + _projDetail.id + '/assets', { assetType: type, assetId: parseInt(picks[i].value), assetName: picks[i].getAttribute('data-name') || '' })
      .then(function(r) { if (r && r.code === 0) okN++; else failN++; next(i + 1); })
      .catch(function() { failN++; next(i + 1); });
  };
  next(0);
};
window.projAssetGoto = function(type, assetId) {
  if (type === 'SYNC_JOB') navigateTo('dbsync', { openDetail: assetId });
  else if (type === 'QUALITY_RULE') navigateTo('governance', { gov: 'quality' });
  else if (type === 'GOV_ISSUE') navigateTo('governance', { gov: 'health', focusIssue: assetId });
  else if (type === 'METRIC') { window.__metricsFocusId = assetId; navigateTo('metrics'); }
  else if (type === 'DATASOURCE') navigateTo('settings', { sm: 'datasource' });
  else if (type === 'SCRIPT') navigateTo('develop');
  else navigateTo('catalog');
};
window.projLoadAssets = function() {
  var pane = document.getElementById('projPane_asset'); if (!pane || !_projDetail) return;
  pane.innerHTML = wsSkeleton(4);
  api('/api/project/' + _projDetail.id + '/assets').then(function(res) {
    var as = (res && res.code === 0) ? (res.data || []) : [];
    var h = '<div style="display:flex;gap:6px;margin-bottom:10px;align-items:center;flex-wrap:wrap;">'
      + '<select id="projAssetType" class="dbsync-form-select" style="width:120px;" onchange="projLoadAssetCandidates()">'
      + Object.keys(PROJ_ASSET_TYPE).map(function(k) { return '<option value="' + k + '">' + PROJ_ASSET_TYPE[k] + '</option>'; }).join('')
      + '</select>'
      + '<select id="projAssetCand" class="dbsync-form-select" style="width:220px;"><option value="">加载中...</option></select>'
      + '<button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projBindAsset()">绑定资产</button>'
      + '<button class="btn btn-sm" data-perm="project:manage" onclick="projBatchBindModal()" title="当前类型下勾选多个资产一次绑定">批量绑定</button></div>';
    if (!as.length) h += '<div style="padding:20px;text-align:center;color:var(--text-muted);">暂无绑定资产，从上方选择类型与资产绑定</div>';
    else {
      var tc = {}; as.forEach(function(a) { tc[a.assetType] = (tc[a.assetType] || 0) + 1; });
      // 类型分布迷你环图 + 徽标
      var palette = { SYNC_JOB: 'var(--primary)', SCRIPT: 'var(--success)', DATASOURCE: 'var(--warning)', QUALITY_RULE: 'var(--primary)' };
      var keys = Object.keys(tc), acc = 0, stops = [];
      keys.forEach(function(k) { var p0 = acc / as.length * 100, p1 = (acc + tc[k]) / as.length * 100; stops.push((palette[k] || 'var(--text-faint)') + ' ' + p0.toFixed(1) + '% ' + p1.toFixed(1) + '%'); acc += tc[k]; });
      h += '<div style="display:flex;align-items:center;gap:14px;margin-bottom:10px;padding:10px;border:1px solid var(--divider);border-radius:var(--radius-lg);">'
        + '<div style="width:72px;height:72px;border-radius:50%;background:conic-gradient(' + stops.join(',') + ');position:relative;flex-shrink:0;"><div style="position:absolute;inset:14px;background:var(--bg-card);border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:15px;font-weight:700;">' + as.length + '</div></div>'
        + '<div style="display:flex;gap:8px;flex-wrap:wrap;">';
      keys.forEach(function(k) { h += '<span style="font-size:12px;display:inline-flex;align-items:center;gap:5px;"><span style="width:9px;height:9px;border-radius:2px;background:' + (palette[k] || 'var(--text-faint)') + ';"></span>' + escapeHtml(PROJ_ASSET_TYPE[k] || k) + ' ' + tc[k] + '</span>'; });
      h += '</div></div>';
      h += '<table class="dbsync-exec-table" style="width:100%;"><thead><tr><th>类型</th><th>资产名</th><th>资产ID</th><th>绑定时间</th><th style="width:132px;white-space:nowrap;">操作</th></tr></thead><tbody>';
      as.forEach(function(a) {
        h += '<tr><td>' + escapeHtml(PROJ_ASSET_TYPE[a.assetType] || a.assetType) + '</td>'
          + '<td>' + escapeHtml(a.assetName || '-') + '</td><td>' + escapeHtml(String(a.assetId == null ? '-' : a.assetId)) + '</td>'
          + '<td style="white-space:nowrap;">' + escapeHtml((a.createdAt || '').replace('T', ' ').slice(0, 16)) + '</td>'
          + '<td><a href="#" onclick="projAssetGoto(\'' + a.assetType + '\',' + a.assetId + ');return false;" style="color:var(--primary);margin-right:8px;" title="跳到该资产所在模块">查看</a>'
          + '<a href="#" onclick="projAssetProjects(\'' + a.assetType + '\',' + a.assetId + ');return false;" style="color:var(--primary);margin-right:8px;">归属</a>'
          + '<a href="#" data-perm="project:manage" onclick="projUnbindAsset(' + a.id + ');return false;" style="color:var(--error);">解绑</a></td></tr>';
      });
      h += '</tbody></table>';
    }
    pane.innerHTML = h;
    projLoadAssetCandidates();
  }).catch(function() { pane.innerHTML = '<div style="color:var(--error);">加载失败</div>'; });
};
window.projLoadAssetCandidates = function() {
  var typeEl = document.getElementById('projAssetType'), candEl = document.getElementById('projAssetCand');
  if (!typeEl || !candEl || !_projDetail) return;
  candEl.innerHTML = '<option value="">加载中...</option>';
  api('/api/project/' + _projDetail.id + '/asset-candidates?type=' + encodeURIComponent(typeEl.value)).then(function(res) {
    var list = (res && res.code === 0) ? (res.data || []) : [];
    var avail = list.filter(function(x) { return !x.bound; });
    if (!avail.length) { candEl.innerHTML = '<option value="">（无可绑定资产）</option>'; return; }
    candEl.innerHTML = avail.map(function(x) { return '<option value="' + x.id + '" data-name="' + escapeHtml(String(x.name || '')) + '">' + escapeHtml(String(x.name || x.id)) + '</option>'; }).join('');
  }).catch(function() { candEl.innerHTML = '<option value="">加载失败</option>'; });
};
window.projBindAsset = function() {
  var type = document.getElementById('projAssetType').value;
  var sel = document.getElementById('projAssetCand');
  var id = sel.value;
  if (!id) { showToast('请选择要绑定的资产', 'error'); return; }
  var opt = sel.options[sel.selectedIndex];
  var name = opt ? opt.getAttribute('data-name') : '';
  apiPost('/api/project/' + _projDetail.id + '/assets', { assetType: type, assetId: parseInt(id), assetName: name }).then(function(res) {
    if (res && res.code === 0) { showToast('已绑定', 'success'); projLoadAssets(); }
    else showToast((res && res.msg) || '绑定失败', 'error');
  }).catch(function() { showToast('绑定失败', 'error'); });
};
window.projUnbindAsset = function(rowId) {
  msgConfirm('解绑资产', '确认解绑该资产？(仅解除项目关联，不删除资产本身)', '确定解绑').then(function(ok) {
    if (!ok) return;
    fetch('/api/project/' + _projDetail.id + '/assets/' + rowId, { method: 'DELETE' }).then(_handleResponse).then(function() { showToast('已解绑', 'success'); projLoadAssets(); })
      .catch(function() { showToast('解绑失败', 'error'); });
  });
};
window.projAssetProjects = function(type, assetId) {
  api('/api/project/asset-projects?type=' + encodeURIComponent(type) + '&assetId=' + assetId).then(function(res) {
    var ps = (res && res.code === 0) ? (res.data || []) : [];
    if (!ps.length) { showToast('该资产仅属于当前项目', 'info'); return; }
    var names = ps.map(function(p) { return p.projectName + '(' + p.projectCode + ')'; }).join('、');
    showToast('归属项目：' + names, 'info');
  }).catch(function() { showToast('查询失败', 'error'); });
};

/* ===== PM-M4：概览大盘 ===== */
window.projLoadOverview = function() {
  var pane = document.getElementById('projPane_overview'); if (!pane || !_projDetail) return;
  pane.innerHTML = wsSkeleton(4);
  api('/api/project/' + _projDetail.id + '/overview').then(function(res) {
    var d = (res && res.code === 0) ? (res.data || {}) : {};
    var ac = d.assetCounts || {};
    // II-5: 空项目引导条——新建后三步配齐, 不让用户面对空荡荡的概览发呆
    var guide = '';
    if ((d.assetTotal || 0) === 0 || (d.memberCount || 0) <= 1) {
      var gbtn = function(tab, label) { return '<button class="btn btn-sm" onclick="var b=document.querySelector(\'#projDetailTabs button[data-ptab=&quot;' + tab + '&quot;]\');if(b)projDetailTab(b,\'' + tab + '\')">' + label + '</button>'; };
      guide = '<div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:10px 14px;margin-bottom:14px;border:1px solid var(--border);border-left:3px solid var(--primary);border-radius:var(--radius-lg);background:var(--bg-card);font-size:12.5px;">'
        + '<b style="color:var(--primary);">项目刚起步</b><span style="color:var(--text-muted);">建议先完成三步:</span>'
        + ((d.assetTotal || 0) === 0 ? gbtn('asset', '① 绑定资产') : '')
        + ((d.memberCount || 0) <= 1 ? gbtn('member', '② 添加成员') : '')
        + gbtn('task', '③ 建任务') + '</div>';
    }
    var h = guide + '<div id="projHealthCard" style="margin-bottom:14px;"></div>'
      + '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(110px,1fr));gap:10px;margin-bottom:14px;">'
      + projTile('资产总数', d.assetTotal != null ? d.assetTotal : 0, 'var(--primary)')
      + projTile('成员', d.memberCount != null ? d.memberCount : 0, 'var(--success)')
      + projTile('发布版本', d.releaseTotal != null ? d.releaseTotal : 0)
      + projTile('待审批', d.releasePending != null ? d.releasePending : 0, (d.releasePending > 0 ? 'var(--warning)' : ''))
      + '</div>';
    h += '<div class="dbsync-detail-card" style="border:1px solid var(--divider);border-radius:var(--radius-lg);padding:12px;margin-bottom:14px;">'
      + '<div style="font-weight:600;margin-bottom:8px;font-size:13px;">资产分布</div><div style="display:flex;gap:16px;flex-wrap:wrap;font-size:13px;">'
      + Object.keys(PROJ_ASSET_TYPE).map(function(k) { return '<span>' + PROJ_ASSET_TYPE[k] + '：<b>' + (ac[k] || 0) + '</b></span>'; }).join('')
      + '</div></div>';
    // N8: 绑定资产运行三组(同步任务/脚本/质量规则), 空组不渲染——健康分扣分可解释
    var runGroup = function(title, g, isQuality) {
      if (!g || !g.total) return '';
      var s = '<div style="margin-bottom:10px;"><div style="font-weight:600;font-size:12.5px;margin-bottom:4px;color:var(--text-regular);">' + title + ' (' + g.total + ')</div>'
        + '<div style="display:flex;gap:16px;font-size:13px;margin-bottom:4px;flex-wrap:wrap;">';
      if (isQuality) {
        s += '<span>通过 <b style="color:var(--success);">' + (g.ok || 0) + '</b></span>'
          + '<span>未达标 <b style="color:var(--error);">' + (g.bad || 0) + '</b></span>'
          + '<span>执行异常 <b style="color:var(--warning-text);">' + (g.abnormal || 0) + '</b></span>'
          + '<span>未执行 <b style="color:var(--text-muted);">' + (g.neverRun || 0) + '</b></span>'
          + (g.avgPassPct != null ? '<span>平均通过率 <b>' + g.avgPassPct + '%</b></span>' : '');
      } else {
        s += '<span>成功 <b style="color:var(--success);">' + (g.success || 0) + '</b></span>'
          + '<span>失败 <b style="color:var(--error);">' + (g.failed || 0) + '</b></span>'
          + '<span>运行中 <b style="color:var(--primary);">' + (g.running || 0) + '</b></span>'
          + '<span>未运行 <b style="color:var(--text-muted);">' + (g.neverRun || 0) + '</b></span>';
      }
      s += '</div>';
      var rec = g.recent || [];
      if (rec.length) {
        s += '<div style="font-size:12px;line-height:1.9;">';
        rec.forEach(function(x) {
          var c = x.status === 'SUCCESS' ? 'var(--success)' : (x.status === 'FAILED' ? 'var(--error)' : (x.status === 'RUNNING' ? 'var(--primary)' : 'var(--text-faint)'));
          s += '<div style="display:flex;gap:8px;"><span style="flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + escapeHtml(x.name || '-') + '</span><span style="color:' + c + ';">' + escapeHtml(x.status || '-') + (isQuality && x.passRate != null ? ' ' + x.passRate + '%' : '') + '</span><span style="color:var(--text-muted);white-space:nowrap;">' + escapeHtml((x.at || '').replace('T', ' ').slice(0, 16)) + '</span></div>';
        });
        s += '</div>';
      }
      return s + '</div>';
    };
    // P4: 指标组复用质量渲染分支(ok/bad/neverRun 同构)
    var metricG = d.metricRuns || {};
    var metricView = metricG.total ? { total: metricG.total, ok: metricG.ok, bad: metricG.bad, abnormal: 0, neverRun: metricG.neverRun, avgPassPct: metricG.okPct, recent: (metricG.recent || []).map(function(x) { return { at: x.at, name: x.name, status: x.status, passRate: x.value }; }) } : null;
    var runsHtml = runGroup('同步任务', d.jobRuns, false) + runGroup('脚本', d.scriptRuns, false) + runGroup('质量规则', d.qualityRuns, true) + runGroup('指标', metricView, true);
    if (runsHtml) {
      h += '<div class="dbsync-detail-card" style="border:1px solid var(--divider);border-radius:var(--radius-lg);padding:12px;margin-bottom:14px;">'
        + '<div style="font-weight:600;margin-bottom:8px;font-size:13px;">绑定资产运行</div>' + runsHtml + '</div>';
    }
    var acts = d.activity || [];
    h += '<div class="dbsync-detail-card" style="border:1px solid var(--divider);border-radius:var(--radius-lg);padding:12px;">'
      + '<div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;"><span style="font-weight:600;font-size:13px;">最近活动</span>';
    if (acts.length) {
      var kinds = { member: ['成员', 'var(--success)'], asset: ['资产', 'var(--primary)'], release: ['发布', 'var(--warning)'] };
      var present = {}; acts.forEach(function(a) { present[a.kind || 'release'] = 1; });
      h += '<span style="margin-left:auto;display:flex;gap:4px;">';
      h += '<span onclick="projFilterOverviewActs(\'\')" class="proj-act-chip" data-k="" data-color="var(--text-regular)" style="cursor:pointer;font-size:var(--fs-xs);padding:1px 8px;border-radius:var(--radius-lg);background:var(--primary);color:var(--text-inverse);">全部</span>';
      Object.keys(kinds).forEach(function(k) { if (present[k]) h += '<span onclick="projFilterOverviewActs(\'' + k + '\')" class="proj-act-chip" data-k="' + k + '" data-color="' + kinds[k][1] + '" style="cursor:pointer;font-size:var(--fs-xs);padding:1px 8px;border-radius:var(--radius-lg);background:var(--bg-hover);color:' + kinds[k][1] + ';">' + kinds[k][0] + '</span>'; });
      h += '</span>';
    }
    h += '</div>';
    if (!acts.length) h += '<div style="color:var(--text-muted);font-size:12px;">暂无活动</div>';
    else {
      h += '<div id="projOverviewActs" style="font-size:12px;line-height:1.9;">';
      acts.forEach(function(a) {
        var dot = a.kind === 'member' ? 'var(--success)' : (a.kind === 'asset' ? 'var(--primary)' : 'var(--warning)');
        h += '<div class="proj-act-row" data-kind="' + escapeHtml(a.kind || 'release') + '" style="display:flex;gap:8px;"><span style="color:' + dot + ';">●</span><span style="flex:1;">' + escapeHtml(a.text || '') + '</span><span style="color:var(--text-muted);white-space:nowrap;">' + escapeHtml((a.at || '').replace('T', ' ').slice(0, 16)) + '</span></div>';
      });
      h += '</div>';
    }
    h += '</div>';
    pane.innerHTML = h;
    projSetTabBadge('release', d.releasePending || 0, 'var(--warning)');
    projSetTabBadge('member', d.memberCount || 0, 'var(--text-faint)');
    projLoadHealthCard();
    window.projFilterOverviewActs = window.projFilterOverviewActs || function(k) {
      Array.prototype.slice.call(document.querySelectorAll('#projOverviewActs .proj-act-row')).forEach(function(r) {
        r.style.display = (!k || (r.getAttribute('data-kind') || '') === k) ? '' : 'none';
      });
      Array.prototype.slice.call(document.querySelectorAll('.proj-act-chip')).forEach(function(c) {
        var on = (c.getAttribute('data-k') || '') === k;
        c.style.background = on ? 'var(--primary)' : 'var(--bg-hover)';
        c.style.color = on ? 'var(--text-inverse)' : (c.getAttribute('data-color') || 'var(--text-regular)');
      });
    };
  }).catch(function() { pane.innerHTML = '<div style="color:var(--error);">加载失败 <a href="#" onclick="projLoadOverview();return false;" style="color:var(--primary);">重试</a></div>'; });
};
// 详情抽屉 tab 数量徽标
window.projSetTabBadge = function(ptab, n, color) {
  var btn = document.querySelector('#projDetailTabs button[data-ptab="' + ptab + '"]'); if (!btn) return;
  var old = btn.querySelector('.proj-tab-badge'); if (old) old.remove();
  if (n > 0) {
    var b = document.createElement('span');
    b.className = 'proj-tab-badge';
    b.style.cssText = 'display:inline-block;min-width:16px;text-align:center;background:' + (color || 'var(--warning)') + ';color:var(--text-inverse);border-radius:var(--radius-lg);font-size:var(--fs-xs);padding:0 5px;margin-left:4px;line-height:16px;';
    b.textContent = n > 99 ? '99+' : n;
    btn.appendChild(b);
  }
};
window.projLoadHealthCard = function() {
  var card = document.getElementById('projHealthCard'); if (!card || !_projDetail) return;
  // B10: 失败态统一渲染"加载失败+重试", 不再用空对象兜底画假红条
  var renderFail = function() {
    card.innerHTML = '<div class="dbsync-detail-card" style="border:1px solid var(--divider);border-radius:var(--radius-lg);padding:12px;color:var(--error);font-size:13px;">健康分加载失败 <a href="#" onclick="projLoadHealthCard();return false;" style="color:var(--primary);">重试</a></div>';
  };
  api('/api/project/' + _projDetail.id + '/health').then(function(res) {
    if (!res || res.code !== 0 || !res.data) { renderFail(); return; }
    var d = res.data;
    var dims = d.dims || {}, dimMax = d.dimMax || {};
    var color = d.total >= 80 ? 'var(--success)' : (d.total >= 60 ? 'var(--primary)' : (d.total >= 40 ? 'var(--warning)' : 'var(--error)'));
    // P2 运行驱动五维(B5/B8/B9 口径): 终态成功率/仅success通过率/超期/积压/近30天协作
    var dimLabel = { run: '运行', quality: '质量', task: '任务', release: '发布', collab: '协作' };
    var dimTip = { run: '绑定同步任务与脚本最近终态成功率', quality: '绑定质量规则平均通过率与指标取值健康率合并(仅计成功执行)', task: '任务超期未完成占比', release: '待审批发布积压', collab: '成员与近30天活动' };
    // N3: 五维条可下钻——点条直达问题处
    var dimGo = {
      run: "projHealthGoto('run')", quality: "projHealthGoto('quality')", task: "projHealthGoto('task')",
      release: "projHealthGoto('release')", collab: "projHealthGoto('collab')"
    };
    var bars = Object.keys(dimLabel).map(function(k) {
      var v = dims[k] || 0, mx = dimMax[k] || 20;
      // II-6: 每维按自身得分率分色——总分高不再掩盖短板维
      var pct = Math.round(v * 100 / mx);
      var dimC = pct >= 80 ? 'var(--success)' : (pct >= 60 ? 'var(--primary)' : (pct >= 40 ? 'var(--warning)' : 'var(--error)'));
      return '<div title="' + dimTip[k] + ' · 点击查看" onclick="' + dimGo[k] + '" onmouseover="this.style.background=\'var(--bg-hover)\'" onmouseout="this.style.background=\'\'" style="display:flex;align-items:center;gap:6px;font-size:12px;margin:2px 0;cursor:pointer;border-radius:var(--radius-sm);padding:1px 4px;"><span style="width:32px;color:var(--text-muted);">' + dimLabel[k] + '</span>'
        + '<div style="flex:1;height:8px;background:var(--bg-hover,var(--divider));border-radius:var(--radius-sm);overflow:hidden;"><div style="width:' + pct + '%;height:100%;background:' + dimC + ';"></div></div><span style="width:38px;text-align:right;color:var(--text-muted);">' + v + '/' + mx + '</span></div>';
    }).join('');
    card.innerHTML = '<div class="dbsync-detail-card" style="border:1px solid var(--divider);border-radius:var(--radius-lg);padding:12px;display:flex;gap:16px;align-items:center;">'
      + '<div style="text-align:center;min-width:90px;"><div style="font-size:30px;font-weight:700;color:' + color + ';">' + (d.total != null ? d.total : '-') + '</div><div style="font-size:12px;color:var(--text-muted);">健康分 · ' + escapeHtml(d.level || '') + '</div></div>'
      + '<div style="flex:1;">' + bars + '</div></div>';
  }).catch(renderFail);
};
// N3: 健康分维度下钻——run→概览运行卡 / quality→质量规则模块 / task→任务页勾超期 / release→发布页 / collab→成员页
window.projHealthGoto = function(dim) {
  if (dim === 'quality') { projAssetGoto('QUALITY_RULE'); return; }
  var tabOf = { task: 'task', release: 'release', collab: 'member', run: 'overview' };
  var tab = tabOf[dim] || 'overview';
  if (dim === 'task') _projTaskFilter.overdue = true;
  var btn = document.querySelector('#projDetailTabs button[data-ptab="' + tab + '"]');
  if (btn) projDetailTab(btn, tab);
  if (dim === 'run') setTimeout(function() {
    var pane = document.getElementById('projPane_overview');
    if (!pane) return;
    var cards = pane.querySelectorAll('.dbsync-detail-card');
    for (var i = 0; i < cards.length; i++) {
      if (cards[i].textContent.indexOf('运行') >= 0) { cards[i].scrollIntoView({ behavior: 'smooth', block: 'center' }); break; }
    }
  }, 600);
};
window.projLoadActivities = function() {
  var pane = document.getElementById('projPane_activity'); if (!pane || !_projDetail) return;
  pane.innerHTML = wsSkeleton(4);
  api('/api/project/' + _projDetail.id + '/activities?limit=200').then(function(res) {
    var as = (res && res.code === 0) ? (res.data || []) : [];
    _projActs = as;
    if (!as.length) { pane.innerHTML = '<div style="padding:24px;text-align:center;color:var(--text-muted);">暂无活动记录（成员/资产/发布/设置等写操作将在此留痕）</div>'; return; }
    var h = projActHeatmap(as)
      + '<div style="display:flex;gap:6px;margin-bottom:10px;align-items:center;flex-wrap:wrap;">'
      + '<select id="projActMethod" class="dbsync-form-select" style="width:auto;" onchange="projRenderActs()"><option value="">全部方法</option><option>POST</option><option>PUT</option><option>DELETE</option><option>GET</option></select>'
      + '<select id="projActStatus" class="dbsync-form-select" style="width:auto;" onchange="projRenderActs()"><option value="">全部状态</option><option value="ok">成功(&lt;400)</option><option value="err">失败(≥400)</option></select>'
      + '<input id="projActSearch" class="dbsync-form-input" style="width:160px;" placeholder="搜索路径/操作人" oninput="projRenderActs()">'
      + '<button class="btn btn-sm" style="margin-left:auto;" onclick="projExportActs()">导出CSV</button></div>'
      + '<div id="projActTblBox"></div>';
    pane.innerHTML = h;
    projRenderActs();
  }).catch(function() { pane.innerHTML = '<div style="color:var(--error);">加载失败 <a href="#" onclick="projLoadActivities();return false;" style="color:var(--primary);">重试</a></div>'; });
};
// 活动贡献热力图(大功能): 近13周按天活动量, GitHub风格色块
function projActHeatmap(acts) {
  var WEEKS = 13;
  var cnt = {}; // YYYY-MM-DD -> n
  acts.forEach(function(a) { var d = (a.createdAt || '').slice(0, 10); if (d) cnt[d] = (cnt[d] || 0) + 1; });
  var today = new Date(); today.setHours(0, 0, 0, 0);
  // 对齐到本周日(列尾)
  var end = new Date(today); end.setDate(end.getDate() + (6 - end.getDay()));
  var start = new Date(end); start.setDate(start.getDate() - (WEEKS * 7 - 1));
  var pad2 = function(n) { return (n < 10 ? '0' : '') + n; };
  var fmt = function(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); };
  var max = 0; Object.keys(cnt).forEach(function(k) { if (cnt[k] > max) max = cnt[k]; });
  var colorOf = function(n) {
    if (!n) return 'var(--bg-hover,var(--border))';
    var r = n / (max || 1);
    return r > 0.66 ? 'var(--primary)' : r > 0.33 ? 'rgba(var(--primary-rgb),.55)' : 'rgba(var(--primary-rgb),.25)';
  };
  var cells = '';
  var totalActs = 0;
  for (var wk = 0; wk < WEEKS; wk++) {
    cells += '<div style="display:flex;flex-direction:column;gap:3px;">';
    for (var dow = 0; dow < 7; dow++) {
      var d = new Date(start); d.setDate(start.getDate() + wk * 7 + dow);
      if (d > today) { cells += '<div style="width:12px;height:12px;"></div>'; continue; }
      var ds = fmt(d), n = cnt[ds] || 0; totalActs += n;
      cells += '<div title="' + ds + ' · ' + n + ' 次活动" style="width:12px;height:12px;border-radius:2px;background:' + colorOf(n) + ';"></div>';
    }
    cells += '</div>';
  }
  return '<div style="border:1px solid var(--divider);border-radius:var(--radius-lg);padding:12px;margin-bottom:12px;">'
    + '<div style="display:flex;align-items:center;margin-bottom:8px;"><span style="font-size:13px;font-weight:600;">活动热力 · 近 ' + WEEKS + ' 周</span>'
    + '<span style="margin-left:auto;font-size:var(--fs-xs);color:var(--text-muted);display:flex;align-items:center;gap:4px;">少 <span style="width:11px;height:11px;background:var(--bg-hover,var(--border));border-radius:2px;display:inline-block;"></span><span style="width:11px;height:11px;background:rgba(var(--primary-rgb),.25);border-radius:2px;display:inline-block;"></span><span style="width:11px;height:11px;background:rgba(var(--primary-rgb),.55);border-radius:2px;display:inline-block;"></span><span style="width:11px;height:11px;background:var(--primary);border-radius:2px;display:inline-block;"></span> 多</span></div>'
    + '<div style="display:flex;gap:3px;overflow-x:auto;">' + cells + '</div>'
    + '<div style="font-size:var(--fs-xs);color:var(--text-muted);margin-top:6px;">窗口内共 ' + totalActs + ' 次活动, 单日峰值 ' + max + ' 次</div></div>';
}
var _projActs = [];
// R38 活动审计下钻: 按审计路径识别所属模块, 提供"前往"
function projActRouteOf(path) {
  if (!path) return null;
  if (path.indexOf('/api/sync-job') >= 0 || path.indexOf('/api/dbsync') >= 0) return { r: 'dbsync', l: '数据同步' };
  if (path.indexOf('/api/quality') >= 0) return { r: 'quality', l: '数据质量' };
  if (path.indexOf('/api/consumption') >= 0) return { r: 'metrics', l: '指标取值' };
  if (path.indexOf('/api/metric') >= 0) return { r: 'metrics', l: '指标管理' };
  if (path.indexOf('/api/mdm') >= 0) return { r: 'mdm', l: '主数据' };
  if (path.indexOf('/api/metadata') >= 0) return { r: 'catalog', l: '数据地图' };
  if (path.indexOf('/api/scheduler') >= 0 || path.indexOf('/api/task-execution') >= 0) return { r: 'operations', l: '数据运维' };
  if (path.indexOf('/api/gov') >= 0) return { r: 'governance', l: '数据治理' };
  if (path.indexOf('/api/rbac') >= 0 || path.indexOf('/api/system') >= 0 || path.indexOf('/api/auth') >= 0) return { r: 'settings', l: '系统管理' };
  if (path.indexOf('/api/project') >= 0) return { r: 'project', l: '项目管理' };
  return null;
}
// III-1: method+path → 人话动作(活动页签可读性); 未命中回退原始 path
function projActHuman(method, path) {
  if (!path) return '';
  var m = method || '';
  var rules = [
    [/\/releases\/\d+\/approve/, '审批通过发布'], [/\/releases\/\d+\/reject/, '驳回发布'],
    [/\/releases\/\d+\/release/, '发布上线'], [/\/releases\/\d+\/rollback/, '回滚发布'],
    [/\/releases$/, m === 'POST' ? '提交发布' : '查看发布'],
    [/\/tasks\/\d+\/comments/, m === 'POST' ? '评论任务' : '查看评论'],
    [/\/tasks\/\d+$/, m === 'DELETE' ? '删除任务' : '更新任务'],
    [/\/tasks$/, m === 'POST' ? '保存任务' : '查看任务'],
    [/\/assets\/\d+$/, '解绑资产'], [/\/assets$/, m === 'POST' ? '绑定资产' : '查看资产'],
    [/\/members\/\d+$/, m === 'DELETE' ? '移除成员' : '修改成员角色'],
    [/\/members$/, m === 'POST' ? '添加成员' : '查看成员'],
    [/\/milestones/, '里程碑操作'], [/\/wiki\//, '文档操作'],
    [/\/archive$/, '归档项目'], [/\/save-as-template/, '存为模板'],
    [/\/api\/project\/save/, '保存项目'], [/\/api\/project\/\d+$/, m === 'DELETE' ? '删除项目' : '查看项目']
  ];
  for (var i = 0; i < rules.length; i++) {
    if (rules[i][0].test(path)) return rules[i][1];
  }
  return '';
}
window.projRenderActs = function() {
  var box = document.getElementById('projActTblBox'); if (!box) return;
  var fm = (document.getElementById('projActMethod') || {}).value || '';
  var fs = (document.getElementById('projActStatus') || {}).value || '';
  var kw = ((document.getElementById('projActSearch') || {}).value || '').trim().toLowerCase();
  var as = _projActs.filter(function(a) {
    if (fm && (a.method || '') !== fm) return false;
    if (fs === 'ok' && !(a.status != null && a.status < 400)) return false;
    if (fs === 'err' && !(a.status != null && a.status >= 400)) return false;
    if (kw && ((a.path || '') + ' ' + (a.userName || '')).toLowerCase().indexOf(kw) < 0) return false;
    return true;
  });
  if (!as.length) { box.innerHTML = '<div style="padding:20px;text-align:center;color:var(--text-muted);">无匹配记录</div>'; return; }
  var h = '<table class="dbsync-exec-table" style="width:100%;"><thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>状态</th><th>前往</th></tr></thead><tbody>';
  as.forEach(function(a) {
    var go = projActRouteOf(a.path || '');
    var goCell = go ? '<a href="#" onclick="navigateTo(\'' + go.r + '\');return false;" style="color:var(--primary)" title="前往' + go.l + '">' + go.l + '</a>' : '-';
    // III-1: 动作列人话优先, 原始 method+path 降为悬浮 title
    var human = projActHuman(a.method, a.path || '');
    var act = human
      ? '<span title="' + escapeHtml((a.method || '') + ' ' + (a.path || '')) + '">' + escapeHtml(human) + '</span>'
      : '<span style="font-family:monospace;font-size:var(--fs-xs);">' + escapeHtml((a.method || '') + ' ' + (a.path || '-')) + '</span>';
    h += '<tr><td style="white-space:nowrap;">' + escapeHtml((a.createdAt || '').replace('T', ' ').slice(0, 19)) + '</td>'
      + '<td>' + escapeHtml(a.userName || '-') + '</td>'
      + '<td style="max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' + act + '</td>'
      + '<td style="color:' + (a.status != null && a.status >= 400 ? 'var(--error)' : 'var(--success)') + ';">' + (a.status != null ? a.status : '-') + '</td>'
      + '<td>' + goCell + '</td></tr>';
  });
  box.innerHTML = h + '</tbody></table><div style="font-size:var(--fs-xs);color:var(--text-muted);margin-top:6px;">共 ' + as.length + ' / ' + _projActs.length + ' 条</div>';
};
window.projExportActs = function() {
  var as = _projActs || []; if (!as.length) { showToast('暂无活动', 'info'); return; }
  var cell = window.csvCell || function(v) { return '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"'; };
  var lines = [['时间', '操作人', '方法', '路径', '状态'].map(cell).join(',')];
  as.forEach(function(a) { lines.push([(a.createdAt || '').replace('T', ' ').slice(0, 19), a.userName, a.method, a.path, a.status].map(cell).join(',')); });
  var blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
  var url = URL.createObjectURL(blob), el = document.createElement('a');
  el.href = url; el.download = 'project_activities_' + (_projDetail ? _projDetail.id : '') + '.csv';
  document.body.appendChild(el); el.click(); document.body.removeChild(el); setTimeout(function() { URL.revokeObjectURL(url); }, 1000);
};

/* ===== PM2-M3：任务待办 + 里程碑 ===== */
var _projMilestones = [];
var TASK_STATUS = { TODO: { t: '待办', c: 'var(--text-faint)' }, DOING: { t: '进行中', c: 'var(--primary)' }, DONE: { t: '完成', c: 'var(--success)' } };
var TASK_PRIO = { HIGH: { t: '高', c: 'var(--error)' }, MEDIUM: { t: '中', c: 'var(--warning)' }, LOW: { t: '低', c: 'var(--text-faint)' } };
var _projTaskFilter = { assignee: '', overdue: false, priority: '' };
var _projTasksAll = [];
var _projTaskView = (function() { try { var v = localStorage.getItem('proj.taskView') || 'list'; return ['list', 'kanban'].indexOf(v) >= 0 ? v : 'list'; } catch (e) { return 'list'; } })();
window.projSetTaskView = function(v) { _projTaskView = v; try { localStorage.setItem('proj.taskView', v); } catch (e) {} projLoadTasks(); };
window.projLoadTasks = function() {
  var pane = document.getElementById('projPane_task'); if (!pane || !_projDetail) return;
  pane.innerHTML = wsSkeleton(4);
  Promise.all([api('/api/project/' + _projDetail.id + '/tasks'), api('/api/project/' + _projDetail.id + '/milestones')]).then(function(arr) {
    var tasksAll = _projTasksAll = _pdata(arr[0]); _projMilestones = _pdata(arr[1]);
    var msName = {}; _projMilestones.forEach(function(m) { msName[m.id] = m.name; });
    var _td = wsLocalDate();
    var asgOpts = []; (function() { var seen = {}; tasksAll.forEach(function(t) { if (t.assignee && !seen[t.assignee]) { seen[t.assignee] = 1; asgOpts.push(t.assignee); } }); })();
    var fAsg = _projTaskFilter.assignee, fOver = _projTaskFilter.overdue, fPrio = _projTaskFilter.priority;
    var tasks = tasksAll.filter(function(t) {
      if (fAsg && (t.assignee || '') !== fAsg) return false;
      if (fOver && !(t.dueDate && t.status !== 'DONE' && t.dueDate < _td)) return false;
      if (fPrio && (t.priority || '') !== fPrio) return false;
      return true;
    });
    var cnt = { TODO: 0, DOING: 0, DONE: 0 };
    tasks.forEach(function(t) { cnt[t.status] = (cnt[t.status] || 0) + 1; });
    var _today = wsLocalDate();
    var overdueN = tasks.filter(function(t) { return t.dueDate && t.status !== 'DONE' && t.dueDate < _today; }).length;
    var donePct = tasks.length ? Math.round(cnt.DONE * 100 / tasks.length) : 0;
    var asgSel = '<select class="dbsync-form-select" style="width:auto;" onchange="_projTaskFilter.assignee=this.value;projLoadTasks();"><option value="">全部指派人</option>' + asgOpts.map(function(a) { return '<option value="' + escapeHtml(a) + '"' + (fAsg === a ? ' selected' : '') + '>' + escapeHtml(a) + '</option>'; }).join('') + '</select>';
    var prioSel = '<select class="dbsync-form-select" style="width:auto;" onchange="_projTaskFilter.priority=this.value;projLoadTasks();"><option value="">全部优先级</option>' + Object.keys(TASK_PRIO).map(function(p) { return '<option value="' + p + '"' + (fPrio === p ? ' selected' : '') + '>' + TASK_PRIO[p].t + '</option>'; }).join('') + '</select>';
    var viewToggle = '<span class="proj-view-toggle" style="display:inline-flex;border:1px solid var(--border);border-radius:var(--radius);overflow:hidden;">'
      + '<a href="#" onclick="projSetTaskView(\'list\');return false;" title="列表视图" style="padding:3px 10px;font-size:12px;text-decoration:none;' + (_projTaskView === 'list' ? 'background:var(--primary);color:var(--text-inverse);' : 'color:var(--text-regular);') + '">列表</a>'
      + '<a href="#" onclick="projSetTaskView(\'kanban\');return false;" title="看板视图" style="padding:3px 10px;font-size:12px;text-decoration:none;border-left:1px solid var(--border);' + (_projTaskView === 'kanban' ? 'background:var(--primary);color:var(--text-inverse);' : 'color:var(--text-regular);') + '">看板</a></span>';
    var h = '<div style="display:flex;gap:8px;margin-bottom:10px;align-items:center;flex-wrap:wrap;">'
      + '<button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projTaskModal()">+ 新建任务</button>'
      + '<button class="btn btn-sm" data-perm="project:manage" onclick="projMilestoneManage()">里程碑管理</button>'
      + viewToggle
      + asgSel + prioSel
      + '<label style="font-size:12px;color:var(--text-muted);cursor:pointer;"><input type="checkbox"' + (fOver ? ' checked' : '') + ' onchange="_projTaskFilter.overdue=this.checked;projLoadTasks();"> 仅超期</label>'
      + '<span style="font-size:12px;color:var(--text-muted);margin-left:6px;">待办 ' + cnt.TODO + ' · 进行中 ' + cnt.DOING + ' · 完成 ' + cnt.DONE + (overdueN ? ' · <span style="color:var(--error);font-weight:600;">超期 ' + overdueN + '</span>' : '') + '</span></div>';
    if (tasks.length) h += '<div style="display:flex;align-items:center;gap:8px;margin-bottom:10px;"><span style="font-size:var(--fs-xs);color:var(--text-muted);">完成率 ' + donePct + '%</span><div style="flex:1;height:6px;background:var(--bg-hover,var(--divider));border-radius:var(--radius-sm);overflow:hidden;"><div style="width:' + donePct + '%;height:100%;background:var(--success);"></div></div></div>';
    // 项目风险提示(创新): 基于全部任务+里程碑前端计算逾期/高优未完成/临近截止/滞后里程碑
    if (tasksAll.length) {
      var _d3 = (function() { var d = new Date(_today + 'T00:00:00'); d.setDate(d.getDate() + 3); var p = function(n) { return (n < 10 ? '0' : '') + n; }; return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()); })();
      var rOver = tasksAll.filter(function(t) { return t.dueDate && t.status !== 'DONE' && t.dueDate < _today; }).length;
      var rHigh = tasksAll.filter(function(t) { return t.priority === 'HIGH' && t.status !== 'DONE'; }).length;
      var rNear = tasksAll.filter(function(t) { return t.dueDate && t.status !== 'DONE' && t.dueDate >= _today && t.dueDate <= _d3; }).length;
      var rBehind = (_projMilestones || []).filter(function(m) {
        if (!(m.endDate && m.endDate < _today)) return false;
        var mt = tasksAll.filter(function(t) { return t.milestoneId === m.id; });
        return mt.length > 0 && mt.filter(function(t) { return t.status === 'DONE'; }).length < mt.length;
      }).length;
      var rLvl = (rOver >= 3 || rHigh >= 5 || rBehind >= 2) ? 2 : (rOver >= 1 || rHigh >= 2 || rBehind >= 1 || rNear >= 3) ? 1 : (rNear > 0 ? 0 : -1);
      if (rLvl >= 0) {
        var rc = ['var(--primary)', 'var(--warning-text)', 'var(--error-text)'][rLvl], rtx = ['提示', '警告', '严重'][rLvl];
        var rparts = [];
        if (rOver) rparts.push('逾期 ' + rOver);
        if (rHigh) rparts.push('高优先级未完成 ' + rHigh);
        if (rNear) rparts.push('3天内截止 ' + rNear);
        if (rBehind) rparts.push('滞后里程碑 ' + rBehind);
        h += '<div style="display:flex;align-items:center;flex-wrap:wrap;gap:10px;padding:8px 14px;margin-bottom:10px;border:1px solid var(--border);border-left:3px solid ' + rc + ';border-radius:var(--radius-lg);background:var(--bg-card);font-size:12px;">'
          + '<b style="color:' + rc + ';">项目风险 · ' + rtx + '</b>'
          + '<span style="color:var(--text-muted);">' + escapeHtml(rparts.join(' · ')) + '</span></div>';
      }
    }
    if (!tasks.length) h += '<div style="padding:20px;text-align:center;color:var(--text-muted);">' + (tasksAll.length ? '无匹配筛选条件的任务，<a href="#" onclick="_projTaskFilter={assignee:\'\',overdue:false,priority:\'\'};projLoadTasks();return false;" style="color:var(--primary);">清除筛选</a>' : '暂无任务') + '</div>';
    else if (_projTaskView === 'kanban') {
      h += projKanbanHtml(tasks, msName, _today);
    }
    else {
      ['TODO', 'DOING', 'DONE'].forEach(function(st) {
        var group = tasks.filter(function(t) { return t.status === st; });
        if (!group.length) return;
        group.sort(function(a, b) { return (a.dueDate || '9999-12-31').localeCompare(b.dueDate || '9999-12-31'); });
        h += '<div style="font-weight:600;font-size:13px;margin:10px 0 4px;color:' + TASK_STATUS[st].c + ';">' + TASK_STATUS[st].t + ' (' + group.length + ')</div>';
        h += '<table class="dbsync-exec-table" style="width:100%;"><tbody>';
        group.forEach(function(t) {
          var pr = TASK_PRIO[t.priority] || { t: t.priority, c: '' };
          h += '<tr><td style="width:50px;"><span style="color:' + pr.c + ';font-size:var(--fs-xs);">' + pr.t + '</span></td>'
            + '<td><b onclick="projTaskView(' + t.id + ')" style="cursor:pointer;" title="点击查看详情">' + escapeHtml(t.title) + '</b>' + projTaskRefBadge(t) + (t.description ? '<div style="font-size:var(--fs-xs);color:var(--text-muted);">' + escapeHtml(t.description) + '</div>' : '') + '</td>'
            + '<td style="width:80px;">' + escapeHtml(t.assignee || '-') + '</td>'
            + '<td style="width:110px;' + (t.dueDate && t.status !== 'DONE' && t.dueDate < _today ? 'color:var(--error);font-weight:600;' : '') + '">' + escapeHtml(t.dueDate || '') + (t.dueDate && t.status !== 'DONE' && t.dueDate < _today ? ' 超期' : '') + '</td>'
            + '<td style="width:90px;color:var(--text-muted);">' + escapeHtml(msName[t.milestoneId] || '') + '</td>'
            + '<td style="width:90px;"><select class="dbsync-form-select" style="width:84px;" onchange="projTaskStatus(' + t.id + ',this.value)">'
            + ['TODO', 'DOING', 'DONE'].map(function(s) { return '<option value="' + s + '"' + (s === t.status ? ' selected' : '') + '>' + TASK_STATUS[s].t + '</option>'; }).join('') + '</select></td>'
            + '<td style="width:80px;"><a href="#" data-perm="project:manage" onclick="projTaskModal(' + t.id + ');return false;" style="color:var(--primary);margin-right:6px;">编辑</a><a href="#" data-perm="project:manage" onclick="projDeleteTask(' + t.id + ');return false;" style="color:var(--error);">删除</a></td></tr>';
        });
        h += '</tbody></table>';
      });
    }
    pane.innerHTML = h;
    if (window.projSetTabBadge) projSetTabBadge('task', (cnt.TODO || 0) + (cnt.DOING || 0), 'var(--primary)');
  }).catch(function() { pane.innerHTML = '<div style="color:var(--error);">加载失败</div>'; });
};
// 任务↔平台实体联动徽标: 点击跳到关联模块(任务不再是孤岛)
var PROJ_REF_LABEL = { SYNC_JOB: '同步任务', SCRIPT: '脚本', QUALITY_RULE: '质量规则', METRIC: '指标', GOV_ISSUE: '治理工单' };
function projTaskRefBadge(t) {
  if (!t.refType || !t.refId) return '';
  var lbl = PROJ_REF_LABEL[t.refType] || t.refType;
  return ' <a href="#" onclick="event.stopPropagation();projAssetGoto(\'' + t.refType + '\',' + t.refId + ');return false;" title="跳到关联的' + lbl + '" style="font-size:var(--fs-xs);color:var(--primary);background:var(--bg-hover);padding:0 6px;border-radius:var(--radius-lg);white-space:nowrap;text-decoration:none;">↗ ' + lbl + '</a>';
}
// 看板视图：支持按 状态/指派人/优先级 分组泳道; 状态分组可拖拽改状态
var _projKbGroup = (function() { try { return localStorage.getItem('proj.kbGroup') || 'status'; } catch (e) { return 'status'; } })();
window.projSetKbGroup = function(g) { _projKbGroup = g; try { localStorage.setItem('proj.kbGroup', g); } catch (e) {} projLoadTasks(); };
function projKanbanHtml(tasks, msName, today) {
  var gb = _projKbGroup, dragOk = (gb === 'status');
  // 计算分组列
  var cols, colMeta;
  if (gb === 'priority') {
    cols = ['HIGH', 'MEDIUM', 'LOW']; colMeta = function(k) { var p = TASK_PRIO[k] || { t: k, c: 'var(--text-faint)' }; return { t: p.t + '优先级', c: p.c }; };
  } else if (gb === 'assignee') {
    var seen = {}; cols = [];
    tasks.forEach(function(t) { var a = t.assignee || '（未指派）'; if (!seen[a]) { seen[a] = 1; cols.push(a); } });
    if (!cols.length) cols = ['（未指派）'];
    colMeta = function(k) { return { t: k, c: 'var(--primary)' }; };
  } else {
    cols = ['TODO', 'DOING', 'DONE']; colMeta = function(k) { return { t: TASK_STATUS[k].t, c: TASK_STATUS[k].c }; };
  }
  var keyOf = function(t) { return gb === 'priority' ? (t.priority || 'MEDIUM') : gb === 'assignee' ? (t.assignee || '（未指派）') : (t.status || 'TODO'); };
  var grpSel = '<div style="display:flex;align-items:center;gap:6px;margin-bottom:10px;"><span style="font-size:12px;color:var(--text-muted);">分组依据</span>'
    + '<select class="dbsync-form-select" style="width:auto;height:28px;font-size:12px;" onchange="projSetKbGroup(this.value)">'
    + [['status', '状态'], ['assignee', '指派人'], ['priority', '优先级']].map(function(o) { return '<option value="' + o[0] + '"' + (gb === o[0] ? ' selected' : '') + '>' + o[1] + '</option>'; }).join('')
    + '</select><span style="font-size:var(--fs-xs);color:var(--text-muted);">' + (dragOk ? '可拖拽卡片改状态' : '该分组为只读视图') + '</span></div>';
  var h = grpSel + '<div class="proj-kanban" style="display:flex;gap:12px;align-items:flex-start;overflow-x:auto;padding-bottom:6px;">';
  cols.forEach(function(st) {
    var group = tasks.filter(function(t) { return keyOf(t) === st; });
    group.sort(function(a, b) { return (a.dueDate || '9999-12-31').localeCompare(b.dueDate || '9999-12-31'); });
    var sc = colMeta(st);
    h += '<div class="proj-kb-col"' + (dragOk ? ' ondragover="projKbDragOver(event)" ondragleave="projKbDragLeave(event)" ondrop="projKbDrop(event,\'' + st + '\')"' : '') + ' data-st="' + escapeHtml(st) + '" style="flex:1;min-width:240px;background:var(--bg-hover,var(--bg-main));border-radius:var(--radius-lg);padding:10px;transition:background var(--dur);">';
    h += '<div style="display:flex;align-items:center;gap:6px;font-weight:600;font-size:13px;margin-bottom:10px;color:' + sc.c + ';"><span style="width:8px;height:8px;border-radius:50%;background:' + sc.c + ';"></span>' + escapeHtml(sc.t) + ' <span style="color:var(--text-muted);font-weight:400;">' + group.length + '</span></div>';
    h += '<div class="proj-kb-cards" style="display:flex;flex-direction:column;gap:8px;min-height:40px;">';
    if (!group.length) h += '<div style="text-align:center;color:var(--text-muted);font-size:var(--fs-xs);padding:12px 0;border:1px dashed var(--border);border-radius:var(--radius-lg);">' + (dragOk ? '拖拽到此' : '无') + '</div>';
    group.forEach(function(t) {
      var pr = TASK_PRIO[t.priority] || { t: t.priority || '', c: 'var(--text-muted)' };
      var od = t.dueDate && t.status !== 'DONE' && t.dueDate < today;
      h += '<div class="proj-kb-card"' + (dragOk ? ' draggable="true" ondragstart="projKbDragStart(event,' + t.id + ')" ondragend="projKbDragEnd(event)"' : '') + ' style="background:var(--bg-card);border:1px solid var(--border);border-left:3px solid ' + pr.c + ';border-radius:var(--radius-lg);padding:9px 10px;cursor:' + (dragOk ? 'grab' : 'default') + ';box-shadow:var(--shadow-sm);">'
        + '<div onclick="projTaskView(' + t.id + ')" style="font-size:13px;font-weight:500;line-height:1.4;margin-bottom:5px;word-break:break-word;cursor:pointer;" title="点击查看详情">' + escapeHtml(t.title) + '</div>'
        + '<div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;font-size:var(--fs-xs);color:var(--text-muted);">'
        + (t.priority ? '<span style="color:' + pr.c + ';font-weight:600;">' + pr.t + '</span>' : '')
        + (t.assignee ? '<span style="background:var(--bg-hover);padding:0 6px;border-radius:var(--radius-lg);">@' + escapeHtml(t.assignee) + '</span>' : '')
        + (t.dueDate ? '<span style="' + (od ? 'color:var(--error);font-weight:600;' : '') + '">' + escapeHtml(t.dueDate) + (od ? ' 超期' : '') + '</span>' : '')
        + (msName[t.milestoneId] ? '<span><span style="display:inline-flex;vertical-align:-2px;">' + DN.icon('flag') + '</span>' + escapeHtml(msName[t.milestoneId]) + '</span>' : '')
        + projTaskRefBadge(t)
        + '</div>'
        + '<div style="margin-top:6px;text-align:right;"><a href="#" data-perm="project:manage" onclick="projTaskModal(' + t.id + ');return false;" style="font-size:var(--fs-xs);color:var(--primary);">编辑</a></div>'
        + '</div>';
    });
    h += '</div></div>';
  });
  h += '</div>';
  if (dragOk) h += '<div style="font-size:var(--fs-xs);color:var(--text-muted);margin-top:8px;">提示：拖拽卡片到其他列即可改变任务状态</div>';
  return h;
}
// 任务详情只读视图(大功能): 完整信息 + 一键状态流转
window.projTaskView = function(id) {
  var t = (_projTasksAll || []).find(function(x) { return x.id === id; });
  if (!t) { projTaskModal(id); return; }
  var today = wsLocalDate();
  var st = TASK_STATUS[t.status] || { t: t.status, c: 'var(--text-faint)' };
  var pr = TASK_PRIO[t.priority] || { t: t.priority || '-', c: 'var(--text-muted)' };
  var ms = (_projMilestones || []).find(function(m) { return m.id === t.milestoneId; });
  var od = t.dueDate && t.status !== 'DONE' && t.dueDate < today;
  var kv = function(k, v) { return '<div style="display:flex;padding:6px 0;border-bottom:1px solid var(--divider);font-size:13px;"><span style="width:80px;flex-shrink:0;color:var(--text-muted);">' + k + '</span><span style="flex:1;">' + v + '</span></div>'; };
  var flow = ['TODO', 'DOING', 'DONE'].map(function(s) {
    var on = t.status === s; var sm = TASK_STATUS[s];
    return '<button class="btn btn-sm" data-perm="project:manage" onclick="projTaskStatus(' + id + ',\'' + s + '\');projCloseModalBox&&projCloseModalBox();" style="' + (on ? 'background:' + sm.c + ';color:var(--text-inverse);border-color:' + sm.c + ';' : '') + '">' + sm.t + '</button>';
  }).join('');
  var h = '<div style="display:flex;align-items:center;gap:8px;margin-bottom:12px;">'
    + '<span style="background:' + st.c + ';color:var(--text-inverse);font-size:12px;padding:2px 10px;border-radius:var(--radius-lg);">' + escapeHtml(st.t) + '</span>'
    + '<span style="color:' + pr.c + ';font-size:12px;font-weight:600;">' + escapeHtml(pr.t) + '优先级</span>'
    + (od ? '<span style="color:var(--error);font-weight:600;font-size:12px;">已超期</span>' : '') + '</div>';
  h += kv('指派人', escapeHtml(t.assignee || '未指派'));
  h += kv('截止日', t.dueDate ? ('<span style="' + (od ? 'color:var(--error);font-weight:600;' : '') + '">' + escapeHtml(t.dueDate) + '</span>') : '—');
  h += kv('里程碑', ms ? escapeHtml(ms.name) : '—');
  h += kv('关联实体', (t.refType && t.refId) ? projTaskRefBadge(t) : '—');
  h += kv('创建', t.createdAt ? '<span title="' + escapeHtml((t.createdAt || '').replace('T', ' ').slice(0, 16)) + '">' + escapeHtml(wsFmtAgo(t.createdAt)) + '</span>' : '—');
  h += kv('更新', t.updatedAt ? '<span title="' + escapeHtml((t.updatedAt || '').replace('T', ' ').slice(0, 16)) + '">' + escapeHtml(wsFmtAgo(t.updatedAt)) + '</span>' : '—');
  h += '<div style="margin:12px 0 4px;font-size:12px;color:var(--text-muted);">描述</div>'
    + '<div style="font-size:13px;line-height:1.7;white-space:pre-wrap;border:1px solid var(--divider);border-radius:var(--radius);padding:10px;background:var(--bg-hover);min-height:50px;">' + (t.description ? escapeHtml(t.description) : '<span style="color:var(--text-muted);">（无描述）</span>') + '</div>';
  h += '<div style="margin-top:14px;display:flex;align-items:center;gap:8px;flex-wrap:wrap;"><span style="font-size:12px;color:var(--text-muted);">快捷流转</span>' + flow
    + '<button class="btn btn-sm btn-primary" style="margin-left:auto;" data-perm="project:manage" onclick="projCloseModalBox&&projCloseModalBox();projTaskModal(' + id + ')">编辑</button></div>';
  // IV-1: 评论时间线——任务下沟通留痕, 不再口头同步
  h += '<div style="margin-top:14px;border-top:1px solid var(--divider);padding-top:10px;">'
    + '<div style="font-size:12px;color:var(--text-muted);margin-bottom:6px;">评论</div>'
    + '<div id="ptcList" style="max-height:180px;overflow:auto;font-size:13px;"><span style="color:var(--text-muted);font-size:12px;">加载中...</span></div>'
    + '<div style="display:flex;gap:6px;margin-top:8px;">'
    + '<input id="ptcInput" class="dbsync-form-input" style="flex:1;" placeholder="写评论..." onkeydown="if(event.key===\'Enter\')projTaskCommentAdd(' + id + ')">'
    + '<button class="btn btn-sm btn-primary" onclick="projTaskCommentAdd(' + id + ')">发送</button></div></div>';
  projShowModalBox('任务 · ' + (t.title || ''), h);
  projTaskCommentsLoad(id);
};
window.projTaskCommentsLoad = function(taskId) {
  var box = document.getElementById('ptcList'); if (!box || !_projDetail) return;
  api('/api/project/' + _projDetail.id + '/tasks/' + taskId + '/comments').then(function(res) {
    var cs = (res && res.code === 0) ? (res.data || []) : [];
    if (!cs.length) { box.innerHTML = '<span style="color:var(--text-muted);font-size:12px;">暂无评论</span>'; return; }
    box.innerHTML = cs.map(function(c) {
      return '<div style="padding:4px 0;border-bottom:1px solid var(--border-light,var(--divider));">'
        + '<span style="font-weight:600;font-size:12px;">' + escapeHtml(c.author || '-') + '</span>'
        + ' <span style="color:var(--text-muted);font-size:var(--fs-xs);">' + escapeHtml((c.createdAt || '').replace('T', ' ').slice(0, 16)) + '</span>'
        + '<div style="white-space:pre-wrap;word-break:break-word;">' + escapeHtml(c.content || '') + '</div></div>';
    }).join('');
    box.scrollTop = box.scrollHeight;
  }).catch(function() { box.innerHTML = '<span style="color:var(--error);font-size:12px;">评论加载失败 <a href="#" onclick="projTaskCommentsLoad(' + taskId + ');return false;" style="color:var(--primary);">重试</a></span>'; });
};
window.projTaskCommentAdd = function(taskId) {
  var input = document.getElementById('ptcInput'); if (!input || !_projDetail) return;
  var content = (input.value || '').trim();
  if (!content) { showToast('评论内容不能为空', 'error'); return; }
  apiPost('/api/project/' + _projDetail.id + '/tasks/' + taskId + '/comments', { content: content }).then(function(res) {
    if (res && res.code === 0) { input.value = ''; projTaskCommentsLoad(taskId); }
    else showToast((res && res.msg) || '评论失败', 'error');
  }).catch(function() { showToast('评论失败', 'error'); });
};
// 里程碑详情(大功能): 甘特/列表点击里程碑→任务清单按状态分组+进度
window.projMilestoneDetail = function(msId) {
  var m = (_projMilestones || []).find(function(x) { return x.id === msId; });
  if (!m) { showToast('里程碑不存在', 'error'); return; }
  var mt = (_projTasksAll || []).filter(function(t) { return t.milestoneId === msId; });
  var today = wsLocalDate();
  var doneN = mt.filter(function(t) { return t.status === 'DONE'; }).length;
  var prog = mt.length ? Math.round(doneN * 100 / mt.length) : 0;
  var overdueN = mt.filter(function(t) { return t.dueDate && t.status !== 'DONE' && t.dueDate < today; }).length;
  var h = '<div style="font-size:12px;color:var(--text-muted);margin-bottom:8px;">'
    + '周期 ' + escapeHtml(m.startDate || '?') + ' ~ ' + escapeHtml(m.endDate || '?')
    + ' · 任务 ' + mt.length + ' · 完成 ' + doneN + (overdueN ? ' · <span style="color:var(--error);font-weight:600;">超期 ' + overdueN + '</span>' : '') + '</div>';
  if (mt.length) h += '<div style="display:flex;align-items:center;gap:8px;margin-bottom:12px;"><span style="font-size:var(--fs-xs);color:var(--text-muted);">完成率 ' + prog + '%</span><div style="flex:1;height:8px;background:var(--bg-hover,var(--divider));border-radius:var(--radius-sm);overflow:hidden;"><div style="width:' + prog + '%;height:100%;background:' + (prog >= 100 ? 'var(--success)' : 'var(--primary)') + ';"></div></div></div>';
  if (!mt.length) h += '<div style="padding:28px 16px;text-align:center;color:var(--text-muted);"><div style="font-size:26px;margin-bottom:6px;opacity:.7;"><span style="font-size:34px;color:var(--text-faint);display:inline-block;">' + DN.icon('inbox') + '</span></div><div style="font-size:13px;">该里程碑暂无关联任务</div><div style="font-size:12px;opacity:.7;margin-top:4px;">在任务中设置里程碑即可归集到此</div></div>';
  else {
    ['DOING', 'TODO', 'DONE'].forEach(function(st) {
      var g = mt.filter(function(t) { return t.status === st; });
      if (!g.length) return;
      g.sort(function(a, b) { return (a.dueDate || '9999').localeCompare(b.dueDate || '9999'); });
      h += '<div style="font-weight:600;font-size:12px;margin:10px 0 4px;color:' + TASK_STATUS[st].c + ';">' + TASK_STATUS[st].t + ' (' + g.length + ')</div>';
      g.forEach(function(t) {
        var od = t.dueDate && t.status !== 'DONE' && t.dueDate < today;
        var pr = TASK_PRIO[t.priority] || { t: '', c: 'var(--text-muted)' };
        h += '<div style="display:flex;align-items:center;gap:8px;padding:5px 0;border-bottom:1px solid var(--divider);font-size:12.5px;">'
          + '<span style="width:3px;height:14px;background:' + pr.c + ';border-radius:2px;flex-shrink:0;"></span>'
          + '<a href="#" onclick="projCloseModalBox&&projCloseModalBox();projTaskModal(' + t.id + ');return false;" style="flex:1;color:var(--text-regular);text-decoration:none;">' + escapeHtml(t.title) + '</a>'
          + (t.assignee ? '<span style="font-size:var(--fs-xs);color:var(--text-muted);">@' + escapeHtml(t.assignee) + '</span>' : '')
          + (t.dueDate ? '<span style="font-size:var(--fs-xs);' + (od ? 'color:var(--error);font-weight:600;' : 'color:var(--text-muted);') + '">' + escapeHtml(t.dueDate) + (od ? ' 超期' : '') + '</span>' : '')
          + '</div>';
      });
    });
  }
  projShowModalBox('里程碑 · ' + (m.name || ''), h);
};
var _projKbDragId = null;
window.projKbDragStart = function(ev, id) { _projKbDragId = id; ev.dataTransfer.effectAllowed = 'move'; try { ev.dataTransfer.setData('text/plain', String(id)); } catch (e) {} var c = ev.target.closest('.proj-kb-card'); if (c) c.style.opacity = '0.5'; };
window.projKbDragEnd = function(ev) { var c = ev.target.closest('.proj-kb-card'); if (c) c.style.opacity = ''; };
window.projKbDragOver = function(ev) { ev.preventDefault(); ev.dataTransfer.dropEffect = 'move'; var col = ev.currentTarget; if (col) col.style.background = 'var(--primary-bg)'; };
window.projKbDragLeave = function(ev) { var col = ev.currentTarget; if (col) col.style.background = ''; };
window.projKbDrop = function(ev, status) {
  ev.preventDefault();
  var col = ev.currentTarget; if (col) col.style.background = '';
  var id = _projKbDragId; _projKbDragId = null;
  if (id == null) return;
  var t = (_projTasksAll || []).find(function(x) { return x.id === id; });
  if (!t || (t.status || 'TODO') === status) return;
  projTaskStatus(id, status);
};
window.projTaskModal = function(taskId) {
  var t = taskId ? null : {};
  Promise.all([api('/api/project/' + _projDetail.id + '/tasks'), api('/api/project/' + _projDetail.id + '/assets'), api('/api/project/' + _projDetail.id + '/members').catch(function() { return null; })]).then(function(arr) {
    var res = arr[0];
    _projTaskRefAssets = _pdata(arr[1]);
    // II-5: 指派人候选=项目成员∪负责人(datalist 保手填兜底)
    var asgSet = {};
    _pdata(arr[2]).forEach(function(m) { if (m && m.username) asgSet[m.username] = 1; });
    if (_projDetail.owner) asgSet[_projDetail.owner] = 1;
    var asgOptions = Object.keys(asgSet).map(function(u) { return '<option value="' + escapeHtml(u) + '">'; }).join('');
    if (taskId) { t = _pdata(res).find(function(x) { return x.id === taskId; }) || {}; }
    var msOpts = '<option value="">（无）</option>' + _projMilestones.map(function(m) { return '<option value="' + m.id + '"' + (t.milestoneId === m.id ? ' selected' : '') + '>' + escapeHtml(m.name) + '</option>'; }).join('');
    // 关联实体: 候选 = 本项目已绑定资产(任务围着项目里的东西转); GOV_ISSUE 只能从治理侧"转任务"带入, 此处只读展示
    var refRow;
    if (t.refType === 'GOV_ISSUE') {
      refRow = '<div class="ds-form-row"><label>关联实体</label><span style="font-size:13px;line-height:30px;">治理工单 #' + t.refId + '（由工单转入）<input type="hidden" id="ptkRefType" value="GOV_ISSUE"><input type="hidden" id="ptkRefId" value="' + t.refId + '"></span></div>';
    } else {
      var refTypes = { '': '（无）', SYNC_JOB: '同步任务', SCRIPT: '脚本', QUALITY_RULE: '质量规则', METRIC: '指标' };
      refRow = '<div class="ds-form-row"><label>关联实体</label><select id="ptkRefType" class="g-modal-input" onchange="projTaskRefTypeChanged()">'
        + Object.keys(refTypes).map(function(k) { return '<option value="' + k + '"' + ((t.refType || '') === k ? ' selected' : '') + '>' + refTypes[k] + '</option>'; }).join('')
        + '</select></div>'
        + '<div class="ds-form-row" id="ptkRefRow" style="display:none;"><label>关联对象</label><select id="ptkRefId" class="g-modal-input"></select></div>';
    }
    var h = '<input type="hidden" id="ptkId" value="' + (t.id || '') + '">'
      + '<div class="ds-form-row"><label>标题</label><input id="ptkTitle" value="' + escapeHtml(t.title || '') + '"></div>'
      + '<div class="ds-form-row"><label>描述</label><input id="ptkDesc" value="' + escapeHtml(t.description || '') + '"></div>'
      + '<div class="ds-form-row"><label>指派给</label><input id="ptkAssignee" list="ptkAsgList" value="' + escapeHtml(t.assignee || '') + '" placeholder="项目成员(可手填其他人)"><datalist id="ptkAsgList">' + asgOptions + '</datalist></div>'
      + '<div class="ds-form-row"><label>优先级</label><select id="ptkPriority" class="g-modal-input">' + ['HIGH', 'MEDIUM', 'LOW'].map(function(p) { return '<option value="' + p + '"' + ((t.priority || 'MEDIUM') === p ? ' selected' : '') + '>' + TASK_PRIO[p].t + '</option>'; }).join('') + '</select></div>'
      + '<div class="ds-form-row"><label>状态</label><select id="ptkStatus" class="g-modal-input">' + ['TODO', 'DOING', 'DONE'].map(function(s) { return '<option value="' + s + '"' + ((t.status || 'TODO') === s ? ' selected' : '') + '>' + TASK_STATUS[s].t + '</option>'; }).join('') + '</select></div>'
      + '<div class="ds-form-row"><label>截止日期</label><input id="ptkDue" type="date" value="' + (t.dueDate || '') + '"></div>'
      + '<div class="ds-form-row"><label>里程碑</label><select id="ptkMilestone" class="g-modal-input">' + msOpts + '</select></div>'
      + refRow
      + '<div style="margin-top:10px;text-align:right;"><button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projSaveTask(this)">保存</button></div>';
    projShowModalBox(taskId ? '编辑任务' : '新建任务', h);
    if (t.refType && t.refType !== 'GOV_ISSUE') projTaskRefTypeChanged(t.refId);
  }).catch(function() { showToast('任务表单加载失败, 请重试', 'error'); });
};
var _projTaskRefAssets = [];
window.projTaskRefTypeChanged = function(selectedId) {
  var typeSel = document.getElementById('ptkRefType'), row = document.getElementById('ptkRefRow'), idSel = document.getElementById('ptkRefId');
  if (!typeSel || !row || !idSel) return;
  var type = typeSel.value;
  if (!type) { row.style.display = 'none'; idSel.innerHTML = ''; return; }
  row.style.display = '';
  var cands = (_projTaskRefAssets || []).filter(function(a) { return a.assetType === type; });
  idSel.innerHTML = cands.length
    ? cands.map(function(a) { return '<option value="' + a.assetId + '"' + (selectedId === a.assetId ? ' selected' : '') + '>' + escapeHtml(a.assetName || ('#' + a.assetId)) + '</option>'; }).join('')
    : '<option value="">（项目暂未绑定该类型资产, 请先到资产页绑定）</option>';
  // B4: 原关联对象已解绑时插"已失效"占位并默认选中, 用户显式改选才换值——不再静默指向第一个候选
  if (selectedId != null && !cands.some(function(a) { return a.assetId === selectedId; })) {
    idSel.insertAdjacentHTML('afterbegin', '<option value="' + selectedId + '" selected>原关联 #' + selectedId + '（已失效, 建议改选或清空）</option>');
    idSel.value = String(selectedId);
  }
};
window.projSaveTask = function(btn) {
  var title = (document.getElementById('ptkTitle').value || '').trim();
  if (!title) { showToast('请填写标题', 'error'); return; }
  var ms = document.getElementById('ptkMilestone').value;
  var body = { title: title, description: document.getElementById('ptkDesc').value || '', assignee: document.getElementById('ptkAssignee').value || '', priority: document.getElementById('ptkPriority').value, status: document.getElementById('ptkStatus').value, dueDate: document.getElementById('ptkDue').value || null, milestoneId: ms ? parseInt(ms) : null };
  var refTypeEl = document.getElementById('ptkRefType'), refIdEl = document.getElementById('ptkRefId');
  body.refType = (refTypeEl && refTypeEl.value) || null;
  body.refId = (body.refType && refIdEl && refIdEl.value) ? parseInt(refIdEl.value) : null;
  if (!body.refId) body.refType = null;
  var id = document.getElementById('ptkId').value;
  if (id) body.id = parseInt(id);
  // loading 态防双提交(校验通过后才禁用;失败路径恢复,成功路径弹窗关闭无需恢复)
  var _txt; if (btn) { _txt = btn.textContent; btn.disabled = true; btn.textContent = '保存中...'; }
  var _restore = function() { if (btn) { btn.disabled = false; btn.textContent = _txt; } };
  apiPost('/api/project/' + _projDetail.id + '/tasks', body).then(function(res) {
    if (res && res.code === 0) {
      // N5: 任务完成触发工单回写, 复检不过后端带回 warning
      var warn = res.data && res.data.warning;
      if (warn) showToast(warn, 'warning'); else showToast('已保存', 'success');
      projCloseModalBox(); projLoadTasks();
    }
    else { showToast((res && res.msg) || '保存失败', 'error'); _restore(); }
  }).catch(function() { showToast('保存失败', 'error'); _restore(); });
};
window.projTaskStatus = function(taskId, status) {
  // 从本地缓存取整条任务, 避免二次全量 GET; 失败回退到拉取
  var t = (_projTasksAll || []).find(function(x) { return x.id === taskId; });
  if (!t) {
    api('/api/project/' + _projDetail.id + '/tasks').then(function(res) {
      var tt = _pdata(res).find(function(x) { return x.id === taskId; }); if (!tt) return;
      tt.status = status;
      apiPost('/api/project/' + _projDetail.id + '/tasks', tt).then(function() { projLoadTasks(); });
    });
    return;
  }
  var body = {}; for (var k in t) { if (t.hasOwnProperty(k)) body[k] = t[k]; }
  body.status = status;
  apiPost('/api/project/' + _projDetail.id + '/tasks', body).then(function(res) {
    if (res && res.code === 0) { t.status = status; projLoadTasks(); }
    else { showToast((res && res.msg) || '更新失败', 'error'); projLoadTasks(); }
  }).catch(function() { showToast('更新失败', 'error'); projLoadTasks(); });
};
window.projDeleteTask = function(taskId) {
  msgConfirm('删除任务', '确认删除该任务？', '确定删除').then(function(ok) {
    if (!ok) return;
    fetch('/api/project/' + _projDetail.id + '/tasks/' + taskId, { method: 'DELETE' }).then(_handleResponse).then(function() { showToast('已删除', 'success'); projLoadTasks(); }).catch(function() { showToast('删除失败', 'error'); });
  });
};
window.projMilestoneManage = function() {
  var h = '<div style="display:flex;gap:6px;margin-bottom:10px;flex-wrap:wrap;"><input id="pmsName" class="dbsync-form-input" style="width:120px;" placeholder="名称"><input id="pmsStart" type="date" class="dbsync-form-input" style="width:130px;"><input id="pmsEnd" type="date" class="dbsync-form-input" style="width:130px;"><button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projSaveMilestone(this)">添加</button></div><div id="pmsList"></div>';
  projShowModalBox('里程碑管理', h);
  projRenderMilestones();
};
// 里程碑风险分类(创新): 对比计划周期与任务实际完成情况, 给出 已完成/已延期/延期风险/进展良好/进行中, 复用已加载 _projTasksAll
function _projMsRisk(m, today) {
  var mt = (_projTasksAll || []).filter(function(t) { return t.milestoneId === m.id; });
  var total = mt.length, done = mt.filter(function(t) { return t.status === 'DONE'; }).length;
  var pct = total ? Math.round(done * 100 / total) : 0;
  var label = '进行中', tone = 'var(--primary)';
  if (!total) { label = '无任务'; tone = 'var(--text-faint)'; }
  else if (done === total) { label = '已完成'; tone = 'var(--success)'; }
  else if (m.endDate && m.endDate < today) { label = '已延期'; tone = 'var(--error-text)'; }
  else if (m.endDate) {
    var d = Math.ceil((new Date(m.endDate + 'T00:00:00') - new Date(today + 'T00:00:00')) / 86400000);
    if (d <= 7 && pct < 60) { label = '延期风险'; tone = 'var(--warning-text)'; }
    else if (pct >= 80) { label = '进展良好'; tone = 'var(--success-text)'; }
  }
  return { total: total, done: done, pct: pct, label: label, tone: tone };
}
function projRenderMilestones() {
  var box = document.getElementById('pmsList'); if (!box) return;
  if (!_projMilestones.length) { box.innerHTML = '<div style="padding:36px 24px;text-align:center;color:var(--text-muted);"><div style="font-size:30px;margin-bottom:8px;opacity:.7;"><span style="font-size:34px;color:var(--text-faint);display:inline-block;">' + DN.icon('flag') + '</span></div><div style="font-size:13px;">暂无里程碑</div><div style="font-size:12px;opacity:.7;margin-top:4px;">创建里程碑可在甘特图中追踪阶段进度</div></div>'; return; }
  var today = wsLocalDate();
  var risks = _projMilestones.map(function(m) { return _projMsRisk(m, today); });
  var late = risks.filter(function(r) { return r.label === '已延期'; }).length;
  var atRisk = risks.filter(function(r) { return r.label === '延期风险'; }).length;
  var summary = (late || atRisk)
    ? '<div style="padding:6px 10px;margin-bottom:8px;border-radius:var(--radius);background:var(--warning-bg);border:1px solid var(--warning-ring);font-size:12px;color:var(--warning-text);">里程碑风险：' + (late ? '已延期 ' + late + ' 个' : '') + (late && atRisk ? '，' : '') + (atRisk ? '延期风险 ' + atRisk + ' 个' : '') + '，建议优先协调</div>'
    : '<div style="font-size:12px;color:var(--success-text);margin-bottom:8px;">✓ 各里程碑进度正常</div>';
  box.innerHTML = summary + _projMilestones.map(function(m, i) {
    var r = risks[i];
    var badge = '<span style="display:inline-block;font-size:var(--fs-xs);padding:1px 8px;border-radius:var(--radius-lg);background:' + r.tone + '1a;color:' + r.tone + ';font-weight:600;">' + r.label + '</span>';
    var prog = r.total ? '<span style="color:var(--text-muted);font-size:var(--fs-xs);">' + r.done + '/' + r.total + ' 任务 · ' + r.pct + '%</span>' : '';
    return '<div style="display:flex;align-items:center;gap:8px;padding:5px 0;font-size:13px;border-bottom:1px solid var(--border-light,var(--divider));"><span style="flex:1;"><b>' + escapeHtml(m.name) + '</b> <span style="color:var(--text-muted);font-size:var(--fs-xs);">' + escapeHtml((m.startDate || '') + (m.endDate ? ' ~ ' + m.endDate : '')) + '</span></span>' + badge + ' ' + prog + ' <a href="#" data-perm="project:manage" onclick="projDelMilestone(' + m.id + ');return false;" style="color:var(--error);">删除</a></div>';
  }).join('');
}
window.projSaveMilestone = function(btn) {
  var name = (document.getElementById('pmsName').value || '').trim();
  if (!name) { showToast('请填写名称', 'error'); return; }
  var _t; if (btn) { _t = btn.textContent; btn.disabled = true; btn.textContent = '保存中...'; }
  var _r = function() { if (btn) { btn.disabled = false; btn.textContent = _t; } };
  apiPost('/api/project/' + _projDetail.id + '/milestones', { name: name, startDate: document.getElementById('pmsStart').value || null, endDate: document.getElementById('pmsEnd').value || null }).then(function(res) {
    if (res && res.code === 0) { _projMilestones.push(res.data); document.getElementById('pmsName').value = ''; projRenderMilestones(); }
    else showToast((res && res.msg) || '添加失败', 'error');
    _r();
  }).catch(function() { showToast('添加失败', 'error'); _r(); });
};
window.projDelMilestone = function(mid) {
  msgConfirm('删除里程碑', '删除里程碑？(其下任务将解除关联)', '确定删除').then(function(ok) {
    if (!ok) return;
  fetch('/api/project/' + _projDetail.id + '/milestones/' + mid, { method: 'DELETE' }).then(_handleResponse).then(function() {
    _projMilestones = _projMilestones.filter(function(m) { return m.id !== mid; }); projRenderMilestones();
  }).catch(function() { showToast('删除失败', 'error'); });
  });
};

/* ===== PM2-M5：文档 Wiki ===== */
var _projWikiPages = [], _projWikiCur = null;
function projSanitizeHtml(html) {
  var tpl = document.createElement('template');
  tpl.innerHTML = html || '';
  var bad = ['script', 'iframe', 'object', 'embed', 'link', 'style', 'form', 'meta', 'base', 'svg'];
  Array.prototype.slice.call(tpl.content.querySelectorAll('*')).forEach(function(el) {
    if (bad.indexOf(el.tagName.toLowerCase()) >= 0) { el.parentNode && el.parentNode.removeChild(el); return; }
    Array.prototype.slice.call(el.attributes).forEach(function(a) {
      var n = a.name.toLowerCase(), v = (a.value || '').replace(/\s+/g, '').toLowerCase();
      if (n.indexOf('on') === 0 || n === 'srcdoc') el.removeAttribute(a.name);
      else if ((n === 'href' || n === 'src' || n === 'xlink:href') && v.indexOf('javascript:') === 0) el.removeAttribute(a.name);
    });
  });
  return tpl.innerHTML;
}
// 轻量 Markdown 渲染：先整体转义(杜绝原始 HTML/XSS)，再对转义后文本套用 markdown 规则。无外部依赖。
function projMdInline(s) {
  s = s.replace(/`([^`]+)`/g, '<code>$1</code>');
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  s = s.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>');
  s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function(m, txt, url) {
    var u = url.replace(/\s+/g, '').toLowerCase();
    if (u.indexOf('javascript:') === 0 || u.indexOf('data:') === 0 || u.indexOf('vbscript:') === 0) return txt;
    return '<a href="' + url + '" target="_blank" rel="noopener noreferrer">' + txt + '</a>';
  });
  return s;
}
function projMdLite(src) {
  var esc = escapeHtml(src || '');
  // 代码块 ``` 优先抽出，避免内部内容被行级规则误处理
  var blocks = [];
  esc = esc.replace(/```([\s\S]*?)```/g, function(m, code) {
    blocks.push('<pre class="proj-code" style="background:var(--bg-hover);padding:10px 12px;border-radius:var(--radius);overflow:auto;">' + code.replace(/^\n/, '') + '</pre>');
    return ' B' + (blocks.length - 1) + ' ';
  });
  var lines = esc.split(/\r?\n/), html = [], inUl = false, inOl = false;
  function closeLists() { if (inUl) { html.push('</ul>'); inUl = false; } if (inOl) { html.push('</ol>'); inOl = false; } }
  for (var i = 0; i < lines.length; i++) {
    var ln = lines[i];
    var bm = ln.match(/^ B(\d+) $/);
    if (bm) { closeLists(); html.push(blocks[+bm[1]]); continue; }
    if (/^\s*$/.test(ln)) { closeLists(); continue; }
    var h = ln.match(/^(#{1,6})\s+(.*)$/);
    if (h) { closeLists(); var lv = h[1].length; html.push('<h' + lv + ' style="margin:.6em 0 .3em;">' + projMdInline(h[2]) + '</h' + lv + '>'); continue; }
    if (/^\s*([-*])\s+/.test(ln)) { if (!inUl) { closeLists(); html.push('<ul style="margin:.3em 0;padding-left:20px;">'); inUl = true; } html.push('<li>' + projMdInline(ln.replace(/^\s*[-*]\s+/, '')) + '</li>'); continue; }
    if (/^\s*\d+\.\s+/.test(ln)) { if (!inOl) { closeLists(); html.push('<ol style="margin:.3em 0;padding-left:20px;">'); inOl = true; } html.push('<li>' + projMdInline(ln.replace(/^\s*\d+\.\s+/, '')) + '</li>'); continue; }
    if (/^\s*&gt;\s?/.test(ln)) { closeLists(); html.push('<blockquote style="margin:.4em 0;padding:2px 10px;border-left:3px solid var(--border);color:var(--text-muted);">' + projMdInline(ln.replace(/^\s*&gt;\s?/, '')) + '</blockquote>'); continue; }
    closeLists();
    html.push('<p style="margin:.4em 0;">' + projMdInline(ln) + '</p>');
  }
  closeLists();
  return html.join('');
}
function projMd(c) { try { if (window.marked) { return projSanitizeHtml(marked.parse ? marked.parse(c || '') : marked(c || '')); } } catch (e) {} return projMdLite(c); }
window.projLoadWiki = function() {
  var pane = document.getElementById('projPane_wiki'); if (!pane || !_projDetail) return;
  pane.innerHTML = wsSkeleton(4);
  api('/api/project/' + _projDetail.id + '/wiki/pages').then(function(res) {
    _projWikiPages = _pdata(res);
    pane.innerHTML = '<div style="display:flex;gap:12px;min-height:320px;">'
      + '<div style="width:190px;border-right:1px solid var(--divider);padding-right:10px;"><button class="btn btn-sm btn-primary" style="width:100%;margin-bottom:8px;" data-perm="project:manage" onclick="projWikiNew()">+ 新建页面</button><input id="projWikiSearch" class="dbsync-form-input" style="width:100%;margin-bottom:8px;" placeholder="搜索文档" oninput="projWikiRenderTree()"><div id="projWikiTree"></div></div>'
      + '<div style="flex:1;" id="projWikiContent"><div style="color:var(--text-muted);padding:20px;">选择左侧页面查看，或新建页面</div></div></div>';
    projWikiRenderTree();
    if (_projWikiPages.length) projWikiSelect((_projWikiCur && _projWikiPages.some(function(p) { return p.id === _projWikiCur; })) ? _projWikiCur : _projWikiPages[0].id);
  }).catch(function() { pane.innerHTML = '<div style="color:var(--error);">加载失败</div>'; });
};
function projWikiRenderTree() {
  var box = document.getElementById('projWikiTree'); if (!box) return;
  if (!_projWikiPages.length) { box.innerHTML = '<div style="padding:32px 16px;text-align:center;color:var(--text-muted);"><div style="font-size:28px;margin-bottom:8px;opacity:.7;"><span style="font-size:34px;color:var(--text-faint);display:inline-block;">' + DN.icon('doc') + '</span></div><div style="font-size:13px;">暂无文档</div><div style="font-size:12px;opacity:.7;margin-top:4px;">新建文档沉淀项目知识</div></div>'; return; }
  function item(p, indent) { return '<div style="padding:4px 0 4px ' + (4 + indent * 14) + 'px;cursor:pointer;font-size:13px;' + (_projWikiCur === p.id ? 'color:var(--primary);font-weight:600;' : '') + '" onclick="projWikiSelect(' + p.id + ')">' + (indent ? '• ' : '') + escapeHtml(p.title) + '</div>'; }
  // 搜索: 命中标题则平铺展示
  var kw = ((document.getElementById('projWikiSearch') || {}).value || '').trim().toLowerCase();
  if (kw) {
    var hits = _projWikiPages.filter(function(p) { return (p.title || '').toLowerCase().indexOf(kw) >= 0; });
    box.innerHTML = hits.length ? hits.map(function(p) { return item(p, 0); }).join('') : '<div style="color:var(--text-muted);font-size:12px;">无匹配文档</div>';
    return;
  }
  var byParent = {};
  _projWikiPages.forEach(function(p) { var k = (!p.parentId || p.parentId === 0) ? 0 : p.parentId; (byParent[k] = byParent[k] || []).push(p); });
  var seen = {}, h = '';
  (function walk(parentId, depth) {
    if (depth > 8) return;
    (byParent[parentId] || []).forEach(function(p) {
      if (seen[p.id]) return; seen[p.id] = true;
      h += item(p, depth); walk(p.id, depth + 1);
    });
  })(0, 0);
  // 父级失联(指向已删页)的孤儿页作为根展示,避免在树中不可见
  _projWikiPages.forEach(function(p) { if (!seen[p.id]) { seen[p.id] = true; h += item(p, 0); } });
  box.innerHTML = h;
}
window.projWikiSelect = function(pageId) {
  _projWikiCur = pageId; projWikiRenderTree();
  api('/api/project/' + _projDetail.id + '/wiki/pages/' + pageId).then(function(res) {
    var p = (res && res.code === 0) ? res.data : null; var c = document.getElementById('projWikiContent'); if (!p || !c) return;
    c.innerHTML = '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;"><b style="font-size:15px;">' + escapeHtml(p.title) + '</b><span><button class="btn btn-sm" data-perm="project:manage" onclick="projWikiEdit(' + p.id + ')">编辑</button> <button class="btn btn-sm" style="color:var(--error);border-color:var(--error);" data-perm="project:manage" onclick="projWikiDelete(' + p.id + ')">删除</button></span></div>'
      + '<div id="projWikiToc"></div>'
      + '<div class="proj-md" id="projWikiBody" style="font-size:13px;line-height:1.7;">' + projMd(p.content) + '</div>'
      + '<div style="font-size:var(--fs-xs);color:var(--text-muted);margin-top:10px;">更新人 ' + escapeHtml(p.updatedBy || '-') + ' · ' + escapeHtml((p.updatedAt || '').replace('T', ' ').slice(0, 16)) + '</div>';
    projWikiBuildToc();
  });
};
// Wiki 目录(TOC)导航 + 字数统计(大功能): 渲染后从正文标题提取
function projWikiBuildToc() {
  var body = document.getElementById('projWikiBody'), tocBox = document.getElementById('projWikiToc');
  if (!body || !tocBox) return;
  var hs = body.querySelectorAll('h1,h2,h3,h4');
  var words = (body.textContent || '').replace(/\s/g, '').length;
  var wordsStr = words.toLocaleString();
  if (hs.length < 2) { tocBox.innerHTML = '<div style="font-size:var(--fs-xs);color:var(--text-muted);margin-bottom:8px;">全文约 ' + wordsStr + ' 字</div>'; return; }
  var items = '';
  Array.prototype.slice.call(hs).forEach(function(h, i) {
    var id = 'wikih_' + i; h.id = id;
    var lv = parseInt(h.tagName.slice(1), 10) || 1;
    items += '<a href="#" onclick="(function(){var e=document.getElementById(\'' + id + '\');if(e)e.scrollIntoView({behavior:\'smooth\',block:\'start\'});})();return false;" style="display:block;padding:2px 0 2px ' + ((lv - 1) * 14) + 'px;font-size:12px;color:var(--text-regular);text-decoration:none;border-left:2px solid transparent;" onmouseover="this.style.color=\'var(--primary)\'" onmouseout="this.style.color=\'var(--text-regular)\'">' + escapeHtml(h.textContent || '') + '</a>';
  });
  tocBox.innerHTML = '<div style="border:1px solid var(--divider);border-radius:var(--radius-lg);padding:10px 12px;margin-bottom:12px;background:var(--bg-hover,var(--bg-main));">'
    + '<div style="display:flex;align-items:center;margin-bottom:6px;"><b style="font-size:12px;">目录</b><span style="margin-left:auto;font-size:var(--fs-xs);color:var(--text-muted);">' + hs.length + ' 节 · 约 ' + wordsStr + ' 字</span></div>'
    + items + '</div>';
}
window.projWikiNew = function() { projWikiEditForm({ title: '', content: '', parentId: 0 }); };
window.projWikiEdit = function(pageId) { api('/api/project/' + _projDetail.id + '/wiki/pages/' + pageId).then(function(res) { if (res && res.code === 0) projWikiEditForm(res.data); }); };
function projWikiEditForm(p) {
  var c = document.getElementById('projWikiContent'); if (!c) return;
  var parentOpts = '<option value="0">（根）</option>' + _projWikiPages.filter(function(x) { return x.id !== p.id; }).map(function(x) { return '<option value="' + x.id + '"' + (p.parentId === x.id ? ' selected' : '') + '>' + escapeHtml(x.title) + '</option>'; }).join('');
  c.innerHTML = '<input type="hidden" id="pwId" value="' + (p.id || '') + '">'
    + '<div style="display:flex;gap:8px;margin-bottom:8px;"><input id="pwTitle" class="dbsync-form-input" style="flex:1;" placeholder="标题" value="' + escapeHtml(p.title || '') + '"><select id="pwParent" class="dbsync-form-select" style="width:140px;">' + parentOpts + '</select></div>'
    + '<textarea id="pwContent" style="width:100%;min-height:240px;padding:10px;border:1px solid var(--border);border-radius:var(--radius);font-family:monospace;font-size:13px;box-sizing:border-box;" placeholder="Markdown 内容">' + escapeHtml(p.content || '') + '</textarea>'
    + '<div style="margin-top:8px;"><button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projWikiSave(this)">保存</button> <button class="btn btn-sm" onclick="projLoadWiki()">取消</button></div>';
}
window.projWikiSave = function(btn) {
  var title = (document.getElementById('pwTitle').value || '').trim();
  if (!title) { showToast('请填写标题', 'error'); return; }
  var id = document.getElementById('pwId').value;
  var body = { title: title, content: document.getElementById('pwContent').value || '', parentId: parseInt(document.getElementById('pwParent').value) || 0 };
  if (id) body.id = parseInt(id);
  var _t, _r = function() { if (btn) { btn.disabled = false; btn.textContent = _t; } };
  if (btn) { _t = btn.textContent; btn.disabled = true; btn.textContent = '保存中...'; }
  apiPost('/api/project/' + _projDetail.id + '/wiki/pages', body).then(function(res) {
    if (res && res.code === 0) { showToast('已保存', 'success'); _projWikiCur = res.data.id; projLoadWiki(); }
    else showToast((res && res.msg) || '保存失败', 'error');
    _r();
  }).catch(function() { showToast('保存失败', 'error'); _r(); });
};
window.projWikiDelete = function(pageId) {
  msgConfirm('删除文档', '删除该文档页面？(子页面将提升为根)', '确定删除').then(function(ok) {
    if (!ok) return;
    fetch('/api/project/' + _projDetail.id + '/wiki/pages/' + pageId, { method: 'DELETE' }).then(_handleResponse).then(function() { _projWikiCur = null; projLoadWiki(); }).catch(function() { showToast('删除失败', 'error'); });
  });
};

/* ===== PM2-M7/M8：工作台 + 模板 + 对比 ===== */
window.projOpenDetailById = function(id, tab) {
  // N2: 可选 tab 参数('task'/'release'/'asset'...), 打开后直达对应页签
  var after = function() {
    if (!tab) return;
    setTimeout(function() {
      var btn = document.querySelector('#projDetailTabs button[data-ptab="' + tab + '"]');
      if (btn) projDetailTab(btn, tab);
    }, 700);
  };
  var p = (_projList || []).find(function(x) { return x.id === id; });
  if (p) { projOpenDetail(id); after(); return; }
  api('/api/project/' + id).then(function(res) {
    if (res && res.code === 0) { _projList = _projList || []; _projList.push(res.data); projOpenDetail(id); after(); }
    else showToast((res && res.msg) || '项目不存在或已删除', 'error');
  }).catch(function() { showToast('项目加载失败, 请重试', 'error'); });
};
window.loadProjectHome = function() {
  var box = document.getElementById('projHomeBox'); if (!box) return;
  box.innerHTML = wsSkeleton(5);
  // II-2 四合一重构: 行动磁贴可点锚滚 / 我提交的发布(驳回意见) / 指给我的工单(服务端过滤) / 我的项目合并
  api('/api/project/home').then(function(res) {
    if (!res || res.code !== 0) { box.innerHTML = '<div style="color:var(--error);">加载失败 <a href="#" onclick="loadProjectHome();return false;" style="color:var(--primary);">重试</a></div>'; return; }
    var d = res.data || {};
    function card(id, title, inner) { return '<div class="dbsync-detail-card" id="' + id + '" style="border:1px solid var(--divider);border-radius:var(--radius-lg);padding:12px;"><div style="font-weight:600;margin-bottom:8px;font-size:13px;">' + title + '</div>' + inner + '</div>'; }
    function empty(t) { return '<div style="color:var(--text-muted);font-size:12px;">' + t + '</div>'; }
    var _homeToday = wsLocalDate();
    var mt = d.myTasks || [];
    var overdueN = mt.filter(function(t) { return t.dueDate && t.status !== 'DONE' && t.dueDate < _homeToday; }).length;
    var myIssues = d.myIssues || [];
    var subs = d.mySubmissions || [];
    var rejected = subs.filter(function(s) { return s.status === 'REJECTED'; }).length;
    // 我的项目 = 我负责 ∪ 收藏(去重, 收藏加星)
    var favSet = {}; (d.favorites || []).forEach(function(p) { favSet[p.id] = true; });
    var mineMap = {}; (d.myProjects || []).forEach(function(p) { mineMap[p.id] = p; });
    (d.favorites || []).forEach(function(p) { mineMap[p.id] = mineMap[p.id] || p; });
    var mineList = Object.keys(mineMap).map(function(k) { return mineMap[k]; });
    var mine = mineList.map(function(p) { return '<div style="padding:3px 0;cursor:pointer;color:var(--primary);" onclick="projOpenDetailById(' + p.id + ')">' + (favSet[p.id] ? '★ ' : '') + escapeHtml(p.projectName) + ' <span style="color:var(--text-muted);font-size:var(--fs-xs);">' + escapeHtml(p.projectCode || '') + '</span></div>'; }).join('') || empty('暂无项目, 到「项目空间」创建或收藏');
    var recent = (d.recent || []).map(function(p) { return '<div style="padding:3px 0;cursor:pointer;" onclick="projOpenDetailById(' + p.id + ')">' + escapeHtml(p.projectName) + ' <span style="color:var(--text-muted);font-size:var(--fs-xs);">' + escapeHtml((p.accessAt || '').replace('T', ' ').slice(0, 16)) + '</span></div>'; }).join('') || empty('暂无访问记录');
    var appr = (d.pendingApprovals || []).map(function(a) { return '<div style="padding:3px 0;">' + escapeHtml(a.projectName) + ' v' + escapeHtml(String(a.versionNo == null ? '' : a.versionNo)) + ' <span style="color:var(--text-muted);font-size:var(--fs-xs);">' + escapeHtml(a.title || '') + '</span> <a href="#" onclick="projOpenDetailById(' + parseInt(a.projectId, 10) + ',&#39;release&#39;);return false;" style="color:var(--primary);">去处理</a></div>'; }).join('') || empty('暂无待审批');
    var subsHtml = subs.map(function(s) {
      var rej = s.status === 'REJECTED';
      return '<div style="padding:3px 0;cursor:pointer;" onclick="projOpenDetailById(' + s.projectId + ',&#39;release&#39;)">'
        + '<span style="font-size:var(--fs-xs);font-weight:600;color:' + (rej ? 'var(--error)' : 'var(--warning-text)') + ';">[' + (rej ? '被驳回' : '待审批') + ']</span> '
        + escapeHtml(s.projectName) + ' v' + s.versionNo + ' ' + escapeHtml(s.title || '')
        + (rej && s.approveComment ? '<div style="font-size:var(--fs-xs);color:var(--error);padding-left:8px;">驳回意见: ' + escapeHtml(s.approveComment) + '</div>' : '')
        + '</div>';
    }).join('') || empty('暂无进行中的发布');
    var issuesHtml = myIssues.map(function(i) {
      var sevC = i.severity === 'HIGH' ? 'var(--error)' : (i.severity === 'MEDIUM' ? 'var(--warning-text)' : 'var(--text-muted)');
      return '<div style="padding:3px 0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;cursor:pointer;" onclick="projAssetGoto(&#39;GOV_ISSUE&#39;,' + i.id + ')" title="' + escapeHtml(i.title || '') + '">'
        + '<span style="color:' + sevC + ';font-size:var(--fs-xs);font-weight:600;">[' + escapeHtml(i.severity || '-') + ']</span> '
        + escapeHtml(i.title || '') + ' <span style="color:var(--text-muted);font-size:var(--fs-xs);">' + escapeHtml(i.status || '') + '</span></div>';
    }).join('') || empty('暂无指给我的工单');
    var tasks = mt.map(function(t) {
      var od = t.dueDate && t.status !== 'DONE' && t.dueDate < _homeToday;
      return '<div style="padding:3px 0;">' + (t.priority === 'HIGH' ? '<span style="color:var(--error);">[高] </span>' : '') + escapeHtml(t.title) + ' <span style="color:var(--text-muted);font-size:var(--fs-xs);">@' + escapeHtml(t.projectName) + (t.dueDate ? ' · ' + t.dueDate : '') + '</span>' + (od ? ' <span style="color:var(--error);font-weight:600;font-size:var(--fs-xs);">超期</span>' : '') + '</div>';
    }).join('') || empty('暂无待办任务');
    // 4 个行动磁贴: 数字只出现这一处, 点击锚滚对应卡
    var kpiCard = function(v, l, c, anchor) { return '<div onclick="var el=document.getElementById(&#39;' + anchor + '&#39;);if(el)el.scrollIntoView({behavior:&#39;smooth&#39;,block:&#39;center&#39;});" title="点击定位" style="flex:1;min-width:120px;background:var(--bg-card);border:1px solid var(--border);border-radius:var(--radius-lg);box-shadow:var(--shadow-sm);padding:14px 18px;cursor:pointer;"><div class="num" style="font-size:24px;font-weight:700;line-height:1.2;color:' + (c || 'var(--text-primary)') + ';">' + v + '</div><div style="font-size:12px;color:var(--text-muted);margin-top:3px;">' + l + '</div></div>'; };
    var kpi = '<div style="display:flex;gap:14px;flex-wrap:wrap;margin-bottom:16px;">'
      + kpiCard(mt.length + (overdueN ? ' <span style="font-size:13px;color:var(--error);">(' + overdueN + '超期)</span>' : ''), '我的待办', overdueN ? 'var(--error)' : null, 'phCardTasks')
      + kpiCard((d.pendingApprovals || []).length, '待我审批', (d.pendingApprovals || []).length ? 'var(--warning-text)' : null, 'phCardAppr')
      + kpiCard(subs.length + (rejected ? ' <span style="font-size:13px;color:var(--error);">(' + rejected + '驳回)</span>' : ''), '我提交的发布', rejected ? 'var(--error)' : null, 'phCardSubs')
      + kpiCard(myIssues.length, '指给我的工单', myIssues.length ? 'var(--warning-text)' : null, 'phCardIssues')
      + '</div>';
    box.innerHTML = kpi + '<div style="display:grid;grid-template-columns:1fr 1fr;gap:14px;">'
      + card('phCardTasks', '我的待办 (' + mt.length + ')' + (overdueN ? ' <span style="color:var(--error);font-weight:600;">· 超期 ' + overdueN + '</span>' : ''), tasks)
      + card('phCardAppr', '待我审批的发布 (' + (d.pendingApprovals || []).length + ')', appr)
      + card('phCardSubs', '我提交的发布 (' + subs.length + ')', subsHtml)
      + card('phCardIssues', '指给我的工单 (' + myIssues.length + ')', issuesHtml)
      + card('phCardMine', '我的项目 (' + mineList.length + ')', mine)
      + card('phCardRecent', '最近访问', recent)
      + '</div>';
  }).catch(function() { box.innerHTML = '<div style="color:var(--error);">加载失败 <a href="#" onclick="loadProjectHome();return false;" style="color:var(--primary);">重试</a></div>'; });
};
window.loadProjectTemplates = function() {
  var box = document.getElementById('projTemplateBox'); if (!box) return;
  box.innerHTML = '<div style="color:var(--text-muted);">加载中...</div>';
  api('/api/project/templates').then(function(res) {
    var ts = _pdata(res);
    var h = '<div style="font-size:12px;color:var(--text-muted);margin-bottom:10px;">把成熟项目「存为模板」(项目详情→设置)，新建时「从模板新建」快速复用 类型/环境/标签/成员角色。</div>';
    if (!ts.length) h += '<div style="padding:20px;text-align:center;color:var(--text-muted);">暂无模板</div>';
    else {
      h += '<table class="dbsync-exec-table" style="width:100%;"><thead><tr><th>模板名</th><th>类型</th><th>描述</th><th>创建人</th><th style="width:160px;">操作</th></tr></thead><tbody>';
      ts.forEach(function(t) {
        h += '<tr><td><b>' + escapeHtml(t.templateName) + '</b></td><td>' + escapeHtml(PROJ_TYPE_LABEL[t.templateType] || t.templateType || '-') + '</td><td>' + escapeHtml(t.description || '-') + '</td><td>' + escapeHtml(t.createdBy || '-') + '</td>'
          + '<td><a href="#" data-perm="project:manage" onclick="projFromTemplateModal(' + t.id + ');return false;" style="color:var(--primary);margin-right:8px;">新建项目</a><a href="#" data-perm="project:manage" onclick="projDeleteTemplate(' + t.id + ');return false;" style="color:var(--error);">删除</a></td></tr>';
      });
      h += '</tbody></table>';
    }
    box.innerHTML = h;
  }).catch(function() { box.innerHTML = '<div style="color:var(--error);">加载失败</div>'; });
};
window.projDeleteTemplate = function(tid) {
  msgConfirm('删除模板', '删除该模板？', '确定删除').then(function(ok) {
    if (!ok) return;
  fetch('/api/project/templates/' + tid, { method: 'DELETE' }).then(_handleResponse).then(function() { showToast('模板已删除', 'success'); loadProjectTemplates(); }).catch(function() { showToast('删除失败', 'error'); });
  });
};
window.projFromTemplateModal = function(presetTid) {
  api('/api/project/templates').then(function(res) {
    var ts = _pdata(res);
    if (!ts.length) { showToast('暂无模板，请先在项目详情→设置「存为模板」', 'info'); return; }
    var opts = ts.map(function(t) { return '<option value="' + t.id + '"' + (presetTid === t.id ? ' selected' : '') + '>' + escapeHtml(t.templateName) + '</option>'; }).join('');
    var h = '<div class="ds-form-row"><label>模板</label><select id="pftTpl" class="g-modal-input">' + opts + '</select></div>'
      + '<div class="ds-form-row"><label>项目名称</label><input id="pftName" placeholder="新项目名称"></div>'
      + '<div style="margin-top:10px;text-align:right;"><button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projDoFromTemplate()">创建</button></div>';
    projShowModalBox('从模板新建项目', h);
  });
};
window.projDoFromTemplate = function() {
  var tid = document.getElementById('pftTpl').value;
  var name = (document.getElementById('pftName').value || '').trim();
  if (!name) { showToast('请填写项目名称', 'error'); return; }
  apiPost('/api/project/templates/' + tid + '/create', { projectName: name }).then(function(res) {
    if (res && res.code === 0) { showToast('已创建', 'success'); projCloseModalBox(); if (document.getElementById('projectSpacePanel').style.display !== 'none') loadProjectSpace(); }
    else showToast((res && res.msg) || '创建失败', 'error');
  }).catch(function() { showToast('创建失败', 'error'); });
};
window.projSaveAsTemplate = function(pid) {
  msgPrompt('存为模板', '模板名称', (_projDetail ? _projDetail.projectName : '') + ' 模板').then(function(name) {
  if (!name || !String(name).trim()) return;
  name = String(name);
  apiPost('/api/project/' + pid + '/save-as-template', { name: name.trim(), description: '' }).then(function(res) {
    if (res && res.code === 0) showToast('已存为模板', 'success');
    else showToast((res && res.msg) || '保存失败', 'error');
  }).catch(function() { showToast('保存失败', 'error'); });
  });
};

/* ===== PM-M5：发布管理 ===== */
var REL_STATUS = { PENDING: { t: '待审批', c: 'var(--warning)' }, APPROVED: { t: '已通过', c: 'var(--primary)' }, REJECTED: { t: '已驳回', c: 'var(--text-faint)' }, RELEASED: { t: '已发布', c: 'var(--success)' }, ROLLED_BACK: { t: '已回滚', c: 'var(--error)' } };
var _projReleases = [];
window.projRelDetail = function(rid) {
  // II-3: 兼容两数据源——发布中心传整行对象, 详情页签传 id 查本地缓存
  var r = (typeof rid === 'object' && rid) ? rid : (_projReleases || []).filter(function(x) { return x.id === rid; })[0];
  if (!r) return;
  var st = REL_STATUS[r.status] || { t: r.status, c: '' };
  var row = function(k, v) { return v ? '<div style="margin:4px 0;"><span style="color:var(--text-muted);font-size:12px;">' + k + '：</span>' + escapeHtml(v) + '</div>' : ''; };
  var relAssets = []; try { relAssets = r.assetJson ? JSON.parse(r.assetJson) : []; } catch (e) {}
  var assetHtml = relAssets.length
    ? '<div style="font-size:12px;color:var(--text-muted);margin:8px 0 4px;">关联资产 (' + relAssets.length + ')</div><div style="font-size:13px;line-height:2;">'
      + relAssets.map(function(a) {
          return '<div style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;"><span style="color:var(--text-muted);font-size:var(--fs-xs);">[' + escapeHtml(PROJ_ASSET_TYPE[a.assetType] || a.assetType || '') + ']</span> ' + escapeHtml(a.assetName || ('#' + a.assetId))
            + ' <a href="#" onclick="projAssetGoto(\'' + escapeHtml(a.assetType || '') + '\',' + (a.assetId || 0) + ');return false;" style="color:var(--primary);font-size:var(--fs-xs);">查看</a></div>';
        }).join('') + '</div>'
    : '';
  var h = '<div style="margin-bottom:8px;">版本 <b>v' + r.versionNo + '</b> <span style="color:' + st.c + ';font-weight:600;margin-left:6px;">' + escapeHtml(st.t) + '</span></div>'
    + row('目标环境', r.targetEnv) + row('提交人', r.submittedBy) + row('提交时间', (r.submittedAt || '').replace('T', ' ').slice(0, 19))
    + (r.approver ? row('审批人', r.approver) : '') + (r.approvedAt ? row('审批时间', (r.approvedAt || '').replace('T', ' ').slice(0, 19)) : '')
    + (r.approveComment ? row('审批意见', r.approveComment) : '')
    + assetHtml
    + '<div style="font-size:12px;color:var(--text-muted);margin:8px 0 4px;">发布内容</div>'
    + '<div class="proj-md" style="font-size:13px;line-height:1.7;border:1px solid var(--divider);border-radius:var(--radius);padding:10px;max-height:300px;overflow:auto;">' + (r.content ? projMd(r.content) : '<span style="color:var(--text-muted);">（无）</span>') + '</div>';
  // II-3: 审批人在详情看完内容与资产清单后就地决策(权限由 my-approvable 显隐, 后端 requireApprover 兜底)
  if (r.status === 'PENDING') {
    h += '<div id="projRelDetailActs" style="margin-top:12px;display:flex;gap:8px;justify-content:flex-end;"></div>';
  }
  projShowModalBox('发布详情 · v' + r.versionNo, h);
  if (r.status === 'PENDING') {
    api('/api/project/my-approvable').then(function(res) {
      var ok = res && res.code === 0 && (res.data || []).indexOf(r.projectId) >= 0;
      var bar = document.getElementById('projRelDetailActs');
      if (!bar) return;
      bar.innerHTML = ok
        ? '<button class="btn btn-sm" style="color:var(--error);border-color:var(--error);" data-perm="project:approve" onclick="projCloseModalBox();projRelAct(' + r.id + ',\'reject\',\'center\')">驳回</button>'
          + '<button class="btn btn-sm btn-primary" data-perm="project:approve" onclick="projCloseModalBox();projRelAct(' + r.id + ',\'approve\',\'center\')">通过</button>'
        : '<span style="font-size:12px;color:var(--text-faint);">需该项目负责人/管理员审批</span>';
    }).catch(function() {});
  }
};
function projRelActionLinks(r, scope) {
  var s = r.status, rid = r.id, a = '';
  if (s === 'PENDING') a = '<a href="#" data-perm="project:approve" onclick="projRelAct(' + rid + ',\'approve\',\'' + scope + '\');return false;" style="color:var(--success);margin-right:8px;">通过</a><a href="#" data-perm="project:approve" onclick="projRelAct(' + rid + ',\'reject\',\'' + scope + '\');return false;" style="color:var(--error);">驳回</a>';
  else if (s === 'APPROVED') a = '<a href="#" data-perm="project:approve" onclick="projRelAct(' + rid + ',\'release\',\'' + scope + '\');return false;" style="color:var(--primary);">发布上线</a>';
  else if (s === 'RELEASED') a = '<a href="#" data-perm="project:approve" onclick="projRelAct(' + rid + ',\'rollback\',\'' + scope + '\');return false;" style="color:var(--error);">回滚</a>';
  else a = '<span style="color:var(--text-muted);">-</span>';
  return a;
}
var _projRelView = (function() { try { return localStorage.getItem('proj.relView') || 'table'; } catch (e) { return 'table'; } })();
window.projSetRelView = function(v) { _projRelView = v; try { localStorage.setItem('proj.relView', v); } catch (e) {} projLoadReleases(); };
// 发布流水线时间线(大功能): 按提交时间倒序的竖向时间线
function projRelTimeline(rs) {
  var ordered = rs.slice().sort(function(a, b) { return String(b.submittedAt || '').localeCompare(String(a.submittedAt || '')); });
  var h = '<div style="position:relative;padding-left:20px;">';
  h += '<div style="position:absolute;left:5px;top:4px;bottom:4px;width:2px;background:var(--divider);"></div>';
  ordered.forEach(function(r) {
    var st = REL_STATUS[r.status] || { t: r.status, c: 'var(--text-faint)' };
    h += '<div style="position:relative;padding:0 0 16px 14px;">'
      + '<div style="position:absolute;left:-19px;top:2px;width:11px;height:11px;border-radius:50%;background:' + st.c + ';border:2px solid var(--bg-card);box-shadow:0 0 0 1px ' + st.c + ';"></div>'
      + '<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">'
      + '<a href="#" onclick="projRelDetail(' + r.id + ');return false;" style="font-weight:600;font-size:13px;color:var(--text-regular);text-decoration:none;">v' + r.versionNo + ' · ' + escapeHtml(r.title || '-') + '</a>'
      + '<span style="font-size:var(--fs-xs);color:' + st.c + ';border:1px solid ' + st.c + ';padding:0 6px;border-radius:var(--radius-lg);">' + escapeHtml(st.t) + '</span>'
      + '<span style="font-size:var(--fs-xs);color:var(--text-muted);">' + escapeHtml(r.targetEnv || '') + '</span></div>'
      + '<div style="font-size:var(--fs-xs);color:var(--text-muted);margin-top:3px;">' + escapeHtml(r.submittedBy || '-') + ' · ' + escapeHtml((r.submittedAt || '').replace('T', ' ').slice(0, 16)) + '　' + projRelActionLinks(r, 'detail') + '</div>'
      + '</div>';
  });
  return h + '</div>';
}
window.projLoadReleases = function() {
  var pane = document.getElementById('projPane_release'); if (!pane || !_projDetail) return;
  pane.innerHTML = wsSkeleton(4);
  api('/api/project/' + _projDetail.id + '/releases').then(function(res) {
    var rs = (res && res.code === 0) ? (res.data || []) : [];
    _projReleases = rs;
    var h = '<div style="display:flex;align-items:center;gap:8px;margin-bottom:10px;"><button class="btn btn-sm btn-primary" data-perm="project:manage" onclick="projOpenReleaseModal()">+ 提交发布</button>'
      + '<span style="margin-left:auto;display:inline-flex;border:1px solid var(--border);border-radius:var(--radius);overflow:hidden;">'
      + '<a href="#" onclick="projSetRelView(\'table\');return false;" style="padding:3px 10px;font-size:12px;text-decoration:none;' + (_projRelView === 'table' ? 'background:var(--primary);color:var(--text-inverse);' : 'color:var(--text-regular);') + '">表格</a>'
      + '<a href="#" onclick="projSetRelView(\'timeline\');return false;" style="padding:3px 10px;font-size:12px;text-decoration:none;border-left:1px solid var(--border);' + (_projRelView === 'timeline' ? 'background:var(--primary);color:var(--text-inverse);' : 'color:var(--text-regular);') + '">时间线</a></span></div>';
    if (!rs.length) h += '<div style="padding:20px;text-align:center;color:var(--text-muted);">暂无发布版本</div>';
    else {
      // 状态统计条
      var sc = {}; rs.forEach(function(r) { sc[r.status] = (sc[r.status] || 0) + 1; });
      h += '<div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px;">';
      Object.keys(sc).forEach(function(s) { var st = REL_STATUS[s] || { t: s, c: 'var(--text-muted)' }; h += '<span style="font-size:var(--fs-xs);padding:1px 8px;border-radius:var(--radius-lg);border:1px solid ' + st.c + ';color:' + st.c + ';">' + escapeHtml(st.t) + ' ' + sc[s] + '</span>'; });
      h += '</div>';
      if (_projRelView === 'timeline') { h += projRelTimeline(rs); pane.innerHTML = h; return; }
      // 待审批(PENDING)置顶, 其余保持原序(稳定排序)
      var ordered = rs.map(function(r, i) { return { r: r, i: i }; }).sort(function(a, b) {
        var pa = a.r.status === 'PENDING' ? 0 : 1, pb = b.r.status === 'PENDING' ? 0 : 1;
        return pa !== pb ? pa - pb : a.i - b.i;
      }).map(function(x) { return x.r; });
      h += '<table class="dbsync-exec-table" style="width:100%;"><thead><tr><th>版本</th><th>标题</th><th>环境</th><th>状态</th><th>提交人</th><th>提交时间</th><th style="width:120px;">操作</th></tr></thead><tbody>';
      ordered.forEach(function(r) {
        var st = REL_STATUS[r.status] || { t: r.status, c: '' };
        var ac = 0; try { ac = r.assetJson ? (JSON.parse(r.assetJson) || []).length : 0; } catch (e) {}
        h += '<tr><td>v' + r.versionNo + '</td><td><a href="#" onclick="projRelDetail(' + r.id + ');return false;" style="color:var(--primary);">' + escapeHtml(r.title || '-') + '</a>' + (ac ? ' <span title="本次发布关联 ' + ac + ' 个资产" style="font-size:var(--fs-xs);color:var(--text-muted);background:var(--bg-hover);padding:0 6px;border-radius:var(--radius-lg);white-space:nowrap;">' + ac + ' 资产</span>' : '') + '</td><td>' + escapeHtml(r.targetEnv || '-') + '</td>'
          + '<td><span style="color:' + st.c + ';font-weight:500;">' + escapeHtml(st.t) + '</span></td>'
          + '<td>' + escapeHtml(r.submittedBy || '-') + '</td>'
          + '<td style="white-space:nowrap;">' + escapeHtml((r.submittedAt || '').replace('T', ' ').slice(0, 16)) + '</td>'
          + '<td>' + projRelActionLinks(r, 'detail') + '</td></tr>';
      });
      h += '</tbody></table>';
    }
    pane.innerHTML = h;
  }).catch(function() { pane.innerHTML = '<div style="color:var(--error);">加载失败</div>'; });
};
window.projOpenReleaseModal = function() {
  document.getElementById('projRelTitle').value = '';
  document.getElementById('projRelEnv').value = 'PROD';
  document.getElementById('projRelContent').value = '';
  // 复位 预览/字数/提交按钮态
  var ta = document.getElementById('projRelContent'), pv = document.getElementById('projRelPreview'), pb = document.getElementById('projRelPreviewBtn'), sb = document.getElementById('projRelSubmitBtn'), wc = document.getElementById('projRelWordCount');
  if (pv) pv.style.display = 'none';
  if (ta) ta.style.display = '';
  if (pb) pb.textContent = '预览';
  if (sb) { sb.disabled = false; sb.textContent = '提交'; }
  if (wc) wc.textContent = '0 字';
  // 关联资产候选 = 项目已绑定资产
  var ab = document.getElementById('projRelAssetBox');
  if (ab) {
    ab.innerHTML = '<span style="color:var(--text-muted);">加载中...</span>';
    api('/api/project/' + _projDetail.id + '/assets').then(function(res) {
      var as = (res && res.code === 0) ? (res.data || []) : [];
      if (!as.length) { ab.innerHTML = '<span style="color:var(--text-muted);">项目暂未绑定资产（可不勾选直接提交）</span>'; return; }
      ab.innerHTML = as.map(function(a) {
        return '<label style="display:block;padding:2px 0;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">'
          + '<input type="checkbox" class="projRelAssetChk" data-type="' + escapeHtml(a.assetType) + '" data-id="' + a.assetId + '" data-name="' + escapeHtml(a.assetName || '') + '"> '
          + '<span style="color:var(--text-muted);font-size:var(--fs-xs);">[' + (PROJ_ASSET_TYPE[a.assetType] || a.assetType) + ']</span> ' + escapeHtml(a.assetName || ('#' + a.assetId)) + '</label>';
      }).join('');
    }).catch(function() { ab.innerHTML = '<span style="color:var(--error);">资产加载失败（可不勾选直接提交）</span>'; });
  }
  document.getElementById('projReleaseModal').classList.add('show');
};
window.projCloseReleaseModal = function() { document.getElementById('projReleaseModal').classList.remove('show'); };
window.projRelContentChanged = function() {
  var v = (document.getElementById('projRelContent') || {}).value || '';
  var wc = document.getElementById('projRelWordCount');
  if (wc) wc.textContent = v.replace(/\s/g, '').length + ' 字';
  // 预览区打开时实时刷新
  var pv = document.getElementById('projRelPreview');
  if (pv && pv.style.display !== 'none') pv.innerHTML = v ? projMd(v) : '<span style="color:var(--text-muted);">（无内容）</span>';
};
window.projToggleRelPreview = function() {
  var ta = document.getElementById('projRelContent'), pv = document.getElementById('projRelPreview'), btn = document.getElementById('projRelPreviewBtn');
  if (!ta || !pv) return;
  var toPreview = pv.style.display === 'none';
  if (toPreview) { pv.innerHTML = ta.value ? projMd(ta.value) : '<span style="color:var(--text-muted);">（无内容）</span>'; pv.style.display = ''; ta.style.display = 'none'; if (btn) btn.textContent = '编辑'; }
  else { pv.style.display = 'none'; ta.style.display = ''; if (btn) btn.textContent = '预览'; }
};
window.projSubmitRelease = function() {
  if (!_projDetail) return;
  var env = document.getElementById('projRelEnv').value;
  var content = document.getElementById('projRelContent').value || '';
  // 生产环境内容为空时软提示(非强制必填, 保留原可选语义)
  if (env === 'PROD' && !content.trim()) {
    msgConfirm('提交发布', '目标为生产环境但未填写发布内容，确认直接提交？', '仍然提交').then(function(ok) {
      if (ok) projDoSubmitRelease();
    });
    return;
  }
  projDoSubmitRelease();
};
function projDoSubmitRelease() {
  var btn = document.getElementById('projRelSubmitBtn');
  if (btn) { if (btn.disabled) return; btn.disabled = true; btn.textContent = '提交中…'; }
  var done = function() { if (btn) { btn.disabled = false; btn.textContent = '提交'; } };
  var relAssets = [];
  Array.prototype.slice.call(document.querySelectorAll('.projRelAssetChk:checked')).forEach(function(c) {
    relAssets.push({ assetType: c.getAttribute('data-type'), assetId: parseInt(c.getAttribute('data-id')), assetName: c.getAttribute('data-name') });
  });
  var body = {
    title: (document.getElementById('projRelTitle').value || '').trim(),
    targetEnv: document.getElementById('projRelEnv').value,
    content: document.getElementById('projRelContent').value || '',
    assetJson: relAssets.length ? JSON.stringify(relAssets) : null
  };
  apiPost('/api/project/' + _projDetail.id + '/releases', body).then(function(res) {
    if (res && res.code === 0) { showToast('已提交发布', 'success'); projCloseReleaseModal(); projLoadReleases(); }
    else { showToast((res && res.msg) || '提交失败', 'error'); done(); }
  }).catch(function() { showToast('提交失败', 'error'); done(); });
}
window.projRelAct = function(rid, action, scope) {
  var refresh = function() { if (scope === 'center') loadProjectReleaseCenter(); else projLoadReleases(); };
  if (action === 'approve' || action === 'reject') {
    // II-3: 站内弹窗替代原生 prompt; 驳回原因必填留痕
    msgPrompt(action === 'approve' ? '审批意见(可选)' : '驳回原因(必填, 提交人会在工作台看到)', action === 'approve' ? '同意发布...' : '请说明驳回原因', '').then(function(comment) {
      if (comment === null) return;
      if (action === 'reject' && !String(comment).trim()) { showToast('驳回必须填写原因', 'error'); return; }
      apiPost('/api/project/releases/' + rid + '/' + action, { comment: comment || '' }).then(function(res) {
        if (res && res.code === 0) { showToast(action === 'approve' ? '已通过' : '已驳回', 'success'); refresh(); }
        else showToast((res && res.msg) || '操作失败', 'error');
      }).catch(function() { showToast('操作失败', 'error'); });
    });
  } else {
    msgConfirm(action === 'release' ? '发布上线' : '回滚版本', action === 'release' ? '确认发布上线该版本？(上线前自动核验关联资产)' : '确认回滚该版本？', action === 'release' ? '确定发布' : '确定回滚').then(function(ok) {
      if (!ok) return;
      apiPost('/api/project/releases/' + rid + '/' + action, {}).then(function(res) {
        if (res && res.code === 0) { showToast(action === 'release' ? '已发布' : '已回滚', 'success'); refresh(); return; }
        var msg = (res && res.msg) || '操作失败';
        // N6 门禁: 警示项可二次确认强制放行(强规则失败后端硬拦, 无 FORCEABLE 前缀)
        if (action === 'release' && msg.indexOf('FORCEABLE:') === 0) {
          var problems = msg.slice('FORCEABLE:'.length);
          msgConfirm('上线核验警示', '<span style="color:var(--error);">' + escapeHtml(problems) + '</span><br><br>存在以上问题, 仍要强制上线吗？', '强制上线').then(function(ok2) {
            if (!ok2) return;
            apiPost('/api/project/releases/' + rid + '/release?force=true', {}).then(function(res2) {
              if (res2 && res2.code === 0) { showToast('已强制发布(核验警示已留痕)', 'success'); refresh(); }
              else showToast((res2 && res2.msg) || '操作失败', 'error');
            }).catch(function() { showToast('操作失败', 'error'); });
          });
          return;
        }
        showToast(msg, 'error');
      }).catch(function() { showToast('操作失败', 'error'); });
    });
  }
};

/* 发布管理 顶级页签：跨项目发布中心 */
var _projReleaseFilter = '';
var _projRelCenterList = [];   // II-3: 行对象缓存供详情弹窗
window.loadProjectReleaseCenter = function() {
  var box = document.getElementById('projReleaseCenterBox'); if (!box) return;
  box.innerHTML = '<div style="color:var(--text-muted);">加载中...</div>';
  Promise.all([
    api('/api/project/releases/all' + (_projReleaseFilter ? '?status=' + _projReleaseFilter : '')),
    api('/api/project/list'),
    api('/api/project/my-approvable').catch(function() { return null; })
  ]).then(function(arr) {
    var rs = (arr[0] && arr[0].code === 0) ? (arr[0].data || []) : [];
    var projs = (arr[1] && arr[1].code === 0) ? (arr[1].data || []) : [];
    // P5 按钮角色化: 无审批权项目的操作列置灰(后端 requireApprover 仍兜底硬拦)
    var approvable = {};
    if (arr[2] && arr[2].code === 0) (arr[2].data || []).forEach(function(pid) { approvable[pid] = true; });
    var canApprove = function(pid) { return !arr[2] || arr[2].code !== 0 ? true : !!approvable[pid]; };
    var nameMap = {}; projs.forEach(function(p) { nameMap[p.id] = p.projectName; });
    var chips = [['', '全部'], ['PENDING', '待审批'], ['APPROVED', '已通过'], ['RELEASED', '已发布'], ['REJECTED', '已驳回'], ['ROLLED_BACK', '已回滚']];
    var h = '<div style="margin-bottom:10px;display:flex;gap:6px;flex-wrap:wrap;">' + chips.map(function(c) {
      var on = _projReleaseFilter === c[0];
      return '<button class="btn btn-sm" style="' + (on ? 'background:var(--primary);color:var(--text-inverse);border-color:var(--primary);' : '') + '" onclick="projReleaseFilter(\'' + c[0] + '\')">' + c[1] + '</button>';
    }).join('') + '</div>';
    if (!rs.length) h += '<div style="padding:24px;text-align:center;color:var(--text-muted);">暂无发布记录</div>';
    else {
      h += '<table class="dbsync-exec-table" style="width:100%;"><thead><tr><th>项目</th><th>版本</th><th>标题</th><th>环境</th><th>状态</th><th>提交人</th><th>提交时间</th><th style="width:120px;">操作</th></tr></thead><tbody>';
      _projRelCenterList = rs;
      rs.forEach(function(r, ri) {
        var st = REL_STATUS[r.status] || { t: r.status, c: '' };
        h += '<tr><td><a href="#" onclick="projOpenDetailById(' + r.projectId + ',\'release\');return false;" style="color:var(--primary);">' + escapeHtml(r.projectName || nameMap[r.projectId] || ('#' + r.projectId)) + '</a></td><td>v' + r.versionNo + '</td>'
          + '<td style="max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="' + escapeHtml(r.title || '') + '"><a href="#" onclick="projRelDetail(_projRelCenterList[' + ri + ']);return false;" style="color:var(--primary);">' + escapeHtml(r.title || '-') + '</a></td>'
          + '<td>' + escapeHtml(r.targetEnv || '-') + '</td><td><span style="color:' + st.c + ';font-weight:500;">' + escapeHtml(st.t) + '</span></td>'
          + '<td>' + escapeHtml(r.submittedBy || '-') + '</td><td style="white-space:nowrap;">' + escapeHtml((r.submittedAt || '').replace('T', ' ').slice(0, 16)) + '</td>'
          + '<td>' + (canApprove(r.projectId) ? projRelActionLinks(r, 'center') : '<span style="color:var(--text-faint);" title="需该项目负责人/管理员操作">无审批权</span>') + '</td></tr>';
      });
      h += '</tbody></table>';
    }
    box.innerHTML = h;
  }).catch(function() { box.innerHTML = '<div style="color:var(--error);">加载失败</div>'; });
};
window.projReleaseFilter = function(s) { _projReleaseFilter = s; loadProjectReleaseCenter(); };
