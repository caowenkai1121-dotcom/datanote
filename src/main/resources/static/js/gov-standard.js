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

  // 数据标准总览(大功能): 数据元/词根/码表/落标率 聚合磁贴
  function buildStdOverview(box) {
    Promise.all([
      DN.get(API + '/elements').catch(function () { return []; }),
      DN.get(API + '/roots').catch(function () { return []; }),
      DN.get(API + '/dicts').catch(function () { return []; }),
      DN.get(API + '/check/runs').catch(function () { return []; })
    ]).then(function (r) {
      if (!document.body.contains(box)) return;
      var els = r[0] || [], roots = r[1] || [], dicts = r[2] || [], runs = r[3] || [];
      var latest = runs.slice().sort(function (a, b) { return (b.id || 0) - (a.id || 0); })[0];
      var rate = latest && latest.passRate != null ? Number(latest.passRate) : null;
      var rTone = rate == null ? 'muted' : rate >= 80 ? 'ok' : rate >= 60 ? 'warn' : 'err';
      box.innerHTML = '';
      box.appendChild(DN.statRow([
        { icon: 'list', label: '数据元', value: els.length },
        { icon: 'tag', label: '命名词根', value: roots.length },
        { icon: 'grid', label: '码表', value: dicts.length },
        { icon: 'check', label: '最新落标率', value: fmtRate(rate), tone: rTone, sub: latest ? ('稽核#' + latest.id) : '尚未稽核' }
      ]));
      box.appendChild(buildStdMaturity(els, roots, dicts, runs).el);
    });
  }

  // 创新功能"数据标准落地概览": 基于已加载数据前端聚合, 展示标准体系成熟度(不调新 API)
  function buildStdMaturity(els, roots, dicts, runs) {
    var card = DN.card({ title: '数据标准落地概览', icon: 'check' });
    // —— 1) 数据元覆盖度: 已标敏感类型/已定密级 的占比, 反映元数据填充完整度 ——
    var elTotal = els.length;
    var sensCnt = els.filter(function (e) { return e.sensitiveType; }).length;
    var secCnt = els.filter(function (e) { return e.securityLevel; }).length;
    var sensRate = Math.round(sensCnt / (elTotal || 1) * 100);
    var secRate = Math.round(secCnt / (elTotal || 1) * 100);
    // —— 2) 词根复用度: 唯一缩写覆盖率, 衡量命名规范沉淀程度 ——
    var rootTotal = roots.length;
    var abbrSet = {};
    roots.forEach(function (r) { var a = (r.abbr || r.wordEn || '').toLowerCase(); if (a) abbrSet[a] = 1; });
    var uniqAbbr = Object.keys(abbrSet).length;
    var reuseRate = Math.round(uniqAbbr / (rootTotal || 1) * 100);
    // —— 3) 落标率: 最新一次 + 趋势(多次稽核) ——
    var ratePts = runs.filter(function (r) { return r.passRate != null; })
      .slice().sort(function (a, b) { return (a.id || 0) - (b.id || 0); })
      .map(function (r) { return Number(r.passRate) || 0; });
    var latestRate = ratePts.length ? ratePts[ratePts.length - 1] : null;

    card.body.appendChild(DN.statRow([
      { icon: 'list', label: '数据元敏感标注', value: sensRate + '%', tone: sensRate >= 50 ? 'ok' : 'warn', sub: sensCnt + '/' + elTotal + ' 已标' },
      { icon: 'shield', label: '数据元定密率', value: secRate + '%', tone: secRate >= 60 ? 'ok' : 'warn', sub: secCnt + '/' + elTotal + ' 已定密' },
      { icon: 'tag', label: '词根唯一复用度', value: reuseRate + '%', tone: reuseRate >= 80 ? 'ok' : 'info', sub: uniqAbbr + ' 个唯一缩写' },
      { icon: 'grid', label: '码表沉淀', value: dicts.length, tone: dicts.length > 0 ? 'ok' : 'muted', sub: '套标准码表' }
    ]));

    // 标准体系成熟度构成(各维度归一到 0~100 用 DN.bars)
    card.body.appendChild(DN.sectionTitle('标准体系成熟度构成'));
    card.body.appendChild(DN.bars([
      { label: '数据元敏感标注', value: sensRate, tone: sensRate >= 50 ? 'ok' : 'warn', display: sensRate + '%' },
      { label: '数据元定密率', value: secRate, tone: secRate >= 60 ? 'ok' : 'warn', display: secRate + '%' },
      { label: '词根唯一复用度', value: reuseRate, tone: reuseRate >= 80 ? 'ok' : 'info', display: reuseRate + '%' },
      { label: '最新落标率', value: latestRate == null ? 0 : Math.round(latestRate), tone: latestRate == null ? 'muted' : (latestRate >= 80 ? 'ok' : latestRate >= 60 ? 'warn' : 'err'), display: fmtRate(latestRate) }
    ]));

    // 落标率趋势曲线(>=2 次稽核才有意义)
    if (ratePts.length >= 2) {
      card.body.appendChild(DN.sectionTitle('近期落标率趋势'));
      var avg = ratePts.reduce(function (x, y) { return x + y; }, 0) / (ratePts.length || 1);
      card.body.appendChild(DN.line(ratePts, { height: 60, max: 100, min: 0, color: avg >= 80 ? '#52c41a' : avg >= 60 ? '#faad14' : '#ff4d4f' }));
    }

    // —— 标准建设简评 ——
    var notes = [];
    if (elTotal === 0) notes.push('尚未录入数据元，建议先沉淀核心字段标准');
    else if (sensRate < 50) notes.push('敏感字段标注偏低，建议补全敏感类型以支撑分级保护');
    if (elTotal > 0 && secRate < 60) notes.push('数据元定密覆盖不足，建议完善密级以满足合规要求');
    if (rootTotal === 0) notes.push('暂无命名词根，建议建立词根库统一英文命名');
    else if (reuseRate >= 80) notes.push('命名词根复用规范，沉淀良好');
    if (dicts.length === 0) notes.push('暂无标准码表，建议沉淀公共枚举值');
    if (latestRate == null) notes.push('尚未执行落标稽核，建议运行稽核检验标准落地情况');
    else if (latestRate >= 80) notes.push('最新落标率达标，标准落地情况良好');
    else notes.push('落标率有待提升，可结合违规 Top 库表针对性整改');
    if (!notes.length) notes.push('各项标准建设指标健康');
    card.body.appendChild(DN.sectionTitle('标准建设简评'));
    var ul = DN.h('ul', { class: 'gov-desc', style: 'margin:0;padding-left:18px;line-height:1.9' });
    notes.forEach(function (t) { ul.appendChild(DN.h('li', { text: t })); });
    card.body.appendChild(ul);
    return card;
  }

  window.GOV_RENDERERS.standard = function (c) {
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
    TABS.forEach(function (t) {
      var a = DN.h('a', {
        class: 'btn', href: 'javascript:void(0)', text: t.label,
        onclick: function () {
          Object.keys(tabEls).forEach(function (k) { tabEls[k].className = 'btn'; });
          a.className = 'btn btn-primary';
          renderSub(t.key, body);
        }
      });
      tabEls[t.key] = a;
      sub.appendChild(a);
    });
    c.appendChild(sub);
    c.appendChild(body);
    tabEls.element.className = 'btn btn-primary';
    renderSub('element', body);
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
  function input(ph) { return DN.h('input', { class: 'iw-form-input', placeholder: ph }); }
  function select(opts, placeholder) {
    var s = DN.h('select', { class: 'iw-form-select' });
    if (placeholder) s.appendChild(DN.h('option', { value: '', text: placeholder }));
    opts.forEach(function (o) { s.appendChild(DN.h('option', { value: o, text: o })); });
    return s;
  }

  /** 内联确认删除链接（不用 window.confirm），返回 Node 供 DN.table 渲染 */
  function delLink(delFn, reload) {
    var a = DN.h('a', { href: 'javascript:void(0)', class: 'btn btn-sm btn-danger', text: '删除' });
    a.onclick = function () {
      if (a.getAttribute('data-confirm') === '1') {
        delFn().then(function () { DN.toast('已删除'); reload(); }).catch(function (e) { DN.toast(e.message, 'error'); });
        return;
      }
      a.setAttribute('data-confirm', '1');
      a.textContent = '确认删除？';
      setTimeout(function () { if (a.parentNode) { a.removeAttribute('data-confirm'); a.textContent = '删除'; } }, 2500);
    };
    return a;
  }

  // ========== 数据元 ==========
  function renderElements(body) {
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
    var save = DN.h('a', {
      class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增数据元', onclick: function () {
        var payload = {
          elementCode: f.element_code.value.trim(), nameCn: f.name_cn.value.trim(),
          dataType: f.data_type.value.trim(), length: parseInt(f.length.value, 10) || null,
          valueDomain: f.value_domain.value.trim(), sensitiveType: f.sensitive_type.value ? f.sensitive_type.value.trim() : null,
          securityLevel: f.security_level.value ? f.security_level.value.trim() : null, description: f.description.value.trim()
        };
        if (!payload.elementCode) { DN.toast('编码必填', 'error'); return; }
        DN.post(API + '/element/save', payload).then(function () {
          DN.toast('已保存'); renderElements(body);
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      }
    });
    form.appendChild(DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: '' }), save]));
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
          { key: 'valueDomain', label: '值域' },
          { key: 'sensitiveType', label: '敏感', render: function (e) { return e.sensitiveType ? DN.pill(e.sensitiveType, 'warn') : ''; } },
          { key: 'securityLevel', label: '密级' },
          { key: '_op', label: '操作', render: function (e) {
              return delLink(function () { return DN.del(API + '/element/' + e.id); }, function () { renderElements(body); });
            } }
        ],
        rows: data || [], searchKeys: ['elementCode', 'nameCn'], searchPlaceholder: '搜索编码/中文名',
        empty: '暂无数据元，使用上方表单新增', emptyIcon: 'doc'
      }));
    }).catch(function (e) { list.innerHTML = ''; list.appendChild(DN.errorBox('加载失败: ' + e.message, function () { body.innerHTML = ''; renderElements(body); })); });
  }

  // ========== 命名词根 ==========
  function renderRoots(body) {
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
    var save = DN.h('a', {
      class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增词根', onclick: function () {
        var payload = {
          wordCn: f.word_cn.value.trim(), wordEn: f.word_en.value.trim(),
          abbr: f.abbr.value.trim(), category: f.category.value.trim()
        };
        if (!payload.wordEn && !payload.abbr) { DN.toast('英文或缩写至少填一个', 'error'); return; }
        DN.post(API + '/root/save', payload).then(function () {
          DN.toast('已保存'); renderRoots(body);
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      }
    });
    form.appendChild(DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: '' }), save]));
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
              return delLink(function () { return DN.del(API + '/root/' + r.id); }, function () { renderRoots(body); });
            } }
        ],
        rows: data || [], searchKeys: ['wordCn', 'wordEn', 'abbr'], searchPlaceholder: '搜索中文/英文/缩写',
        empty: '暂无词根，使用上方表单新增', emptyIcon: 'tag'
      }));
    }).catch(function (e) { list.innerHTML = ''; list.appendChild(DN.errorBox('加载失败: ' + e.message, function () { body.innerHTML = ''; renderRoots(body); })); });
  }

  // ========== 码表 ==========
  function renderDicts(body) {
    var form = DN.h('div', { class: 'gov-form', style: 'max-width:560px' });
    var f = { dict_code: input('如 GENDER'), dict_name: input('名称'), description: input('描述（可选）') };
    form.appendChild(formRow('编码', f.dict_code));
    form.appendChild(formRow('名称', f.dict_name));
    form.appendChild(formRow('描述', f.description));
    var save = DN.h('a', {
      class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增码表', onclick: function () {
        var payload = { dictCode: f.dict_code.value.trim(), dictName: f.dict_name.value.trim(), description: f.description.value.trim() };
        if (!payload.dictCode) { DN.toast('编码必填', 'error'); return; }
        DN.post(API + '/dict/save', payload).then(function () { DN.toast('已保存'); renderDicts(body); })
          .catch(function (e) { DN.toast(e.message, 'error'); });
      }
    });
    form.appendChild(DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: '' }), save]));
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
                style: 'color:var(--primary,#1890ff)', onclick: function () { openDictItems(d); } });
            } },
          { key: 'dictName', label: '名称' },
          { key: 'description', label: '描述' },
          { key: '_op', label: '操作', render: function (d) {
              return delLink(function () { return DN.del(API + '/dict/' + d.id); }, function () { renderDicts(body); });
            } }
        ],
        rows: data || [], searchKeys: ['dictCode', 'dictName'], searchPlaceholder: '搜索编码/名称',
        empty: '暂无码表，使用上方表单新增', emptyIcon: 'list'
      }));
    }).catch(function (e) { list.innerHTML = ''; list.appendChild(DN.errorBox('加载失败: ' + e.message, function () { body.innerHTML = ''; renderDicts(body); })); });
  }

  /** 码表明细项：用抽屉承载（表单 + 明细表） */
  function openDictItems(dict) {
    var d = DN.drawer('码表项 — ' + dict.dictCode, DN.skeleton(4));
    refreshDictItems(d.body, dict.id);
  }

  function refreshDictItems(box, dictId) {
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    DN.get(API + '/dict/' + dictId).then(function (data) {
      box.innerHTML = '';
      var form = DN.h('div', { class: 'gov-form' });
      var k = input('码值'), v = input('含义'), s = input('排序');
      form.appendChild(formRow('码值', k));
      form.appendChild(formRow('含义', v));
      form.appendChild(formRow('排序', s));
      var add = DN.h('a', {
        class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增项', onclick: function () {
          if (!k.value.trim()) { DN.toast('码值必填', 'error'); return; }
          DN.post(API + '/dict/item/save', { dictId: Number(dictId), itemKey: k.value.trim(), itemValue: v.value.trim(), sort: parseInt(s.value, 10) || 0 })
            .then(function () { DN.toast('已保存'); refreshDictItems(box, dictId); }).catch(function (e) { DN.toast(e.message, 'error'); });
        }
      });
      form.appendChild(DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: '' }), add]));
      DN.enterSubmit(form);
      box.appendChild(form);
      box.appendChild(DN.table({
        columns: [
          { key: 'itemKey', label: '码值' },
          { key: 'itemValue', label: '含义' },
          { key: 'sort', label: '排序', align: 'right', render: function (it) { return it.sort == null ? 0 : it.sort; } },
          { key: '_op', label: '操作', render: function (it) {
              return delLink(function () { return DN.del(API + '/dict/item/' + it.id); }, function () { refreshDictItems(box, dictId); });
            } }
        ],
        rows: (data && data.items) || [], search: false, empty: '暂无明细项，使用上方表单新增'
      }));
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.errorBox('加载失败: ' + e.message, function () { refreshDictItems(box, dictId); })); });
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
    runCard.body.appendChild(DN.h('a', {
      class: 'btn btn-primary', href: 'javascript:void(0)', text: '执行落标稽核', onclick: function () {
        var scope = picker.db() ? (picker.table() ? picker.db() + '.' + picker.table() : picker.db()) : '';
        var url = API + '/check/run' + (scope ? '?scope=' + encodeURIComponent(scope) : '');
        DN.post(url).then(function (run) {
          DN.toast('稽核完成，落标率 ' + fmtRate(run.passRate));
          showRun(result, run);
          loadRuns(historyBody);
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      }
    }));
    runCard.body.appendChild(result);
    body.appendChild(runCard.el);

    // 违规 Top 排行（最近一次稽核）
    var topCard = DN.card({ title: '规范违规 Top 库表（最近一次稽核）', icon: 'alert' });
    topCard.el.classList.add('primary');
    var topBody = topCard.body;
    topBody.appendChild(DN.skeleton(3));
    body.appendChild(topCard.el);
    function loadTop() {
      topBody.innerHTML = '';
      topBody.appendChild(DN.skeleton(3));
      DN.get(API + '/top-violations?limit=10').then(function (rows) {
        topBody.innerHTML = '';
        if (!rows || !rows.length) { topBody.appendChild(DN.empty('暂无违规数据（先执行落标稽核）', 'check')); return; }
        topBody.appendChild(DN.heat(rows.map(function (r) {
          return { label: r.db + '.' + r.table, value: Number(r.violations) || 0, display: (r.violations || 0) + ' 列' };
        }), { rgb: [255, 77, 79] }));
      }).catch(function () { topBody.innerHTML = ''; topBody.appendChild(DN.errorBox('加载失败', function () { loadTop(); })); });
    }
    loadTop();

    var historyCard = DN.card({ title: '稽核历史', icon: 'clock' });
    var historyBody = historyCard.body;
    historyBody.appendChild(DN.skeleton(3));
    body.appendChild(historyCard.el);
    loadRuns(historyBody);
  }

  function loadRuns(box) {
    DN.get(API + '/check/runs').then(function (runs) {
      box.innerHTML = '';
      runs = runs || [];
      // 落标率趋势(按 id 升序取时间序), >=2 次才有意义
      var pts = runs.filter(function (r) { return r.passRate != null; })
        .slice().sort(function (a, b) { return (a.id || 0) - (b.id || 0); })
        .map(function (r) { return Number(r.passRate) || 0; });
      if (pts.length >= 2) {
        box.appendChild(DN.sectionTitle('落标率趋势 · 治理改善曲线'));
        var avg = pts.reduce(function (x, y) { return x + y; }, 0) / pts.length;
        box.appendChild(DN.line(pts, { height: 70, max: 100, min: 0, color: avg >= 80 ? '#52c41a' : avg >= 60 ? '#faad14' : '#ff4d4f' }));
      }
      box.appendChild(DN.table({
        columns: [
          { key: 'id', label: 'ID', render: function (r) {
              return DN.h('a', { href: 'javascript:void(0)', text: '#' + r.id, style: 'color:var(--primary,#1890ff)',
                onclick: function () {
                  DN.get(API + '/check/run/' + r.id).then(function (run) {
                    showRun(document.getElementById('checkResult'), run);
                  }).catch(function (e) { DN.toast(e.message, 'error'); });
                } });
            } },
          { key: 'scope', label: '范围', render: function (r) { return r.scope || '全量'; } },
          { key: 'totalCount', label: '总数', align: 'right', render: function (r) { return r.totalCount || 0; } },
          { key: 'violationCount', label: '不合规', align: 'right', render: function (r) { return r.violationCount || 0; } },
          { key: 'passRate', label: '落标率', align: 'right', render: function (r) {
              if (r.passRate == null) return '-';
              var t = r.passRate >= 80 ? 'ok' : (r.passRate >= 60 ? 'warn' : 'err');
              return DN.pill(fmtRate(r.passRate), t);
            } },
          { key: 'createdAt', label: '时间' }
        ],
        rows: runs, searchKeys: ['scope'], searchPlaceholder: '搜索范围',
        empty: '尚无稽核记录，点击上方执行', emptyIcon: 'clock'
      }));
    }).catch(function () { box.innerHTML = ''; box.appendChild(DN.errorBox('加载失败', function () { loadRuns(box); })); });
  }

  function showRun(box, run) {
    if (!box) return;
    box.innerHTML = '';
    var rate = (run.passRate == null ? 0 : Number(run.passRate));
    var tone = rate >= 80 ? 'ok' : (rate >= 60 ? 'warn' : 'err');
    box.appendChild(DN.statRow([
      { icon: 'check', label: '落标率', value: fmtRate(run.passRate), tone: tone },
      { icon: 'doc', label: '稽核字段', value: run.totalCount || 0 },
      { icon: 'alert', label: '不合规', value: run.violationCount || 0, tone: 'err' }
    ]));
    var detail = [];
    try { detail = run.detail ? JSON.parse(run.detail) : []; } catch (e) { detail = []; DN.toast('稽核明细解析失败', 'warn'); }
    // 违规原因 Top 聚合(先看主要违规类型再下钻)
    var detailTbl = DN.table({
      columns: [
        { key: 'tableMetaId', label: '表ID', align: 'right', sortable: true, render: function (d) { return d.tableMetaId == null ? '' : d.tableMetaId; } },
        { key: 'columnName', label: '列名', sortable: true },
        { key: 'dataType', label: '类型', sortable: true },
        { key: 'reason', label: '原因', sortable: true }
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
