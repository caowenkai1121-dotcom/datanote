/* 数据模型 — 三层建模(业务/逻辑/物理) + L1-L5 主题域分层 + 数仓分层 + 申请审批流转 + 模型生成。
   独立顶部菜单(datamodel)。权限: datamodel:edit(建模) / datamodel:approve(审批)。 */
(function () {
  var DM = { subjects: [], models: [], curType: '', curSubject: null };
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
      + '    <button class="btn btn-sm btn-primary" data-perm="datamodel:edit" onclick="dmNewModel()" style="margin-left:auto;">+ 新建模型</button>'
      + '  </div>'
      + '  <div id="dmModelList"></div>'
      + '</div></div>';
    renderTypeTabs();
    dmLoadSubjects();
    dmLoadModels();
    dmLoadChangeBadge();
  };

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
      if (m.modelType === 'LOGIC') ops += '<a href="#" data-perm="datamodel:edit" onclick="dmGenPhysical(' + m.id + ');return false;" style="color:#2f9e44;margin-right:8px;">生成物理</a>';
      if (m.modelType === 'PHYS') ops += '<a href="#" onclick="dmShowDdl(' + m.id + ');return false;" style="color:#e8590c;margin-right:8px;">DDL</a>';
      ops += '<a href="#" data-perm="datamodel:edit" onclick="dmDeleteModel(' + m.id + ');return false;" style="color:var(--error);">删除</a>';
      h += '<tr><td style="font-family:monospace;">' + esc(m.modelCode) + '</td><td><b>' + esc(m.modelName) + '</b></td><td>' + typeBadge + '</td>'
        + '<td>' + (m.dwLayer ? esc(m.dwLayer) : '-') + '</td><td style="font-size:12px;">' + esc(subName) + '</td><td>' + st + '</td>'
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
        + '</div><div id="dmEntityBox"></div>';
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
        + '<div style="display:flex;align-items:center;gap:8px;margin-bottom:6px;"><b>' + esc(e.entityName) + '</b> <span style="font-family:monospace;font-size:12px;color:var(--text-muted);">' + esc(e.entityCode) + '</span> <span style="font-size:var(--fs-xs);color:var(--primary);">L' + (e.level || 4) + '</span>'
        + (e.physicalTable ? ' <span style="font-size:var(--fs-xs);color:var(--text-faint);">→ ' + esc(e.physicalTable) + '</span>' : '')
        + (canEdit ? '<a href="#" data-perm="datamodel:edit" onclick="dmEditAttrs(' + e.id + ',' + m.id + ');return false;" style="margin-left:auto;font-size:12px;color:var(--primary);">编辑属性</a> <a href="#" data-perm="datamodel:edit" onclick="dmDelEntity(' + e.id + ',' + m.id + ');return false;" style="font-size:12px;color:var(--error);">删除</a>' : '')
        + '</div>'
        + '<table class="dbsync-exec-table" style="width:100%;font-size:12px;"><thead><tr><th>属性</th><th>名称</th><th>类型</th><th>可空</th><th>数据标准</th></tr></thead><tbody>' + rows + '</tbody></table></div>';
    }).join('');
    if (window.dnApplyBtnPerms) dnApplyBtnPerms(box);
  }
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
    });
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
        + '<td><input class="dbsync-form-input dmA-elem" style="width:100px;" value="' + esc(a.elementCode || '') + '" placeholder="数据标准码"></td>'
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
    var h = '<div style="min-width:340px;"><div style="font-size:13px;color:var(--text-muted);margin-bottom:8px;">提交后模型进入「待审批」，由审批人决定发布。</div>'
      + '<textarea id="dmSubReason" class="dbsync-form-input" style="width:100%;min-height:72px;" placeholder="申请说明(可选)"></textarea>'
      + '<div style="text-align:right;margin-top:10px;"><button class="btn btn-sm" onclick="projCloseModalBox()">取消</button> <button class="btn btn-sm btn-primary" onclick="dmDoSubmit(' + id + ')">提交</button></div></div>';
    projShowModalBox('提交模型审批', h);
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
    });
  };
  window.dmReview = function (changeId, action) {
    var comment = prompt(action === 'approve' ? '审批通过意见(可选):' : '驳回原因:', '');
    if (comment === null) return;
    apiPost('/api/datamodel/change/' + changeId + '/' + action, { comment: comment }).then(function (res) {
      if (res && res.code === 0) { toast(action === 'approve' ? '已通过, 模型已发布' : '已驳回', 'success'); projCloseModalBox(); dmLoadModels(); dmLoadChangeBadge(); }
      else toast((res && res.msg) || '操作失败', 'error');
    }).catch(function () { toast('操作失败', 'error'); });
  };

  // ---------- 生成 ----------
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
        + '<div style="text-align:right;margin-top:10px;"><button class="btn btn-sm btn-primary" onclick="dmCopyDdl()">复制</button> <button class="btn btn-sm" onclick="projCloseModalBox()">关闭</button></div></div>';
      window.__dmDdl = ddl;
      projShowModalBox('物理模型 DDL', h);
    }).catch(function () { toast('生成失败', 'error'); });
  };
  window.dmCopyDdl = function () { try { navigator.clipboard.writeText(window.__dmDdl || ''); toast('已复制', 'success'); } catch (e) { toast('复制失败', 'error'); } };
})();
