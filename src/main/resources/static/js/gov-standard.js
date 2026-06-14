/* 治理模块：数据标准（M7 数据元 + 命名词根 + 码表 + 落标稽核） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var API = '/api/gov/standard';
  var TABS = [
    { key: 'element', label: '数据元' },
    { key: 'root', label: '命名词根' },
    { key: 'dict', label: '码表' },
    { key: 'check', label: '落标稽核' }
  ];

  // 枚举字典（手输 → 下拉）
  var SENSITIVE_TYPES = ['PHONE', 'EMAIL', 'ID_CARD', 'BANK_CARD', 'USCC'];
  var ROOT_CATEGORIES = ['业务', '技术', '通用', '时间', '地区', '状态', '金额', '数量'];
  var DATA_TYPES = ['VARCHAR', 'CHAR', 'TEXT', 'INT', 'BIGINT', 'DECIMAL', 'DOUBLE', 'DATE', 'DATETIME', 'TIMESTAMP', 'BOOLEAN'];
  var securityLevels = ['公开', '内部', '秘密', '机密']; // 兜底，加载后用方案密级覆盖

  // 落标率格式化：保留最多 1 位小数，避免后端浮点 83.33333% 难看
  function fmtRate(v) { if (v == null) return '-'; var n = Number(v); if (isNaN(n)) return '-'; return (Math.round(n * 10) / 10) + '%'; }

  // 超长文本截断 + title 悬浮全文，避免列宽被撑破；返回 Node
  function truncCell(text, max) {
    var s = text == null ? '' : String(text);
    if (!s) return '';
    max = max || 40;
    if (s.length <= max) return DN.h('span', { text: s });
    return DN.h('span', { text: s.slice(0, max) + '…', title: s, style: 'cursor:help' });
  }

  // 提交按钮防重复点击：执行期禁用 + 文案改“处理中…”，promise 结束后恢复；同时按钮自身做 in-flight 去重
  function guardSubmit(btn, busyText, task) {
    if (btn._busy) return;                 // 去重：忽略执行中的二次点击
    btn._busy = true;
    var old = btn.textContent;
    btn.classList.add('is-disabled');
    btn.style.pointerEvents = 'none';
    btn.style.opacity = '0.6';
    btn.textContent = busyText || '处理中…';
    function done() {
      btn._busy = false;
      btn.classList.remove('is-disabled');
      btn.style.pointerEvents = '';
      btn.style.opacity = '';
      btn.textContent = old;
    }
    var p = task();
    if (p && typeof p.then === 'function') p.then(done, done); else done();
  }

  // R25 联动：概览磁贴不再是纯展示死胡同 —— 点击切到本模块对应 Tab。
  // 渲染器进入时把内部 Tab 切换函数挂到 _activeGoTab，供概览(buildStdOverview)调用。
  var _activeGoTab = null;
  function goTab(key) { if (typeof _activeGoTab === 'function') _activeGoTab(key); }

  // 数据标准总览: 数据元/词根/码表/最新落标率/定密率/敏感标注率 聚合磁贴(6 个)
  function buildStdOverview(box) {
    if (box._loading) return;             // 去重：避免重复并发拉取(快速重进/重复调用)
    box._loading = true;
    box.innerHTML = '';
    box.appendChild(DN.skeleton(2));      // 加载骨架
    Promise.all([
      DN.get(API + '/elements').catch(function () { return []; }),
      DN.get(API + '/roots').catch(function () { return []; }),
      DN.get(API + '/dicts').catch(function () { return []; }),
      DN.get(API + '/check/runs').catch(function () { return []; })
    ]).then(function (r) {
      box._loading = false;
      if (!document.body.contains(box)) return;
      var els = (r && r[0]) || [], roots = (r && r[1]) || [], dicts = (r && r[2]) || [], runs = (r && r[3]) || [];
      var latest = runs.slice().sort(function (a, b) { return (b.id || 0) - (a.id || 0); })[0];
      var rate = latest && latest.passRate != null ? Number(latest.passRate) : null;
      var rTone = rate == null ? 'muted' : rate >= 80 ? 'ok' : rate >= 60 ? 'warn' : 'err';
      // 数据元覆盖度: 已定密级/已标敏感类型 的占比(原"落地概览"卡迁来)
      var elTotal = els.length;
      var sensCnt = els.filter(function (e) { return e.sensitiveType; }).length;
      var secCnt = els.filter(function (e) { return e.securityLevel; }).length;
      var sensRate = Math.round(sensCnt / (elTotal || 1) * 100);
      var secRate = Math.round(secCnt / (elTotal || 1) * 100);
      box.innerHTML = '';
      box.appendChild(DN.statRow([
        { icon: 'list', label: '数据元', value: els.length, title: '查看数据元', onClick: function () { goTab('element'); } },
        { icon: 'tag', label: '命名词根', value: roots.length, title: '查看命名词根', onClick: function () { goTab('root'); } },
        { icon: 'grid', label: '码表', value: dicts.length, title: '查看码表', onClick: function () { goTab('dict'); } },
        { icon: 'check', label: '最新落标率', value: fmtRate(rate), tone: rTone, sub: latest ? ('稽核#' + latest.id) : '尚未稽核', title: '查看落标稽核', onClick: function () { goTab('check'); } },
        { icon: 'shield', label: '数据元定密率', value: secRate + '%', tone: secRate >= 60 ? 'ok' : 'warn', sub: secCnt + '/' + elTotal + ' 已定密', title: '去数据元补全密级', onClick: function () { goTab('element'); } },
        { icon: 'list', label: '敏感标注率', value: sensRate + '%', tone: sensRate >= 50 ? 'ok' : 'warn', sub: sensCnt + '/' + elTotal + ' 已标', title: '去数据元补全敏感标注', onClick: function () { goTab('element'); } }
      ]));
    }).catch(function () {                 // 兜底：内层均已 catch，理论不会进；防御性错误重试
      box._loading = false;
      if (!document.body.contains(box)) return;
      box.innerHTML = '';
      box.appendChild(DN.errorBox('总览加载失败', function () { buildStdOverview(box); }));
    });
  }

  window.GOV_RENDERERS.standard = function (c) {
    // R25：每次进入读取联动上下文（ctx.tab 指定初始 Tab，供概览磁贴/外部深链下钻）
    var ctx = window.__govCtx || {};

    // 预取密级方案（落标/数据元密级下拉用），失败保留兜底
    DN.get('/api/gov/classification/levels').then(function (rows) {
      if (rows && rows.length) securityLevels = rows.map(function (r) { return r.levelName; });
    }).catch(function () {});

    var ov = DN.h('div', { id: 'stdOverview', style: 'margin-bottom:14px' });
    c.appendChild(ov);
    buildStdOverview(ov);

    var sub = DN.h('div', { class: 'gov-desc', id: 'stdSub', style: 'display:flex;gap:8px' });
    var body = DN.h('div', { id: 'stdBody' });
    var tabEls = {};
    // Tab 切换：更新高亮 + 渲染对应子页；抽成函数供概览磁贴/深链调用
    function switchTab(key) {
      if (!tabEls[key]) key = 'element';
      Object.keys(tabEls).forEach(function (k) { tabEls[k].className = 'btn'; });
      tabEls[key].className = 'btn btn-primary';
      renderSub(key, body);
    }
    TABS.forEach(function (t) {
      var a = DN.h('a', {
        class: 'btn', href: 'javascript:void(0)', text: t.label,
        onclick: function () { switchTab(t.key); }
      });
      tabEls[t.key] = a;
      sub.appendChild(a);
    });
    c.appendChild(sub);
    c.appendChild(body);
    _activeGoTab = switchTab;   // 概览磁贴点击经此切 Tab，消除纯展示死胡同
    var initTab = (ctx.tab && tabEls[ctx.tab]) ? ctx.tab : 'element';
    switchTab(initTab);
  };

  function renderSub(key, body) {
    body.innerHTML = '';
    if (key === 'element') renderElements(body);
    else if (key === 'root') renderRoots(body);
    else if (key === 'dict') renderDicts(body);
    else if (key === 'check') renderCheck(body);
  }

  // ========== 表单控件 ==========
  function formRow(label, control) {
    var row = DN.h('div', { class: 'ds-form-row' });
    row.appendChild(DN.h('label', { text: label }));
    row.appendChild(control);
    return row;
  }
  function input(ph) { return DN.h('input', { class: 'dn-form-input', placeholder: ph }); }
  function select(opts, placeholder) {
    var s = DN.h('select', { class: 'dn-form-select' });
    if (placeholder) s.appendChild(DN.h('option', { value: '', text: placeholder }));
    opts.forEach(function (o) { s.appendChild(DN.h('option', { value: o, text: o })); });
    return s;
  }

  /** 行级编辑链接：点击后由调用方回填表单进入编辑态 */
  function editLink(fn) {
    return DN.h('a', { href: 'javascript:void(0)', class: 'btn btn-sm', text: '编辑', 'data-perm': 'governance:standard', onclick: fn });
  }

  /** 下拉回显：值不在既有选项时临时补一个 option，避免编辑回显丢失原值 */
  function setSelect(sel, val) {
    val = val == null ? '' : String(val);
    sel.value = val;
    if (val && sel.value !== val) {
      sel.appendChild(DN.h('option', { value: val, text: val }));
      sel.value = val;
    }
  }

  /** 操作列容器：编辑 + 删除并排 */
  function opCell(nodes) {
    var span = DN.h('span', { style: 'display:inline-flex;gap:6px;white-space:nowrap' });
    nodes.forEach(function (n) { span.appendChild(n); });
    return span;
  }

  /** 内联确认删除链接（不用 window.confirm），返回 Node 供 DN.table 渲染 */
  function delLink(delFn, reload) {
    var a = DN.h('a', { href: 'javascript:void(0)', class: 'btn btn-sm btn-danger', text: '删除', 'data-perm': 'governance:standard' });
    a.onclick = function () {
      if (a.getAttribute('data-confirm') === '1') {
        if (a._busy) return;             // 删除请求去重，防双击重复删除
        a._busy = true;
        delFn().then(function () { DN.toast('已删除', 'ok'); reload(); })
          .catch(function (e) { a._busy = false; DN.toast(e && e.message || '删除失败', 'err'); });
        return;
      }
      a.setAttribute('data-confirm', '1');
      a.textContent = '确认删除？';
      setTimeout(function () { if (a.parentNode) { a.removeAttribute('data-confirm'); a.textContent = '删除'; } }, 2500);
    };
    return a;
  }

  // 数据标准影响分析: 反查引用该数据元的模型属性(联动数据模型模块)
  function govStdImpact(code) {
    DN.get('/api/datamodel/standard-impact?elementCode=' + encodeURIComponent(code)).then(function (data) {
      var rows = data || [];
      var tbl = rows.length
        ? '<table class="dbsync-exec-table" style="width:100%;"><thead><tr><th>模型</th><th>类型</th><th>状态</th><th>实体</th><th>属性</th><th>当前类型</th></tr></thead><tbody>'
          + rows.map(function (x) {
            return '<tr><td><b>' + DN.esc(x.modelName) + '</b><br><span style="font-size:11px;color:var(--text-faint);font-family:monospace;">' + DN.esc(x.modelCode) + '</span></td><td>' + DN.esc(x.modelType) + '</td><td>' + DN.esc(x.status) + '</td><td>' + DN.esc(x.entityName) + '</td><td style="font-family:monospace;">' + DN.esc(x.attrCode) + '</td><td>' + DN.esc(x.dataType || '') + '</td></tr>';
          }).join('') + '</tbody></table>'
        : '<div style="padding:20px;color:var(--text-muted);text-align:center;">无数据模型引用该数据元</div>';
      var h = '<div style="min-width:560px;max-height:440px;overflow:auto;"><div style="font-size:12px;color:var(--text-muted);margin-bottom:8px;">引用数据元 <b>' + DN.esc(code) + '</b> 的模型属性（改动标准前评估影响面，共 <b>' + rows.length + '</b> 处）</div>' + tbl + '</div>';
      if (window.projShowModalBox) window.projShowModalBox('数据标准影响分析 · ' + DN.esc(code), h);
    }).catch(function (e) { DN.toast('影响分析加载失败: ' + (e && e.message ? e.message : e), 'error'); });
  }

  // ========== 数据元 ==========
  function renderElements(body) {
    var editingId = null;   // 非空 = 编辑态，提交 payload 带 id 走后端 upsert 更新
    var form = DN.h('div', { class: 'gov-form', style: 'max-width:560px' });
    var f = {
      element_code: input('如 USER_NAME'),
      name_cn: input('中文名'),
      data_type: select(DATA_TYPES, '选择类型'),
      length: input('长度（可选）'),
      value_domain: input('值域（可选）'),
      sensitive_type: select(SENSITIVE_TYPES, '非敏感'),
      security_level: select(securityLevels, '选择密级'),
      description: input('描述（可选）')
    };
    form.appendChild(formRow('编码', f.element_code));
    form.appendChild(formRow('中文名', f.name_cn));
    form.appendChild(formRow('类型', f.data_type));
    form.appendChild(formRow('长度', f.length));
    form.appendChild(formRow('值域', f.value_domain));
    form.appendChild(formRow('敏感类型', f.sensitive_type));
    form.appendChild(formRow('密级', f.security_level));
    form.appendChild(formRow('描述', f.description));
    // 取消编辑：整体重渲染回到新增态
    var cancelEdit = DN.h('a', {
      class: 'btn', href: 'javascript:void(0)', text: '取消编辑', style: 'display:none;margin-left:8px',
      onclick: function () { body.innerHTML = ''; renderElements(body); }
    });
    // 行级编辑：回填表单进入编辑态；编码为唯一引用键，编辑时禁改
    function startEdit(e) {
      editingId = e.id;
      f.element_code.value = e.elementCode || '';
      f.element_code.disabled = true;
      f.name_cn.value = e.nameCn || '';
      setSelect(f.data_type, e.dataType);
      f.length.value = e.length == null ? '' : e.length;
      f.value_domain.value = e.valueDomain || '';
      setSelect(f.sensitive_type, e.sensitiveType);
      setSelect(f.security_level, e.securityLevel);
      f.description.value = e.description || '';
      save.textContent = '保存修改';
      cancelEdit.style.display = '';
      if (form.scrollIntoView) form.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
    var save = DN.h('a', {
      class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增数据元', 'data-perm': 'governance:standard', onclick: function () {
        var code = f.element_code.value.trim();
        if (!code) { DN.toast('编码必填', 'err'); f.element_code.focus(); return; }
        // 编码格式校验：字母开头，仅允许字母/数字/下划线（数据元命名规范）
        if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(code)) { DN.toast('编码须字母开头，仅含字母/数字/下划线', 'err'); f.element_code.focus(); return; }
        if (!f.name_cn.value.trim()) { DN.toast('中文名必填', 'err'); f.name_cn.focus(); return; }
        // 长度范围校验：可空，填则须为正整数（避免负值/0/非法字符入库）
        var lenRaw = f.length.value.trim(), len = null;
        if (lenRaw) {
          if (!/^\d+$/.test(lenRaw) || parseInt(lenRaw, 10) <= 0) { DN.toast('长度须为正整数', 'err'); f.length.focus(); return; }
          len = parseInt(lenRaw, 10);
        }
        var payload = {
          elementCode: code, nameCn: f.name_cn.value.trim(),
          dataType: f.data_type.value.trim(), length: len,
          valueDomain: f.value_domain.value.trim(), sensitiveType: f.sensitive_type.value ? f.sensitive_type.value.trim() : null,
          securityLevel: f.security_level.value ? f.security_level.value.trim() : null, description: f.description.value.trim()
        };
        if (editingId != null) payload.id = editingId;
        guardSubmit(save, '保存中…', function () {
          return DN.post(API + '/element/save', payload).then(function () {
            DN.toast('已保存', 'ok'); renderElements(body);
          }).catch(function (e) { DN.toast(e && e.message || '保存失败', 'err'); });
        });
      }
    });
    form.appendChild(DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: '' }), save, cancelEdit]));
    DN.enterSubmit(form);

    var listCard = DN.card({ title: '数据元清单', icon: 'doc' });
    listCard.body.appendChild(form);
    var list = DN.h('div'); listCard.body.appendChild(list);
    list.appendChild(DN.skeleton(3));
    body.appendChild(listCard.el);

    DN.get(API + '/elements').then(function (data) {
      list.innerHTML = '';
      list.appendChild(DN.table({
        columns: [
          { key: 'elementCode', label: '编码' },
          { key: 'nameCn', label: '中文名' },
          { key: 'dataType', label: '类型' },
          { key: 'length', label: '长度', align: 'right', render: function (e) { return e.length == null ? '' : e.length; } },
          { key: 'valueDomain', label: '值域', render: function (e) { return truncCell(e.valueDomain, 30); } },
          { key: 'sensitiveType', label: '敏感', render: function (e) { return e.sensitiveType ? DN.pill(e.sensitiveType, 'warn') : ''; } },
          { key: 'securityLevel', label: '密级' },
          { key: '_op', label: '操作', render: function (e) {
              return opCell([
                editLink(function () { startEdit(e); }),
                DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '影响', title: '查看引用该数据元的模型属性(改标准前评估影响)', onclick: function () { govStdImpact(e.elementCode); } }),
                delLink(function () { return DN.del(API + '/element/' + e.id); }, function () { renderElements(body); })
              ]);
            } }
        ],
        rows: data || [], searchKeys: ['elementCode', 'nameCn'], searchPlaceholder: '搜索编码/中文名',
        empty: '暂无数据元，使用上方表单新增', emptyIcon: 'doc'
      }));
    }).catch(function (e) { list.innerHTML = ''; list.appendChild(DN.errorBox('加载失败：' + (e && e.message || '未知错误'), function () { body.innerHTML = ''; renderElements(body); })); });
  }

  // ========== 命名词根 ==========
  function renderRoots(body) {
    var editingId = null;   // 非空 = 编辑态，提交 payload 带 id 走后端 upsert 更新
    var form = DN.h('div', { class: 'gov-form', style: 'max-width:560px' });
    var f = {
      word_cn: input('中文'),
      word_en: input('英文'),
      abbr: input('缩写'),
      category: select(ROOT_CATEGORIES, '选择分类')
    };
    form.appendChild(formRow('中文', f.word_cn));
    form.appendChild(formRow('英文', f.word_en));
    form.appendChild(formRow('缩写', f.abbr));
    form.appendChild(formRow('分类', f.category));
    // 取消编辑：整体重渲染回到新增态
    var cancelEdit = DN.h('a', {
      class: 'btn', href: 'javascript:void(0)', text: '取消编辑', style: 'display:none;margin-left:8px',
      onclick: function () { body.innerHTML = ''; renderRoots(body); }
    });
    // 行级编辑：回填表单进入编辑态
    function startEdit(r) {
      editingId = r.id;
      f.word_cn.value = r.wordCn || '';
      f.word_en.value = r.wordEn || '';
      f.abbr.value = r.abbr || '';
      setSelect(f.category, r.category);
      save.textContent = '保存修改';
      cancelEdit.style.display = '';
      if (form.scrollIntoView) form.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
    var save = DN.h('a', {
      class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增词根', 'data-perm': 'governance:standard', onclick: function () {
        var payload = {
          wordCn: f.word_cn.value.trim(), wordEn: f.word_en.value.trim(),
          abbr: f.abbr.value.trim(), category: f.category.value.trim()
        };
        if (editingId != null) payload.id = editingId;
        if (!payload.wordCn) { DN.toast('中文必填', 'err'); f.word_cn.focus(); return; }
        if (!payload.wordEn && !payload.abbr) { DN.toast('英文或缩写至少填一个', 'err'); f.word_en.focus(); return; }
        // 英文/缩写格式校验：仅允许英文字母（命名词根用于生成英文字段名）
        if (payload.wordEn && !/^[A-Za-z]+$/.test(payload.wordEn)) { DN.toast('英文仅允许字母', 'err'); f.word_en.focus(); return; }
        if (payload.abbr && !/^[A-Za-z]+$/.test(payload.abbr)) { DN.toast('缩写仅允许字母', 'err'); f.abbr.focus(); return; }
        guardSubmit(save, '保存中…', function () {
          return DN.post(API + '/root/save', payload).then(function () {
            DN.toast('已保存', 'ok'); renderRoots(body);
          }).catch(function (e) { DN.toast(e && e.message || '保存失败', 'err'); });
        });
      }
    });
    form.appendChild(DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: '' }), save, cancelEdit]));
    DN.enterSubmit(form);

    var listCard = DN.card({ title: '命名词根', icon: 'tag' });
    listCard.body.appendChild(form);
    var list = DN.h('div'); listCard.body.appendChild(list);
    list.appendChild(DN.skeleton(3));
    body.appendChild(listCard.el);

    DN.get(API + '/roots').then(function (data) {
      list.innerHTML = '';
      list.appendChild(DN.table({
        columns: [
          { key: 'wordCn', label: '中文' },
          { key: 'wordEn', label: '英文' },
          { key: 'abbr', label: '缩写' },
          { key: 'category', label: '分类', render: function (r) { return r.category ? DN.pill(r.category, 'info') : ''; } },
          { key: '_op', label: '操作', render: function (r) {
              return opCell([
                editLink(function () { startEdit(r); }),
                delLink(function () { return DN.del(API + '/root/' + r.id); }, function () { renderRoots(body); })
              ]);
            } }
        ],
        rows: data || [], searchKeys: ['wordCn', 'wordEn', 'abbr'], searchPlaceholder: '搜索中文/英文/缩写',
        empty: '暂无词根，使用上方表单新增', emptyIcon: 'tag'
      }));
    }).catch(function (e) { list.innerHTML = ''; list.appendChild(DN.errorBox('加载失败：' + (e && e.message || '未知错误'), function () { body.innerHTML = ''; renderRoots(body); })); });
  }

  // ========== 码表 ==========
  function renderDicts(body) {
    var editingId = null;   // 非空 = 编辑态，提交 payload 带 id 走后端 upsert 更新
    var form = DN.h('div', { class: 'gov-form', style: 'max-width:560px' });
    var f = { dict_code: input('如 GENDER'), dict_name: input('名称'), description: input('描述（可选）') };
    form.appendChild(formRow('编码', f.dict_code));
    form.appendChild(formRow('名称', f.dict_name));
    form.appendChild(formRow('描述', f.description));
    // 取消编辑：整体重渲染回到新增态
    var cancelEdit = DN.h('a', {
      class: 'btn', href: 'javascript:void(0)', text: '取消编辑', style: 'display:none;margin-left:8px',
      onclick: function () { body.innerHTML = ''; renderDicts(body); }
    });
    // 行级编辑：回填表单进入编辑态；编码为枚举引用键，编辑时禁改
    function startEdit(d) {
      editingId = d.id;
      f.dict_code.value = d.dictCode || '';
      f.dict_code.disabled = true;
      f.dict_name.value = d.dictName || '';
      f.description.value = d.description || '';
      save.textContent = '保存修改';
      cancelEdit.style.display = '';
      if (form.scrollIntoView) form.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
    var save = DN.h('a', {
      class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增码表', 'data-perm': 'governance:standard', onclick: function () {
        var payload = { dictCode: f.dict_code.value.trim(), dictName: f.dict_name.value.trim(), description: f.description.value.trim() };
        if (editingId != null) payload.id = editingId;
        if (!payload.dictCode) { DN.toast('编码必填', 'err'); f.dict_code.focus(); return; }
        // 编码格式校验：字母开头，仅含字母/数字/下划线（码表编码作为枚举引用键）
        if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(payload.dictCode)) { DN.toast('编码须字母开头，仅含字母/数字/下划线', 'err'); f.dict_code.focus(); return; }
        if (!payload.dictName) { DN.toast('名称必填', 'err'); f.dict_name.focus(); return; }
        guardSubmit(save, '保存中…', function () {
          return DN.post(API + '/dict/save', payload).then(function () { DN.toast('已保存', 'ok'); renderDicts(body); })
            .catch(function (e) { DN.toast(e && e.message || '保存失败', 'err'); });
        });
      }
    });
    form.appendChild(DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: '' }), save, cancelEdit]));
    DN.enterSubmit(form);

    var listCard = DN.card({ title: '码表', icon: 'list' });
    listCard.body.appendChild(form);
    var list = DN.h('div'); listCard.body.appendChild(list);
    list.appendChild(DN.skeleton(3));
    body.appendChild(listCard.el);

    DN.get(API + '/dicts').then(function (data) {
      list.innerHTML = '';
      list.appendChild(DN.table({
        columns: [
          { key: 'dictCode', label: '编码', render: function (d) {
              return DN.h('a', { href: 'javascript:void(0)', text: d.dictCode || '',
                title: (d.dictCode || '') + ' · 点击查看码表明细项',
                style: 'color:var(--primary)', onclick: function () { openDictItems(d); } });
            } },
          { key: 'dictName', label: '名称', render: function (d) { return truncCell(d.dictName, 24); } },
          { key: 'description', label: '描述', render: function (d) { return truncCell(d.description, 36); } },
          { key: '_op', label: '操作', render: function (d) {
              return opCell([
                editLink(function () { startEdit(d); }),
                delLink(function () { return DN.del(API + '/dict/' + d.id); }, function () { renderDicts(body); })
              ]);
            } }
        ],
        rows: data || [], searchKeys: ['dictCode', 'dictName'], searchPlaceholder: '搜索编码/名称',
        empty: '暂无码表，使用上方表单新增', emptyIcon: 'list'
      }));
    }).catch(function (e) { list.innerHTML = ''; list.appendChild(DN.errorBox('加载失败：' + (e && e.message || '未知错误'), function () { body.innerHTML = ''; renderDicts(body); })); });
  }

  /** 码表明细项：用抽屉承载（表单 + 明细表） */
  function openDictItems(dict) {
    var dictId = dict.id;
    var editingItemId = null;   // 非空 = 编辑态，提交 payload 带 id 走后端 upsert 更新
    // 输入字段
    var k = input('码值'), v = input('含义'), s = input('排序');

    // 分组小节：新增码表项
    var sec = DN.formSection('新增码表项');
    sec.add(DN.field('码值', k, { required: true }));
    sec.add(DN.formGrid2([DN.field('含义', v), DN.field('排序', s, { hint: '非负整数，越小越靠前' })]));
    // 取消编辑：清空表单回到新增态
    var cancelEdit = DN.h('a', {
      class: 'btn btn-sm', href: 'javascript:void(0)', text: '取消编辑', style: 'display:none;margin-bottom:8px',
      onclick: function () { resetItemForm(); }
    });
    sec.add(cancelEdit);
    function resetItemForm() {
      editingItemId = null;
      k.value = ''; v.value = ''; s.value = '';
      cancelEdit.style.display = 'none';
      foot.reset('新增项');
    }
    // 行级编辑：回填表单进入编辑态
    function startEditItem(it) {
      editingItemId = it.id;
      k.value = it.itemKey || '';
      v.value = it.itemValue || '';
      s.value = it.sort == null ? '' : it.sort;
      cancelEdit.style.display = '';
      foot.reset('保存修改');
      k.focus();
    }

    var listEl = DN.h('div');
    listEl.appendChild(DN.skeleton(3));

    var body = DN.h('div');
    body.appendChild(sec.el);
    body.appendChild(listEl);

    var dr, foot;
    var doAdd = function () {
      if (foot.ok.disabled) return;
      if (!k.value.trim()) { DN.toast('码值必填', 'err'); k.focus(); return; }
      // 排序校验：可空(默认0)，填则须为非负整数
      var sortRaw = s.value.trim(), sortVal = 0;
      if (sortRaw) {
        if (!/^\d+$/.test(sortRaw)) { DN.toast('排序须为非负整数', 'err'); s.focus(); return; }
        sortVal = parseInt(sortRaw, 10);
      }
      var payload = { dictId: Number(dictId), itemKey: k.value.trim(), itemValue: v.value.trim(), sort: sortVal };
      if (editingItemId != null) payload.id = editingItemId;
      foot.busy('保存中…');
      DN.post(API + '/dict/item/save', payload)
        .then(function () { DN.toast('已保存', 'ok'); resetItemForm(); refreshDictItems(listEl, dictId, startEditItem); })
        .catch(function (e) { DN.toast(e && e.message || '保存失败', 'err'); foot.reset(editingItemId != null ? '保存修改' : '新增项'); });
    };
    foot = DN.drawerFoot({ okText: '新增项', onOk: doAdd, onCancel: function () { dr.close(); } });
    dr = DN.drawer('码表项 — ' + dict.dictCode, body, foot.el);
    DN.enterSubmit(body, doAdd);
    refreshDictItems(listEl, dictId, startEditItem);
  }

  function refreshDictItems(listEl, dictId, onEdit) {
    listEl.innerHTML = '';
    listEl.appendChild(DN.skeleton(3));
    DN.get(API + '/dict/' + dictId).then(function (data) {
      listEl.innerHTML = '';
      listEl.appendChild(DN.table({
        columns: [
          { key: 'itemKey', label: '码值', render: function (it) { return truncCell(it.itemKey, 20); } },
          { key: 'itemValue', label: '含义', render: function (it) { return truncCell(it.itemValue, 36); } },
          { key: 'sort', label: '排序', align: 'right', render: function (it) { return it.sort == null ? 0 : it.sort; } },
          { key: '_op', label: '操作', render: function (it) {
              var ops = [];
              if (onEdit) ops.push(editLink(function () { onEdit(it); }));
              ops.push(delLink(function () { return DN.del(API + '/dict/item/' + it.id); }, function () { refreshDictItems(listEl, dictId, onEdit); }));
              return opCell(ops);
            } }
        ],
        rows: (data && data.items) || [], search: false, empty: '暂无明细项，使用上方表单新增', emptyIcon: 'list'
      }));
    }).catch(function (e) { listEl.innerHTML = ''; listEl.appendChild(DN.errorBox('加载失败：' + (e && e.message || '未知错误'), function () { refreshDictItems(listEl, dictId, onEdit); })); });
  }

  // ========== 落标稽核 ==========
  function renderCheck(body) {
    var runCard = DN.card({ title: '执行落标稽核', icon: 'check' });
    runCard.body.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-bottom:10px',
      text: '选择稽核范围（库 / 库+表）记录到本次结果；不选则记为全量。' }));
    // 范围选择：库 / 库+表（dbTablePicker）
    var picker = DN.dbTablePicker({ withColumn: false });
    runCard.body.appendChild(picker.el);
    var result = DN.h('div', { id: 'checkResult', style: 'margin-top:8px' });
    var runBtn = DN.h('a', {
      class: 'btn btn-primary', href: 'javascript:void(0)', text: '执行落标稽核', 'data-perm': 'governance:standard', onclick: function () {
        var scope = picker.db() ? (picker.table() ? picker.db() + '.' + picker.table() : picker.db()) : '';
        // 全量稽核为较重操作，二次确认防误触
        var pre = scope ? Promise.resolve(true)
          : DN.confirm('未选择范围，将对全量字段执行落标稽核，可能耗时较久，是否继续？', { title: '稽核确认' });
        pre.then(function (ok) {
          if (!ok) return;
          var url = API + '/check/run' + (scope ? '?scope=' + encodeURIComponent(scope) : '');
          guardSubmit(runBtn, '稽核中…', function () {
            return DN.post(url).then(function (run) {
              run = run || {};
              DN.toast('稽核完成，落标率 ' + fmtRate(run.passRate), 'ok');
              showRun(result, run);
              loadRuns(historyBody);
              loadTop();                 // 同步刷新违规 Top，与本次结果联动一致
            }).catch(function (e) { DN.toast(e && e.message || '稽核失败', 'err'); });
          });
        });
      }
    });
    runCard.body.appendChild(runBtn);
    runCard.body.appendChild(result);
    body.appendChild(runCard.el);

    // 违规 Top 排行（最近一次稽核）
    var topCard = DN.card({ title: '规范违规 Top 库表（最近一次稽核）', icon: 'alert' });
    topCard.el.classList.add('primary');
    var topBody = topCard.body;
    topBody.appendChild(DN.skeleton(3));
    body.appendChild(topCard.el);
    function loadTop() {
      if (topBody._loading) return;        // 去重：避免重复并发拉取
      topBody._loading = true;
      topBody.innerHTML = '';
      topBody.appendChild(DN.skeleton(3));
      DN.get(API + '/top-violations?limit=10').then(function (rows) {
        topBody._loading = false;
        topBody.innerHTML = '';
        rows = rows || [];
        if (!rows.length) { topBody.appendChild(DN.empty('暂无违规数据（先执行落标稽核）', 'check')); return; }
        topBody.appendChild(DN.heat(rows.map(function (r) {
          r = r || {};
          return { label: (r.db || '?') + '.' + (r.table || '?'), value: Number(r.violations) || 0, display: (r.violations || 0) + ' 列' };
        }), { rgb: [255, 77, 79] }));
      }).catch(function () { topBody._loading = false; topBody.innerHTML = ''; topBody.appendChild(DN.errorBox('加载失败', function () { loadTop(); })); });
    }
    loadTop();

    var historyCard = DN.card({ title: '稽核历史', icon: 'clock' });
    var historyBody = historyCard.body;
    historyBody.appendChild(DN.skeleton(3));
    body.appendChild(historyCard.el);
    loadRuns(historyBody);
  }

  function loadRuns(box) {
    if (box._loading) return;               // 去重：避免重复并发拉取(执行稽核后多次触发)
    box._loading = true;
    DN.get(API + '/check/runs').then(function (runs) {
      box._loading = false;
      box.innerHTML = '';
      runs = runs || [];
      // 落标率趋势(按 id 升序取时间序), >=2 次才有意义
      var pts = runs.filter(function (r) { return r.passRate != null; })
        .slice().sort(function (a, b) { return (a.id || 0) - (b.id || 0); })
        .map(function (r) { return Number(r.passRate) || 0; });
      if (pts.length >= 2) {
        box.appendChild(DN.sectionTitle('落标率趋势 · 治理改善曲线'));
        var avg = pts.reduce(function (x, y) { return x + y; }, 0) / pts.length;
        box.appendChild(DN.line(pts, { height: 70, max: 100, min: 0, color: avg >= 80 ? 'var(--success)' : avg >= 60 ? 'var(--warning)' : 'var(--error)' }));
      }
      box.appendChild(DN.table({
        columns: [
          { key: 'id', label: 'ID', render: function (r) {
              return DN.h('a', { href: 'javascript:void(0)', text: '#' + r.id, style: 'color:var(--primary)',
                onclick: function () {
                  DN.get(API + '/check/run/' + r.id).then(function (run) {
                    var rb = document.getElementById('checkResult');
                    showRun(rb, run);
                    if (rb && rb.scrollIntoView) rb.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); // 下钻后滚动到结果区
                  }).catch(function (e) { DN.toast(e && e.message || '加载失败', 'err'); });
                } });
            } },
          { key: 'scope', label: '范围', render: function (r) { return truncCell(r.scope || '全量', 28); } },
          { key: 'totalCount', label: '总数', align: 'right', render: function (r) { return r.totalCount || 0; } },
          { key: 'violationCount', label: '不合规', align: 'right', render: function (r) { return r.violationCount || 0; } },
          { key: 'passRate', label: '落标率', align: 'right', render: function (r) {
              if (r.passRate == null) return '-';
              var pr = Number(r.passRate);
              var t = pr >= 80 ? 'ok' : (pr >= 60 ? 'warn' : 'err');
              return DN.pill(fmtRate(r.passRate), t);
            } },
          { key: 'createdAt', label: '时间', render: function (r) { return r.createdAt ? DN.timeAgo(r.createdAt) : '-'; } }
        ],
        rows: runs, searchKeys: ['scope'], searchPlaceholder: '搜索范围',
        empty: '尚无稽核记录，点击上方执行', emptyIcon: 'clock'
      }));
    }).catch(function () { box._loading = false; box.innerHTML = ''; box.appendChild(DN.errorBox('加载失败', function () { loadRuns(box); })); });
  }

  function showRun(box, run) {
    if (!box) return;
    run = run || {};
    box.innerHTML = '';
    var rate = (run.passRate == null ? 0 : Number(run.passRate));
    var tone = rate >= 80 ? 'ok' : (rate >= 60 ? 'warn' : 'err');
    box.appendChild(DN.statRow([
      { icon: 'check', label: '落标率', value: fmtRate(run.passRate), tone: tone },
      { icon: 'doc', label: '稽核字段', value: run.totalCount || 0 },
      { icon: 'alert', label: '不合规', value: run.violationCount || 0, tone: 'err' }
    ]));
    var detail = [];
    try { var pd = run.detail ? JSON.parse(run.detail) : []; detail = Array.isArray(pd) ? pd : []; } catch (e) { detail = []; DN.toast('稽核明细解析失败', 'warn'); }
    // 违规原因 Top 聚合(先看主要违规类型再下钻)
    var detailTbl = DN.table({
      columns: [
        { key: 'tableMetaId', label: '表ID', align: 'right', sortable: true, render: function (d) { return d.tableMetaId == null ? '' : d.tableMetaId; } },
        { key: 'columnName', label: '列名', sortable: true, render: function (d) { return truncCell(d.columnName, 28); } },
        { key: 'dataType', label: '类型', sortable: true },
        { key: 'reason', label: '原因', sortable: true, render: function (d) { return truncCell(d.reason, 40); } }
      ],
      rows: detail, searchKeys: ['columnName', 'reason'], searchPlaceholder: '搜索列名/原因',
      exportName: '落标稽核明细', empty: '无不合规项', emptyIcon: 'check'
    });
    if (detail.length) {
      var byReason = {};
      detail.forEach(function (d) { var k = d.reason || '其他'; byReason[k] = (byReason[k] || 0) + 1; });
      var curReason = null;
      var items = Object.keys(byReason).sort(function (a, b) { return byReason[b] - byReason[a]; })
        .map(function (k) {
          return { label: k, value: byReason[k], tone: 'err', display: byReason[k] + ' 列', onClick: function () { curReason = (curReason === k) ? null : k; detailTbl.reload(curReason ? detail.filter(function (d) { return (d.reason || '其他') === curReason; }) : detail); DN.toast(curReason ? ('已筛选: ' + curReason) : '已显示全部', 'info'); } };
        });
      box.appendChild(DN.sectionTitle('违规原因分布（点击联动筛选明细）'));
      box.appendChild(DN.bars(items));
      box.appendChild(DN.sectionTitle('不合规明细'));
    }
    box.appendChild(detailTbl);
  }
})();
