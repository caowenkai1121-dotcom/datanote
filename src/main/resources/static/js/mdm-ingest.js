/* 主数据(MDM) · 源数据接入 渲染器 —— 整合管线(R121): 从源库表消费记录→匹配→合并为黄金记录。
   选实体 + 源库.表 → 加载源列 → 配置字段映射(源列→属性) → 导入。重复导入按(源系统,源ID)幂等更新同一记录。
   注册到 window.MDM_RENDERERS.ingest。 */
(function () {
  'use strict';
  window.MDM_RENDERERS = window.MDM_RENDERERS || {};

  window.MDM_RENDERERS.ingest = function (c) {
    var st = { attrs: [], cols: [] };
    var card = DN.card({ title: '源表导入配置', icon: 'download' });
    c.appendChild(card.el);
    var body = card.body;

    // 行1: 目标实体
    var entitySel = DN.h('select', { class: 'dn-form-select', style: 'min-width:260px' });
    entitySel.appendChild(DN.h('option', { value: '', text: '加载实体中…' }));
    body.appendChild(rowField('目标实体', entitySel));

    // 行2: 源库 / 源表 / 加载列
    var dbI = DN.h('input', { class: 'dn-form-input', style: 'width:180px', placeholder: '源库, 如 xh_dms' });
    var tbI = DN.h('input', { class: 'dn-form-input', style: 'width:220px', placeholder: '源表, 如 t_sales_order' });
    var loadBtn = DN.h('a', { class: 'btn btn-sm btn-primary', href: 'javascript:void(0)', text: '加载源列', style: 'background:var(--primary);color:var(--text-inverse)' });
    body.appendChild(rowField('源库 . 源表', DN.h('span', { style: 'display:inline-flex;gap:8px;align-items:center' }, [dbI, DN.h('span', { text: '.' }), tbI, loadBtn])));

    // 行3: 选项
    var sysI = DN.h('input', { class: 'dn-form-input', style: 'width:180px', placeholder: '默认=源库名' });
    var activeCb = DN.h('input', { type: 'checkbox' }); activeCb.checked = true;
    var limitI = DN.h('input', { class: 'dn-form-input', type: 'number', style: 'width:100px', value: '500' });
    body.appendChild(rowField('源系统标识', DN.h('span', { style: 'display:inline-flex;gap:18px;align-items:center;flex-wrap:wrap' }, [
      sysI,
      DN.h('label', { style: 'display:inline-flex;gap:5px;align-items:center;font-size:13px;cursor:pointer' }, [activeCb, DN.h('span', { text: '导入即生效(否则草稿)' })]),
      DN.h('span', { style: 'display:inline-flex;gap:5px;align-items:center;font-size:13px' }, [DN.h('span', { text: '最多导入行' }), limitI])
    ])));

    // 映射区(加载列后填充)
    var mapBox = DN.h('div', { style: 'margin-top:6px' });
    body.appendChild(mapBox);

    // 导入按钮 + 结果
    var importBtn = DN.h('a', { class: 'btn btn-primary', 'data-perm': 'mdm:manage', href: 'javascript:void(0)', text: '开始导入', style: 'background:var(--primary);color:var(--text-inverse);margin-top:10px;display:none' });
    var resultBox = DN.h('div', { style: 'margin-top:12px' });
    body.appendChild(importBtn);
    body.appendChild(resultBox);

    // ---- 加载实体 ----
    DN.get('/api/mdm/entities').then(function (list) {
      entitySel.innerHTML = '';
      var arr = Array.isArray(list) ? list : [];
      if (!arr.length) { entitySel.appendChild(DN.h('option', { value: '', text: '(暂无实体, 请先在域与实体建模创建)' })); return; }
      entitySel.appendChild(DN.h('option', { value: '', text: '请选择目标实体' }));
      arr.forEach(function (e) { entitySel.appendChild(DN.h('option', { value: e.id, text: (e.domainName ? e.domainName + ' / ' : '') + (e.entityName || ('#' + e.id)) })); });
    }).catch(function () { entitySel.innerHTML = ''; entitySel.appendChild(DN.h('option', { value: '', text: '(实体加载失败)' })); });

    entitySel.onchange = function () {
      st.attrs = [];
      mapBox.innerHTML = ''; importBtn.style.display = 'none'; resultBox.innerHTML = '';
      var eid = entitySel.value;
      if (!eid) return;
      DN.get('/api/mdm/attributes?entityId=' + encodeURIComponent(eid)).then(function (a) {
        st.attrs = Array.isArray(a) ? a : [];
        if (st.cols.length) buildMapping(); // 已加载源列则重建映射
      }).catch(function () { DN.toast('属性加载失败', 'err'); });
    };

    loadBtn.onclick = function () {
      if (loadBtn.textContent === '加载中…') return; // 防重复点击导致并发请求
      var db = dbI.value.trim(), tb = tbI.value.trim();
      if (!db || !tb) { DN.toast('请填源库与源表', 'warn'); return; }
      if (!entitySel.value) { DN.toast('请先选择目标实体', 'warn'); return; }
      loadBtn.textContent = '加载中…';
      DN.get('/api/mdm/ingest/columns?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(tb)).then(function (cols) {
        st.cols = Array.isArray(cols) ? cols : [];
        loadBtn.textContent = '加载源列';
        if (!st.cols.length) { mapBox.innerHTML = ''; importBtn.style.display = 'none'; resultBox.innerHTML = ''; mapBox.appendChild(DN.empty('源表无列或读取为空', 'list')); return; }
        if (!sysI.value.trim()) sysI.value = db;
        buildMapping();
      }).catch(function (e) { loadBtn.textContent = '加载源列'; DN.toast('读取源列失败: ' + (e && e.message || ''), 'err'); });
    };

    // ---- 构建字段映射 UI(源列 → 属性, 含源ID列选择) ----
    var srcIdSel;
    function buildMapping() {
      mapBox.innerHTML = '';
      mapBox.appendChild(DN.h('div', { style: 'font-weight:600;font-size:13px;margin:10px 0 6px', text: '字段映射(源列 → 主数据属性)' }));
      // 源ID列
      srcIdSel = DN.h('select', { class: 'dn-form-select', style: 'min-width:200px' });
      srcIdSel.appendChild(DN.h('option', { value: '', text: '(用业务主键作源ID)' }));
      st.cols.forEach(function (col) { srcIdSel.appendChild(DN.h('option', { value: col, text: col })); });
      // 默认源ID列优先 id
      st.cols.forEach(function (col) { if (/^id$/i.test(col)) srcIdSel.value = col; });
      mapBox.appendChild(rowField('源ID列(幂等键)', srcIdSel));

      var tbl = DN.h('table', { style: 'border-collapse:collapse;width:100%;font-size:13px;margin-top:6px' });
      var hr = DN.h('tr', {});
      ['源列', '→ 主数据属性'].forEach(function (h) { hr.appendChild(DN.h('th', { text: h, style: 'border:1px solid var(--border);padding:6px 10px;text-align:left;background:var(--bg-main)' })); });
      tbl.appendChild(hr);
      st._sel = {};
      st.cols.forEach(function (col) {
        var sel = DN.h('select', { class: 'dn-form-select', style: 'min-width:220px' });
        sel.appendChild(DN.h('option', { value: '', text: '(不导入)' }));
        st.attrs.forEach(function (a) {
          var tag = (a.isKey === 1 ? ' [关键]' : (a.isUnique === 1 ? ' [唯一]' : '')) + (a.required === 1 ? ' *' : '');
          sel.appendChild(DN.h('option', { value: a.attrCode, text: (a.attrName || a.attrCode) + tag }));
        });
        // 自动匹配: 源列名 == attrCode/attrName(忽略大小写)
        var auto = st.attrs.filter(function (a) { var lc = col.toLowerCase(); return (a.attrCode || '').toLowerCase() === lc || (a.attrName || '').toLowerCase() === lc; })[0];
        if (auto) sel.value = auto.attrCode;
        st._sel[col] = sel;
        var tr = DN.h('tr', {});
        tr.appendChild(DN.h('td', { text: col, title: col, style: 'border:1px solid var(--border);padding:5px 10px;max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }));
        var td2 = DN.h('td', { style: 'border:1px solid var(--border);padding:5px 10px' }); td2.appendChild(sel);
        tr.appendChild(td2);
        tbl.appendChild(tr);
      });
      mapBox.appendChild(tbl);
      importBtn.style.display = 'inline-block';
    }

    importBtn.onclick = function () {
      if (!entitySel.value) { DN.toast('请选择目标实体', 'warn'); return; }
      var mapping = {};
      Object.keys(st._sel || {}).forEach(function (col) { var v = st._sel[col].value; if (v) mapping[col] = v; });
      if (!Object.keys(mapping).length) { DN.toast('请至少映射一个字段', 'warn'); return; }
      DN.confirm('确认从 ' + dbI.value.trim() + '.' + tbI.value.trim() + ' 导入到所选实体的黄金记录？\n重复导入将按(源系统,源ID)幂等更新。', { title: '导入确认' }).then(function (ok) {
        if (!ok) return;
        importBtn.textContent = '导入中…'; importBtn.style.pointerEvents = 'none';
        DN.post('/api/mdm/ingest/from-table', {
          entityId: Number(entitySel.value), db: dbI.value.trim(), table: tbI.value.trim(),
          mapping: mapping, sourceSystem: sysI.value.trim(), sourceIdColumn: srcIdSel ? srcIdSel.value : '',
          limit: Number(limitI.value) || 500, activate: !!activeCb.checked
        }).then(function (r) {
          importBtn.textContent = '开始导入'; importBtn.style.pointerEvents = '';
          r = r || {};
          resultBox.innerHTML = '';
          resultBox.appendChild(DN.statRow([
            { icon: 'list', label: '读取行', value: r.total || 0 },
            { icon: 'check', label: '新建', value: r.created || 0, tone: 'ok' },
            { icon: 'edit', label: '更新', value: r.updated || 0, tone: 'info' },
            { icon: 'alert', label: '跳过', value: r.skipped || 0, tone: (r.skipped ? 'warn' : 'muted') }
          ]));
          var errs = r.errors || [];
          if (errs.length) {
            var ec = DN.h('div', { style: 'margin-top:8px' });
            ec.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin:0 0 4px', text: '部分行未导入(前 ' + errs.length + ' 条):' }));
            errs.forEach(function (m) { ec.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);line-height:1.7', text: '· ' + m })); });
            resultBox.appendChild(ec);
          }
          DN.toast('导入完成: 新建 ' + (r.created || 0) + ' / 更新 ' + (r.updated || 0), 'ok');
          resultBox.appendChild(DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '查看黄金记录', style: 'margin-top:8px', onclick: function () { if (window.mdmGoModule) mdmGoModule('goldenrecord'); } }));
        }).catch(function (e) {
          importBtn.textContent = '开始导入'; importBtn.style.pointerEvents = '';
          DN.toast('导入失败: ' + (e && e.message || ''), 'err');
        });
      });
    };

    function rowField(label, node) {
      return DN.h('div', { style: 'display:flex;gap:12px;align-items:center;margin-bottom:10px;flex-wrap:wrap' }, [
        DN.h('span', { text: label, style: 'min-width:96px;font-size:13px;color:var(--text-regular)' }),
        node
      ]);
    }
  };
})();
