/* 数据模型 — 三层建模(业务/逻辑/物理) + L1-L5 主题域分层 + 数仓分层 + 申请审批流转 + 模型生成。
   独立顶部菜单(datamodel)。权限: datamodel:edit(建模) / datamodel:approve(审批)。 */
(function () {
  var DM = { subjects: [], models: [], curType: '', curSubject: null, elements: [] };
  var TYPE_LABEL = { BIZ: '业务模型', LOGIC: '逻辑模型', PHYS: '物理模型' };
  var TYPE_COLOR = { BIZ: 'var(--primary)', LOGIC: '#2f9e44', PHYS: '#e8590c' };
  var STATUS_LABEL = { DRAFT: '草稿', PENDING: '待审批', PUBLISHED: '已发布', REJECTED: '已驳回', ARCHIVED: '已归档' };
  var STATUS_PILL = { DRAFT: 'is-muted', PENDING: 'is-warn', PUBLISHED: 'is-ok', REJECTED: 'is-error', ARCHIVED: 'is-muted' };
  var DW_LAYERS = ['', 'ODS', 'DWD', 'DIM', 'DWS', 'ADS'];
  var DATA_TYPES = ['STRING', 'INT', 'BIGINT', 'DECIMAL', 'DOUBLE', 'DATE', 'DATETIME', 'BOOLEAN', 'TEXT'];

  function esc(s) { return (window.escapeHtml ? escapeHtml(String(s == null ? '' : s)) : String(s == null ? '' : s)); }
  function toast(m, t) { if (window.showToast) showToast(m, t || ''); }

  window.loadDataModel = function () {
    var root = document.getElementById('dmRoot'); if (!root) return;
    root.innerHTML =
      '<div class="ops-layout" style="height:100%;overflow:hidden;">'
      + '<div class="ops-sidebar" style="width:248px;overflow:auto;">'
      + '  <div style="padding:12px 14px;font-weight:600;font-size:13px;display:flex;align-items:center;justify-content:space-between;">主题域 (L1-L5)'
      + '    <a href="#" data-perm="datamodel:edit" onclick="dmNewSubject();return false;" style="font-size:12px;color:var(--primary);">+ 新建</a></div>'
      + '  <div id="dmSubjectTree" style="padding:0 6px 12px;"></div>'
      + '  <div style="border-top:1px solid var(--border);margin-top:6px;padding:10px 14px;">'
      + '    <a href="#" onclick="dmShowChanges();return false;" style="font-size:13px;color:var(--primary);">📋 审批工单 <span id="dmChangeBadge" style="font-size:var(--fs-xs);"></span></a></div>'
      + '</div>'
      + '<div class="ops-main" style="flex:1;overflow:auto;padding:16px 20px;">'
      + '  <div style="display:flex;align-items:center;gap:10px;margin-bottom:14px;flex-wrap:wrap;">'
      + '    <h2 style="font-size:17px;font-weight:600;margin:0;">数据模型</h2>'
      + '    <div id="dmTypeTabs" style="display:flex;gap:6px;margin-left:8px;"></div>'
      + '    <span id="dmCurSubject" style="font-size:12px;color:var(--text-muted);"></span>'
      + '    <button class="btn btn-sm" onclick="dmShowDashboard()" style="margin-left:auto;">📊 看板</button>'
      + '    <button class="btn btn-sm" data-perm="datamodel:edit" onclick="dmReverseImport()">⬇ 逆向导入</button>'
      + '    <button class="btn btn-sm btn-primary" data-perm="datamodel:edit" onclick="dmNewModel()">+ 新建模型</button>'
      + '  </div>'
      + '  <div id="dmModelList"></div>'
      + '</div></div>';
    renderTypeTabs();
    dmLoadSubjects();
    dmLoadModels();
    dmLoadChangeBadge();
    dmLoadElements();
  };
  // 数据标准(数据元)加载为 datalist, 供属性绑标准 + 联动带出类型/长度
  function dmLoadElements() {
    api('/api/gov/standard/elements').then(function (res) {
      DM.elements = (res && res.code === 0 && res.data) || [];
      var dl = document.getElementById('dmElementList');
      if (!dl) { dl = document.createElement('datalist'); dl.id = 'dmElementList'; document.body.appendChild(dl); }
      dl.innerHTML = DM.elements.map(function (e) { return '<option value="' + esc(e.elementCode) + '" label="' + esc(e.nameCn || '') + '">'; }).join('');
    }).catch(function () {});
  }

  function renderTypeTabs() {
    var box = document.getElementById('dmTypeTabs'); if (!box) return;
    var tabs = [['', '全部'], ['BIZ', '业务'], ['LOGIC', '逻辑'], ['PHYS', '物理']];
    box.innerHTML = tabs.map(function (t) {
      var on = DM.curType === t[0];
      return '<button class="btn btn-sm' + (on ? ' btn-primary' : '') + '" onclick="dmSetType(\'' + t[0] + '\')">' + t[1] + '</button>';
    }).join('');
  }
  window.dmSetType = function (t) { DM.curType = t; renderTypeTabs(); dmLoadModels(); };

  // ---------- 主题域 L1-L5 树 ----------
  function dmLoadSubjects() {
    api('/api/subject/tree').then(function (res) {
      DM.subjects = (res && res.code === 0 && res.data) || [];
      renderSubjectTree();
    }).catch(function () {});
  }
  function renderSubjectTree() {
    var box = document.getElementById('dmSubjectTree'); if (!box) return;
    var h = '<div style="padding:4px 8px;font-size:13px;cursor:pointer;border-radius:var(--radius);' + (DM.curSubject == null ? 'background:var(--bg-hover);' : '') + '" onclick="dmSetSubject(null)">全部主题域</div>';
    h += renderSubNodes(DM.subjects, 0);
    box.innerHTML = h;
  }
  function renderSubNodes(nodes, depth) {
    if (!nodes || !nodes.length) return '';
    return nodes.map(function (n) {
      var lv = n.level || (depth + 1);
      var lt = n.layerType ? ' <span style="font-size:var(--fs-xs);color:var(--text-faint);">' + esc(n.layerType) + '</span>' : '';
      var on = DM.curSubject === n.id;
      var pad = 8 + depth * 14;
      var s = '<div style="padding:4px 8px 4px ' + pad + 'px;font-size:13px;cursor:pointer;border-radius:var(--radius);' + (on ? 'background:var(--bg-hover);' : '') + '" onclick="dmSetSubject(' + n.id + ')">'
        + '<span style="font-size:var(--fs-xs);color:var(--primary);">L' + lv + '</span> ' + esc(n.name) + lt + '</div>';
      s += renderSubNodes(n.children, depth + 1);
      return s;
    }).join('');
  }
  window.dmSetSubject = function (id) { DM.curSubject = id; renderSubjectTree(); updateSubjectLabel(); dmLoadModels(); };
  function updateSubjectLabel() {
    var el = document.getElementById('dmCurSubject'); if (!el) return;
    if (DM.curSubject == null) { el.textContent = ''; return; }
    var name = findSubjectName(DM.subjects, DM.curSubject);
    el.textContent = name ? ('· 主题域: ' + name) : '';
  }
  function findSubjectName(nodes, id) {
    for (var i = 0; nodes && i < nodes.length; i++) {
      if (nodes[i].id === id) return nodes[i].name;
      var r = findSubjectName(nodes[i].children, id); if (r) return r;
    }
    return null;
  }
  function flatSubjects(nodes, out) {
    out = out || [];
    (nodes || []).forEach(function (n) { out.push(n); flatSubjects(n.children, out); });
    return out;
  }

  window.dmNewSubject = function () {
    var flat = flatSubjects(DM.subjects);
    var opts = '<option value="">— 顶层(L1) —</option>' + flat.map(function (s) {
      return '<option value="' + s.id + '">' + esc(s.name) + ' (L' + (s.level || '?') + ')</option>';
    }).join('');
    var layerOpts = DW_LAYERS.map(function (l) { return '<option value="' + l + '">' + (l || '（无数仓分层）') + '</option>'; }).join('');
    var fi = 'class="dbsync-form-input" style="width:100%;"', lab = 'style="display:block;font-size:12px;color:var(--text-muted);margin-bottom:3px;"';
    var h = '<div style="display:flex;flex-direction:column;gap:10px;min-width:360px;">'
      + '<div><label ' + lab + '>主题域名称</label><input id="dmsName" ' + fi + ' placeholder="如 交易域/订单"></div>'
      + '<div><label ' + lab + '>父主题域(决定 L 层级)</label><select id="dmsParent" class="dbsync-form-select" style="width:100%;">' + opts + '</select></div>'
      + '<div><label ' + lab + '>数仓分层(可选)</label><select id="dmsLayer" class="dbsync-form-select" style="width:100%;">' + layerOpts + '</select></div>'
      + '<div style="text-align:right;margin-top:4px;"><button class="btn btn-sm" onclick="projCloseModalBox()">取消</button> <button class="btn btn-sm btn-primary" onclick="dmSaveSubject()">保存</button></div></div>';
    projShowModalBox('新建主题域', h);
  };
  window.dmSaveSubject = function () {
    var name = (document.getElementById('dmsName').value || '').trim();
    if (!name) { toast('请填写名称', 'error'); return; }
    var parentId = document.getElementById('dmsParent').value;
    var layerType = document.getElementById('dmsLayer').value;
    apiPost('/api/subject', { name: name, parentId: parentId ? parseInt(parentId) : null, layerType: layerType || null })
      .then(function (res) { if (res && res.code === 0) { toast('已创建', 'success'); projCloseModalBox(); dmLoadSubjects(); } else toast((res && res.msg) || '失败', 'error'); })
      .catch(function () { toast('保存失败', 'error'); });
  };

  // ---------- 模型列表 ----------
  function dmLoadModels() {
    var q = '/api/datamodel/models?';
    if (DM.curType) q += 'type=' + DM.curType + '&';
    if (DM.curSubject != null) q += 'subjectId=' + DM.curSubject;
    api(q).then(function (res) {
      DM.models = (res && res.code === 0 && res.data) || [];
      renderModels();
    }).catch(function () { var b = document.getElementById('dmModelList'); if (b) b.innerHTML = '<div style="color:var(--error);">加载失败</div>'; });
  }
  function renderModels() {
    var box = document.getElementById('dmModelList'); if (!box) return;
    if (!DM.models.length) { box.innerHTML = '<div style="padding:32px;text-align:center;color:var(--text-muted);">暂无模型，点击右上「+ 新建模型」</div>'; return; }
    var h = '<table class="dbsync-exec-table" style="width:100%;"><thead><tr><th>编码</th><th>名称</th><th>类型</th><th>数仓层</th><th>主题域</th><th>状态</th><th>版本</th><th>实体</th><th style="width:280px;">操作</th></tr></thead><tbody>';
    DM.models.forEach(function (m) {
      var tc = TYPE_COLOR[m.modelType] || 'var(--text-muted)';
      var typeBadge = '<span style="font-size:var(--fs-xs);padding:1px 7px;border-radius:var(--radius-lg);background:' + tc + '1a;color:' + tc + ';">' + (TYPE_LABEL[m.modelType] || m.modelType) + '</span>';
      var st = '<span class="gov-pill ' + (STATUS_PILL[m.status] || 'is-muted') + '">' + (STATUS_LABEL[m.status] || m.status) + '</span>';
      var subName = findSubjectName(DM.subjects, m.subjectId) || '-';
      var ops = '<a href="#" onclick="dmOpenModel(' + m.id + ');return false;" style="color:var(--primary);margin-right:8px;">详情</a>';
      if (m.status === 'DRAFT' || m.status === 'REJECTED') ops += '<a href="#" data-perm="datamodel:edit" onclick="dmSubmit(' + m.id + ');return false;" style="color:var(--primary);margin-right:8px;">提交审批</a>';
      if (m.modelType === 'BIZ') ops += '<a href="#" data-perm="datamodel:edit" onclick="dmGenLogical(' + m.id + ');return false;" style="color:#2f9e44;margin-right:8px;">生成逻辑</a>';
      if (m.modelType === 'LOGIC') ops += '<a href="#" data-perm="datamodel:edit" onclick="dmGenPhysical(' + m.id + ');return false;" style="color:#2f9e44;margin-right:8px;">生成物理</a>';
      if (m.modelType === 'PHYS') ops += '<a href="#" onclick="dmShowDdl(' + m.id + ');return false;" style="color:#e8590c;margin-right:8px;">DDL</a>';
      if (m.modelType === 'PHYS' && m.status === 'PUBLISHED') ops += '<a href="#" data-perm="datamodel:edit" onclick="dmPublishAsset(' + m.id + ');return false;" style="color:#1971c2;margin-right:8px;">落地资产</a>';
      ops += '<a href="#" data-perm="datamodel:edit" onclick="dmDeleteModel(' + m.id + ');return false;" style="color:var(--error);">删除</a>';
      h += '<tr><td style="font-family:monospace;"><span style="display:inline-block;max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;vertical-align:middle;" title="' + esc(m.modelCode) + '">' + esc(m.modelCode) + '</span></td>'
        + '<td><b style="display:inline-block;max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;vertical-align:middle;" title="' + esc(m.modelName) + '">' + esc(m.modelName) + '</b></td><td>' + typeBadge + '</td>'
        + '<td>' + (m.dwLayer ? esc(m.dwLayer) : '-') + '</td><td style="font-size:12px;max-width:140px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="' + esc(subName) + '">' + esc(subName) + '</td><td>' + st + '</td>'
        + '<td>v' + (m.version || 1) + '</td><td>' + (m.entityCount || 0) + '</td><td style="white-space:nowrap;">' + ops + '</td></tr>';
    });
    box.innerHTML = h + '</tbody></table>';
    if (window.dnApplyBtnPerms) dnApplyBtnPerms(box);
  }

  // ---------- 新建/编辑模型 ----------
  window.dmNewModel = function (model) {
    var flat = flatSubjects(DM.subjects);
    var subOpts = '<option value="">— 不归属 —</option>' + flat.map(function (s) { return '<option value="' + s.id + '"' + (model && model.subjectId === s.id ? ' selected' : '') + '>' + esc(s.name) + '</option>'; }).join('');
    var typeOpts = ['BIZ', 'LOGIC', 'PHYS'].map(function (t) { return '<option value="' + t + '"' + (model && model.modelType === t ? ' selected' : '') + '>' + TYPE_LABEL[t] + '</option>'; }).join('');
    var layerOpts = DW_LAYERS.map(function (l) { return '<option value="' + l + '"' + (model && model.dwLayer === l ? ' selected' : '') + '>' + (l || '（无）') + '</option>'; }).join('');
    var fi = 'class="dbsync-form-input" style="width:100%;"', lab = 'style="display:block;font-size:12px;color:var(--text-muted);margin-bottom:3px;"';
    var h = '<div style="display:flex;flex-direction:column;gap:10px;min-width:400px;">'
      + '<div><label ' + lab + '>模型编码</label><input id="dmfCode" ' + fi + ' ' + (model ? 'value="' + esc(model.modelCode) + '" disabled' : 'placeholder="字母开头, 如 ORDER_LM"') + '></div>'
      + '<div><label ' + lab + '>模型名称</label><input id="dmfName" ' + fi + ' value="' + (model ? esc(model.modelName) : '') + '" placeholder="如 订单逻辑模型"></div>'
      + '<div style="display:flex;gap:10px;"><div style="flex:1;"><label ' + lab + '>类型</label><select id="dmfType" class="dbsync-form-select" style="width:100%;"' + (model ? ' disabled' : '') + '>' + typeOpts + '</select></div>'
      + '<div style="flex:1;"><label ' + lab + '>数仓分层</label><select id="dmfLayer" class="dbsync-form-select" style="width:100%;">' + layerOpts + '</select></div></div>'
      + '<div><label ' + lab + '>主题域</label><select id="dmfSubject" class="dbsync-form-select" style="width:100%;">' + subOpts + '</select></div>'
      + '<div><label ' + lab + '>说明</label><input id="dmfDesc" ' + fi + ' value="' + (model ? esc(model.description || '') : '') + '" placeholder="可选"></div>'
      + '<div style="text-align:right;margin-top:4px;"><button class="btn btn-sm" onclick="projCloseModalBox()">取消</button> <button class="btn btn-sm btn-primary" onclick="dmSaveModel(' + (model ? model.id : 0) + ')">保存</button></div></div>';
    projShowModalBox(model ? '编辑模型' : '新建模型', h);
  };
  window.dmSaveModel = function (id) {
    var body = {
      modelName: (document.getElementById('dmfName').value || '').trim(),
      dwLayer: document.getElementById('dmfLayer').value || null,
      subjectId: document.getElementById('dmfSubject').value ? parseInt(document.getElementById('dmfSubject').value) : null,
      description: (document.getElementById('dmfDesc').value || '').trim()
    };
    if (id) { body.id = id; } else {
      body.modelCode = (document.getElementById('dmfCode').value || '').trim();
      body.modelType = document.getElementById('dmfType').value;
      if (!body.modelCode) { toast('请填写模型编码', 'error'); return; }
    }
    if (!body.modelName) { toast('请填写模型名称', 'error'); return; }
    apiPost('/api/datamodel/model', body).then(function (res) {
      if (res && res.code === 0) { toast('已保存', 'success'); projCloseModalBox(); dmLoadModels(); }
      else toast((res && res.msg) || '保存失败', 'error');
    }).catch(function () { toast('保存失败', 'error'); });
  };
  window.dmDeleteModel = function (id) {
    DN.confirm('确认删除该模型？将级联删除其实体/属性/关系/工单。', { title: '删除模型', danger: true }).then(function (ok) {
      if (!ok) return;
      api('/api/datamodel/model/' + id, { method: 'DELETE' }).then(function (res) {
        if (res && res.code === 0) { toast('已删除', 'success'); dmLoadModels(); } else toast((res && res.msg) || '删除失败', 'error');
      }).catch(function () { toast('删除失败', 'error'); });
    });
  };

  // ---------- 模型详情(实体 + 属性编辑 + 关系) ----------
  window.dmOpenModel = function (id) {
    api('/api/datamodel/model/' + id).then(function (res) {
      if (!res || res.code !== 0) { toast('加载失败', 'error'); return; }
      var m = res.data;
      var canEdit = (m.status === 'DRAFT' || m.status === 'REJECTED');
      var body = document.createElement('div');
      body.innerHTML =
        '<div style="font-size:13px;color:var(--text-muted);margin-bottom:12px;">'
        + '<b style="color:var(--text-primary);font-size:15px;">' + esc(m.modelName) + '</b> '
        + '<span style="font-family:monospace;">' + esc(m.modelCode) + '</span> · ' + (TYPE_LABEL[m.modelType] || m.modelType)
        + ' · <span class="gov-pill ' + (STATUS_PILL[m.status] || 'is-muted') + '">' + (STATUS_LABEL[m.status] || m.status) + '</span> · v' + (m.version || 1)
        + (m.sourceModelName ? ' · 溯源: ' + esc(m.sourceModelName) : '')
        + (m.description ? '<div style="margin-top:4px;">' + esc(m.description) + '</div>' : '') + '</div>'
        + '<div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;"><b style="font-size:13px;">实体</b>'
        + (canEdit ? '<button class="btn btn-sm" data-perm="datamodel:edit" onclick="dmAddEntity(' + m.id + ')">+ 实体</button>' : '')
        + '<button class="btn btn-sm" onclick="dmShowER(' + m.id + ')" style="margin-left:auto;">🔗 ER 图</button>'
        + '<button class="btn btn-sm" onclick="dmShowVersions(' + m.id + ')">🕑 版本历史</button>'
        + '</div><div id="dmEntityBox"></div>'
        + '<div style="display:flex;align-items:center;gap:8px;margin:14px 0 6px;"><b style="font-size:13px;">关系</b>'
        + (canEdit ? '<button class="btn btn-sm" data-perm="datamodel:edit" onclick="dmAddRelation(' + m.id + ')">+ 关系</button>' : '')
        + '</div><div id="dmRelBox"></div>';
      var foot = null;
      if (window.DN && DN.drawer) {
        var dr = DN.drawer('模型详情 · ' + esc(m.modelName), body, null);
        window.__dmDrawer = dr;
      } else {
        projShowModalBox('模型详情 · ' + esc(m.modelName), body.outerHTML);
      }
      renderEntities(m);
    }).catch(function () { toast('加载失败', 'error'); });
  };
  function renderEntities(m) {
    var box = document.getElementById('dmEntityBox'); if (!box) return;
    var canEdit = (m.status === 'DRAFT' || m.status === 'REJECTED');
    if (!m.entities || !m.entities.length) { box.innerHTML = '<div style="padding:16px;color:var(--text-muted);">暂无实体</div>'; if (window.dnApplyBtnPerms) dnApplyBtnPerms(box); return; }
    box.innerHTML = m.entities.map(function (e) {
      var attrs = e.attributes || [];
      var rows = attrs.map(function (a) {
        return '<tr><td style="font-family:monospace;">' + esc(a.attrCode) + (a.isPk == 1 ? ' <span style="color:#e8590c;font-size:var(--fs-xs);">PK</span>' : '') + '</td><td>' + esc(a.attrName || '') + '</td><td>' + esc(a.dataType || '') + (a.dataLength ? '(' + esc(a.dataLength) + ')' : '') + '</td><td>' + (a.isNullable == 0 ? '否' : '是') + '</td><td style="font-size:12px;color:var(--text-muted);">' + esc(a.elementCode || '') + '</td></tr>';
      }).join('') || '<tr><td colspan="5" style="color:var(--text-muted);">无属性</td></tr>';
      return '<div style="border:1px solid var(--border);border-radius:var(--radius);padding:10px 12px;margin-bottom:10px;">'
        + '<div style="display:flex;align-items:center;gap:8px;margin-bottom:6px;"><b style="max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="' + esc(e.entityName) + '">' + esc(e.entityName) + '</b> <span style="font-family:monospace;font-size:12px;color:var(--text-muted);max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="' + esc(e.entityCode) + '">' + esc(e.entityCode) + '</span> <span style="font-size:var(--fs-xs);color:var(--primary);flex-shrink:0;">L' + (e.level || 4) + '</span>'
        + (e.physicalTable ? ' <span style="font-size:var(--fs-xs);color:var(--text-faint);">→ ' + esc(e.physicalTable) + '</span>' : '')
        + (canEdit ? '<a href="#" data-perm="datamodel:edit" onclick="dmEditAttrs(' + e.id + ',' + m.id + ');return false;" style="margin-left:auto;font-size:12px;color:var(--primary);">编辑属性</a> <a href="#" data-perm="datamodel:edit" onclick="dmDelEntity(' + e.id + ',' + m.id + ');return false;" style="font-size:12px;color:var(--error);">删除</a>' : '')
        + '</div>'
        + '<table class="dbsync-exec-table" style="width:100%;font-size:12px;"><thead><tr><th>属性</th><th>名称</th><th>类型</th><th>可空</th><th>数据标准</th></tr></thead><tbody>' + rows + '</tbody></table></div>';
    }).join('');
    // 关系渲染
    var rbox = document.getElementById('dmRelBox');
    if (rbox) {
      var rels = m.relations || [];
      rbox.innerHTML = rels.length ? rels.map(function (r) {
        var sn = (m.entities.filter(function (e) { return e.id === r.sourceEntityId; })[0] || {}).entityName || ('#' + r.sourceEntityId);
        var tn = (m.entities.filter(function (e) { return e.id === r.targetEntityId; })[0] || {}).entityName || ('#' + r.targetEntityId);
        return '<div style="display:flex;align-items:center;gap:8px;font-size:12.5px;padding:3px 0;">' + esc(sn) + ' <span style="color:var(--primary);font-weight:600;">—' + esc(r.relationType) + '→</span> ' + esc(tn) + (r.description ? ' <span style="color:var(--text-muted);">(' + esc(r.description) + ')</span>' : '') + (canEdit ? ' <a href="#" data-perm="datamodel:edit" onclick="dmDelRelation(' + r.id + ',' + m.id + ');return false;" style="color:var(--error);margin-left:auto;">删除</a>' : '') + '</div>';
      }).join('') : '<div style="color:var(--text-muted);font-size:12px;">暂无关系</div>';
    }
    if (window.dnApplyBtnPerms) { dnApplyBtnPerms(box); if (rbox) dnApplyBtnPerms(rbox); }
  }
  window.dmAddRelation = function (modelId) {
    api('/api/datamodel/model/' + modelId).then(function (res) {
      var ents = (res.data && res.data.entities) || [];
      if (ents.length < 1) { toast('请先创建实体', 'error'); return; }
      var opts = ents.map(function (e) { return '<option value="' + e.id + '">' + esc(e.entityName) + '</option>'; }).join('');
      var typeOpts = ['1:1', '1:N', 'M:N'].map(function (t) { return '<option value="' + t + '">' + t + '</option>'; }).join('');
      var lab = 'style="display:block;font-size:12px;color:var(--text-muted);margin-bottom:3px;"';
      var h = '<div style="display:flex;flex-direction:column;gap:10px;min-width:340px;">'
        + '<div><label ' + lab + '>源实体</label><select id="dmrSrc" class="dbsync-form-select" style="width:100%;">' + opts + '</select></div>'
        + '<div><label ' + lab + '>关系类型</label><select id="dmrType" class="dbsync-form-select" style="width:100%;">' + typeOpts + '</select></div>'
        + '<div><label ' + lab + '>目标实体</label><select id="dmrTgt" class="dbsync-form-select" style="width:100%;">' + opts + '</select></div>'
        + '<div><label ' + lab + '>说明</label><input id="dmrDesc" class="dbsync-form-input" style="width:100%;" placeholder="可选"></div>'
        + '<div style="text-align:right;"><button class="btn btn-sm" onclick="projCloseModalBox()">取消</button> <button class="btn btn-sm btn-primary" onclick="dmSaveRelation(' + modelId + ')">保存</button></div></div>';
      projShowModalBox('新建关系', h);
    }).catch(function () { toast('加载实体失败', 'error'); });
  };
  window.dmSaveRelation = function (modelId) {
    var src = parseInt(document.getElementById('dmrSrc').value), tgt = parseInt(document.getElementById('dmrTgt').value);
    if (isNaN(src) || isNaN(tgt)) { toast('请选择源与目标实体', 'error'); return; }
    if (src === tgt) { toast('源与目标实体不能相同', 'error'); return; }
    apiPost('/api/datamodel/relation', { modelId: modelId, sourceEntityId: src, targetEntityId: tgt, relationType: document.getElementById('dmrType').value, description: (document.getElementById('dmrDesc').value || '').trim() }).then(function (res) {
      if (res && res.code === 0) { toast('关系已保存', 'success'); projCloseModalBox(); dmReopenModel(modelId); } else toast((res && res.msg) || '保存失败', 'error');
    }).catch(function () { toast('保存失败', 'error'); });
  };
  window.dmDelRelation = function (relId, modelId) {
    api('/api/datamodel/relation/' + relId, { method: 'DELETE' }).then(function () { toast('已删除', 'success'); dmReopenModel(modelId); }).catch(function () { toast('删除失败', 'error'); });
  };
  window.dmAddEntity = function (modelId) {
    var fi = 'class="dbsync-form-input" style="width:100%;"', lab = 'style="display:block;font-size:12px;color:var(--text-muted);margin-bottom:3px;"';
    var h = '<div style="display:flex;flex-direction:column;gap:10px;min-width:340px;">'
      + '<div><label ' + lab + '>实体编码</label><input id="dmeCode" ' + fi + ' placeholder="字母开头, 如 OrderHead"></div>'
      + '<div><label ' + lab + '>实体名称</label><input id="dmeName" ' + fi + ' placeholder="如 订单主体"></div>'
      + '<div><label ' + lab + '>层级</label><select id="dmeLevel" class="dbsync-form-select" style="width:100%;"><option value="3">L3 业务对象</option><option value="4" selected>L4 逻辑实体</option></select></div>'
      + '<div><label ' + lab + '>业务定义</label><input id="dmeDef" ' + fi + ' placeholder="可选"></div>'
      + '<div style="text-align:right;"><button class="btn btn-sm" onclick="projCloseModalBox()">取消</button> <button class="btn btn-sm btn-primary" onclick="dmSaveEntity(' + modelId + ')">保存</button></div></div>';
    projShowModalBox('新建实体', h);
  };
  window.dmSaveEntity = function (modelId) {
    var body = { modelId: modelId, entityCode: (document.getElementById('dmeCode').value || '').trim(), entityName: (document.getElementById('dmeName').value || '').trim(), level: parseInt(document.getElementById('dmeLevel').value), bizDefinition: (document.getElementById('dmeDef').value || '').trim() };
    if (!body.entityCode || !body.entityName) { toast('请填写编码与名称', 'error'); return; }
    apiPost('/api/datamodel/entity', body).then(function (res) {
      if (res && res.code === 0) { toast('已保存', 'success'); projCloseModalBox(); dmReopenModel(modelId); } else toast((res && res.msg) || '失败', 'error');
    }).catch(function () { toast('保存失败', 'error'); });
  };
  window.dmDelEntity = function (entityId, modelId) {
    DN.confirm('删除该实体及其全部属性？', { title: '删除实体', danger: true }).then(function (ok) {
      if (!ok) return;
      api('/api/datamodel/entity/' + entityId, { method: 'DELETE' }).then(function () { toast('已删除', 'success'); dmReopenModel(modelId); });
    });
  };
  // 属性编辑器(可加行/删行)
  window.dmEditAttrs = function (entityId, modelId) {
    api('/api/datamodel/model/' + modelId).then(function (res) {
      var m = res.data, ent = (m.entities || []).filter(function (e) { return e.id === entityId; })[0];
      var attrs = (ent && ent.attributes) || [];
      window.__dmAttrEdit = { entityId: entityId, modelId: modelId, rows: attrs.map(function (a) { return Object.assign({}, a); }) };
      renderAttrEditor();
    }).catch(function () { toast('加载属性失败', 'error'); });
  };
  function renderAttrEditor() {
    var st = window.__dmAttrEdit;
    var typeOpt = function (v) { return DATA_TYPES.map(function (t) { return '<option value="' + t + '"' + (v === t ? ' selected' : '') + '>' + t + '</option>'; }).join(''); };
    var rowsHtml = st.rows.map(function (a, i) {
      return '<tr>'
        + '<td><input class="dbsync-form-input dmA-code" style="width:110px;" value="' + esc(a.attrCode || '') + '"></td>'
        + '<td><input class="dbsync-form-input dmA-name" style="width:110px;" value="' + esc(a.attrName || '') + '"></td>'
        + '<td><select class="dbsync-form-select dmA-type" style="width:96px;">' + typeOpt(a.dataType) + '</select></td>'
        + '<td><input class="dbsync-form-input dmA-len" style="width:60px;" value="' + esc(a.dataLength || '') + '"></td>'
        + '<td style="text-align:center;"><input type="checkbox" class="dmA-pk"' + (a.isPk == 1 ? ' checked' : '') + '></td>'
        + '<td style="text-align:center;"><input type="checkbox" class="dmA-null"' + (a.isNullable != 0 ? ' checked' : '') + '></td>'
        + '<td><input class="dbsync-form-input dmA-elem" list="dmElementList" onchange="dmElemChanged(this)" style="width:120px;" value="' + esc(a.elementCode || '') + '" placeholder="选数据标准"></td>'
        + '<td><a href="#" onclick="dmAttrDelRow(' + i + ');return false;" style="color:var(--error);">×</a></td></tr>';
    }).join('');
    var h = '<div style="min-width:640px;">'
      + '<table class="dbsync-exec-table" style="width:100%;"><thead><tr><th>编码</th><th>名称</th><th>类型</th><th>长度</th><th>主键</th><th>可空</th><th>数据标准</th><th></th></tr></thead><tbody id="dmAttrRows">' + rowsHtml + '</tbody></table>'
      + '<div style="margin-top:8px;"><button class="btn btn-sm" onclick="dmAttrAddRow()">+ 添加属性</button></div>'
      + '<div style="text-align:right;margin-top:12px;"><button class="btn btn-sm" onclick="projCloseModalBox()">取消</button> <button class="btn btn-sm btn-primary" onclick="dmSaveAttrs()">保存属性</button></div></div>';
    projShowModalBox('编辑实体属性', h);
  }
  function collectAttrRows() {
    var rows = [];
    document.querySelectorAll('#dmAttrRows tr').forEach(function (tr) {
      var code = (tr.querySelector('.dmA-code').value || '').trim(); if (!code) return;
      rows.push({
        attrCode: code, attrName: (tr.querySelector('.dmA-name').value || '').trim(),
        dataType: tr.querySelector('.dmA-type').value, dataLength: (tr.querySelector('.dmA-len').value || '').trim() || null,
        isPk: tr.querySelector('.dmA-pk').checked ? 1 : 0, isNullable: tr.querySelector('.dmA-null').checked ? 1 : 0,
        elementCode: (tr.querySelector('.dmA-elem').value || '').trim() || null
      });
    });
    return rows;
  }
  window.dmAttrAddRow = function () { window.__dmAttrEdit.rows = collectAttrRows(); window.__dmAttrEdit.rows.push({ dataType: 'STRING', isNullable: 1 }); renderAttrEditor(); };
  window.dmAttrDelRow = function (i) { window.__dmAttrEdit.rows = collectAttrRows(); window.__dmAttrEdit.rows.splice(i, 1); renderAttrEditor(); };
  window.dmSaveAttrs = function () {
    var st = window.__dmAttrEdit, rows = collectAttrRows();
    apiPost('/api/datamodel/entity/' + st.entityId + '/attributes', rows).then(function (res) {
      if (res && res.code === 0) { toast('属性已保存', 'success'); projCloseModalBox(); dmReopenModel(st.modelId); } else toast((res && res.msg) || '失败', 'error');
    }).catch(function () { toast('保存失败', 'error'); });
  };
  function dmReopenModel(id) { if (window.__dmDrawer && window.__dmDrawer.close) window.__dmDrawer.close(); dmLoadModels(); setTimeout(function () { dmOpenModel(id); }, 80); }

  // ---------- 流转 ----------
  window.dmSubmit = function (id) {
    api('/api/datamodel/model/' + id + '/validate').then(function (res) {
      var v = (res && res.code === 0 && res.data) || { valid: true, errors: [], warnings: [] };
      var errs = v.errors || [], warns = v.warnings || [];
      var chk = '';
      if (errs.length) chk += '<div style="background:rgba(224,65,78,.08);border:1px solid var(--error);border-radius:var(--radius);padding:8px 10px;margin-bottom:8px;"><b style="color:var(--error);font-size:12px;">✗ 须修正(' + errs.length + ')，修正后方可提交</b><ul style="margin:4px 0 0;padding-left:18px;font-size:12px;color:var(--error);">' + errs.map(function (e) { return '<li>' + esc(e) + '</li>'; }).join('') + '</ul></div>';
      if (warns.length) chk += '<div style="background:rgba(245,159,0,.08);border:1px solid var(--warning,#f59f00);border-radius:var(--radius);padding:8px 10px;margin-bottom:8px;"><b style="color:#b8860b;font-size:12px;">⚠ 建议(' + warns.length + ')</b><ul style="margin:4px 0 0;padding-left:18px;font-size:12px;color:var(--text-regular);">' + warns.map(function (w) { return '<li>' + esc(w) + '</li>'; }).join('') + '</ul></div>';
      if (!errs.length && !warns.length) chk = '<div style="color:var(--success);font-size:12.5px;margin-bottom:8px;">✓ 规范校验通过</div>';
      var btn = errs.length ? '<button class="btn btn-sm btn-primary" disabled style="opacity:.5;cursor:not-allowed;">提交</button>' : '<button class="btn btn-sm btn-primary" onclick="dmDoSubmit(' + id + ')">提交</button>';
      var h = '<div style="min-width:400px;max-width:500px;">' + chk
        + '<div style="font-size:13px;color:var(--text-muted);margin-bottom:8px;">提交后模型进入「待审批」，由审批人决定发布。</div>'
        + '<textarea id="dmSubReason" class="dbsync-form-input" style="width:100%;min-height:64px;" placeholder="申请说明(可选)"></textarea>'
        + '<div style="text-align:right;margin-top:10px;"><button class="btn btn-sm" onclick="projCloseModalBox()">取消</button> ' + btn + '</div></div>';
      projShowModalBox('提交模型审批', h);
    }).catch(function () { toast('规范校验失败', 'error'); });
  };
  window.dmDoSubmit = function (id) {
    apiPost('/api/datamodel/model/' + id + '/submit', { reason: (document.getElementById('dmSubReason').value || '').trim() }).then(function (res) {
      if (res && res.code === 0) { toast('已提交审批', 'success'); projCloseModalBox(); dmLoadModels(); dmLoadChangeBadge(); } else toast((res && res.msg) || '提交失败', 'error');
    }).catch(function () { toast('提交失败', 'error'); });
  };
  function dmLoadChangeBadge() {
    api('/api/datamodel/changes?status=pending').then(function (res) {
      var n = (res && res.code === 0 && res.data) ? res.data.length : 0;
      var b = document.getElementById('dmChangeBadge'); if (b) b.innerHTML = n ? '<span style="background:var(--warning,#f59f00);color:#fff;border-radius:8px;padding:0 6px;">' + n + '</span>' : '';
    }).catch(function () {});
  }
  window.dmShowChanges = function () {
    api('/api/datamodel/changes').then(function (res) {
      var list = (res && res.code === 0 && res.data) || [];
      var rows = list.map(function (c) {
        var stp = c.status === 'pending' ? 'is-warn' : (c.status === 'approved' ? 'is-ok' : 'is-error');
        var stl = c.status === 'pending' ? '待审批' : (c.status === 'approved' ? '已通过' : '已驳回');
        var ops = c.status === 'pending'
          ? '<a href="#" data-perm="datamodel:approve" onclick="dmReview(' + c.id + ',\'approve\');return false;" style="color:#2f9e44;margin-right:8px;">通过</a><a href="#" data-perm="datamodel:approve" onclick="dmReview(' + c.id + ',\'reject\');return false;" style="color:var(--error);">驳回</a>'
          : (esc(c.reviewer || '') + (c.reviewComment ? ' · ' + esc(c.reviewComment) : ''));
        return '<tr><td><b>' + esc(c.modelName || ('#' + c.modelId)) + '</b><br><span style="font-size:var(--fs-xs);color:var(--text-muted);">' + esc(c.modelCode || '') + '</span></td><td>' + esc(c.changeType) + '</td><td style="font-size:12px;">' + esc(c.reason || '-') + '</td><td>' + esc(c.requestedBy || '') + '</td><td><span class="gov-pill ' + stp + '">' + stl + '</span></td><td style="white-space:nowrap;">' + ops + '</td></tr>';
      }).join('') || '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:20px;">暂无工单</td></tr>';
      var h = '<div style="min-width:660px;max-height:460px;overflow:auto;"><table class="dbsync-exec-table" style="width:100%;"><thead><tr><th>模型</th><th>类型</th><th>说明</th><th>申请人</th><th>状态</th><th>操作</th></tr></thead><tbody>' + rows + '</tbody></table></div>';
      projShowModalBox('模型审批工单', h);
      var box = document.querySelector('.proj-modal-box, [class*=modal]'); if (window.dnApplyBtnPerms && box) dnApplyBtnPerms(box);
    }).catch(function () { toast('加载工单失败', 'error'); });
  };
  window.dmReview = function (changeId, action) {
    var isReject = action === 'reject';
    var lab = isReject ? '驳回原因(必填):' : '审批通过意见(可选):';
    var h = '<div style="min-width:380px;max-width:440px;">'
      + '<div style="font-size:13px;color:var(--text-muted);margin-bottom:8px;">' + lab + '</div>'
      + '<textarea id="dmReviewComment" class="dbsync-form-input" style="width:100%;min-height:72px;" placeholder="' + (isReject ? '说明驳回原因, 便于申请人修正 (Ctrl+Enter 提交)' : '可选 (Ctrl+Enter 提交)') + '" onkeydown="if(event.ctrlKey&&event.key===\'Enter\'){event.preventDefault();dmReviewSubmit(' + changeId + ',\'' + action + '\');}"></textarea>'
      + '<div style="text-align:right;margin-top:12px;"><button class="btn btn-sm" onclick="projCloseModalBox()">取消</button> '
      + '<button class="btn btn-sm btn-primary" onclick="dmReviewSubmit(' + changeId + ',\'' + action + '\')">' + (isReject ? '确认驳回' : '确认通过') + '</button></div></div>';
    projShowModalBox(isReject ? '驳回模型变更' : '通过模型变更', h);
    setTimeout(function () { var t = document.getElementById('dmReviewComment'); if (t) t.focus(); }, 50);
  };
  window.dmReviewSubmit = function (changeId, action) {
    var el = document.getElementById('dmReviewComment');
    var comment = el ? el.value.trim() : '';
    if (action === 'reject' && !comment) { toast('请填写驳回原因', 'error'); return; }
    apiPost('/api/datamodel/change/' + changeId + '/' + action, { comment: comment }).then(function (res) {
      if (res && res.code === 0) { toast(action === 'approve' ? '已通过, 模型已发布' : '已驳回', 'success'); projCloseModalBox(); dmLoadModels(); dmLoadChangeBadge(); }
      else toast((res && res.msg) || '操作失败', 'error');
    }).catch(function () { toast('操作失败', 'error'); });
  };

  // ---------- 生成 ----------
  window.dmGenLogical = function (id) {
    DN.confirm('由该业务模型生成逻辑模型？将复制业务对象为逻辑实体(L3→L4)。', { title: '生成逻辑模型' }).then(function (ok) {
      if (!ok) return;
      apiPost('/api/datamodel/model/' + id + '/generate-logical', {}).then(function (res) {
        if (res && res.code === 0) { toast('已生成逻辑模型: ' + (res.data && res.data.modelName), 'success'); dmLoadModels(); } else toast((res && res.msg) || '生成失败', 'error');
      }).catch(function () { toast('生成失败', 'error'); });
    });
  };
  window.dmGenPhysical = function (id) {
    DN.confirm('由该逻辑模型生成物理模型？将复制实体/属性并按规范映射物理表名与字段。', { title: '生成物理模型' }).then(function (ok) {
      if (!ok) return;
      apiPost('/api/datamodel/model/' + id + '/generate-physical', {}).then(function (res) {
        if (res && res.code === 0) { toast('已生成物理模型: ' + (res.data && res.data.modelName), 'success'); dmLoadModels(); } else toast((res && res.msg) || '生成失败', 'error');
      }).catch(function () { toast('生成失败', 'error'); });
    });
  };
  window.dmShowDdl = function (id) {
    api('/api/datamodel/model/' + id + '/ddl').then(function (res) {
      if (!res || res.code !== 0) { toast('生成失败', 'error'); return; }
      var ddl = res.data || '-- 无实体';
      var h = '<div style="min-width:600px;"><div style="font-size:12px;color:var(--text-muted);margin-bottom:6px;">建表 DDL(可复制到数据库执行)</div>'
        + '<pre style="background:var(--bg-hover);border:1px solid var(--border);border-radius:var(--radius);padding:12px;max-height:420px;overflow:auto;font-size:12px;white-space:pre-wrap;">' + esc(ddl) + '</pre>'
        + '<div style="text-align:right;margin-top:10px;"><button class="btn btn-sm btn-primary" onclick="dmCopyDdl()">复制</button> <button class="btn btn-sm" onclick="dmGotoDevelopWithDdl()">去数据开发</button> <button class="btn btn-sm" onclick="projCloseModalBox()">关闭</button></div></div>';
      window.__dmDdl = ddl;
      projShowModalBox('物理模型 DDL', h);
    }).catch(function () { toast('生成失败', 'error'); });
  };
  window.dmCopyDdl = function () { try { navigator.clipboard.writeText(window.__dmDdl || ''); toast('已复制', 'success'); } catch (e) { toast('复制失败', 'error'); } };
  // 闭合 建模→DDL→开发 链路: 复制 DDL 并跳数据开发, 用户新建脚本粘贴即可写 ETL
  window.dmGotoDevelopWithDdl = function () {
    try { navigator.clipboard.writeText(window.__dmDdl || ''); } catch (e) {}
    projCloseModalBox();
    if (window.navigateTo) navigateTo('develop');
    toast('DDL 已复制，到数据开发新建脚本粘贴即可', 'success');
  };

  // ---------- 逆向导入(物理表 → 物理模型) ----------
  window.dmReverseImport = function () {
    api('/api/metadata-center/tables?limit=300').then(function (res) {
      var d = (res && res.code === 0) ? res.data : null;
      var list = Array.isArray(d) ? d : (d && (d.list || d.rows || d.records) || []);
      list = list.filter(function (t) { return t && t.id != null; });   // 过滤缺元数据ID的记录, 防逆向请求 tableMetaId=undefined
      var rows = list.map(function (t) {
        return '<tr><td style="font-family:monospace;">' + esc(t.databaseName || '') + '.' + esc(t.tableName || '') + '</td><td style="font-size:12px;">' + esc(t.tableComment || '') + '</td><td><a href="#" onclick="dmDoReverse(' + t.id + ');return false;" style="color:var(--primary);">逆向</a></td></tr>';
      }).join('') || '<tr><td colspan="3" style="color:var(--text-muted);text-align:center;padding:16px;">无已采集物理表，请先在「数据地图」采集表元数据</td></tr>';
      var h = '<div style="min-width:540px;max-height:440px;overflow:auto;"><div style="font-size:12px;color:var(--text-muted);margin-bottom:8px;">选择已采集的物理表，逆向生成物理模型(表→实体, 列→属性, 类型反归一化, 主键/可空识别)。' + (DM.curSubject != null ? ' 归属当前主题域。' : '') + '</div>'
        + '<table class="dbsync-exec-table" style="width:100%;"><thead><tr><th>库.表</th><th>注释</th><th>操作</th></tr></thead><tbody>' + rows + '</tbody></table></div>';
      projShowModalBox('逆向导入物理模型', h);
    }).catch(function () { toast('加载物理表失败', 'error'); });
  };
  window.dmDoReverse = function (tableMetaId) {
    apiPost('/api/datamodel/reverse', { tableMetaId: tableMetaId, subjectId: DM.curSubject }).then(function (res) {
      if (res && res.code === 0) { toast('已逆向生成: ' + (res.data && res.data.modelName), 'success'); projCloseModalBox(); DM.curType = ''; renderTypeTabs(); dmLoadModels(); }
      else toast((res && res.msg) || '逆向失败', 'error');
    }).catch(function () { toast('逆向失败', 'error'); });
  };
  window.dmElemChanged = function (input) {
    var code = (input.value || '').trim(); if (!code) return;
    var el = DM.elements.filter(function (e) { return e.elementCode === code; })[0];
    if (!el) return;
    var tr = input.closest('tr'); if (!tr) return;
    if (el.dataType) {
      var ts = tr.querySelector('.dmA-type'), dt = String(el.dataType).toUpperCase();
      for (var i = 0; i < ts.options.length; i++) { if (ts.options[i].value === dt || dt.indexOf(ts.options[i].value) >= 0) { ts.value = ts.options[i].value; break; } }
    }
    if (el.length) { var le = tr.querySelector('.dmA-len'); if (le) le.value = el.length; }
  };

  // ---------- 资产落地(物理模型 → 数据地图) ----------
  window.dmPublishAsset = function (id) {
    DN.confirm('将该物理模型落地为数据资产？实体/属性将注册到「数据地图」(库 model_<编码>)，可被治理/血缘消费。', { title: '落地数据资产' }).then(function (ok) {
      if (!ok) return;
      apiPost('/api/datamodel/model/' + id + '/publish-asset', {}).then(function (res) {
        if (res && res.code === 0) {
          var db = res.data.database;
          // 落地成功→引导前往数据地图(闭合 建模→落地→地图 链路, 库已在 DB, 直接带库名搜索)
          DN.confirm('已落地 ' + res.data.tables + ' 表/' + res.data.columns + ' 字段，回填分级 ' + (res.data.gradedColumns || 0) + ' 列，派生质量规则建议 ' + (res.data.qualityRules || 0) + ' 条。前往数据地图查看？',
            { title: '落地成功', okText: '前往数据地图', cancelText: '留在本页' }).then(function (go) {
              if (go && window.navigateTo) navigateTo('catalog', { datamapSearch: db });
            });
        } else toast((res && res.msg) || '落地失败', 'error');
      }).catch(function () { toast('落地失败', 'error'); });
    });
  };

  // ---------- ER 图(实体-属性-关系可视化) ----------
  window.dmShowER = function (modelId) {
    api('/api/datamodel/model/' + modelId).then(function (res) {
      if (!res || res.code !== 0 || !res.data) { toast('加载ER图失败', 'error'); return; }
      var m = res.data; var ents = m.entities || [], rels = m.relations || [];
      if (!ents.length) { toast('该模型暂无实体', 'error'); return; }
      var boxes = ents.map(function (e) {
        var attrs = (e.attributes || []).map(function (a) {
          return '<div style="padding:2px 8px;font-size:11px;border-top:1px solid var(--border);white-space:nowrap;">' + (a.isPk == 1 ? '🔑 ' : '') + esc(a.attrCode) + ' <span style="color:var(--text-faint);">' + esc(a.dataType || '') + '</span></div>';
        }).join('');
        return '<div class="dm-er-ent" data-eid="' + e.id + '" style="display:inline-block;vertical-align:top;border:1.5px solid var(--primary);border-radius:6px;margin:14px;min-width:148px;background:var(--bg-card);box-shadow:var(--shadow-sm);position:relative;z-index:2;">'
          + '<div style="background:var(--primary);color:#fff;padding:4px 10px;font-weight:600;font-size:12px;border-radius:4px 4px 0 0;">' + esc(e.entityName) + '</div>' + attrs + '</div>';
      }).join('');
      var relList = rels.length ? '<div style="margin-top:12px;font-size:12px;"><b>关系</b>' + rels.map(function (r) {
        var sn = (ents.filter(function (e) { return e.id === r.sourceEntityId; })[0] || {}).entityName || ('#' + r.sourceEntityId);
        var tn = (ents.filter(function (e) { return e.id === r.targetEntityId; })[0] || {}).entityName || ('#' + r.targetEntityId);
        return '<div style="padding:2px 0;">' + esc(sn) + ' <span style="color:var(--primary);font-weight:600;">—' + esc(r.relationType) + '→</span> ' + esc(tn) + (r.description ? ' <span style="color:var(--text-muted);">(' + esc(r.description) + ')</span>' : '') + '</div>';
      }).join('') + '</div>' : '<div style="margin-top:10px;color:var(--text-muted);font-size:12px;">暂无关系</div>';
      var h = '<div style="min-width:580px;max-width:90vw;max-height:62vh;overflow:auto;"><div id="dmErCanvas" style="position:relative;"><svg id="dmErSvg" style="position:absolute;top:0;left:0;z-index:1;pointer-events:none;overflow:visible;"></svg>' + boxes + '</div>' + relList + '</div>';
      projShowModalBox('ER 图 · ' + esc(m.modelName), h);
      setTimeout(function () { dmDrawErLines(rels); }, 90);
    }).catch(function () { toast('加载ER图失败', 'error'); });
  };
  function dmDrawErLines(rels) {
    var canvas = document.getElementById('dmErCanvas'), svg = document.getElementById('dmErSvg');
    if (!canvas || !svg || !rels || !rels.length) return;
    var cr = canvas.getBoundingClientRect();
    svg.setAttribute('width', canvas.scrollWidth); svg.setAttribute('height', canvas.scrollHeight);
    function box(eid) { return canvas.querySelector('.dm-er-ent[data-eid="' + eid + '"]'); }
    var lines = '';
    rels.forEach(function (r) {
      var s = box(r.sourceEntityId), t = box(r.targetEntityId); if (!s || !t) return;
      var sr = s.getBoundingClientRect(), tr = t.getBoundingClientRect();
      var x1 = sr.left + sr.width / 2 - cr.left, y1 = sr.top + sr.height / 2 - cr.top;
      var x2 = tr.left + tr.width / 2 - cr.left, y2 = tr.top + tr.height / 2 - cr.top;
      lines += '<line x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 + '" stroke="var(--primary)" stroke-width="1.5" stroke-dasharray="4 3"/>';
      lines += '<circle cx="' + x2 + '" cy="' + y2 + '" r="3.5" fill="var(--primary)"/>';
    });
    svg.innerHTML = lines;
  }

  // ---------- 版本历史 ----------
  window.dmShowVersions = function (modelId) {
    api('/api/datamodel/model/' + modelId + '/versions').then(function (res) {
      var vers = (res && res.code === 0 && res.data) || [];
      var rows = vers.map(function (v) {
        return '<tr><td><input type="checkbox" class="dmVerChk" value="' + v.id + '"></td><td><b>v' + v.version + '</b></td><td style="font-size:12px;">' + esc(String(v.publishedAt || '').replace('T', ' ').slice(0, 16)) + '</td><td>' + esc(v.publishedBy || '') + '</td><td style="font-size:12px;">' + esc(v.changeSummary || '-') + '</td><td><a href="#" onclick="dmViewVersion(' + v.id + ');return false;" style="color:var(--primary);">查看快照</a></td></tr>';
      }).join('') || '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:18px;">暂无发布版本(模型审批通过后产生版本快照)</td></tr>';
      var cmp = vers.length >= 2 ? '<div style="margin-bottom:8px;"><button class="btn btn-sm" onclick="dmCompareSelected()">⇄ 对比所选两版</button> <span style="font-size:12px;color:var(--text-muted);">勾选两个版本对比字段差异</span></div>' : '';
      var h = '<div style="min-width:560px;max-height:440px;overflow:auto;">' + cmp + '<table class="dbsync-exec-table" style="width:100%;"><thead><tr><th style="width:28px;"></th><th>版本</th><th>发布时间</th><th>发布人</th><th>说明</th><th></th></tr></thead><tbody>' + rows + '</tbody></table></div>';
      projShowModalBox('版本历史', h);
    }).catch(function () { toast('加载版本历史失败', 'error'); });
  };
  window.dmCompareSelected = function () {
    var ids = Array.prototype.slice.call(document.querySelectorAll('.dmVerChk:checked')).map(function (c) { return parseInt(c.value); });
    if (ids.length !== 2) { toast('请勾选两个版本', 'error'); return; }
    ids.sort(function (a, b) { return a - b; });   // 旧→新, 使「新增/删除」方向直观
    api('/api/datamodel/compare?from=' + ids[0] + '&to=' + ids[1]).then(function (res) {
      if (!res || res.code !== 0) { toast((res && res.msg) || '对比失败', 'error'); return; }
      var d = res.data;
      var h = '<div style="min-width:480px;max-height:440px;overflow:auto;font-size:13px;"><div style="margin-bottom:10px;color:var(--text-muted);">v' + d.fromVersion + ' → v' + d.toVersion + '</div>';
      if (d.identical) { h += '<div style="color:var(--success);">✓ 两版本无差异</div>'; }
      else {
        if (d.addedEntities && d.addedEntities.length) h += '<div style="margin-bottom:6px;"><b style="color:#2f9e44;">＋ 新增实体</b>: ' + d.addedEntities.map(esc).join('、') + '</div>';
        if (d.removedEntities && d.removedEntities.length) h += '<div style="margin-bottom:6px;"><b style="color:var(--error);">－ 删除实体</b>: ' + d.removedEntities.map(esc).join('、') + '</div>';
        (d.entityDiffs || []).forEach(function (ed) {
          h += '<div style="border:1px solid var(--border);border-radius:var(--radius);padding:8px 10px;margin-bottom:8px;"><b>' + esc(ed.entity) + '</b>';
          if (ed.addedAttrs && ed.addedAttrs.length) h += '<div style="color:#2f9e44;">＋ ' + ed.addedAttrs.map(esc).join('、') + '</div>';
          if (ed.removedAttrs && ed.removedAttrs.length) h += '<div style="color:var(--error);">－ ' + ed.removedAttrs.map(esc).join('、') + '</div>';
          (ed.changedAttrs || []).forEach(function (ch) { h += '<div style="color:#b8860b;">～ ' + esc(ch.attr) + ': ' + esc(ch.from) + ' → ' + esc(ch.to) + '</div>'; });
          h += '</div>';
        });
      }
      h += '</div>';
      projShowModalBox('版本对比 v' + d.fromVersion + ' → v' + d.toVersion, h);
    }).catch(function () { toast('对比失败', 'error'); });
  };
  window.dmViewVersion = function (vid) {
    api('/api/datamodel/version/' + vid).then(function (res) {
      if (!res || res.code !== 0 || !res.data) { toast('加载版本快照失败', 'error'); return; }
      var v = res.data; var snap = {}; try { snap = JSON.parse(v.snapshotJson || '{}'); } catch (e) {}
      var ents = (snap.entities || []).map(function (e) {
        return '<div style="border:1px solid var(--border);border-radius:var(--radius);padding:8px 10px;margin-bottom:8px;"><b>' + esc(e.entityName) + '</b> <span style="font-size:11px;color:var(--text-muted);">L' + (e.level || 4) + '</span><div style="font-size:12px;margin-top:4px;">' + ((e.attributes || []).map(function (a) { return (a.isPk == 1 ? '🔑' : '') + esc(a.attrCode) + ':' + esc(a.dataType || ''); }).join(' , ') || '无属性') + '</div></div>';
      }).join('') || '<div style="color:var(--text-muted);">无实体</div>';
      var h = '<div style="min-width:480px;max-height:440px;overflow:auto;"><div style="font-size:13px;margin-bottom:10px;"><b>v' + v.version + '</b> · ' + esc(String(v.publishedAt || '').replace('T', ' ').slice(0, 16)) + ' · 发布人 ' + esc(v.publishedBy || '') + (v.changeSummary ? '<div style="color:var(--text-muted);margin-top:2px;">' + esc(v.changeSummary) + '</div>' : '') + '</div>' + ents + '</div>';
      projShowModalBox('版本 v' + v.version + ' 快照', h);
    }).catch(function () { toast('加载版本快照失败', 'error'); });
  };

  // ---------- 建模覆盖度看板 ----------
  window.dmShowDashboard = function () {
    api('/api/datamodel/dashboard').then(function (res) {
      var d = (res && res.code === 0 && res.data) || {};
      function card(label, val, color) { return '<div style="flex:1;min-width:110px;border:1px solid var(--border);border-radius:var(--radius);padding:12px 14px;"><div style="font-size:22px;font-weight:700;color:' + (color || 'var(--text-primary)') + ';">' + val + '</div><div style="font-size:12px;color:var(--text-muted);margin-top:2px;">' + label + '</div></div>'; }
      var bt = d.byType || {}, bs = d.byStatus || {};
      var typeBar = Object.keys(bt).map(function (k) { return '<span style="margin-right:14px;">' + (TYPE_LABEL[k] || k) + ': <b>' + bt[k] + '</b></span>'; }).join('');
      var statusBar = Object.keys(bs).map(function (k) { return '<span style="margin-right:14px;">' + (STATUS_LABEL[k] || k) + ': <b>' + bs[k] + '</b></span>'; }).join('');
      var h = '<div style="min-width:580px;">'
        + '<div style="display:flex;gap:10px;flex-wrap:wrap;margin-bottom:14px;">'
        + card('模型总数', d.totalModels || 0)
        + card('已发布', d.published || 0, 'var(--success)')
        + card('落地资产表', d.landedTables || 0, '#1971c2')
        + card('标准覆盖率', (d.standardCoverage || 0) + '%', '#e8590c')
        + card('待审工单', d.pendingChanges || 0, '#b8860b')
        + '</div>'
        + '<div style="font-size:13px;margin-bottom:8px;"><b>类型分布</b>　' + typeBar + '</div>'
        + '<div style="font-size:13px;margin-bottom:8px;"><b>状态分布</b>　' + statusBar + '</div>'
        + '<div style="font-size:12.5px;color:var(--text-muted);border-top:1px solid var(--border);padding-top:8px;">实体 ' + (d.entities || 0) + ' · 关系 ' + (d.relations || 0) + ' · 属性 ' + (d.totalAttributes || 0) + '（绑数据标准 ' + (d.boundAttributes || 0) + '） · 覆盖主题域 ' + (d.subjectsWithModels || 0) + ' 个</div>'
        + '</div>';
      projShowModalBox('建模覆盖度看板', h);
    }).catch(function () { toast('看板加载失败', 'error'); });
  };
})();
