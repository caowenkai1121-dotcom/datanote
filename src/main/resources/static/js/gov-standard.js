/* 治理模块：数据标准（M7 数据元 + 命名词根 + 码表 + 落标稽核） */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var API = '/api/gov/standard';
  var PAGE_SIZE = 20;
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

  // ========== 通用表格卡片 ==========
  function table(head, rows) {
    var html = '<table style="width:100%;border-collapse:collapse;background:var(--bg-card,#fff);font-size:13px">' +
      '<thead><tr style="text-align:left;color:var(--text-muted,#86909c);border-bottom:1px solid var(--border,#e5e6eb)">' +
      head.map(function (h) { return '<th style="padding:8px">' + DN.esc(h) + '</th>'; }).join('') +
      '</tr></thead><tbody>' + rows + '</tbody></table>';
    var box = DN.h('div', { html: html });
    box.querySelectorAll('td').forEach(function (td) {
      td.style.padding = '8px'; td.style.borderBottom = '1px solid var(--divider,#f2f3f5)';
    });
    return box;
  }

  function delBtn() {
    return '<a href="javascript:void(0)" class="js-del btn btn-sm btn-danger">删除</a>';
  }

  /**
   * 列表区：搜索 + 客户端分页 + 系统表格。
   * cfg: { container, data, head, match(item,kw)->bool, rowHtml(item)->trHtml,
   *        empty, searchPh, afterRender(tableEl) }
   */
  function renderList(cfg) {
    var state = { page: 1, kw: '' };
    var wrap = DN.h('div');
    var toolbar = DN.h('div', { style: 'display:flex;gap:8px;align-items:center;margin-bottom:12px' });
    var search = DN.h('input', { class: 'iw-form-input', placeholder: cfg.searchPh || '搜索', style: 'width:220px' });
    toolbar.appendChild(search);
    var listBox = DN.h('div');
    wrap.appendChild(toolbar);
    wrap.appendChild(listBox);
    cfg.container.appendChild(wrap);

    function draw() {
      listBox.innerHTML = '';
      if (!cfg.data.length) {
        listBox.appendChild(DN.h('div', { class: 'gov-placeholder', text: cfg.empty }));
        return;
      }
      var rows = state.kw
        ? cfg.data.filter(function (it) { return cfg.match(it, state.kw.toLowerCase()); })
        : cfg.data;
      if (!rows.length) { listBox.appendChild(DN.h('div', { class: 'gov-placeholder', text: '无匹配结果' })); return; }
      var totalPages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
      if (state.page > totalPages) state.page = totalPages;
      var pageRows = rows.slice((state.page - 1) * PAGE_SIZE, state.page * PAGE_SIZE);
      var t = table(cfg.head, pageRows.map(cfg.rowHtml).join(''));
      listBox.appendChild(t);
      if (cfg.afterRender) cfg.afterRender(t);
      // 分页条
      var bar = DN.h('div', { style: 'display:flex;justify-content:flex-end;align-items:center;gap:8px;margin-top:12px;font-size:13px;color:var(--text-muted,#86909c)' });
      bar.appendChild(DN.h('span', { text: '共 ' + rows.length + ' 条 · ' + state.page + '/' + totalPages }));
      var prev = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '上一页' });
      var next = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '下一页' });
      prev.onclick = function () { if (state.page > 1) { state.page--; draw(); } };
      next.onclick = function () { if (state.page < totalPages) { state.page++; draw(); } };
      bar.appendChild(prev); bar.appendChild(next);
      listBox.appendChild(bar);
    }
    search.oninput = function () { state.kw = search.value.trim(); state.page = 1; draw(); search.focus(); };
    draw();
  }

  // ========== 数据元 ==========
  function renderElements(body) {
    var form = DN.h('div', { style: 'max-width:520px;margin-bottom:18px' });
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
          valueDomain: f.value_domain.value.trim(), sensitiveType: f.sensitive_type.value.trim(),
          securityLevel: f.security_level.value.trim(), description: f.description.value.trim()
        };
        if (!payload.elementCode) { DN.toast('编码必填', 'error'); return; }
        DN.post(API + '/element/save', payload).then(function () {
          DN.toast('已保存'); renderElements(body);
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      }
    });
    form.appendChild(DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: '' }), save]));
    body.appendChild(form);

    var list = DN.h('div', { id: 'elemList' });
    body.appendChild(list);
    DN.get(API + '/elements').then(function (data) {
      renderList({
        container: list, data: data || [], searchPh: '搜索编码/中文名',
        head: ['编码', '中文名', '类型', '长度', '值域', '敏感', '密级', '操作'],
        empty: '暂无数据元，使用上方表单新增',
        match: function (e, kw) {
          return ((e.elementCode || '') + ' ' + (e.nameCn || '')).toLowerCase().indexOf(kw) >= 0;
        },
        rowHtml: function (e) {
          return '<tr data-id="' + e.id + '"><td>' + DN.esc(e.elementCode) + '</td><td>' + DN.esc(e.nameCn || '') +
            '</td><td>' + DN.esc(e.dataType || '') + '</td><td>' + (e.length == null ? '' : e.length) +
            '</td><td>' + DN.esc(e.valueDomain || '') + '</td><td>' + DN.esc(e.sensitiveType || '') +
            '</td><td>' + DN.esc(e.securityLevel || '') + '</td><td>' + delBtn() + '</td></tr>';
        },
        afterRender: function (t) {
          bindDel(t, function (id) { return DN.del(API + '/element/' + id); }, function () { renderElements(body); });
        }
      });
    }).catch(function (e) { list.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message })); });
  }

  // ========== 命名词根 ==========
  function renderRoots(body) {
    var form = DN.h('div', { style: 'max-width:520px;margin-bottom:18px' });
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
    body.appendChild(form);

    var list = DN.h('div', { id: 'rootList' });
    body.appendChild(list);
    DN.get(API + '/roots').then(function (data) {
      renderList({
        container: list, data: data || [], searchPh: '搜索中文/英文/缩写',
        head: ['中文', '英文', '缩写', '分类', '操作'],
        empty: '暂无词根，使用上方表单新增',
        match: function (r, kw) {
          return ((r.wordCn || '') + ' ' + (r.wordEn || '') + ' ' + (r.abbr || '')).toLowerCase().indexOf(kw) >= 0;
        },
        rowHtml: function (r) {
          return '<tr data-id="' + r.id + '"><td>' + DN.esc(r.wordCn || '') + '</td><td>' + DN.esc(r.wordEn || '') +
            '</td><td>' + DN.esc(r.abbr || '') + '</td><td>' + DN.esc(r.category || '') + '</td><td>' + delBtn() + '</td></tr>';
        },
        afterRender: function (t) {
          bindDel(t, function (id) { return DN.del(API + '/root/' + id); }, function () { renderRoots(body); });
        }
      });
    }).catch(function (e) { list.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message })); });
  }

  // ========== 码表 ==========
  function renderDicts(body) {
    var form = DN.h('div', { style: 'max-width:520px;margin-bottom:18px' });
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
    body.appendChild(form);

    var list = DN.h('div', { id: 'dictList' });
    var itemBox = DN.h('div', { id: 'dictItems', class: 'gov-desc' });
    body.appendChild(list);
    body.appendChild(itemBox);
    DN.get(API + '/dicts').then(function (data) {
      renderList({
        container: list, data: data || [], searchPh: '搜索编码/名称',
        head: ['编码', '名称', '描述', '操作'],
        empty: '暂无码表，使用上方表单新增',
        match: function (d, kw) {
          return ((d.dictCode || '') + ' ' + (d.dictName || '')).toLowerCase().indexOf(kw) >= 0;
        },
        rowHtml: function (d) {
          return '<tr data-id="' + d.id + '"><td><a href="javascript:void(0)" class="js-items" style="color:var(--primary,#165dff)">' +
            DN.esc(d.dictCode) + '</a></td><td>' + DN.esc(d.dictName || '') + '</td><td>' + DN.esc(d.description || '') +
            '</td><td>' + delBtn() + '</td></tr>';
        },
        afterRender: function (t) {
          bindDel(t, function (id) { return DN.del(API + '/dict/' + id); }, function () { renderDicts(body); });
          t.querySelectorAll('.js-items').forEach(function (a) {
            a.addEventListener('click', function () {
              var id = a.closest('tr').getAttribute('data-id');
              renderDictItems(itemBox, id);
            });
          });
        }
      });
    }).catch(function (e) { list.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message })); });
  }

  function renderDictItems(box, dictId) {
    box.innerHTML = '';
    DN.get(API + '/dict/' + dictId).then(function (data) {
      box.appendChild(DN.h('div', { class: 'gov-h1', text: '码表项: ' + DN.esc(data.dict.dictCode), style: 'font-size:14px;margin:8px 0' }));
      var form = DN.h('div', { style: 'max-width:520px;margin-bottom:14px' });
      var k = input('码值');
      var v = input('含义');
      var s = input('排序');
      form.appendChild(formRow('码值', k));
      form.appendChild(formRow('含义', v));
      form.appendChild(formRow('排序', s));
      var add = DN.h('a', {
        class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增项', onclick: function () {
          if (!k.value.trim()) { DN.toast('码值必填', 'error'); return; }
          DN.post(API + '/dict/item/save', { dictId: Number(dictId), itemKey: k.value.trim(), itemValue: v.value.trim(), sort: parseInt(s.value, 10) || 0 })
            .then(function () { DN.toast('已保存'); renderDictItems(box, dictId); }).catch(function (e) { DN.toast(e.message, 'error'); });
        }
      });
      form.appendChild(DN.h('div', { class: 'ds-form-row' }, [DN.h('label', { text: '' }), add]));
      box.appendChild(form);
      var items = data.items || [];
      if (!items.length) { box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无明细项，使用上方表单新增' })); return; }
      var rows = items.map(function (it) {
        return '<tr data-id="' + it.id + '"><td>' + DN.esc(it.itemKey || '') + '</td><td>' + DN.esc(it.itemValue || '') +
          '</td><td>' + (it.sort == null ? 0 : it.sort) + '</td><td>' + delBtn() + '</td></tr>';
      }).join('');
      var t = table(['码值', '含义', '排序', '操作'], rows);
      bindDel(t, function (id) { return DN.del(API + '/dict/item/' + id); }, function () { renderDictItems(box, dictId); });
      box.appendChild(t);
    }).catch(function (e) { box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message })); });
  }

  // ========== 落标稽核 ==========
  function renderCheck(body) {
    var bar = DN.h('div', { class: 'gov-desc' });
    body.appendChild(DN.h('div', { class: 'iw-form-hint', text: '选择稽核范围（库 / 库+表）记录到本次结果；不选则记为全量。',
      style: 'margin-bottom:6px' }));
    // 范围选择：库 / 库+表（dbTablePicker）
    var picker = DN.dbTablePicker({ withColumn: false });
    bar.appendChild(picker.el);
    bar.appendChild(DN.h('a', {
      class: 'btn btn-primary', href: 'javascript:void(0)', text: '执行落标稽核', onclick: function () {
        var scope = picker.db() ? (picker.table() ? picker.db() + '.' + picker.table() : picker.db()) : '';
        var url = API + '/check/run' + (scope ? '?scope=' + encodeURIComponent(scope) : '');
        DN.post(url).then(function (run) {
          DN.toast('稽核完成，落标率 ' + run.passRate + '%');
          showRun(result, run);
          loadRuns(history);
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      }
    }));
    body.appendChild(bar);
    var result = DN.h('div', { id: 'checkResult' });
    var history = DN.h('div', { id: 'checkHistory', class: 'gov-desc' });
    body.appendChild(result);
    body.appendChild(history);
    loadRuns(history);
  }

  function loadRuns(box) {
    DN.get(API + '/check/runs').then(function (runs) {
      box.innerHTML = '';
      box.appendChild(DN.h('div', { class: 'gov-h1', text: '稽核历史', style: 'font-size:14px;margin:12px 0 6px' }));
      var listBox = DN.h('div');
      box.appendChild(listBox);
      renderList({
        container: listBox, data: runs || [], searchPh: '搜索范围',
        head: ['ID', '范围', '总数', '不合规', '落标率', '时间'],
        empty: '尚无稽核记录，点击上方执行',
        match: function (r, kw) { return String(r.scope || '').toLowerCase().indexOf(kw) >= 0; },
        rowHtml: function (r) {
          return '<tr data-id="' + r.id + '"><td><a href="javascript:void(0)" class="js-view" style="color:var(--primary,#165dff)">#' + r.id +
            '</a></td><td>' + DN.esc(r.scope || '全量') + '</td><td style="text-align:right">' + (r.totalCount || 0) +
            '</td><td style="text-align:right">' + (r.violationCount || 0) + '</td><td style="text-align:right">' +
            (r.passRate == null ? '-' : r.passRate + '%') + '</td><td>' + DN.esc(r.createdAt || '') + '</td></tr>';
        },
        afterRender: function (t) {
          t.querySelectorAll('.js-view').forEach(function (a) {
            a.addEventListener('click', function () {
              var id = a.closest('tr').getAttribute('data-id');
              DN.get(API + '/check/run/' + id).then(function (run) {
                showRun(document.getElementById('checkResult'), run);
              }).catch(function (e) { DN.toast(e.message, 'error'); });
            });
          });
        }
      });
    }).catch(function () {});
  }

  function showRun(box, run) {
    box.innerHTML = '';
    box.appendChild(DN.h('div', {
      class: 'gov-h1', style: 'font-size:18px;margin:8px 0',
      text: '落标率 ' + (run.passRate == null ? '-' : run.passRate + '%') +
        '（共 ' + (run.totalCount || 0) + ' 字段，不合规 ' + (run.violationCount || 0) + '）'
    }));
    var detail = [];
    try { detail = run.detail ? JSON.parse(run.detail) : []; } catch (e) { detail = []; }
    if (!detail.length) { box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '无不合规项' })); return; }
    var rows = detail.map(function (d) {
      return '<tr><td>' + (d.tableMetaId == null ? '' : d.tableMetaId) + '</td><td>' + DN.esc(d.columnName || '') +
        '</td><td>' + DN.esc(d.dataType || '') + '</td><td>' + DN.esc(d.reason || '') + '</td></tr>';
    }).join('');
    box.appendChild(DN.h('div', { class: 'gov-h1', text: '不合规清单', style: 'font-size:14px;margin:12px 0 6px' }));
    box.appendChild(table(['表ID', '列名', '类型', '原因'], rows));
  }

  // ========== 删除按钮绑定（就近内联确认，不用 window.confirm） ==========
  function bindDel(tableEl, delFn, reload) {
    tableEl.querySelectorAll('.js-del').forEach(function (a) {
      a.addEventListener('click', function () {
        var tr = a.closest('tr');
        var id = tr.getAttribute('data-id');
        if (a.getAttribute('data-confirm') === '1') {
          delFn(id).then(function () { DN.toast('已删除'); reload(); }).catch(function (e) { DN.toast(e.message, 'error'); });
          return;
        }
        a.setAttribute('data-confirm', '1');
        a.textContent = '确认删除？';
        setTimeout(function () {
          if (a.parentNode) { a.removeAttribute('data-confirm'); a.textContent = '删除'; }
        }, 2500);
      });
    });
  }
})();
