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

  window.GOV_RENDERERS.standard = function (c) {
    var sub = DN.h('div', { class: 'gov-desc', id: 'stdSub' });
    var body = DN.h('div', { id: 'stdBody' });
    TABS.forEach(function (t) {
      sub.appendChild(DN.h('a', {
        class: 'gov-btn', href: 'javascript:void(0)', text: t.label,
        style: 'margin-right:8px', onclick: function () { renderSub(t.key, body); }
      }));
    });
    c.appendChild(sub);
    c.appendChild(body);
    renderSub('element', body);
  };

  function renderSub(key, body) {
    body.innerHTML = '';
    if (key === 'element') renderElements(body);
    else if (key === 'root') renderRoots(body);
    else if (key === 'dict') renderDicts(body);
    else if (key === 'check') renderCheck(body);
  }

  // ========== 通用表格 ==========
  function table(head, rows) {
    var html = '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
      '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
      head.map(function (h) { return '<th style="padding:8px">' + DN.esc(h) + '</th>'; }).join('') +
      '</tr></thead><tbody>' + rows + '</tbody></table>';
    var box = DN.h('div', { html: html });
    box.querySelectorAll('td').forEach(function (td) {
      td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5';
    });
    return box;
  }

  function delBtn() {
    return '<a href="javascript:void(0)" class="js-del" style="color:#f53f3f">删除</a>';
  }

  // ========== 数据元 ==========
  function renderElements(body) {
    var form = DN.h('div', { class: 'gov-desc' });
    var f = {};
    ['element_code:编码', 'name_cn:中文名', 'data_type:类型', 'length:长度', 'value_domain:值域',
      'sensitive_type:敏感类型', 'security_level:密级', 'description:描述'].forEach(function (p) {
      var kv = p.split(':');
      var inp = DN.h('input', { placeholder: kv[1], style: 'margin:0 6px 6px 0;padding:4px 6px;border:1px solid #ddd;border-radius:4px' });
      f[kv[0]] = inp; form.appendChild(inp);
    });
    form.appendChild(DN.h('a', {
      class: 'gov-btn', href: 'javascript:void(0)', text: '新增数据元', onclick: function () {
        var payload = {
          elementCode: f['element_code'].value.trim(), nameCn: f['name_cn'].value.trim(),
          dataType: f['data_type'].value.trim(), length: parseInt(f['length'].value, 10) || null,
          valueDomain: f['value_domain'].value.trim(), sensitiveType: f['sensitive_type'].value.trim(),
          securityLevel: f['security_level'].value.trim(), description: f['description'].value.trim()
        };
        if (!payload.elementCode) { DN.toast('编码必填', 'error'); return; }
        DN.post(API + '/element/save', payload).then(function () {
          DN.toast('已保存'); renderElements(body);
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      }
    }));
    body.appendChild(form);

    var list = DN.h('div', { id: 'elemList' });
    body.appendChild(list);
    DN.get(API + '/elements').then(function (data) {
      if (!data || !data.length) { list.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无数据元' })); return; }
      var rows = data.map(function (e) {
        return '<tr data-id="' + e.id + '"><td>' + DN.esc(e.elementCode) + '</td><td>' + DN.esc(e.nameCn || '') +
          '</td><td>' + DN.esc(e.dataType || '') + '</td><td>' + (e.length == null ? '' : e.length) +
          '</td><td>' + DN.esc(e.valueDomain || '') + '</td><td>' + DN.esc(e.sensitiveType || '') +
          '</td><td>' + DN.esc(e.securityLevel || '') + '</td><td>' + delBtn() + '</td></tr>';
      }).join('');
      var t = table(['编码', '中文名', '类型', '长度', '值域', '敏感', '密级', '操作'], rows);
      bindDel(t, function (id) { return DN.del(API + '/element/' + id); }, function () { renderElements(body); });
      list.appendChild(t);
    }).catch(function (e) { list.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message })); });
  }

  // ========== 命名词根 ==========
  function renderRoots(body) {
    var form = DN.h('div', { class: 'gov-desc' });
    var f = {};
    ['word_cn:中文', 'word_en:英文', 'abbr:缩写', 'category:分类'].forEach(function (p) {
      var kv = p.split(':');
      var inp = DN.h('input', { placeholder: kv[1], style: 'margin:0 6px 6px 0;padding:4px 6px;border:1px solid #ddd;border-radius:4px' });
      f[kv[0]] = inp; form.appendChild(inp);
    });
    form.appendChild(DN.h('a', {
      class: 'gov-btn', href: 'javascript:void(0)', text: '新增词根', onclick: function () {
        var payload = {
          wordCn: f['word_cn'].value.trim(), wordEn: f['word_en'].value.trim(),
          abbr: f['abbr'].value.trim(), category: f['category'].value.trim()
        };
        if (!payload.wordEn && !payload.abbr) { DN.toast('英文或缩写至少填一个', 'error'); return; }
        DN.post(API + '/root/save', payload).then(function () {
          DN.toast('已保存'); renderRoots(body);
        }).catch(function (e) { DN.toast(e.message, 'error'); });
      }
    }));
    body.appendChild(form);

    var list = DN.h('div', { id: 'rootList' });
    body.appendChild(list);
    DN.get(API + '/roots').then(function (data) {
      if (!data || !data.length) { list.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无词根' })); return; }
      var rows = data.map(function (r) {
        return '<tr data-id="' + r.id + '"><td>' + DN.esc(r.wordCn || '') + '</td><td>' + DN.esc(r.wordEn || '') +
          '</td><td>' + DN.esc(r.abbr || '') + '</td><td>' + DN.esc(r.category || '') + '</td><td>' + delBtn() + '</td></tr>';
      }).join('');
      var t = table(['中文', '英文', '缩写', '分类', '操作'], rows);
      bindDel(t, function (id) { return DN.del(API + '/root/' + id); }, function () { renderRoots(body); });
      list.appendChild(t);
    }).catch(function (e) { list.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message })); });
  }

  // ========== 码表 ==========
  function renderDicts(body) {
    var form = DN.h('div', { class: 'gov-desc' });
    var f = {};
    ['dict_code:编码', 'dict_name:名称', 'description:描述'].forEach(function (p) {
      var kv = p.split(':');
      var inp = DN.h('input', { placeholder: kv[1], style: 'margin:0 6px 6px 0;padding:4px 6px;border:1px solid #ddd;border-radius:4px' });
      f[kv[0]] = inp; form.appendChild(inp);
    });
    form.appendChild(DN.h('a', {
      class: 'gov-btn', href: 'javascript:void(0)', text: '新增码表', onclick: function () {
        var payload = { dictCode: f['dict_code'].value.trim(), dictName: f['dict_name'].value.trim(), description: f['description'].value.trim() };
        if (!payload.dictCode) { DN.toast('编码必填', 'error'); return; }
        DN.post(API + '/dict/save', payload).then(function () { DN.toast('已保存'); renderDicts(body); })
          .catch(function (e) { DN.toast(e.message, 'error'); });
      }
    }));
    body.appendChild(form);

    var list = DN.h('div', { id: 'dictList' });
    var itemBox = DN.h('div', { id: 'dictItems', class: 'gov-desc' });
    body.appendChild(list);
    body.appendChild(itemBox);
    DN.get(API + '/dicts').then(function (data) {
      if (!data || !data.length) { list.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无码表' })); return; }
      var rows = data.map(function (d) {
        return '<tr data-id="' + d.id + '"><td><a href="javascript:void(0)" class="js-items" style="color:#165dff">' +
          DN.esc(d.dictCode) + '</a></td><td>' + DN.esc(d.dictName || '') + '</td><td>' + DN.esc(d.description || '') +
          '</td><td>' + delBtn() + '</td></tr>';
      }).join('');
      var t = table(['编码', '名称', '描述', '操作'], rows);
      bindDel(t, function (id) { return DN.del(API + '/dict/' + id); }, function () { renderDicts(body); });
      t.querySelectorAll('.js-items').forEach(function (a) {
        a.addEventListener('click', function () {
          var id = a.closest('tr').getAttribute('data-id');
          renderDictItems(itemBox, id);
        });
      });
      list.appendChild(t);
    }).catch(function (e) { list.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message })); });
  }

  function renderDictItems(box, dictId) {
    box.innerHTML = '';
    DN.get(API + '/dict/' + dictId).then(function (data) {
      box.appendChild(DN.h('div', { class: 'gov-h1', text: '码表项: ' + DN.esc(data.dict.dictCode), style: 'font-size:14px;margin:8px 0' }));
      var k = DN.h('input', { placeholder: '码值', style: 'margin:0 6px 6px 0;padding:4px 6px;border:1px solid #ddd;border-radius:4px' });
      var v = DN.h('input', { placeholder: '含义', style: 'margin:0 6px 6px 0;padding:4px 6px;border:1px solid #ddd;border-radius:4px' });
      var s = DN.h('input', { placeholder: '排序', style: 'width:60px;margin:0 6px 6px 0;padding:4px 6px;border:1px solid #ddd;border-radius:4px' });
      var add = DN.h('a', {
        class: 'gov-btn', href: 'javascript:void(0)', text: '新增项', onclick: function () {
          if (!k.value.trim()) { DN.toast('码值必填', 'error'); return; }
          DN.post(API + '/dict/item/save', { dictId: Number(dictId), itemKey: k.value.trim(), itemValue: v.value.trim(), sort: parseInt(s.value, 10) || 0 })
            .then(function () { renderDictItems(box, dictId); }).catch(function (e) { DN.toast(e.message, 'error'); });
        }
      });
      box.appendChild(DN.h('div', null, [k, v, s, add]));
      var items = data.items || [];
      if (!items.length) { box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无明细项' })); return; }
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
    bar.appendChild(DN.h('a', {
      class: 'gov-btn', href: 'javascript:void(0)', text: '执行落标稽核', onclick: function () {
        DN.post(API + '/check/run').then(function (run) {
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
      if (!runs || !runs.length) { box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '尚无稽核记录' })); return; }
      var rows = runs.map(function (r) {
        return '<tr data-id="' + r.id + '"><td><a href="javascript:void(0)" class="js-view" style="color:#165dff">#' + r.id +
          '</a></td><td>' + DN.esc(r.scope || '') + '</td><td style="text-align:right">' + (r.totalCount || 0) +
          '</td><td style="text-align:right">' + (r.violationCount || 0) + '</td><td style="text-align:right">' +
          (r.passRate == null ? '-' : r.passRate + '%') + '</td><td>' + DN.esc(r.createdAt || '') + '</td></tr>';
      }).join('');
      var t = table(['ID', '范围', '总数', '不合规', '落标率', '时间'], rows);
      t.querySelectorAll('.js-view').forEach(function (a) {
        a.addEventListener('click', function () {
          var id = a.closest('tr').getAttribute('data-id');
          DN.get(API + '/check/run/' + id).then(function (run) {
            showRun(document.getElementById('checkResult'), run);
          }).catch(function (e) { DN.toast(e.message, 'error'); });
        });
      });
      box.appendChild(DN.h('div', { class: 'gov-h1', text: '稽核历史', style: 'font-size:14px;margin:12px 0 6px' }));
      box.appendChild(t);
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

  // ========== 删除按钮绑定 ==========
  function bindDel(tableEl, delFn, reload) {
    tableEl.querySelectorAll('.js-del').forEach(function (a) {
      a.addEventListener('click', function () {
        var id = a.closest('tr').getAttribute('data-id');
        if (!confirm('确认删除？')) return;
        delFn(id).then(function () { DN.toast('已删除'); reload(); }).catch(function (e) { DN.toast(e.message, 'error'); });
      });
    });
  }
})();
