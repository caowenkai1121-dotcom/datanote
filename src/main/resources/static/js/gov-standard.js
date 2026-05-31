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

  window.GOV_RENDERERS.standard = function (c) {
    // 预取密级方案（落标/数据元密级下拉用），失败保留兜底
    DN.get('/api/gov/classification/levels').then(function (rows) {
      if (rows && rows.length) securityLevels = rows.map(function (r) { return r.levelName; });
    }).catch(function () {});

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
    }).catch(function (e) { list.innerHTML = ''; list.appendChild(DN.empty('加载失败: ' + e.message, 'alert')); });
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
    }).catch(function (e) { list.innerHTML = ''; list.appendChild(DN.empty('加载失败: ' + e.message, 'alert')); });
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
    }).catch(function (e) { list.innerHTML = ''; list.appendChild(DN.empty('加载失败: ' + e.message, 'alert')); });
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
        rows: data.items || [], search: false, empty: '暂无明细项，使用上方表单新增'
      }));
    }).catch(function (e) { box.innerHTML = ''; box.appendChild(DN.empty('加载失败: ' + e.message, 'alert')); });
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
          DN.toast('稽核完成，落标率 ' + run.passRate + '%');
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
    DN.get(API + '/top-violations?limit=10').then(function (rows) {
      topBody.innerHTML = '';
      if (!rows || !rows.length) { topBody.appendChild(DN.empty('暂无违规数据（先执行落标稽核）', 'check')); return; }
      topBody.appendChild(DN.heat(rows.map(function (r) {
        return { label: r.db + '.' + r.table, value: Number(r.violations) || 0, display: (r.violations || 0) + ' 列' };
      }), { rgb: [255, 77, 79] }));
    }).catch(function () { topBody.innerHTML = ''; topBody.appendChild(DN.empty('加载失败', 'alert')); });

    var historyCard = DN.card({ title: '稽核历史', icon: 'clock' });
    var historyBody = historyCard.body;
    historyBody.appendChild(DN.skeleton(3));
    body.appendChild(historyCard.el);
    loadRuns(historyBody);
  }

  function loadRuns(box) {
    DN.get(API + '/check/runs').then(function (runs) {
      box.innerHTML = '';
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
              return DN.pill(r.passRate + '%', t);
            } },
          { key: 'createdAt', label: '时间' }
        ],
        rows: runs || [], searchKeys: ['scope'], searchPlaceholder: '搜索范围',
        empty: '尚无稽核记录，点击上方执行', emptyIcon: 'clock'
      }));
    }).catch(function () { box.innerHTML = ''; box.appendChild(DN.empty('加载失败', 'alert')); });
  }

  function showRun(box, run) {
    if (!box) return;
    box.innerHTML = '';
    var rate = (run.passRate == null ? 0 : Number(run.passRate));
    var tone = rate >= 80 ? 'ok' : (rate >= 60 ? 'warn' : 'err');
    box.appendChild(DN.statRow([
      { icon: 'check', label: '落标率', value: (run.passRate == null ? '-' : run.passRate + '%'), tone: tone },
      { icon: 'doc', label: '稽核字段', value: run.totalCount || 0 },
      { icon: 'alert', label: '不合规', value: run.violationCount || 0, tone: 'err' }
    ]));
    var detail = [];
    try { detail = run.detail ? JSON.parse(run.detail) : []; } catch (e) { detail = []; DN.toast('稽核明细解析失败', 'warn'); }
    // 违规原因 Top 聚合(先看主要违规类型再下钻)
    if (detail.length) {
      var byReason = {};
      detail.forEach(function (d) { var k = d.reason || '其他'; byReason[k] = (byReason[k] || 0) + 1; });
      var items = Object.keys(byReason).sort(function (a, b) { return byReason[b] - byReason[a]; })
        .map(function (k) { return { label: k, value: byReason[k], tone: 'err', display: byReason[k] + ' 列' }; });
      box.appendChild(DN.sectionTitle('违规原因分布'));
      box.appendChild(DN.bars(items));
      box.appendChild(DN.sectionTitle('不合规明细'));
    }
    box.appendChild(DN.table({
      columns: [
        { key: 'tableMetaId', label: '表ID', align: 'right', sortable: true, render: function (d) { return d.tableMetaId == null ? '' : d.tableMetaId; } },
        { key: 'columnName', label: '列名', sortable: true },
        { key: 'dataType', label: '类型', sortable: true },
        { key: 'reason', label: '原因', sortable: true }
      ],
      rows: detail, searchKeys: ['columnName', 'reason'], searchPlaceholder: '搜索列名/原因',
      exportName: '落标稽核明细', empty: '无不合规项', emptyIcon: 'check'
    }));
  }
})();
