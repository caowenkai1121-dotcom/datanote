/* 治理模块：分类分级 + 敏感识别（M8）—— 分级模型 / 规则管理 / 采样识别 / 人工打标 */
(function () {
  'use strict';
  window.GOV_RENDERERS = window.GOV_RENDERERS || {};

  var levelNames = []; // 当前 scheme 下的密级名称(供确认下拉)
  var clPicker = null; // 对表识别库表选择器
  var _focusWaitTimer = null; // focusScanOnTable 等表选项的轮询句柄: 新一次定位前先清掉上一次, 防累积无主定时器
  var rulesTable = null; // 敏感规则表格句柄
  var _scanBtn = null; // 「识别」按钮句柄(加载中置灰)
  var _pendBtn = null; // 「扫描待确认列」按钮句柄(加载中置灰)
  var _busy = {}; // 各异步动作的去重/防重复提交标记(scan/pending/levels/rules/heat)

  function errMsg(e) { return (e && e.message) ? e.message : '请稍后重试'; }
  // 加载中禁用按钮(置灰+aria-disabled),返回还原函数,防重复提交
  function lockBtn(btn, busyText) {
    if (!btn) return function () {};
    var old = btn.textContent, oldPe = btn.style.pointerEvents;
    btn.textContent = busyText || '处理中…';
    btn.style.pointerEvents = 'none'; btn.style.opacity = '0.6'; btn.setAttribute('aria-disabled', 'true');
    return function () { btn.textContent = old; btn.style.pointerEvents = oldPe; btn.style.opacity = ''; btn.removeAttribute('aria-disabled'); };
  }

  window.GOV_RENDERERS.classification = function (c) {
    // 1. 分级模型(等级药丸作图例并入热力卡,模型切换挂卡头 actions)
    var schemeSel = DN.h('select', { id: 'clScheme', class: 'dn-form-select', style: 'width:auto;min-width:140px' });
    schemeSel.appendChild(DN.h('option', { value: 'NATIONAL', text: '国家三级' }));
    schemeSel.appendChild(DN.h('option', { value: 'FINANCE', text: '金融五级' }));
    schemeSel.addEventListener('change', loadLevels);

    // 敏感分布热力（按表敏感列数 Top30）
    var heatCard = DN.card({ title: '敏感分布热力（按表敏感列数）', icon: 'tag', actions: schemeSel });
    heatCard.el.classList.add('primary');
    heatCard.body.appendChild(DN.h('div', { id: 'clLevels' }, [DN.skeleton(1)]));
    heatCard.body.appendChild(DN.h('div', { id: 'clHeat' }, [DN.skeleton(2)]));
    c.appendChild(heatCard.el);

    // 待确认敏感列提醒（基于热力涉及的表，扫描出置信度中等待人工确认的列）
    var pendBtn = DN.h('a', { class: 'btn btn-ghost', href: 'javascript:void(0)', text: '扫描待确认列', 'data-perm': 'governance:manage', onclick: loadPendingCols });
    _pendBtn = pendBtn; // 供 loadPendingCols 加载中置灰防重复扫描
    var pendCard = DN.card({ title: '待确认敏感列提醒（置信度中等）', icon: 'alert', actions: pendBtn });
    c.appendChild(pendCard.el);
    pendCard.body.appendChild(DN.h('div', { class: 'gov-desc', text: '聚合已识别出敏感特征但置信度处于中等区间（50%~80%）的列，需人工确认后到「对表采样识别」中打标。' }));
    pendCard.body.appendChild(DN.h('div', { id: 'clPending' }, [DN.empty('点击右上角「扫描待确认列」开始聚合', 'alert')]));

    // 2. 敏感规则管理
    var addRuleBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '新增规则', 'data-perm': 'governance:manage', onclick: toggleRuleForm });
    var ruleCard = DN.card({ title: '敏感识别规则', icon: 'shield', actions: addRuleBtn });
    c.appendChild(ruleCard.el);
    ruleCard.body.appendChild(DN.h('div', { id: 'clRuleForm' })); // 就近 .gov-form 容器（替代 prompt）
    var rulesBox = DN.h('div', { id: 'clRules' }, [DN.skeleton(3)]);
    ruleCard.body.appendChild(rulesBox);

    // 3. 对表识别
    clPicker = DN.dbTablePicker({});
    var scanBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '识别', 'data-perm': 'governance:manage', onclick: scanTable });
    _scanBtn = scanBtn; // 供 scanTable 加载中置灰防重复识别
    var trailBtn = DN.h('a', { class: 'btn btn-ghost', href: 'javascript:void(0)', text: '打标历史', onclick: function () {
      var db = clPicker.db(), table = clPicker.table();
      if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
      showAuditTrail(db, table);
    } });
    var scanCard = DN.card({ title: '对表采样识别', icon: 'search' });
    c.appendChild(scanCard.el);
    var q = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;flex-wrap:wrap' });
    q.appendChild(clPicker.el);
    q.appendChild(scanBtn);
    q.appendChild(trailBtn);
    scanCard.body.appendChild(q);
    scanCard.body.appendChild(DN.h('div', { id: 'clScan' }));

    loadLevels();
    loadRules();
    loadHeatmap();

    // R21 深链：接收 ctx.table({db,table}) 时，自动定位到「对表采样识别」并识别该表的列密级
    var ctx = window.__govCtx || {};
    var t = ctx.table || (ctx.db && (ctx.tableName || ctx.table) ? { db: ctx.db, table: ctx.tableName || ctx.table } : null);
    if (t && t.db && t.table) {
      // 等待 picker 库清单异步就绪后再回填识别
      setTimeout(function () { focusScanOnTable(t.db, t.table); }, 0);
    }
  };

  var _clHeatRows = [];
  function loadHeatmap() {
    var box = document.getElementById('clHeat');
    if (box) { box.innerHTML = ''; box.appendChild(DN.skeleton(2)); }
    DN.get('/api/gov/classification/heatmap').then(function (rows) {
      _clHeatRows = Array.isArray(rows) ? rows : [];
      renderClHeat();
    }).catch(function (e) {
      _clHeatRows = [];
      var b = document.getElementById('clHeat');
      if (b) { b.innerHTML = ''; b.appendChild(DN.errorBox('敏感分布热力加载失败：' + errMsg(e), loadHeatmap)); }
    });
  }
  function renderClHeat() {
    var box = document.getElementById('clHeat'); if (!box) return;
    var rows = Array.isArray(_clHeatRows) ? _clHeatRows : [];
    box.innerHTML = '';
    if (!rows.length) { box.appendChild(DN.empty('暂无已标敏感列（先在下方采样识别并确认打标）', 'tag')); return; }
    var totalCols = rows.reduce(function (s, r) { return s + (Number(r.count) || 0); }, 0);
    var maxRow = rows.reduce(function (m, r) { return (Number(r.count) || 0) > (Number(m.count) || 0) ? r : m; }, rows[0]);
    box.appendChild(DN.statRow([
      { icon: 'tag', label: '已标敏感列', value: totalCols, tone: 'warn' },
      { icon: 'db', label: '涉及表数', value: rows.length },
      { icon: 'alert', label: '单表最多', value: (Number(maxRow.count) || 0) + ' 列', sub: maxRow.db + '.' + maxRow.table }
    ]));
    box.appendChild(DN.heat(rows.map(function (r) {
      return { label: r.db + '.' + r.table, value: Number(r.count) || 0, _db: r.db, _table: r.table };
    }), {
      rgb: [255, 77, 79],
      onClick: function (it) { showAuditTrail(it._db, it._table); }
    }));
  }
  function showAuditTrail(db, table) {
    if (!db || !table) { DN.toast('缺少库或表信息，无法查看打标历史', 'error'); return; }
    var d = DN.drawer('打标审计溯源 · ' + db + '.' + table, DN.skeleton(4));
    DN.get('/api/gov/classification/audit-trail?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table)).then(function (rows) {
      rows = Array.isArray(rows) ? rows : [];
      var body = DN.h('div', {});
      body.appendChild(DN.h('div', { class: 'gov-desc', text: db + '.' + table + ' · 共 ' + rows.length + ' 条打标记录' }));
      if (!rows.length) { body.appendChild(DN.empty('该表暂无打标历史', 'doc')); }
      else body.appendChild(DN.table({
        search: false, pageSize: 15,
        columns: [
          { key: 'columnName', label: '列', render: function (r) { return ellip(r.columnName, 30); } },
          { label: '原密级', render: function (r) { return r.oldLevel || '-'; } },
          { label: '新密级', render: function (r) { return DN.pill(r.newLevel || '-', 'info'); } },
          { key: 'sensitiveType', label: '敏感类型', render: function (r) { return ellip(r.sensitiveType, 20); } },
          { key: 'operator', label: '操作人', render: function (r) { return ellip(r.operator, 20); } },
          { label: '时间', render: function (r) { return DN.timeAgo(r.createdAt); } }
        ],
        rows: rows
      }));
      d.body.innerHTML = ''; d.body.appendChild(body); // 复用已打开抽屉,避免叠开第二层
    }).catch(function (e) {
      d.body.innerHTML = '';
      d.body.appendChild(DN.errorBox('打标历史加载失败：' + errMsg(e), function () { d.close(); showAuditTrail(db, table); }));
    });
  }

  function loadLevels() {
    var schemeSel = document.getElementById('clScheme');
    var box = document.getElementById('clLevels');
    if (!schemeSel || !box) return;
    var scheme = schemeSel.value;
    box.innerHTML = ''; box.appendChild(DN.skeleton(1));
    DN.get('/api/gov/classification/levels?scheme=' + encodeURIComponent(scheme)).then(function (rows) {
      rows = Array.isArray(rows) ? rows : [];
      levelNames = rows.map(function (r) { return r.levelName; }).filter(Boolean);
      box.innerHTML = '';
      if (!rows.length) { box.appendChild(DN.empty('该分级方案下暂无密级定义', 'layers')); return; }
      var wrap = DN.h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' });
      rows.forEach(function (r) { wrap.appendChild(DN.pill(r.levelName || '-', 'info')); });
      box.appendChild(wrap);
    }).catch(function (e) {
      levelNames = [];
      box.innerHTML = ''; box.appendChild(DN.errorBox('分级模型加载失败：' + errMsg(e), loadLevels));
    });
  }

  // 超长文本截断 + title 悬停查看全文(模式/类型可能很长)
  function ellip(s, n) {
    s = (s == null ? '' : String(s)); n = n || 40;
    if (s.length <= n) return s || '-';
    return DN.h('span', { title: s, text: s.slice(0, n) + '…' });
  }

  function loadRules() {
    var box = document.getElementById('clRules');
    if (!box) return;
    box.innerHTML = ''; box.appendChild(DN.skeleton(3));
    DN.get('/api/gov/classification/rules').then(function (rows) {
      rows = Array.isArray(rows) ? rows : [];
      box.innerHTML = '';
      var tbl = DN.table({
        columns: [
          { key: 'ruleName', label: '规则名', render: function (r) { return ellip(r.ruleName, 30); } },
          { label: '匹配', render: function (r) { return DN.pill(r.matchType || '-', 'muted'); } },
          { key: 'pattern', label: '模式', render: function (r) { return ellip(r.pattern, 40); } },
          { key: 'sensitiveType', label: '类型', render: function (r) { return ellip(r.sensitiveType, 20); } },
          { label: '建议密级', render: function (r) { return r.suggestLevel || '-'; } },
          { label: '状态', render: function (r) { return DN.pill(r.enabled === 1 ? '启用' : '停用', r.enabled === 1 ? 'ok' : 'muted'); } },
          { label: '操作', render: renderRuleOps }
        ],
        rows: rows,
        searchKeys: ['ruleName', 'matchType', 'pattern', 'sensitiveType', 'suggestLevel'],
        searchPlaceholder: '搜索规则名/类型/模式...',
        empty: '暂无规则', emptyIcon: 'shield'
      });
      rulesTable = tbl;
      box.appendChild(tbl);
    }).catch(function (e) {
      box.innerHTML = ''; box.appendChild(DN.errorBox('敏感规则加载失败：' + errMsg(e), loadRules));
    });
  }

  function renderRuleOps(r) {
    var wrap = DN.h('span', {});
    var toggleA = DN.h('a', {
      href: 'javascript:void(0)', text: r.enabled === 1 ? '停用' : '启用',
      'data-perm': 'governance:manage',
      style: 'color:var(--primary);margin-right:12px',
      onclick: function () {
        var restore = lockBtn(toggleA, '处理中…');
        DN.post('/api/gov/classification/rules/' + r.id + '/toggle')
          .then(function () { DN.toast(r.enabled === 1 ? '已停用' : '已启用', 'success'); loadRules(); })
          .catch(function (e) { restore(); DN.toast(errMsg(e), 'error'); });
      }
    });
    wrap.appendChild(toggleA);
    var delA = DN.h('a', {
      href: 'javascript:void(0)', text: '删除', 'data-perm': 'governance:manage', style: 'color:var(--error)',
      onclick: function () {
        DN.confirm('确定删除规则「' + (r.ruleName || '') + '」吗？删除后将不再用于敏感识别，且无法撤销。', { title: '删除确认', danger: true }).then(function (ok) {
          if (!ok) return;
          var restore = lockBtn(delA, '删除中…');
          DN.del('/api/gov/classification/rules/' + r.id)
            .then(function () { DN.toast('已删除', 'success'); loadRules(); })
            .catch(function (e) { restore(); DN.toast(errMsg(e), 'error'); });
        });
      }
    });
    wrap.appendChild(delA);
    return wrap;
  }

  // 就近 .gov-form 新增规则表单（替代 window.prompt 链）
  function toggleRuleForm() {
    var box = document.getElementById('clRuleForm');
    if (!box) return;
    if (box.firstChild) { box.innerHTML = ''; return; }
    box.innerHTML = '';

    var name = DN.h('input', { class: 'dn-form-input', placeholder: '规则名' });
    var matchSel = DN.h('select', { class: 'dn-form-select' });
    [['COLUMN_NAME', '按列名关键词'], ['REGEX', '按正则'], ['VALIDATOR', '按校验器']].forEach(function (m) {
      matchSel.appendChild(DN.h('option', { value: m[0], text: m[1] + '(' + m[0] + ')' }));
    });
    var pattern = DN.h('input', { class: 'dn-form-input', placeholder: '关键词逗号分隔 / 正则 / 校验器名(PHONE,EMAIL,ID_CARD,BANKCARD,USCC)' });
    var sensitiveType = DN.h('input', { class: 'dn-form-input', placeholder: '敏感类型(如 PHONE)' });
    var suggestLevel = DN.h('input', { class: 'dn-form-input', placeholder: '建议密级(如 重要，可空)' });

    var panel = DN.h('div', { class: 'gov-form', style: 'max-width:560px' });

    var sec = DN.formSection('新增敏感规则');
    sec.add(DN.formGrid2([
      DN.field('规则名', name, { required: true }),
      DN.field('匹配方式', matchSel, { required: true })
    ]));
    sec.add(DN.field('模式', pattern, { required: true, hint: '关键词逗号分隔 / 正则 / 校验器名(PHONE,EMAIL,ID_CARD,BANKCARD,USCC)' }));
    sec.add(DN.formGrid2([
      DN.field('敏感类型', sensitiveType, { required: true }),
      DN.field('建议密级', suggestLevel, { hint: '可空，如：重要' })
    ]));
    panel.appendChild(sec.el);

    var saveBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '保存', 'data-perm': 'governance:manage',
      onclick: function () {
        var nm = name.value.trim(), pt = pattern.value.trim(), st = sensitiveType.value.trim();
        if (!nm) { DN.toast('规则名必填', 'error'); name.focus(); return; }
        if (nm.length > 60) { DN.toast('规则名过长（最多 60 字）', 'error'); name.focus(); return; }
        if (!pt) { DN.toast('模式必填', 'error'); pattern.focus(); return; }
        if (!st) { DN.toast('敏感类型必填', 'error'); sensitiveType.focus(); return; }
        // 正则匹配方式：校验模式是否为合法正则，避免存入坏规则
        if (matchSel.value === 'REGEX') {
          try { new RegExp(pt); } catch (re) { DN.toast('正则表达式不合法：' + re.message, 'error'); pattern.focus(); return; }
        }
        if (_busy.rules) return; // 去重:保存进行中不再并发提交
        _busy.rules = true;
        var restore = lockBtn(saveBtn, '保存中…');
        DN.post('/api/gov/classification/rules', {
          ruleName: nm, matchType: matchSel.value,
          pattern: pt, sensitiveType: st,
          suggestLevel: suggestLevel.value.trim(), enabled: 1
        }).then(function () { _busy.rules = false; DN.toast('已保存', 'success'); box.innerHTML = ''; loadRules(); })
          .catch(function (e) { _busy.rules = false; restore(); DN.toast(errMsg(e), 'error'); });
      } });
    var actions = DN.h('div', { style: 'display:flex;gap:8px;margin-top:4px' });
    actions.appendChild(saveBtn);
    actions.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '取消',
      onclick: function () { box.innerHTML = ''; } }));
    panel.appendChild(actions);
    box.appendChild(panel);
    DN.enterSubmit(panel, function () { saveBtn.onclick(); });
  }

  // 置信度着色：>=80 绿，>=50 黄，否则红
  function confTone(v) {
    v = Number(v) || 0;
    return v >= 80 ? 'ok' : v >= 50 ? 'warn' : 'err';
  }

  function scanTable() {
    if (!clPicker) return;
    var db = clPicker.db();
    var table = clPicker.table();
    if (!db || !table) { DN.toast('请先选择库与表', 'error'); return; }
    var box = document.getElementById('clScan');
    if (!box) return;
    if (_busy.scan) return; // 去重:识别进行中不再并发
    _busy.scan = true;
    var unlock = lockBtn(_scanBtn, '识别中…');
    box.innerHTML = '';
    box.appendChild(DN.skeleton(3));
    DN.get('/api/gov/classification/scan?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table))
      .then(function (rows) {
        _busy.scan = false; unlock();
        box.innerHTML = '';
        rows = Array.isArray(rows) ? rows : [];
        if (!rows.length) { box.appendChild(DN.empty('未识别到敏感列', 'check')); return; }
        rows.sort(function (a, b) { return (Number(b.confidence) || 0) - (Number(a.confidence) || 0); }); // 高置信度优先

        var opts = (levelNames || []).map(function (n) { return '<option value="' + DN.esc(n) + '">' + DN.esc(n) + '</option>'; }).join('');
        if (!levelNames || !levelNames.length) { box.appendChild(DN.alertNode('密级清单尚未加载，建议密级暂不可选，请在「敏感分布热力」卡右上角切换分级模型重新加载。', 'warn')); }
        var selAll = DN.h('input', { type: 'checkbox', title: '全选' });
        var confirmBtn = DN.h('a', { class: 'btn btn-primary', href: 'javascript:void(0)', text: '确认打标(已勾选)', 'data-perm': 'governance:manage' });
        var minConfSel = DN.h('select', { class: 'dn-form-select', style: 'width:auto' });
        [['0', '全部置信度'], ['50', '≥50%'], ['70', '≥70%'], ['80', '≥80%']].forEach(function (o) { minConfSel.appendChild(DN.h('option', { value: o[0], text: o[1] })); });
        minConfSel.onchange = function () { var mc = Number(minConfSel.value) || 0; rows.forEach(function (r) { if (r._cb) r._cb.checked = false; }); tbl.reload(rows.filter(function (r) { return (Number(r.confidence) || 0) >= mc; })); };
        var checkBtn = DN.h('a', { class: 'btn btn-ghost', href: 'javascript:void(0)', text: '勾选≥阈值', onclick: function () {
          var mc = Number(minConfSel.value) || 0, n = 0;
          rows.forEach(function (r) { if ((Number(r.confidence) || 0) >= mc) { r._cb.checked = true; n++; } });
          DN.toast('已勾选 ' + n + ' 列', 'info');
        } });

        var tbl = DN.table({
          search: false,
          toolbar: [minConfSel, checkBtn, confirmBtn],
          exportName: '敏感识别结果',
          columns: [
            { label: '', render: function (r) { return r._cb; } },
            { key: 'column', label: '列', exportValue: function (r) { return r.column; } },
            { key: 'sensitiveType', label: '敏感类型', exportValue: function (r) { return r.sensitiveType; } },
            { label: '置信度', exportValue: function (r) { return (r.confidence || 0) + '%'; }, render: function (r) { return DN.pill((r.confidence || 0) + '%', confTone(r.confidence)); } },
            { label: '当前密级', exportValue: function (r) { return r.currentLevel || ''; }, render: function (r) { return r.currentLevel || '-'; } },
            { label: '建议密级', exportValue: function (r) { return r.suggestLevel || ''; }, render: function (r) { return r._levelSel; } },
            { label: '操作', render: function (r) {
              return DN.h('a', { href: 'javascript:void(0)', text: '查看该表',
                title: '回到数据地图查看 ' + db + '.' + table + ' 的字段与血缘',
                style: 'color:var(--primary);text-decoration:none',
                onclick: function () { if (DN.tablePreview) DN.tablePreview(db, table); else if (window.navigateTo) navigateTo('catalog', { openTable: { db: db, table: table } }); } });
            } }
          ],
          rows: rows,
          empty: '未识别到敏感列', emptyIcon: 'check'
        });

        // 为每行就近构造勾选框与密级下拉（render 时已引用，先在 reload 前装配）
        rows.forEach(function (r) {
          r._cb = DN.h('input', { type: 'checkbox', 'aria-label': '选择 ' + (r.db || '') + '.' + (r.table || '') + '.' + (r.column || '') });
          var s = DN.h('select', { class: 'dn-form-select', style: 'width:auto;min-width:110px' });
          s.innerHTML = opts;
          if (r.suggestLevel) s.value = r.suggestLevel;
          r._levelSel = s;
        });
        tbl.reload(rows);

        selAll.onclick = function () {
          var checked = selAll.checked;
          rows.forEach(function (r) { r._cb.checked = checked; });
        };
        // 把全选放进表头第一列标签位（toolbar 旁补一个全选开关，避免依赖表头渲染）
        var selAllWrap = DN.h('label', { style: 'display:inline-flex;align-items:center;gap:4px;font-size:13px;color:var(--text-muted)' },
          [selAll, DN.h('span', { text: '全选' })]);
        if (confirmBtn.parentNode) confirmBtn.parentNode.insertBefore(selAllWrap, confirmBtn);

        confirmBtn.onclick = function () { confirmLabels(db, table, rows, confirmBtn); };
        box.appendChild(tbl);
      }).catch(function (e) {
        _busy.scan = false; unlock();
        box.innerHTML = '';
        box.appendChild(DN.errorBox('识别失败: ' + errMsg(e), function () { scanTable(); }));
      });
  }

  function confirmLabels(db, table, rows, btn) {
    var picked = (rows || []).filter(function (r) { return r._cb && r._cb.checked; });
    if (!picked.length) { DN.toast('请先勾选要打标的列', 'error'); return; }
    // 校验：勾选的每列都必须选定新密级，否则打标无意义
    var noLevel = picked.filter(function (r) { return !r._levelSel || !r._levelSel.value; });
    if (noLevel.length) { DN.toast('有 ' + noLevel.length + ' 列未选择「建议密级」，请先选定密级', 'error'); return; }
    DN.confirm('将对 ' + picked.length + ' 列写入新密级标记，确认打标？', { title: '打标确认' }).then(function (ok) {
      if (!ok) return;
      var restore = lockBtn(btn, '打标中…');
      // 逐列独立提交：每个请求各自吞错收集成败，部分成功也不丢已落库的列
      var tasks = picked.map(function (r) {
        return DN.post('/api/gov/classification/confirm', {
          db: db, table: table, column: r.column, newLevel: r._levelSel.value,
          sensitiveType: r.sensitiveType, reason: '采样识别确认'
        }).then(function () { return true; }).catch(function () { return false; });
      });
      Promise.all(tasks).then(function (results) {
        restore();
        var okCnt = results.filter(function (v) { return v; }).length;
        var fail = results.length - okCnt;
        if (fail === 0) {
          DN.toast('已打标 ' + okCnt + ' 列', 'success');
        } else {
          DN.toast('已打标 ' + okCnt + '/' + results.length + ' 列，' + fail + ' 列失败', 'error');
        }
        // 无论部分成功还是全成功，都刷新热力与识别结果，保证界面与库一致
        loadHeatmap(); // 打标后敏感分布热力同步刷新(联动)
        scanTable();
      });
    });
  }

  // 待确认敏感列：对热力涉及的表逐表采样识别，聚合置信度 50%~80% 的列（复用 scan 端点）
  function loadPendingCols() {
    var box = document.getElementById('clPending'); if (!box) return;
    if (_busy.pending) return; // 去重:扫描进行中不再并发
    var tables = (Array.isArray(_clHeatRows) ? _clHeatRows : []).filter(function (r) { return r && r.db && r.table; });
    if (!tables.length) {
      box.innerHTML = '';
      var em0 = DN.empty('暂无已标敏感表可供扫描', 'tag');
      em0.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '前往「对表采样识别」打标',
        style: 'color:var(--primary);font-size:12px;text-decoration:none',
        onclick: function () { if (clPicker && clPicker.el && clPicker.el.closest) { var card = clPicker.el.closest('.gov-card'); if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'start' }); } } }));
      box.appendChild(em0);
      return;
    }
    _busy.pending = true;
    var unlock = lockBtn(_pendBtn, '扫描中…');
    var scanList = tables.slice(0, 30); // 上限守卫：仅扫描敏感列数最多的前 30 张表
    box.innerHTML = '';
    box.appendChild(DN.h('div', { class: 'gov-desc', text: '正在扫描 ' + scanList.length + ' 张表…' }));
    box.appendChild(DN.skeleton(3));
    var jobs = scanList.map(function (t) {
      return DN.get('/api/gov/classification/scan?db=' + encodeURIComponent(t.db) + '&table=' + encodeURIComponent(t.table))
        .then(function (cols) { return { db: t.db, table: t.table, cols: Array.isArray(cols) ? cols : [] }; })
        .catch(function () { return { db: t.db, table: t.table, cols: [] }; });
    });
    Promise.all(jobs).then(function (results) {
      _busy.pending = false; unlock();
      var pending = [];
      results.forEach(function (res) {
        (res.cols || []).forEach(function (col) {
          var conf = Number(col.confidence) || 0;
          if (conf >= 50 && conf < 80) {
            pending.push({
              db: res.db, table: res.table,
              column: col.column || '',
              sensitiveType: col.sensitiveType || '-',
              confidence: conf,
              currentLevel: col.currentLevel || '',
              suggestLevel: col.suggestLevel || ''
            });
          }
        });
      });
      pending.sort(function (a, b) { return b.confidence - a.confidence; }); // 高置信优先（更接近可确认）
      renderPendingCols(box, pending, scanList.length, tables.length);
    }).catch(function (e) {
      _busy.pending = false; unlock();
      box.innerHTML = ''; box.appendChild(DN.errorBox('待确认列扫描失败：' + errMsg(e), loadPendingCols));
    });
  }

  // 定位到「对表采样识别」：回填库/表选择器并重新识别（picker 内部用原生 select，标准 DOM 回填）
  function focusScanOnTable(db, table) {
    if (!clPicker || !db || !table) { DN.toast('无法定位该表', 'error'); return; }
    var sels = clPicker.el.querySelectorAll('select');
    var dbSel = sels[0], tbSel = sels[1];
    if (!dbSel || !tbSel) { DN.toast('选择器加载失败，请在「对表采样识别」中手动选择库与表后识别', 'error'); return; }
    // 回填库并触发其 onchange 异步加载表清单，轮询等待目标表选项出现后回填表并识别
    if (dbSel.value !== db) { dbSel.value = db; if (typeof dbSel.onchange === 'function') dbSel.onchange(); }
    if (_focusWaitTimer) { clearTimeout(_focusWaitTimer); _focusWaitTimer = null; }
    var tries = 0;
    (function waitTable() {
      var hit = Array.prototype.some.call(tbSel.options, function (o) { return o.value === table; });
      if (hit) {
        _focusWaitTimer = null;
        tbSel.value = table; if (typeof tbSel.onchange === 'function') tbSel.onchange();
        var card = clPicker.el.closest ? clPicker.el.closest('.gov-card') : null;
        if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
        scanTable();
      } else if (tries++ < 20) { _focusWaitTimer = setTimeout(waitTable, 100); }
      else { _focusWaitTimer = null; DN.toast('已定位到库「' + db + '」，请手动选择表「' + table + '」后识别', 'info'); }
    })();
  }

  function renderPendingCols(box, rows, scanned, totalTables) {
    box.innerHTML = '';
    if (!rows.length) {
      var em = DN.empty('已扫描 ' + scanned + ' 张表，未发现置信度中等（50%~80%）待确认的列', 'check');
      em.appendChild(DN.h('div', { class: 'gov-desc', style: 'margin-top:6px;font-size:12px',
        text: '说明：识别结果均为高置信（≥80%，可直接打标）或低置信（<50%，可忽略）。如需复核，可在下方「对表采样识别」中逐表查看。' }));
      box.appendChild(em);
      return;
    }
    var tableSet = {}, typeSet = {}, confSum = 0;
    rows.forEach(function (r) { tableSet[r.db + '.' + r.table] = 1; if (r.sensitiveType) typeSet[r.sensitiveType] = 1; confSum += (Number(r.confidence) || 0); });
    var avgConf = rows.length ? Math.round(confSum / rows.length) : 0; // 除零守卫
    box.appendChild(DN.statRow([
      { icon: 'alert', label: '待确认列', value: rows.length, tone: 'warn' },
      { icon: 'db', label: '涉及表数', value: Object.keys(tableSet).length, sub: '已扫描 ' + scanned + (totalTables > scanned ? '/' + totalTables : '') + ' 表' },
      { icon: 'tag', label: '敏感类型数', value: Object.keys(typeSet).length },
      { icon: 'check', label: '平均置信度', value: avgConf + '%', tone: confTone(avgConf) }
    ]));
    box.appendChild(DN.table({
      exportName: '待确认敏感列',
      columns: [
        { key: 'db', label: '库', sortable: true, copyable: true, exportValue: function (r) { return r.db; } },
        { key: 'table', label: '表', sortable: true, exportValue: function (r) { return r.table; },
          render: function (r) {
            return DN.h('a', { href: 'javascript:void(0)', text: r.table || '-',
              title: '定位到「对表采样识别」复核 ' + (r.db || '') + '.' + (r.table || '') + ' 后打标',
              style: 'color:var(--primary);text-decoration:none',
              onclick: function () { focusScanOnTable(r.db, r.table); } });
          } },
        { key: 'column', label: '列', sortable: true, copyable: true, exportValue: function (r) { return r.column; } },
        { key: 'sensitiveType', label: '敏感类型', sortable: true, exportValue: function (r) { return r.sensitiveType; } },
        { key: 'confidence', label: '置信度', align: 'center', sortable: true, exportValue: function (r) { return r.confidence + '%'; }, render: function (r) { return DN.pill(r.confidence + '%', confTone(r.confidence)); } },
        { label: '当前密级', exportValue: function (r) { return r.currentLevel || ''; }, render: function (r) { return r.currentLevel || '-'; } },
        { label: '建议密级', exportValue: function (r) { return r.suggestLevel || ''; }, render: function (r) { return r.suggestLevel ? DN.pill(r.suggestLevel, 'info') : '-'; } },
        { label: '操作', render: function (r) {
          return DN.h('a', { href: 'javascript:void(0)', text: '查看该表',
            title: '回到数据地图查看 ' + (r.db || '') + '.' + (r.table || '') + ' 的字段与血缘',
            style: 'color:var(--primary);text-decoration:none',
            onclick: function () { if (DN.tablePreview) DN.tablePreview(r.db, r.table); else if (window.navigateTo) navigateTo('catalog', { openTable: { db: r.db, table: r.table } }); } });
        } }
      ],
      rows: rows,
      searchKeys: ['db', 'table', 'column', 'sensitiveType'],
      searchPlaceholder: '搜索库/表/列/类型...',
      empty: '无待确认列', emptyIcon: 'check'
    }));
  }
})();
