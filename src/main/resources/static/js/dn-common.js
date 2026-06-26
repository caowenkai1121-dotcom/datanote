/* DataNote 治理公共层 —— 被 governance.html 等治理页面复用。零框架依赖。 */
(function (global) {
  'use strict';
  var DN = {};

  // CSRF(P1): 鉴权开启后写请求自动带 X-XSRF-TOKEN(读 Spring 下发的 XSRF-TOKEN cookie)。
  // 仅同源 + 非安全方法注入; 开放模式无该 cookie 则跳过。一处覆盖 DN.* 与所有裸 fetch。
  (function () {
    if (global.__dnCsrfPatched || typeof global.fetch !== 'function') return;
    global.__dnCsrfPatched = true;
    var _fetch = global.fetch.bind(global);
    function csrfToken() {
      var m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
      return m ? decodeURIComponent(m[1]) : null;
    }
    global.fetch = function (input, init) {
      init = init || {};
      var method = (init.method || (input && typeof input === 'object' && input.method) || 'GET').toUpperCase();
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var sameOrigin = url.charAt(0) === '/' || url.indexOf(global.location.origin) === 0;
      if (sameOrigin && method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
        var t = csrfToken();
        if (t) {
          var h = new Headers(init.headers || {});
          if (!h.has('X-XSRF-TOKEN')) h.set('X-XSRF-TOKEN', t);
          init.headers = h;
        }
        if (!init.credentials) init.credentials = 'same-origin';
      }
      return _fetch(input, init);
    };
  })();

  /** 统一请求：解析 R<T> 信封（code===0 成功），失败抛 Error */
  DN.api = function (url, options) {
    return fetch(url, options || {}).then(function (resp) {
      var _bad = false;
      return resp.json().catch(function () { _bad = true; return {}; }).then(function (body) {
        if (body && typeof body.code !== 'undefined') {
          if (body.code === 0) return body.data;
          throw new Error(body.msg || ('请求失败(' + body.code + ')'));
        }
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        if (_bad) throw new Error('响应格式错误(非JSON)'); // 服务器返回HTML错误页等
        return body;
      });
    });
  };

  DN.get = function (url) { return DN.api(url); };

  DN.post = function (url, data) {
    return DN.api(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: data != null ? JSON.stringify(data) : undefined
    });
  };

  DN.put = function (url, data) {
    return DN.api(url, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: data != null ? JSON.stringify(data) : undefined
    });
  };

  DN.del = function (url) { return DN.api(url, { method: 'DELETE' }); };

  /** 轻量 toast 提示 */
  DN.toast = function (msg, type) {
    // 容器纵向堆叠(原各 toast 都 fixed top:64 right:24 会重叠丢信息): 多 toast 依次排列
    var box = document.getElementById('dn-toast-box');
    if (!box) {
      box = document.createElement('div'); box.id = 'dn-toast-box';
      box.style.cssText = 'position:fixed;top:64px;right:24px;z-index:var(--z-toast,9999);display:flex;flex-direction:column;gap:8px;align-items:flex-end;pointer-events:none;';
      document.body.appendChild(box);
    }
    var t = document.createElement('div');
    t.className = 'dn-toast dn-toast-' + (type || 'info');
    t.style.position = 'static'; t.style.pointerEvents = 'auto';   // 覆盖 .dn-toast 的 fixed, 改由容器排布
    t.setAttribute('role', 'status'); t.setAttribute('aria-live', 'polite'); // 屏幕阅读器播报提示
    t.textContent = msg;
    box.appendChild(t);
    setTimeout(function () { t.classList.add('dn-toast-show'); }, 10);
    setTimeout(function () {
      t.classList.remove('dn-toast-show');
      setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 300);
    }, 2600);
  };

  /** 复制文本到剪贴板：优先 navigator.clipboard，降级 textarea+execCommand。silent=true 不弹 toast(调用方自定义反馈) */
  DN.copy = function (text, silent) {
    text = String(text == null ? '' : text);
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { if (!silent) DN.toast('已复制', 'ok'); }, function () { if (!silent) DN.toast('复制失败', 'err'); });
        return;
      }
    } catch (e) {}
    try {
      var ta = document.createElement('textarea'); ta.value = text;
      ta.style.position = 'fixed'; ta.style.opacity = '0'; document.body.appendChild(ta); ta.select();
      document.execCommand('copy'); document.body.removeChild(ta); if (!silent) DN.toast('已复制', 'ok');
    } catch (e2) { if (!silent) DN.toast('复制失败', 'err'); }
  };

  /** DOM 简化器：DN.h('div', {class:'x', onclick:fn}, [child|text]) */
  DN.h = function (tag, attrs, children) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (k === 'class') el.className = v;
        else if (k === 'html') el.innerHTML = v;
        else if (k === 'text') el.textContent = (v == null ? '' : v);
        else if (k.indexOf('on') === 0 && typeof v === 'function') el.addEventListener(k.slice(2), v);
        else if (v != null) el.setAttribute(k, v);   // 跳过 null/undefined,避免写出字面 "null"/"undefined" 属性
      });
    }
    if (children != null) {
      if (!Array.isArray(children)) children = [children];
      children.forEach(function (c) {
        if (c == null) return;
        el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
      });
    }
    return el;
  };

  /** HTML 转义 */
  DN.esc = function (s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  };

  /** 字节可读化 */
  DN.fmtBytes = function (n) {
    if (n == null) return '-';
    var v = Number(n); if (isNaN(v) || v < 0) return '-';
    var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return v.toFixed(1) + ' ' + u[i];
  };

  // 评分着色档(>=85 ok / >=60 warn / else err) —— 原 home-dashboard/gov-overview 各重复一份, 合并至此
  DN.tone = function (v) { var n = Number(v); if (!isFinite(n)) n = 0; return n >= 85 ? 'ok' : (n >= 60 ? 'warn' : 'err'); };
  DN.round1 = function (v) { return Math.round((Number(v) || 0) * 10) / 10; };
  DN.fmtInt = function (v) { return String(Math.round(Number(v) || 0)).replace(/\B(?=(\d{3})+(?!\d))/g, ','); };

  // 友好相对时间："3分钟前"/"2小时前"/"昨天 14:30"，超 7 天回退绝对时间。无效/空返回 '-'。
  DN.fmtAgo = function (ts) {
    if (ts == null || ts === '') return '-';
    var s = String(ts).replace('T', ' ').trim();
    // 兼容 'YYYY-MM-DD HH:mm:ss' 与 ISO，统一按本地时间解析
    var d = new Date(s.indexOf('-') > 0 ? s.replace(/-/g, '/').split('.')[0] : s);
    if (isNaN(d.getTime())) return String(ts);
    var diff = (Date.now() - d.getTime()) / 1000;       // 秒
    if (diff < 0) return s.slice(0, 19);                 // 未来时间直接显示绝对
    if (diff < 60) return '刚刚';
    if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前';
    if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前';
    // 「昨天」按自然日历日判定（而非 48 小时时间差），跨日边界才准确
    var d0 = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    var now = new Date();
    var n0 = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    var dayDiff = Math.round((n0 - d0) / 86400000);
    if (dayDiff === 1) return '昨天 ' + s.slice(11, 16);
    if (diff < 604800) return Math.floor(diff / 86400) + ' 天前';
    return s.slice(0, 10);                               // 超 7 天显示日期
  };

  // 相对时间 <span>，title 挂完整绝对时间（鼠标悬停可见，审计可溯源）
  DN.timeAgo = function (ts) {
    var full = ts == null ? '' : String(ts).replace('T', ' ').slice(0, 19);
    return DN.h('span', { text: DN.fmtAgo(ts), title: full, style: 'white-space:nowrap' });
  };

  // 表单回车提交：在容器内输入框按 Enter 触发提交（textarea/Shift+Enter 不触发，避免误提交）。
  // fn 省略时自动点击容器内首个 .btn-primary 按钮。
  DN.enterSubmit = function (container, fn) {
    if (!container || !container.addEventListener) return;
    container.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
      var t = e.target, tag = t && t.tagName;
      if (tag === 'TEXTAREA' || tag === 'BUTTON' || tag === 'A') return;
      if (typeof fn === 'function') { e.preventDefault(); fn(); return; }
      var btn = container.querySelector('.btn-primary');
      if (btn && !btn.disabled) { e.preventDefault(); btn.click(); }
    });
  };

  // ===== 元数据下拉数据源（数仓侧，复用工作台同款接口） =====
  DN.metaDatabases = function () { return DN.get('/api/metadata/databases'); };
  DN.metaTables = function (db) { return DN.get('/api/metadata/tables?db=' + encodeURIComponent(db)); };
  DN.metaColumns = function (db, table) {
    return DN.get('/api/metadata/columns?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table));
  };
  /** 复制文本到剪贴板(clipboard API + execCommand 兜底) + 成功 toast。供各处复用。 */
  DN.copyText = function (text, okMsg) {
    var t = String(text == null ? '' : text);
    var done = function () { if (DN.toast) DN.toast(okMsg || ('已复制 ' + t), 'ok'); };
    var fb = function () { try { var ta = document.createElement('textarea'); ta.value = t; ta.style.position = 'fixed'; ta.style.opacity = '0'; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); document.body.removeChild(ta); } catch (e) {} };
    if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(t).then(done).catch(function () { fb(); done(); }); }
    else { fb(); done(); }
  };
  /** 表预览抽屉(点表名【原地出结果】, 不跳数据地图): 摘要+字段表, 末尾可跳完整视图。供任意模块复用(禁跳转#5)。 */
  DN.tablePreview = function (db, table) {
    if (!db || !table) return;
    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { text: '加载中…', style: 'padding:20px;color:var(--text-muted);font-size:13px;' }));
    var dr = DN.drawer(db + '.' + table, body);
    function row(k, v) { return DN.h('div', { style: 'display:flex;gap:10px;font-size:12.5px;margin:3px 0;' }, [DN.h('span', { text: k, style: 'color:var(--text-muted);min-width:56px;flex:0 0 auto;' }), DN.h('span', { text: String(v == null ? '-' : v), style: 'color:var(--text-regular);word-break:break-all;' })]); }
    Promise.all([
      DN.get('/api/metadata/table-detail?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table)).catch(function () { return null; }),
      DN.metaColumns(db, table).catch(function () { return []; }),
      DN.get('/api/quality/rules').catch(function () { return []; })   // 质量覆盖: 该表已配几条质量规则
    ]).then(function (rs) {
      var d = rs[0] || {}, info = d.tableInfo || {}, tm = d.tableMeta || {}, cols = rs[1] || [];
      var ruleN = (Array.isArray(rs[2]) ? rs[2] : []).filter(function (r) { return r && r.databaseName === db && r.tableName === table; }).length;
      body.innerHTML = '';
      var sum = DN.h('div', { style: 'border:1px solid var(--border);border-radius:var(--radius-md);padding:12px 14px;margin-bottom:12px;background:var(--bg-card);' });
      if (info.comment) sum.appendChild(row('说明', info.comment));
      sum.appendChild(row('行数', info.rowCount != null ? info.rowCount : '-'));
      if (info.engine) sum.appendChild(row('引擎', info.engine));
      if (tm.owner) sum.appendChild(row('负责人', tm.owner));
      if (tm.tags) sum.appendChild(DN.h('div', { style: 'display:flex;gap:10px;font-size:12.5px;margin:3px 0;align-items:flex-start;' }, [
        DN.h('span', { text: '标签', style: 'color:var(--text-muted);min-width:56px;flex:0 0 auto;' }),
        DN.h('span', { style: 'display:flex;flex-wrap:wrap;gap:4px;' }, String(tm.tags).split(',').map(function (t) { t = t.trim(); return t ? DN.pill(t, 'info') : null; }).filter(Boolean))
      ]));
      sum.appendChild(row('质量规则', ruleN > 0 ? (ruleN + ' 条') : '未配置(建议加)'));   // 治理覆盖可见
      if (info.updateTime) sum.appendChild(row('更新', info.updateTime));
      body.appendChild(sum);
      body.appendChild(DN.h('div', { text: '字段 (' + cols.length + ')', style: 'font-weight:600;font-size:13px;margin:8px 0 6px;color:var(--text-primary);' }));
      if (!cols.length) {
        body.appendChild(DN.h('div', { text: '暂无字段信息', style: 'color:var(--text-muted);font-size:12px;' }));
      } else {
        if (cols.length > 8) { // 宽表: 字段搜索过滤
          var fsearch = DN.h('input', { placeholder: '搜索字段名/中文名/类型…', style: 'width:100%;box-sizing:border-box;margin-bottom:6px;padding:5px 9px;font-size:12px;border:1px solid var(--border);border-radius:var(--radius);background:var(--bg-body);color:var(--text-primary);' });
          fsearch.oninput = function () {
            var kw = (fsearch.value || '').trim().toLowerCase();
            for (var i = 0; i < tbl.rows.length; i++) { var rw = tbl.rows[i]; if (rw === thr) continue; rw.style.display = (!kw || (rw.getAttribute('data-fk') || '').indexOf(kw) >= 0) ? '' : 'none'; }
          };
          body.appendChild(fsearch);
        }
        var scroll = DN.h('div', { style: 'max-height:46vh;overflow:auto;border:1px solid var(--divider);border-radius:var(--radius);' });
        var tbl = DN.h('table', { style: 'width:100%;font-size:12px;border-collapse:collapse;' });
        var thr = DN.h('tr', {});
        ['#', '字段', '中文名', '类型'].forEach(function (h) { thr.appendChild(DN.h('th', { text: h, style: 'text-align:left;padding:5px 9px;border-bottom:1px solid var(--divider);position:sticky;top:0;background:var(--bg-main);font-weight:600;' })); });
        tbl.appendChild(thr);
        cols.forEach(function (c, i) {
          c = c || {}; var tr = DN.h('tr', { 'data-fk': ((c.name || '') + ' ' + (c.comment || '') + ' ' + (c.type || '')).toLowerCase() });
          [String(i + 1), c.name || '', c.comment || '', c.type || ''].forEach(function (v) { tr.appendChild(DN.h('td', { text: v, title: v, style: 'padding:5px 9px;border-bottom:1px solid var(--divider);white-space:nowrap;max-width:200px;overflow:hidden;text-overflow:ellipsis;' })); });
          tbl.appendChild(tr);
        });
        scroll.appendChild(tbl); body.appendChild(scroll);
      }
      var actBar = DN.h('div', { style: 'margin-top:12px;display:flex;gap:8px;flex-wrap:wrap;align-items:center;' });
      // 复制 db.table(写SQL常用)
      var cp = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '复制表名' });
      cp.onclick = function () { DN.copyText(db + '.' + table); };
      actBar.appendChild(cp);
      // 复制起手查询 SQL(写脚本常用)
      var cq = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '复制查询' });
      cq.onclick = function () { DN.copyText('SELECT * FROM `' + db + '`.`' + table + '` LIMIT 100', '已复制查询 SQL'); };
      actBar.appendChild(cq);
      // 复制全部字段名(逗号分隔, 写 SELECT 列清单常用)
      if (cols.length) {
        var cf = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '复制字段名' });
        cf.onclick = function () { DN.copyText(cols.map(function (c) { return (c && c.name) || ''; }).filter(Boolean).join(', '), '已复制 ' + cols.length + ' 个字段名'); };
        actBar.appendChild(cf);
      }
      // 导出字段清单CSV(建表文档/对接常用)
      if (cols.length && DN.exportRows) {
        var ex = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '导出字段' });
        ex.onclick = function () { DN.exportRows(db + '.' + table + '_字段', ['字段', '中文名', '类型'], cols.map(function (c) { c = c || {}; return [c.name || '', c.comment || '', c.type || '']; })); };
        actBar.appendChild(ex);
      }
      // 编辑业务标签(原标签仅展示不可改): 内联输入逗号分隔标签 → 保存
      var etag = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '编辑标签', 'data-perm': 'catalog:edit' });
      etag.onclick = function () {
        if (document.getElementById('dnTagEditRow')) return;
        var inp = DN.h('input', { class: 'dn-form-input', value: (tm.tags || ''), placeholder: '业务标签(逗号分隔, 如 核心,交易域)', style: 'flex:1;min-width:160px;' });
        var sv = DN.h('a', { class: 'btn btn-sm btn-primary', href: 'javascript:void(0)', text: '保存' });
        var cc = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '取消' });
        var rowE = DN.h('div', { id: 'dnTagEditRow', style: 'margin-top:8px;display:flex;gap:8px;align-items:center;width:100%;' }, [inp, sv, cc]);
        cc.onclick = function () { rowE.remove(); };
        sv.onclick = function () {
          sv.style.pointerEvents = 'none'; sv.textContent = '保存中…';
          DN.post('/api/metadata/table/set-tags', { db: db, table: table, tags: inp.value.trim() })
            .then(function () { DN.toast('标签已保存', 'ok'); rowE.remove(); if (DN.tablePreview) { if (dr && dr.close) dr.close(); DN.tablePreview(db, table); } })
            .catch(function (e) { sv.style.pointerEvents = ''; sv.textContent = '保存'; DN.toast('保存失败: ' + (e && e.message || ''), 'err'); });
        };
        actBar.parentNode.insertBefore(rowE, actBar.nextSibling); inp.focus();
      };
      actBar.appendChild(etag);
      // 编辑负责人(数据 steward; 原仅展示)
      var eown = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '编辑负责人', 'data-perm': 'catalog:edit' });
      eown.onclick = function () {
        if (document.getElementById('dnOwnerEditRow')) return;
        var inp = DN.h('input', { class: 'dn-form-input', value: (tm.owner || ''), placeholder: '负责人(账号/姓名)', list: 'dnUserList', style: 'flex:1;min-width:160px;' });
        var sv = DN.h('a', { class: 'btn btn-sm btn-primary', href: 'javascript:void(0)', text: '保存' });
        var cc = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '取消' });
        var rowE = DN.h('div', { id: 'dnOwnerEditRow', style: 'margin-top:8px;display:flex;gap:8px;align-items:center;width:100%;' }, [inp, sv, cc]);
        cc.onclick = function () { rowE.remove(); };
        sv.onclick = function () {
          sv.style.pointerEvents = 'none'; sv.textContent = '保存中…';
          DN.post('/api/metadata/table/set-owner', { db: db, table: table, owner: inp.value.trim() })
            .then(function () { DN.toast('负责人已保存', 'ok'); rowE.remove(); if (DN.tablePreview) { if (dr && dr.close) dr.close(); DN.tablePreview(db, table); } })
            .catch(function (e) { sv.style.pointerEvents = ''; sv.textContent = '保存'; DN.toast('保存失败: ' + (e && e.message || ''), 'err'); });
        };
        actBar.parentNode.insertBefore(rowE, actBar.nextSibling); inp.focus();
      };
      actBar.appendChild(eown);
      if (window.openQualityRuleForm) { // 原地闭环: 预览表时直接为它建质量规则, 不跳治理页
        var qr = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '＋ 为此表建质量规则' });
        qr.onclick = function () { window.openQualityRuleForm({ db: db, table: table }); };
        actBar.appendChild(qr);
      }
      if (window.navigateTo) {
        var full = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '在数据地图查看完整(血缘/质量/评论) →' });
        full.onclick = function () { if (dr && dr.close) dr.close(); navigateTo('catalog', { openTable: { db: db, table: table } }); };
        actBar.appendChild(full);
      }
      if (actBar.children.length) body.appendChild(actBar);
    });
  };

  /** 指标预览抽屉(点指标【原地看 KPI 摘要】, 不跳驾驶舱): 当前/目标/达成/环同比+口径, 末尾可跳完整驾驶舱。复用(禁跳转#5)。 */
  DN.metricPreview = function (id) {
    if (id == null) return;
    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { text: '加载中…', style: 'padding:20px;color:var(--text-muted);font-size:13px;' }));
    var dr = DN.drawer('指标预览', body);
    function num(v) { if (v == null || v === '') return '-'; var n = Number(v); return isNaN(n) ? String(v) : (Math.round(n * 100) / 100).toLocaleString(); }
    function delta(p) { if (p == null) return DN.h('span', { text: '-', style: 'color:var(--text-faint);' }); var up = Number(p) >= 0; return DN.h('span', { text: (up ? '▲ ' : '▼ ') + Math.abs(Number(p)) + '%', style: 'color:' + (up ? 'var(--success)' : 'var(--error)') + ';font-weight:600;' }); }
    function tile(label, valNode) { var t = DN.h('div', { style: 'flex:1;min-width:96px;background:var(--bg-card);border:1px solid var(--border);border-radius:var(--radius-md);padding:10px 12px;' }); t.appendChild(DN.h('div', { text: label, style: 'font-size:11px;color:var(--text-muted);margin-bottom:4px;' })); t.appendChild(valNode); return t; }
    DN.get('/api/consumption/metric/' + id + '/detail').then(function (d) {
      d = d || {}; var m = d.metric || {}, unit = m.unit || '';
      body.innerHTML = '';
      if (dr && dr.setTitle) dr.setTitle('指标: ' + (m.metricName || ('#' + id)));
      var nameLine = DN.h('div', { style: 'display:flex;gap:8px;align-items:center;margin-bottom:4px;flex-wrap:wrap;' });
      nameLine.appendChild(DN.h('span', { text: (m.metricName || ('指标#' + id)) + (m.metricCode ? ' (' + m.metricCode + ')' : ''), style: 'font-weight:600;font-size:14px;color:var(--text-primary);' }));
      if (m.metricCode) { var cpc = DN.h('a', { href: 'javascript:void(0)', text: '复制编码', title: '复制指标编码', style: 'font-size:12px;color:var(--primary);' }); cpc.onclick = function () { DN.copyText(m.metricCode); }; nameLine.appendChild(cpc); }
      body.appendChild(nameLine);
      if (m.category) body.appendChild(DN.h('div', { text: '分类: ' + m.category, style: 'font-size:12px;color:var(--text-muted);margin-bottom:8px;' }));
      var ach = d.achievement, achColor = ach == null ? 'var(--text-regular)' : (ach >= 100 ? 'var(--success)' : (ach >= 80 ? 'var(--warning)' : 'var(--error)'));
      var grid = DN.h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap;margin:8px 0;' });
      grid.appendChild(tile('当前值', DN.h('div', { text: num(d.current != null ? d.current : d.currentText) + ' ' + unit, style: 'font-size:18px;font-weight:700;color:var(--primary);' })));
      grid.appendChild(tile('目标值', DN.h('div', { text: num(d.target) + ' ' + unit, style: 'font-size:18px;font-weight:700;' })));
      grid.appendChild(tile('达成率', DN.h('div', { text: ach != null ? ach + '%' : '-', style: 'font-size:18px;font-weight:700;color:' + achColor + ';' })));
      grid.appendChild(tile('环比', delta(d.mom)));
      grid.appendChild(tile('同比', delta(d.yoy)));
      body.appendChild(grid);
      if (m.calcFormula) {
        var capHd = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;margin:6px 0 2px;' }, [
          DN.h('span', { text: '口径', style: 'font-size:12px;color:var(--text-muted);' }),
          (function () { var cpf = DN.h('a', { href: 'javascript:void(0)', text: '复制', style: 'font-size:11px;color:var(--primary);' }); cpf.onclick = function () { DN.copyText(m.calcFormula, '已复制口径'); }; return cpf; })()
        ]);
        body.appendChild(capHd);
        body.appendChild(DN.h('pre', { text: m.calcFormula, style: 'font-size:12px;background:var(--bg-main);border:1px solid var(--divider);border-radius:var(--radius);padding:8px 10px;white-space:pre-wrap;word-break:break-all;margin:0;' }));
      }
      var mbar = DN.h('div', { style: 'margin-top:12px;display:flex;gap:8px;flex-wrap:wrap;align-items:center;' });
      // 原地编辑(editMetric 全局弹窗常驻), 不跳指标管理(#5)
      if (window.editMetric) { var edm = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '编辑', 'data-perm': 'metrics:edit' }); edm.onclick = function () { if (dr && dr.close) dr.close(); window.editMetric(id); }; mbar.appendChild(edm); }
      var openFull = window.openMetricFull || window.openMetricDetail; // 用 Full 避免回到预览(防自循环)
      if (openFull) { var full = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '完整指标驾驶舱(预警/预测/趋势) →' }); full.onclick = function () { if (dr && dr.close) dr.close(); openFull(id); }; mbar.appendChild(full); }
      if (mbar.children.length) body.appendChild(mbar);
    }).catch(function (e) { body.innerHTML = ''; body.appendChild(DN.h('div', { text: '加载失败: ' + (e && e.message ? e.message : e), style: 'color:var(--error);font-size:13px;padding:16px;' })); });
  };

  /** 治理工单预览抽屉(点工单【原地看详情】, 不跳治理页): 标题/状态/级别/负责人/对象/描述+去处理入口。
   *  ponytail: 无单工单接口, 拉工单列表筛 id(列表通常 <数百, 可接受); 量大时再加后端 /issues/{id}。*/
  DN.issuePreview = function (id) {
    if (id == null) return;
    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { text: '加载中…', style: 'padding:20px;color:var(--text-muted);font-size:13px;' }));
    var dr = DN.drawer('工单预览', body);
    var TONE = { OPEN: 'warn', FIXING: 'info', RESOLVED: 'ok', VERIFIED: 'ok', CLOSED: 'muted', REJECTED: 'err' };
    function row(k, v) { return DN.h('div', { style: 'display:flex;gap:10px;font-size:12.5px;margin:4px 0;' }, [DN.h('span', { text: k, style: 'color:var(--text-muted);min-width:56px;flex:0 0 auto;' }), DN.h('span', { text: String(v == null || v === '' ? '-' : v), style: 'color:var(--text-regular);word-break:break-all;' })]); }
    DN.get('/api/gov/health/issues').then(function (rows) {
      var it = (Array.isArray(rows) ? rows : []).filter(function (x) { return x && String(x.id) === String(id); })[0];
      body.innerHTML = '';
      if (!it) { body.appendChild(DN.h('div', { text: '未找到该工单(可能已关闭或被删除)', style: 'color:var(--text-muted);font-size:13px;padding:16px;' })); return; }
      if (dr && dr.setTitle) dr.setTitle('工单: ' + (it.title ? it.title.slice(0, 24) : ('#' + id)));
      body.appendChild(DN.h('div', { text: it.title || ('工单#' + id), style: 'font-weight:600;font-size:14px;margin-bottom:8px;color:var(--text-primary);line-height:1.4;' }));
      var pills = DN.h('div', { style: 'display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px;' });
      pills.appendChild(DN.pill(it.status || 'OPEN', TONE[it.status] || 'muted'));
      if (it.severity) pills.appendChild(DN.pill(it.severity, it.severity === 'HIGH' ? 'err' : it.severity === 'MEDIUM' ? 'warn' : 'muted'));
      if (it.issueType) pills.appendChild(DN.pill(it.issueType, 'info'));
      body.appendChild(pills);
      var meta = DN.h('div', { style: 'border:1px solid var(--border);border-radius:var(--radius-md);padding:10px 12px;margin-bottom:10px;background:var(--bg-card);' });
      meta.appendChild(row('负责人', it.owner || '未分配'));
      if (it.objectRef) meta.appendChild(row('对象', it.objectRef));
      if (it.createdAt) meta.appendChild(row('创建', String(it.createdAt).replace('T', ' ').slice(0, 16)));
      body.appendChild(meta);
      if (it.description) { body.appendChild(DN.h('div', { text: '描述', style: 'font-size:12px;color:var(--text-muted);margin:6px 0 2px;' })); body.appendChild(DN.h('div', { text: it.description, style: 'font-size:12.5px;color:var(--text-regular);white-space:pre-wrap;word-break:break-all;line-height:1.5;' })); }
      var ibar = DN.h('div', { style: 'margin-top:12px;display:flex;gap:8px;flex-wrap:wrap;align-items:center;' });
      // 原地快捷流转: 未关闭工单可直接标记已解决, 无需跳治理页(#5)
      if (it.status && it.status !== 'CLOSED' && it.status !== 'RESOLVED') {
        var rs = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '标记已解决', 'data-perm': 'governance:issue' });
        rs.onclick = function () {
          if (rs.dataset.busy) return; rs.dataset.busy = '1'; rs.textContent = '处理中…';
          DN.post('/api/gov/health/issues/' + id + '/transition', { status: 'RESOLVED' })
            .then(function () { DN.toast('已标记解决', 'ok'); if (dr && dr.close) dr.close(); })
            .catch(function (e) { DN.toast('流转失败: ' + (e && e.message ? e.message : e), 'err'); rs.dataset.busy = ''; rs.textContent = '标记已解决'; });
        };
        ibar.appendChild(rs);
      }
      if (window.navigateTo) { var go = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '去治理工单中心(流转/指派) →' }); go.onclick = function () { if (dr && dr.close) dr.close(); navigateTo('governance', { gov: 'health', focusIssue: id }); }; ibar.appendChild(go); }
      if (ibar.children.length) body.appendChild(ibar);
    }).catch(function (e) { body.innerHTML = ''; body.appendChild(DN.h('div', { text: '加载失败: ' + (e && e.message ? e.message : e), style: 'color:var(--error);font-size:13px;padding:16px;' })); });
  };

  /** 质量规则预览抽屉(点规则【原地看配置+近期执行】, 不跳治理质量页)。ponytail: 拉 /quality/rules 筛 id。*/
  DN.qualityRulePreview = function (id) {
    if (id == null) return;
    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { text: '加载中…', style: 'padding:20px;color:var(--text-muted);font-size:13px;' }));
    var dr = DN.drawer('质量规则预览', body);
    function row(k, v) { return DN.h('div', { style: 'display:flex;gap:10px;font-size:12.5px;margin:4px 0;' }, [DN.h('span', { text: k, style: 'color:var(--text-muted);min-width:56px;flex:0 0 auto;' }), DN.h('span', { text: String(v == null || v === '' ? '-' : v), style: 'color:var(--text-regular);word-break:break-all;' })]); }
    Promise.all([
      DN.get('/api/quality/rules').catch(function () { return []; }),
      DN.get('/api/quality/rule/' + encodeURIComponent(id) + '/runs').catch(function () { return []; })
    ]).then(function (rs) {
      var r = (Array.isArray(rs[0]) ? rs[0] : []).filter(function (x) { return x && String(x.id) === String(id); })[0];
      var runs = Array.isArray(rs[1]) ? rs[1] : [];
      body.innerHTML = '';
      if (!r) { body.appendChild(DN.h('div', { text: '未找到该规则', style: 'color:var(--text-muted);font-size:13px;padding:16px;' })); return; }
      if (dr && dr.setTitle) dr.setTitle('规则: ' + (r.ruleName ? r.ruleName.slice(0, 24) : ('#' + id)));
      body.appendChild(DN.h('div', { text: r.ruleName || ('规则#' + id), style: 'font-weight:600;font-size:14px;margin-bottom:8px;color:var(--text-primary);' }));
      var pills = DN.h('div', { style: 'display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px;' });
      pills.appendChild(DN.pill(r.status === 1 ? '启用' : '停用', r.status === 1 ? 'ok' : 'muted'));
      if (r.severity) pills.appendChild(DN.pill(r.severity, r.severity === 'error' ? 'err' : r.severity === 'warning' ? 'warn' : 'muted'));
      if (r.blockDownstream === 1) pills.appendChild(DN.pill('强阻断', 'warn'));
      body.appendChild(pills);
      var meta = DN.h('div', { style: 'border:1px solid var(--border);border-radius:var(--radius-md);padding:10px 12px;margin-bottom:10px;background:var(--bg-card);' });
      meta.appendChild(row('目标', (r.databaseName || '') + '.' + (r.tableName || '') + (r.columnName ? ('.' + r.columnName) : '')));
      if (r.ruleType) meta.appendChild(row('类型', r.ruleType));
      if (r.dimension) meta.appendChild(row('维度', r.dimension));
      if (r.passThreshold != null) meta.appendChild(row('阈值', r.passThreshold + '%'));
      if (r.scheduleCron) meta.appendChild(row('调度', r.scheduleCron));
      body.appendChild(meta);
      body.appendChild(DN.h('div', { text: '近期执行 (' + runs.length + ')', style: 'font-weight:600;font-size:13px;margin:6px 0 4px;' }));
      if (!runs.length) { body.appendChild(DN.h('div', { text: '暂无执行记录', style: 'color:var(--text-muted);font-size:12px;' })); }
      else {
        runs.slice(0, 5).forEach(function (rn) {
          var ok = (rn.passRate != null ? rn.passRate : 100) >= (r.passThreshold != null ? r.passThreshold : 100);
          var line = DN.h('div', { style: 'display:flex;gap:8px;align-items:center;font-size:12px;padding:4px 0;border-bottom:1px solid var(--divider);' });
          line.appendChild(DN.h('span', { text: ok ? '✓' : '✗', style: 'color:' + (ok ? 'var(--success)' : 'var(--error)') + ';font-weight:700;' }));
          line.appendChild(DN.h('span', { text: (rn.passRate != null ? rn.passRate + '%' : '-') + (rn.failCount != null ? (' · 失败 ' + rn.failCount) : ''), style: 'color:var(--text-regular);' }));
          line.appendChild(DN.h('span', { text: rn.runAt ? String(rn.runAt).replace('T', ' ').slice(5, 16) : '', style: 'margin-left:auto;color:var(--text-muted);' }));
          body.appendChild(line);
        });
      }
      if (window.navigateTo) { var go = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '去质量中心(编辑/执行) →', style: 'margin-top:12px;display:inline-block;' }); go.onclick = function () { if (dr && dr.close) dr.close(); navigateTo('governance', { gov: 'quality' }); }; body.appendChild(go); }
    }).catch(function (e) { body.innerHTML = ''; body.appendChild(DN.h('div', { text: '加载失败: ' + (e && e.message ? e.message : e), style: 'color:var(--error);font-size:13px;padding:16px;' })); });
  };

  /**
   * 级联库/表(/列)下拉选择器，用 dn-form-select 表单体系，避免用户手输。
   * opts: { withColumn, defaultDb, onChange(db,table,column) }
   * 返回 { el, db(), table(), column() }
   */
  DN.dbTablePicker = function (opts) {
    opts = opts || {};
    var withColumn = !!opts.withColumn;
    function sel(ph) {
      // width:auto 覆盖表单类的 width:100%, 否则每个 select 占满整行致 flex 容器换行竖排(库/表错位)
      var s = DN.h('select', { class: 'dn-form-select', style: 'width:auto;min-width:150px;margin-right:4px;' });
      s.innerHTML = '<option value="">' + ph + '</option>';
      return s;
    }
    function lbl(t) { return DN.h('span', { text: t, style: 'color:var(--text-muted);font-size:13px;margin-right:4px;' }); }
    function addOpts(s, items, val, text) {
      (items || []).forEach(function (it) {
        var o = document.createElement('option');
        o.value = val(it); o.textContent = text(it);
        s.appendChild(o);
      });
    }
    var dbSel = sel('选择库'), tbSel = sel('选择表'), colSel = withColumn ? sel('选择字段') : null;
    var wrap = DN.h('div', { style: 'display:flex;gap:6px;align-items:center;flex-wrap:wrap;margin-bottom:14px;' },
      [lbl('库'), dbSel, lbl('表'), tbSel].concat(withColumn ? [lbl('字段'), colSel] : []));
    function fire() { if (opts.onChange) opts.onChange(dbSel.value, tbSel.value, colSel ? colSel.value : null); }
    // 加载表清单; preselect 用于编辑态回填已存表(异步入 DOM 后再选中), cb 在完成时回调
    function loadTables(preselect, cb) {
      tbSel.innerHTML = '<option value="">选择表</option>';
      if (colSel) colSel.innerHTML = '<option value="">选择字段</option>';
      if (!dbSel.value) { if (cb) cb(); return; }
      DN.metaTables(dbSel.value).then(function (ts) {
        addOpts(tbSel, ts, function (t) { return t; }, function (t) { return t; });
        if (preselect) tbSel.value = preselect;
        if (cb) cb();
      }).catch(function () { DN.toast('加载表清单失败', 'error'); if (cb) cb(); });
    }
    function loadColumns(preselect, cb) {
      if (!colSel) { if (cb) cb(); return; }
      colSel.innerHTML = '<option value="">选择字段</option>';
      if (!dbSel.value || !tbSel.value) { if (cb) cb(); return; }
      DN.metaColumns(dbSel.value, tbSel.value).then(function (cs) {
        addOpts(colSel, cs, function (c) { return c.name; }, function (c) { return c.name + (c.type ? ' (' + c.type + ')' : ''); });
        if (preselect) colSel.value = preselect;
        if (cb) cb();
      }).catch(function () { DN.toast('加载字段清单失败', 'error'); if (cb) cb(); });
    }
    dbSel.onchange = function () { loadTables(null); fire(); };
    tbSel.onchange = function () { loadColumns(null); fire(); };
    if (colSel) colSel.onchange = fire;
    DN.metaDatabases().then(function (dbs) {
      addOpts(dbSel, dbs, function (d) { return d; }, function (d) { return d; });
      if (opts.defaultDb) {
        dbSel.value = opts.defaultDb;
        // 编辑态: 级联回填 库→表→列(异步链), 不丢失已保存的表/列上下文
        loadTables(opts.defaultTable || null, function () {
          if (opts.defaultTable && colSel) loadColumns(opts.defaultColumn || null, fire);
          else fire();
        });
      }
    }).catch(function () {});
    return { el: wrap, db: function () { return dbSel.value; }, table: function () { return tbSel.value; }, column: function () { return colSel ? colSel.value : null; } };
  };

  // ===== 现代 UI 套件（配合 css/gov-modern.css，让各治理模块渲染一致的现代界面） =====
  var ICONS = {
    grid: '<rect x="3" y="3" width="6" height="6" rx="1"/><rect x="11" y="3" width="6" height="6" rx="1"/><rect x="3" y="11" width="6" height="6" rx="1"/><rect x="11" y="11" width="6" height="6" rx="1"/>',
    chart: '<path d="M3 16V8M8 16V4M13 16v-6M3 16h14"/>',
    db: '<ellipse cx="10" cy="5" rx="6.5" ry="2.5"/><path d="M3.5 5v10c0 1.4 2.9 2.5 6.5 2.5s6.5-1.1 6.5-2.5V5"/><path d="M3.5 10c0 1.4 2.9 2.5 6.5 2.5s6.5-1.1 6.5-2.5"/>',
    lineage: '<circle cx="5" cy="5" r="2.2"/><circle cx="15" cy="10" r="2.2"/><circle cx="5" cy="15" r="2.2"/><path d="M7 6l6 3M7 14l6-3"/>',
    shield: '<path d="M10 2l7 3v5c0 4-3 6.8-7 8-4-1.2-7-4-7-8V5l7-3z"/><path d="M7 9.5l2 2 4-4"/>',
    doc: '<rect x="4" y="2.5" width="12" height="15" rx="1.5"/><path d="M7 7h6M7 10h6M7 13h4"/>',
    tag: '<path d="M3 3h6l8 8-6 6-8-8V3z"/><circle cx="6.5" cy="6.5" r="1.2"/>',
    alert: '<path d="M10 3l8 14H2L10 3z"/><path d="M10 8v4M10 14.5h.01"/>',
    check: '<circle cx="10" cy="10" r="7.5"/><path d="M6.5 10l2.5 2.5 4.5-5"/>',
    search: '<circle cx="8.5" cy="8.5" r="5.5"/><path d="M13 13l4 4"/>',
    inbox: '<path d="M3 12l2.5-7h9L17 12v4H3v-4z"/><path d="M3 12h4l1 2h4l1-2h4"/>',
    clock: '<circle cx="10" cy="10" r="7.5"/><path d="M10 5.5V10l3 2"/>',
    user: '<circle cx="10" cy="6.5" r="3"/><path d="M4 17c0-3.3 2.7-5 6-5s6 1.7 6 5"/>',
    lock: '<rect x="4" y="9" width="12" height="8" rx="1.5"/><path d="M7 9V6.5a3 3 0 016 0V9"/>',
    layers: '<path d="M10 3l7 3.5-7 3.5-7-3.5L10 3z"/><path d="M3 10l7 3.5 7-3.5M3 13.5l7 3.5 7-3.5"/>',
    list: '<path d="M6 5h11M6 10h11M6 15h11"/><circle cx="3" cy="5" r="1"/><circle cx="3" cy="10" r="1"/><circle cx="3" cy="15" r="1"/>',
    pin: '<path d="M10 17v-4M6 13h8l-1-4 1.5-3h-9L7 9l-1 4z"/>',
    flag: '<path d="M5 3v14M5 4h9l-2 3 2 3H5"/>',
    bot: '<rect x="4" y="7" width="12" height="9" rx="2"/><path d="M10 4v3M7.5 11h.01M12.5 11h.01"/><circle cx="10" cy="3" r="1"/>'
  };
  DN.icon = function (name, attrs) {
    var p = ICONS[name] || ICONS.grid;
    // 默认 1em(= 容器 font-size)。SVG 不写 width/height 时按规范默认 100%, 在无尺寸容器里会撑成 300px 巨图;
    // 用 1em 让图标随文字大小走(永不撑大), 既有 .xxx svg{width:Npx} 之类 CSS 规则优先级更高仍可覆盖到精确尺寸。
    // 需要更大图标的容器: 给容器设 font-size(如 40px)即可, 或用 CSS 给该处 svg 设 width/height。
    return '<svg width="1em" height="1em" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" ' + (attrs || '') + '>' + p + '</svg>';
  };

  DN.statTile = function (o) {
    o = o || {};
    var _val = (o.value == null ? '-' : String(o.value));
    var meta = DN.h('div', {}, [
      DN.h('div', { class: 'v', text: _val, title: _val }),
      DN.h('div', { class: 'l', text: o.label || '' })
    ]);
    if (o.sub) meta.appendChild(DN.h('div', { class: 'sub', text: o.sub }));
    var tile = DN.h('div', { class: 'gov-stat' + (o.tone ? ' tone-' + o.tone : '') + (typeof o.onClick === 'function' ? ' clickable' : '') },
      [DN.h('div', { class: 'ic', html: DN.icon(o.icon || 'chart') }), meta]);
    if (typeof o.onClick === 'function') {
      tile.setAttribute('tabindex', '0'); tile.setAttribute('role', 'button'); if (o.title) tile.title = o.title;
      tile.addEventListener('click', o.onClick);
      tile.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); o.onClick(); } });
    }
    return tile;
  };
  DN.statRow = function (tiles) { return DN.h('div', { class: 'gov-stats' }, (tiles || []).map(function (t) { return DN.statTile(t); })); };

  /** 卡片：返回 {el, body}，把内容 append 到 body */
  DN.card = function (o) {
    o = o || {};
    var body = DN.h('div', { class: 'gov-card-bd' });
    var hd = DN.h('div', { class: 'gov-card-hd' }, [
      DN.h('span', { class: 'ic', html: DN.icon(o.icon || 'grid') }),
      DN.h('span', { text: o.title || '' }), DN.h('span', { class: 'sp' })
    ]);
    if (o.actions) (Array.isArray(o.actions) ? o.actions : [o.actions]).forEach(function (a) { if (a) hd.appendChild(a); });
    return { el: DN.h('div', { class: 'gov-card' }, [hd, body]), body: body };
  };

  // 通用 CSV 导出(BOM+CRLF+注入防护): headers=表头数组, rows=二维数组。供 DN.table 之外的自定义列表复用。
  DN.exportRows = function (name, headers, rows) {
    function cell(v) { var s = String(v == null ? '' : v).replace(/[\r\n]+/g, ' '); if (/^[=+\-@]/.test(s)) s = "'" + s; return '"' + s.replace(/"/g, '""') + '"'; }
    var lines = [(headers || []).map(cell).join(',')];
    (rows || []).forEach(function (r) { lines.push((r || []).map(cell).join(',')); });
    var blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
    var url = URL.createObjectURL(blob), a = document.createElement('a');
    a.href = url; a.download = (name || 'export') + '_' + new Date().toISOString().slice(0, 10) + '.csv';
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    if (DN.toast) DN.toast('已导出 ' + ((rows || []).length) + ' 行 CSV', 'ok'); // 成功反馈
  };

  DN.empty = function (text, icon, action) {
    var children = [DN.h('span', { html: DN.icon(icon || 'inbox') }), DN.h('div', { class: 'et', text: text || '暂无数据' })];
    // 可选行动按钮: 空态直接引导下一步(如"去新建"), 提升可达性。action={label,onClick}
    if (action && action.label && typeof action.onClick === 'function') {
      var b = DN.h('a', { class: 'btn btn-sm btn-primary', href: 'javascript:void(0)', text: action.label, style: 'margin-top:10px;' });
      b.onclick = action.onClick;
      children.push(b);
    }
    return DN.h('div', { class: 'gov-empty' }, children);
  };
  // 错误态 + 重试按钮：加载失败时展示原因 + 可点重试(重新执行 retryFn)，替代纯空态/纯 toast，让用户无需刷新整页即可恢复
  DN.errorBox = function (text, retryFn) {
    var box = DN.h('div', { class: 'gov-empty' }, [DN.h('span', { html: DN.icon('alert') }), DN.h('div', { class: 'et', text: text || '加载失败' })]);
    if (typeof retryFn === 'function') {
      box.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', style: 'margin-top:10px', text: '重试', onclick: function () { retryFn(); } }));
    }
    return box;
  };
  DN.skeleton = function (rows) {
    var w = DN.h('div', {});
    for (var i = 0; i < (rows || 3); i++) w.appendChild(DN.h('div', { class: 'gov-skel', style: 'width:' + (100 - i * 7) + '%' }));
    return w;
  };
  DN.pill = function (text, tone) { return DN.h('span', { class: 'gov-pill is-' + (tone || 'muted'), text: text }); };

  function toneColor(t) { return t === 'ok' ? 'var(--success)' : t === 'warn' ? 'var(--warning)' : t === 'err' ? 'var(--error)' : 'var(--primary)'; }
  DN.bars = function (items) {
    items = (items || []).filter(function (i) { return i != null; });   // 剔除空项,防 i.max/i.value 取值崩
    var max = Math.max.apply(null, items.map(function (i) { return i.max || i.value || 0; }).concat([1]));
    var w = DN.h('div', {});
    items.forEach(function (i) {
      var pct = Math.round((i.value || 0) / (i.max || max || 1) * 100);
      var fill = DN.h('div', { class: 'bf', style: 'width:' + pct + '%;background:' + toneColor(i.tone) });
      var bar = DN.h('div', { class: 'gov-bar' }, [DN.h('span', { class: 'bl', text: i.label, title: i.label == null ? '' : String(i.label) }), DN.h('div', { class: 'bt' }, [fill]), DN.h('span', { class: 'bv', text: (i.display != null ? i.display : i.value) })]);
      if (typeof i.onClick === 'function') { bar.style.cursor = 'pointer'; bar.title = '点击筛选'; bar.addEventListener('click', function () { i.onClick(i); }); }
      w.appendChild(bar);
    });
    return w;
  };

  /** 环形仪表：值 0-100，按分值着色(红<60/黄<80/绿) */
  DN.gauge = function (val, opts) {
    opts = opts || {}; var size = opts.size || 124, sw = 11, r = (size - sw) / 2, c = 2 * Math.PI * r;
    var v = Math.max(0, Math.min(100, Number(val) || 0)); var col = v < 60 ? 'var(--error)' : v < 80 ? 'var(--warning)' : 'var(--success)';
    var svg = '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '">'
      + '<circle cx="' + (size / 2) + '" cy="' + (size / 2) + '" r="' + r + '" fill="none" style="stroke:var(--bg-sunken)" stroke-width="' + sw + '"/>'
      + '<circle cx="' + (size / 2) + '" cy="' + (size / 2) + '" r="' + r + '" fill="none" stroke-width="' + sw + '" stroke-linecap="round" stroke-dasharray="' + c + '" stroke-dashoffset="' + (c * (1 - v / 100)) + '" transform="rotate(-90 ' + (size / 2) + ' ' + (size / 2) + ')" style="stroke:' + col + ';transition:stroke-dashoffset .6s var(--ease)"/></svg>';
    var ring = DN.h('div', { style: 'position:relative;width:' + size + 'px;height:' + size + 'px', html: svg });
    ring.appendChild(DN.h('div', { style: 'position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center' },
      [DN.h('div', { class: 'gv num', style: 'color:' + col, text: (opts.decimals ? v.toFixed(opts.decimals) : Math.round(v)) }), opts.label ? DN.h('div', { class: 'gl', text: opts.label }) : null]));
    return DN.h('div', { class: 'gov-gauge' }, [ring]);
  };

  /**
   * 现代表格(内置搜索+客户端分页+空态)。
   * o: { columns:[{key,label,align,render(row)->Node|string, html:bool}], rows, pageSize, search, searchKeys, searchPlaceholder, toolbar, empty, emptyIcon }
   * 返回 element，附 .reload(rows)
   */
  DN.table = function (o) {
    o = o || {}; var cols = o.columns || [], all = (o.rows || []).filter(function (r) { return r != null; }), pageSize = o.pageSize || 20, page = 1, q = '';
    var sortKey = null, sortDir = 1, inp = null, _t = null;
    var wrap = DN.h('div', {});
    if (o.search !== false) {
      inp = DN.h('input', { placeholder: o.searchPlaceholder || '搜索...', oninput: function () { clr.style.display = inp.value ? '' : 'none'; clearTimeout(_t); inp.style.opacity = '0.6'; _t = setTimeout(function () { q = inp.value.trim().toLowerCase(); page = 1; draw(); inp.style.opacity = '1'; }, 220); } });
      var clr = DN.h('span', { class: 'gov-search-clr', text: '×', title: '清除', onclick: function () { inp.value = ''; q = ''; clr.style.display = 'none'; page = 1; draw(); inp.focus(); } });
      clr.style.display = 'none'; // 仅在有输入时显示清除按钮
      var bar = DN.h('div', { class: 'gov-toolbar' }, [DN.h('div', { class: 'gov-search' }, [DN.h('span', { html: DN.icon('search') }), inp, clr])]);
      if (o.exportName) bar.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '导出CSV', onclick: function () { exportCsv(); } }));
      if (o.toolbar) (Array.isArray(o.toolbar) ? o.toolbar : [o.toolbar]).forEach(function (t) { if (t) bar.appendChild(t); });
      wrap.appendChild(bar);
    } else if (o.toolbar || o.exportName) {
      var bar2 = DN.h('div', { class: 'gov-toolbar' });
      if (o.exportName) bar2.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '导出CSV', onclick: function () { exportCsv(); } }));
      if (o.toolbar) (Array.isArray(o.toolbar) ? o.toolbar : [o.toolbar]).forEach(function (t) { if (t) bar2.appendChild(t); }); wrap.appendChild(bar2);
    }
    var tw = DN.h('div', { class: 'gov-tbl-wrap' }), pager = DN.h('div', { class: 'gov-pager' });
    wrap.appendChild(tw); wrap.appendChild(pager);
    function filt() {
      var data = !q ? all.slice() : all.filter(function (r) {
        var s = (o.searchKeys || Object.keys(r)).map(function (k) { return r[k]; }).join(' ');
        return String(s).toLowerCase().indexOf(q) >= 0;
      });
      if (sortKey) {
        data.sort(function (a, b) {
          var va = a[sortKey], vb = b[sortKey];
          var na = Number(va), nb = Number(vb);
          if (!isNaN(na) && !isNaN(nb) && va !== '' && vb !== '') return (na - nb) * sortDir;
          return String(va == null ? '' : va).localeCompare(String(vb == null ? '' : vb)) * sortDir;
        });
      }
      return data;
    }
    function exportCsv() {
      var data = filt();
      var head = cols.filter(function (c) { return c.label; }).map(function (c) { return c.label; });
      var lines = [head.map(csvCell).join(',')];
      data.forEach(function (r) {
        lines.push(cols.filter(function (c) { return c.label; }).map(function (c) {
          var v = c.exportValue ? c.exportValue(r) : (r[c.key] == null ? '' : r[c.key]);
          return csvCell(v);
        }).join(','));
      });
      var blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
      var url = URL.createObjectURL(blob), a = document.createElement('a');
      a.href = url; a.download = (o.exportName || 'export') + '_' + new Date().toISOString().slice(0, 10) + '.csv';
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    }
    function csvCell(v) { var s = String(v == null ? '' : v).replace(/[\r\n]+/g, ' '); if (/^[=+\-@]/.test(s)) s = "'" + s; return '"' + s.replace(/"/g, '""') + '"'; }
    // 搜索命中高亮(仅纯文本单元格; Node/html 自定义渲染不动), 与全站即时搜索一致
    function hlMark(s) { if (!q) return null; var i = s.toLowerCase().indexOf(q); if (i < 0) return null; return DN.esc(s.slice(0, i)) + '<mark style="background:var(--warning-bg);color:inherit;padding:0 1px;border-radius:2px;">' + DN.esc(s.slice(i, i + q.length)) + '</mark>' + DN.esc(s.slice(i + q.length)); }
    function draw() {
      var data = filt(), total = data.length, pages = Math.max(1, Math.ceil(total / pageSize));
      if (page > pages) page = pages;
      tw.innerHTML = '';
      if (!total) {
        if (q && all.length) {
          var em = DN.empty('未找到匹配「' + (inp ? inp.value : q) + '」的记录', 'search');
          em.appendChild(DN.h('a', { href: 'javascript:void(0)', text: '清空搜索', style: 'color:var(--primary);font-size:12px', onclick: function () { if (inp) inp.value = ''; q = ''; page = 1; draw(); } }));
          tw.appendChild(em);
        } else tw.appendChild(DN.empty(o.empty || '暂无数据', o.emptyIcon));
        pager.innerHTML = ''; return;
      }
      var thead = '<thead><tr>' + cols.map(function (c) {
        var arrow = c.sortable ? (sortKey === c.key ? (sortDir > 0 ? ' ▲' : ' ▼') : ' ⇅') : '';
        return '<th scope="col"' + (c.align ? ' style="text-align:' + c.align + (c.sortable ? ';cursor:pointer' : '') + '"' : (c.sortable ? ' style="cursor:pointer"' : '')) + (c.sortable ? ' data-sk="' + DN.esc(c.key) + '" role="button" tabindex="0"' : '') + '>' + DN.esc(c.label) + arrow + '</th>';
      }).join('') + '</tr></thead>';
      var table = DN.h('table', { class: 'gov-tbl', html: thead }), tbody = document.createElement('tbody');
      Array.prototype.slice.call(table.querySelectorAll('th[data-sk]')).forEach(function (th) {
        var doSort = function () { var k = th.getAttribute('data-sk'); if (sortKey === k) sortDir = -sortDir; else { sortKey = k; sortDir = 1; } page = 1; draw(); };
        th.addEventListener('click', doSort);
        th.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); doSort(); } }); // 键盘可排序
      });
      data.slice((page - 1) * pageSize, page * pageSize).forEach(function (r) {
        var tr = document.createElement('tr');
        if (typeof o.onRow === 'function') {
          tr.style.cursor = 'pointer';
          tr.setAttribute('role', 'button'); tr.setAttribute('tabindex', '0');
          var _fire = function (e) { if (e.target && e.target.closest && e.target.closest('a,button,input,select,label')) return; o.onRow(r); };
          tr.addEventListener('click', _fire);
          tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); o.onRow(r); } });
        }
        cols.forEach(function (c) {
          var td = document.createElement('td'); if (c.align) { td.style.textAlign = c.align; if (c.align === 'right') td.style.fontVariantNumeric = 'tabular-nums'; }
          var cell = c.render ? c.render(r) : (r[c.key] == null ? '' : r[c.key]);
          if (cell instanceof Node) td.appendChild(cell); else if (c.html) td.innerHTML = cell; else { var _cs = String(cell); var _hl = hlMark(_cs); if (_hl != null) td.innerHTML = _hl; else td.textContent = _cs; }
          if (c.copyable) {
            td.style.cursor = 'copy';
            var cv = c.exportValue ? c.exportValue(r) : (r[c.key] == null ? td.textContent : r[c.key]);
            td.title = '点击复制：' + String(cv);
            td.addEventListener('click', function (e) { e.stopPropagation(); DN.copy(cv); });
          }
          tr.appendChild(td);
        });
        tbody.appendChild(tr);
      });
      table.appendChild(tbody); tw.appendChild(table);
      pager.innerHTML = '';
      pager.appendChild(DN.h('span', { text: (q ? '匹配 ' + total + '/' + all.length : '共 ' + total) + ' 条' + (pages > 1 ? ' · ' + page + '/' + pages : '') }));
      if (pages > 1) {
        pager.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '上一页', onclick: function () { if (page > 1) { page--; draw(); } } }));
        pager.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '下一页', onclick: function () { if (page < pages) { page++; draw(); } } }));
      }
    }
    draw();
    wrap.reload = function (rows) { all = (rows || []).filter(function (r) { return r != null; }); page = 1; draw(); };
    return wrap;
  };

  /** 右侧抽屉：返回 {close, body}。footerNode 可选: 渲染为 sticky 底部操作栏(.df), 永远贴底不随内容滚走。 */
  DN.drawer = function (title, bodyNode, footerNode, onClose) {
    // 替换旧抽屉时先解绑其 keydown 监听,避免监听器泄漏累积
    if (DN._drawerKey) { document.removeEventListener('keydown', DN._drawerKey); DN._drawerKey = null; }
    var old = document.getElementById('govDrawerMask'); if (old) old.remove();
    var oldD = document.querySelector('.gov-drawer'); if (oldD) oldD.remove();
    var _prevFocus = document.activeElement; // 关闭后归还焦点(a11y)
    var mask = DN.h('div', { id: 'govDrawerMask' });
    var bd = DN.h('div', { class: 'db' }); if (bodyNode) bd.appendChild(bodyNode);
    var df = footerNode ? DN.h('div', { class: 'df' }, [footerNode]) : null;
    function onKey(e) {
      if (e.key === 'Escape') { close(); return; }
      if (e.key === 'Tab') { // 焦点陷阱:Tab 在抽屉内循环(规范模态 a11y)
        var fs = Array.prototype.filter.call(
          dr.querySelectorAll('a[href],button,input,select,textarea,[tabindex]:not([tabindex="-1"])'),
          function (el) { return !el.disabled && el.offsetParent !== null; });
        if (!fs.length) return;
        var first = fs[0], last = fs[fs.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    }
    var _closing = false;
    function close() { if (_closing) return; _closing = true; mask.onclick = null; document.removeEventListener('keydown', onKey); if (DN._drawerKey === onKey) DN._drawerKey = null; mask.classList.remove('show'); dr.classList.remove('show'); try { if (_prevFocus && _prevFocus.focus) _prevFocus.focus(); } catch (e) {} try { if (typeof onClose === 'function') onClose(); } catch (e) {} setTimeout(function () { if (mask.parentNode) mask.remove(); if (dr.parentNode) dr.remove(); }, 250); }
    var titleSpan = DN.h('span', { text: title || '' });   // 暴露 setTitle, 供异步加载后更新标题(如 metricPreview)
    var escHint = DN.h('span', { text: 'Esc', title: '按 Esc 关闭', style: 'font-size:11px;color:var(--text-faint);border:1px solid var(--border);border-radius:var(--radius-sm);padding:0 5px;margin-left:auto;line-height:1.6;' });
    var drKids = [DN.h('div', { class: 'dh' }, [titleSpan, escHint, DN.h('button', { class: 'x', text: '×', onclick: close, 'aria-label': '关闭', style: 'margin-left:8px;' })]), bd];
    if (df) drKids.push(df);
    var dr = DN.h('div', { class: 'gov-drawer', role: 'dialog', 'aria-modal': 'true', 'aria-label': title || '详情' }, drKids);
    mask.onclick = close;
    document.body.appendChild(mask); document.body.appendChild(dr);
    DN._drawerKey = onKey; document.addEventListener('keydown', onKey);
    requestAnimationFrame(function () { mask.classList.add('show'); dr.classList.add('show'); var f = bd.querySelector('input,select,textarea,button'); if (f) try { f.focus(); } catch (e) {} });
    return { close: close, body: bd, setTitle: function (t) { titleSpan.textContent = t || ''; } };
  };

  /**
   * 关闭全站所有抽屉(统一清理点)。供 navigateTo 在切换视图前调用,
   * 防止任何抽屉(DN.drawer 的 .gov-drawer / 项目详情 / 同步详情 / AI 抽屉)在跳转后
   * 残留遮罩盖住新视图、并泄漏键盘监听。覆盖三套抽屉实现。
   */
  DN.closeAllDrawers = function () {
    // 1) DN.drawer 动态抽屉(.gov-drawer + #govDrawerMask)+ 解绑其 keydown 监听(防泄漏)
    if (DN._drawerKey) { document.removeEventListener('keydown', DN._drawerKey); DN._drawerKey = null; }
    var gm = document.getElementById('govDrawerMask'); if (gm) gm.remove();
    Array.prototype.forEach.call(document.querySelectorAll('.gov-drawer'), function (d) { if (d.parentNode) d.remove(); });
    // 2) 静态抽屉(项目详情/同步详情)走各自 close(含其状态清理),不存在则忽略; AI抽屉已统一为 .gov-drawer(上方已处理)
    try { if (typeof window.projCloseDetail === 'function') window.projCloseDetail(); } catch (e) {}
    try { if (typeof window.dbsyncCloseDetail === 'function') window.dbsyncCloseDetail(); } catch (e) {}
  };

  // ===== 表单组件(配合 app.css .dn-* 表单设计系统, 让抽屉/弹窗表单美观人性化) =====
  /** 输入框: opts {placeholder, type, value, disabled, textarea} */
  DN.formInput = function (opts) {
    opts = opts || {};
    var el = DN.h(opts.textarea ? 'textarea' : 'input', { class: 'dn-form-input' });
    if (opts.type && !opts.textarea) el.type = opts.type;
    if (opts.placeholder != null) el.placeholder = opts.placeholder;
    if (opts.value != null) el.value = String(opts.value);
    if (opts.disabled) el.disabled = true;
    return el;
  };
  /** 下拉: options 为 [val|[val,label],...], val 为当前值 */
  /** 字段: 标签(可必填红星) + 控件 + 提示。opts {hint, required} */
  DN.field = function (label, control, opts) {
    opts = opts || {};
    var lab = DN.h('label', { text: label || '' });
    if (opts.required) lab.appendChild(DN.h('span', { class: 'req', text: '*' }));
    var f = DN.h('div', { class: 'dn-field' + (opts.col2 ? ' dn-field-col2' : '') }, [lab, control]);
    if (opts.hint) f.appendChild(DN.h('div', { class: 'dn-hint', text: opts.hint }));
    return f;
  };
  /** 分组小节: 标题 + 容纳字段的容器。返回 {el, add(node)} */
  DN.formSection = function (title) {
    var box = DN.h('div', { class: 'dn-form-section' });
    if (title) box.appendChild(DN.h('div', { class: 'dn-sec-title', text: title }));
    return { el: box, add: function (n) { box.appendChild(n); return box; } };
  };
  /** 短字段两列容器: 把多个 dn-field 并成两列 */
  DN.formGrid2 = function (fields) {
    var g = DN.h('div', { class: 'dn-form-grid2' });
    (fields || []).forEach(function (f) { if (f) g.appendChild(f); });
    return g;
  };
  /** 抽屉底部操作栏: 取消(ghost) + 主按钮。opts {okText, onOk, onCancel(默认关闭), extra(左侧节点)}; 返回 {el, ok, busy(text), reset(text)} */
  DN.drawerFoot = function (opts) {
    opts = opts || {};
    var ok = DN.h('button', { class: 'btn btn-primary btn-sm', text: opts.okText || '保存', style: 'height:32px;padding:0 18px;' });
    var cancel = DN.h('button', { class: 'btn btn-sm', text: opts.cancelText || '取消', style: 'height:32px;padding:0 16px;' });
    var foot = DN.h('div', {});
    if (opts.extra) foot.appendChild(DN.h('span', { class: 'foot-grow' }, [opts.extra]));
    foot.appendChild(cancel); foot.appendChild(ok);
    if (typeof opts.onOk === 'function') ok.onclick = opts.onOk;
    cancel.onclick = function () { if (typeof opts.onCancel === 'function') opts.onCancel(); };
    return {
      el: foot, ok: ok, cancel: cancel,
      busy: function (t) { ok.disabled = true; cancel.disabled = true; ok.textContent = t || '保存中…'; },
      reset: function (t) { ok.disabled = false; cancel.disabled = false; ok.textContent = t || (opts.okText || '保存'); }
    };
  };

  /** 纯 SVG 雷达图。dims:[{label,value(0-100)}]，opts:{size,color} */
  DN.radar = function (dims, opts) {
    opts = opts || {}; dims = dims || [];
    var n = dims.length; if (!n) return DN.empty('暂无维度数据', 'chart');
    var size = opts.size || 230, cx = size / 2, cy = size / 2, r = size / 2 - 38, color = opts.color || 'var(--primary)';
    function pt(i, frac) { var a = -Math.PI / 2 + i * 2 * Math.PI / n; return [cx + r * frac * Math.cos(a), cy + r * frac * Math.sin(a)]; }
    var svg = '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '">';
    [0.25, 0.5, 0.75, 1].forEach(function (fr) {
      var ps = []; for (var i = 0; i < n; i++) { var p = pt(i, fr); ps.push(p[0].toFixed(1) + ',' + p[1].toFixed(1)); }
      svg += '<polygon points="' + ps.join(' ') + '" fill="none" style="stroke:var(--divider)" stroke-width="1"/>';
    });
    for (var i = 0; i < n; i++) {
      var pe = pt(i, 1); svg += '<line x1="' + cx + '" y1="' + cy + '" x2="' + pe[0].toFixed(1) + '" y2="' + pe[1].toFixed(1) + '" style="stroke:var(--divider)"/>';
      var lp = pt(i, 1.18); var anchor = Math.abs(lp[0] - cx) < 6 ? 'middle' : (lp[0] > cx ? 'start' : 'end');
      svg += '<text x="' + lp[0].toFixed(1) + '" y="' + lp[1].toFixed(1) + '" font-size="11" style="fill:var(--text-faint)" text-anchor="' + anchor + '" dominant-baseline="middle">' + DN.esc(dims[i].label) + '</text>';
    }
    var dp = []; for (var i = 0; i < n; i++) { var v = Math.max(0, Math.min(100, dims[i].value || 0)) / 100; var p = pt(i, v); dp.push(p[0].toFixed(1) + ',' + p[1].toFixed(1)); }
    svg += '<polygon points="' + dp.join(' ') + '" style="fill:rgba(var(--primary-rgb),.16);stroke:' + color + '" stroke-width="2"/>';
    for (var i = 0; i < n; i++) { var v = Math.max(0, Math.min(100, dims[i].value || 0)) / 100; var p = pt(i, v); svg += '<circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="3" style="fill:' + color + '"/>'; }
    return DN.h('div', { class: 'gov-radar', html: svg + '</svg>' });
  };

  /** 纯 SVG 折线/面积趋势图。values:[number]，opts:{height,color,max,min,area} */
  DN.line = function (values, opts) {
    opts = opts || {}; var data = (values || []).map(Number).filter(function (x) { return !isNaN(x); });
    if (data.length < 2) return DN.empty('趋势数据不足（需至少 2 个数据点）', 'chart');
    var h = opts.height || 72, pad = 6, vw = 300, n = data.length;
    // 自适应区间(原强塞 0/1 致平直序列贴边+面积满填, 看似空块): 按数据范围留白, 平直则居中
    var max = opts.max != null ? opts.max : Math.max.apply(null, data), min = opts.min != null ? opts.min : Math.min.apply(null, data);
    if (max === min) { max = min + 1; min = min - 1; }
    else { var _pd = (max - min) * 0.15; max += _pd; min -= _pd; }
    var stepX = n > 1 ? (vw - pad * 2) / (n - 1) : 0, col = opts.color || 'var(--primary)';
    function X(i) { return pad + i * stepX; } function Y(v) { return (h - pad) - ((v - min) / (max - min)) * (h - pad * 2); }
    var pts = data.map(function (v, i) { return X(i).toFixed(1) + ',' + Y(v).toFixed(1); });
    var svg = '<svg width="100%" height="' + h + '" viewBox="0 0 ' + vw + ' ' + h + '" preserveAspectRatio="none">';
    svg += '<line x1="' + pad + '" y1="' + (h - pad) + '" x2="' + (vw - pad) + '" y2="' + (h - pad) + '" style="stroke:var(--divider)" vector-effect="non-scaling-stroke"/>';
    if (opts.area !== false) svg += '<path d="M' + pts.join(' L') + ' L' + X(n - 1).toFixed(1) + ',' + (h - pad) + ' L' + X(0).toFixed(1) + ',' + (h - pad) + ' Z" style="fill:' + col + '" opacity="0.10"/>';
    svg += '<polyline points="' + pts.join(' ') + '" fill="none" stroke-width="2" vector-effect="non-scaling-stroke" stroke-linejoin="round" style="stroke:' + col + '"/></svg>';
    return DN.h('div', { class: 'gov-line', html: svg });
  };

  /** 历史+预测折线。values:[number]历史, opts:{forecast:Number 下一期预测, target:Number 目标线, height, color, forecastColor} */
  DN.forecast = function (values, opts) {
    opts = opts || {};
    var data = (values || []).map(Number).filter(function (x) { return !isNaN(x); });
    if (data.length < 1) return DN.empty('暂无趋势数据', 'chart');
    var fc = (opts.forecast != null && !isNaN(Number(opts.forecast))) ? Number(opts.forecast) : null;
    var h = opts.height || 140, pad = 8;
    var series = data.slice(); if (fc != null) series.push(fc);
    var allv = series.slice(); if (opts.target != null && !isNaN(Number(opts.target))) allv.push(Number(opts.target));
    var max = Math.max.apply(null, allv), min = Math.min.apply(null, allv);
    if (max === min) { max = min + 1; }
    var n = series.length, vw = 600, stepX = n > 1 ? (vw - pad * 2) / (n - 1) : 0;
    var col = opts.color || 'var(--primary)', fcol = opts.forecastColor || 'var(--warning)';
    function X(i) { return pad + i * stepX; } function Y(v) { return (h - pad) - ((v - min) / (max - min)) * (h - pad * 2); }
    var histPts = data.map(function (v, i) { return X(i).toFixed(1) + ',' + Y(v).toFixed(1); });
    var svg = '<svg width="100%" height="' + h + '" viewBox="0 0 ' + vw + ' ' + h + '" preserveAspectRatio="none">';
    svg += '<line x1="' + pad + '" y1="' + (h - pad) + '" x2="' + (vw - pad) + '" y2="' + (h - pad) + '" style="stroke:var(--divider)" vector-effect="non-scaling-stroke"/>';
    if (opts.target != null && !isNaN(Number(opts.target))) {
      var ty = Y(Number(opts.target)).toFixed(1);
      svg += '<line x1="' + pad + '" y1="' + ty + '" x2="' + (vw - pad) + '" y2="' + ty + '" stroke-dasharray="6 4" style="stroke:var(--success)" vector-effect="non-scaling-stroke"/>';
    }
    svg += '<path d="M' + histPts.join(' L') + ' L' + X(data.length - 1).toFixed(1) + ',' + (h - pad) + ' L' + X(0).toFixed(1) + ',' + (h - pad) + ' Z" style="fill:' + col + '" opacity="0.08"/>';
    svg += '<polyline points="' + histPts.join(' ') + '" fill="none" stroke-width="2" vector-effect="non-scaling-stroke" stroke-linejoin="round" style="stroke:' + col + '"/>';
    if (fc != null && data.length >= 1) {
      var li = data.length - 1;
      svg += '<line x1="' + X(li).toFixed(1) + '" y1="' + Y(data[li]).toFixed(1) + '" x2="' + X(n - 1).toFixed(1) + '" y2="' + Y(fc).toFixed(1) + '" stroke-dasharray="6 4" stroke-width="2" vector-effect="non-scaling-stroke" style="stroke:' + fcol + '"/>';
      svg += '<circle cx="' + X(n - 1).toFixed(1) + '" cy="' + Y(fc).toFixed(1) + '" r="3.5" style="fill:' + fcol + '"/>';
    }
    data.forEach(function (v, i) { svg += '<circle cx="' + X(i).toFixed(1) + '" cy="' + Y(v).toFixed(1) + '" r="2.5" style="fill:' + col + '"/>'; });
    svg += '</svg>';
    return DN.h('div', { class: 'gov-line', html: svg });
  };

  /** 环形占比图。segments:[{label,value,color}]，opts:{size,stroke,centerLabel,centerSub} */
  DN.donut = function (segments, opts) {
    opts = opts || {}; var segs = (segments || []).filter(function (s) { return s && (s.value || 0) > 0; });
    var total = segs.reduce(function (a, s) { return a + (s.value || 0); }, 0);
    var size = opts.size || 120, sw = opts.stroke || 14, r = (size - sw) / 2, c = 2 * Math.PI * r, cx = size / 2;
    var svg = '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '"><circle cx="' + cx + '" cy="' + cx + '" r="' + r + '" fill="none" style="stroke:var(--bg-sunken)" stroke-width="' + sw + '"/>';
    var off = 0;
    segs.forEach(function (s) { var len = c * (total ? s.value / total : 0); svg += '<circle cx="' + cx + '" cy="' + cx + '" r="' + r + '" fill="none" stroke-width="' + sw + '" stroke-dasharray="' + len.toFixed(2) + ' ' + (c - len).toFixed(2) + '" stroke-dashoffset="' + (-off).toFixed(2) + '" transform="rotate(-90 ' + cx + ' ' + cx + ')" style="stroke:' + (s.color || 'var(--chart-1)') + ';transition:stroke-dasharray .5s"/>'; off += len; });
    svg += '</svg>';
    var ring = DN.h('div', { style: 'position:relative;width:' + size + 'px;height:' + size + 'px', html: svg });
    if (opts.centerLabel != null || opts.centerSub != null) ring.appendChild(DN.h('div', { style: 'position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center' },
      [opts.centerLabel != null ? DN.h('div', { style: 'font-size:20px;font-weight:750;line-height:1', text: String(opts.centerLabel) }) : null, opts.centerSub ? DN.h('div', { style: 'font-size:var(--fs-xs);color:var(--text-muted);margin-top:2px', text: opts.centerSub }) : null]));
    if (!opts.legend) return ring;
    // 可选内置图例：环 + 图例并排
    var lg = DN.h('div', { class: 'gov-legend' });
    segs.forEach(function (s) { lg.appendChild(DN.h('span', {}, [DN.h('i', { style: 'background:' + (s.color || 'var(--chart-1)') }), DN.h('span', { text: (s.label != null ? s.label : '') + ' ' + (s.value || 0) })])); });
    return DN.h('div', { style: 'display:flex;align-items:center;gap:18px;flex-wrap:wrap' }, [ring, lg]);
  };

  /** 热力清单：items:[{label,value,display?}] 按值映射蓝色深浅。opts:{rgb,onClick} */
  DN.heat = function (items, opts) {
    opts = opts || {}; items = (items || []).filter(function (i) { return i != null; }); if (!items.length) return DN.empty('暂无数据');
    var vals = items.map(function (i) { return Number(i.value) || 0; });
    var max = Math.max.apply(null, vals.concat([1]));
    var base = opts.rgb || [64, 81, 211];
    var w = DN.h('div', { class: 'gov-heat' });
    items.forEach(function (it) {
      var a = (0.1 + (it.value || 0) / max * 0.8).toFixed(2);
      var lbl = it.label == null ? '' : String(it.label);
      var cell = DN.h('div', { class: 'gov-heat-cell', style: 'background:rgba(' + base.join(',') + ',' + a + ');', title: lbl + ': ' + (it.value || 0) },
        [DN.h('span', { class: 'hl', text: lbl, title: lbl }), DN.h('span', { class: 'hv', text: String(it.display != null ? it.display : it.value) })]);
      if (opts.onClick) { cell.style.cursor = 'pointer'; cell.addEventListener('click', function () { opts.onClick(it); }); }
      w.appendChild(cell);
    });
    return w;
  };


  /** 可关闭的持久告警条节点。tone: warn|err|ok|info */
  DN.alertNode = function (msg, tone) {
    var bar = DN.h('div', { class: 'gov-alert is-' + (tone || 'warn') });
    bar.appendChild(DN.h('span', { class: 'ic', html: DN.icon(tone === 'ok' ? 'check' : 'alert') }));
    bar.appendChild(DN.h('span', { class: 'm', text: msg }));
    bar.appendChild(DN.h('button', { class: 'x', text: '×', 'aria-label': '关闭', onclick: function () { bar.remove(); } }));
    return bar;
  };

  /** 小节标题 */
  DN.sectionTitle = function (text) { return DN.h('div', { class: 'gov-section-title', text: text }); };

  /**
   * v3 统一确认弹窗(取代 window.confirm)。返回 Promise<boolean>。
   * opts: { title, danger(危险操作红色主按钮), okText, cancelText }
   * 用法: DN.confirm('删除任务「xx」?', {danger:true}).then(ok => { if (ok) ... })
   */
  DN.confirm = function (message, opts) {
    opts = opts || {};
    return new Promise(function (resolve) {
      var prevFocus = document.activeElement;
      var ok = DN.h('button', { class: 'btn ' + (opts.danger ? 'btn-confirm-danger' : 'btn-primary'), text: opts.okText || '确认' });
      var cancel = DN.h('button', { class: 'btn', text: opts.cancelText || '取消' });
      var modal = DN.h('div', { class: 'dn-modal', role: 'alertdialog', 'aria-modal': 'true', 'aria-label': opts.title || '确认操作' }, [
        DN.h('div', { class: 'dm-title', text: opts.title || '确认操作' }),
        DN.h('div', { class: 'dm-body', text: message || '' }),
        DN.h('div', { class: 'dm-foot' }, [cancel, ok])
      ]);
      var mask = DN.h('div', { class: 'dn-modal-mask' }, [modal]);
      var done = false;
      function close(result) {
        if (done) return; done = true;
        document.removeEventListener('keydown', onKey);
        mask.classList.remove('show');
        try { if (prevFocus && prevFocus.focus) prevFocus.focus(); } catch (e) {}
        setTimeout(function () { if (mask.parentNode) mask.remove(); }, 200);
        resolve(result);
      }
      function onKey(e) {
        if (e.key === 'Escape') { close(false); return; }
        if (e.key === 'Tab') {  // 双按钮焦点循环
          e.preventDefault();
          (document.activeElement === ok ? cancel : ok).focus();
        }
      }
      ok.onclick = function () { close(true); };
      cancel.onclick = function () { close(false); };
      mask.onclick = function (e) { if (e.target === mask) close(false); };
      document.addEventListener('keydown', onKey);
      document.body.appendChild(mask);
      requestAnimationFrame(function () { mask.classList.add('show'); try { ok.focus(); } catch (e) {} });
    });
  };

  // ===== 统一审批中心(各流待办聚合 + 内联通过/驳回, 不跳转) =====
  var APV_FLOW = { MDM_CHANGE: '主数据变更', DATAMODEL_CHANGE: '数据模型变更', SCRIPT_CHANGE: '脚本上线' };
  DN.approvalBadge = function (n) {
    var b = document.getElementById('dnApprovalBadge'); if (!b) return;
    if (n > 0) { b.textContent = n > 99 ? '99+' : String(n); b.style.display = ''; } else b.style.display = 'none';
  };
  DN.approvalBadgeRefresh = function () {
    DN.get('/api/approval/pending').then(function (rows) { DN.approvalBadge(Array.isArray(rows) ? rows.length : 0); }).catch(function () {});
  };
  DN.approvalCenter = function () {
    var body = DN.h('div', {});
    var mode = 'pending';
    var tabP = DN.h('a', { class: 'btn btn-sm btn-primary', href: 'javascript:void(0)', text: '待办' });
    var tabA = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '历史/全部' });
    var tabBar = DN.h('div', { style: 'display:flex;gap:8px;margin-bottom:10px;' }, [tabP, tabA]);
    var listBox = DN.h('div', {});
    body.appendChild(tabBar); body.appendChild(listBox);
    var dr = DN.drawer('审批中心', body);
    tabP.onclick = function () { mode = 'pending'; tabP.className = 'btn btn-sm btn-primary'; tabA.className = 'btn btn-sm'; load(); };
    tabA.onclick = function () { mode = 'all'; tabA.className = 'btn btn-sm btn-primary'; tabP.className = 'btn btn-sm'; load(); };
    function review(a, approve, btn, comment) {
      btn.style.pointerEvents = 'none'; btn.textContent = '处理中…';
      DN.post('/api/approval/' + a.id + '/review', { approve: approve, comment: comment || '' })
        .then(function () { DN.toast(approve ? '已通过' : '已驳回', 'ok'); load(); })
        .catch(function (e) { btn.style.pointerEvents = ''; btn.textContent = approve ? '通过' : '驳回'; DN.toast('失败: ' + (e && e.message ? e.message : ''), 'err'); });
    }
    function stPill(s) { return s === 'APPROVED' ? DN.pill('已通过', 'ok') : s === 'REJECTED' ? DN.pill('已驳回', 'err') : DN.pill('待审', 'warn'); }
    function load() {
      listBox.innerHTML = ''; listBox.appendChild(DN.h('div', { text: '加载中…', style: 'padding:16px;color:var(--text-muted);font-size:13px;' }));
      DN.get(mode === 'pending' ? '/api/approval/pending' : '/api/approval/list').then(function (rows) {
        listBox.innerHTML = '';
        rows = Array.isArray(rows) ? rows : [];
        if (mode === 'pending') DN.approvalBadge(rows.length);
        if (!rows.length) { listBox.appendChild(DN.empty(mode === 'pending' ? '暂无待审批事项 👍' : '暂无审批记录', 'check')); return; }
        rows.forEach(function (a) {
          a = a || {};
          var accent = a.status === 'APPROVED' ? 'var(--success)' : a.status === 'REJECTED' ? 'var(--error)' : 'var(--warning)';
          var card = DN.h('div', { style: 'border:1px solid var(--border);border-left:3px solid ' + accent + ';border-radius:var(--radius-md);padding:10px 12px;margin-bottom:10px;background:var(--bg-card);' });
          card.appendChild(DN.h('div', { style: 'display:flex;align-items:center;gap:8px;margin-bottom:4px;' }, [
            DN.pill(APV_FLOW[a.flowType] || a.flowType || '审批', 'info'),
            DN.h('span', { text: a.title || ('#' + a.id), title: a.title || '', style: 'font-weight:600;font-size:13px;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' }),
            stPill(a.status)
          ]));
          card.appendChild(DN.h('div', { text: '提交人 ' + (a.submitter || '-') + ' · ' + (a.createdAt || '') + (a.reviewer ? (' · 审批 ' + a.reviewer) : ''), style: 'font-size:12px;color:var(--text-muted);margin-bottom:8px;' }));
          if (a.status === 'PENDING') {
            var bar = DN.h('div', { style: 'display:flex;gap:8px;align-items:center;' });
            var ok = DN.h('a', { class: 'btn btn-sm btn-primary', href: 'javascript:void(0)', text: '通过', 'data-perm': 'governance:manage' });
            ok.onclick = function () { review(a, true, ok); };
            var no = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '驳回', style: 'color:var(--error)', 'data-perm': 'governance:manage' });
            // 内联驳回原因(替代 window.prompt, 更美更顺): 点驳回→展开输入框+确认/取消
            no.onclick = function () {
              if (card.querySelector('.dn-apv-reject')) return;
              var inp = DN.h('input', { class: 'dn-form-input', placeholder: '驳回原因(必填)', style: 'flex:1;min-width:140px;' });
              var cfm = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '确认驳回', style: 'color:var(--error)' });
              var cnl = DN.h('a', { class: 'btn btn-sm', href: 'javascript:void(0)', text: '取消' });
              var row = DN.h('div', { class: 'dn-apv-reject', style: 'display:flex;gap:8px;align-items:center;margin-top:8px;' }, [inp, cfm, cnl]);
              cnl.onclick = function () { row.remove(); };
              cfm.onclick = function () { var c = inp.value.trim(); if (!c) { DN.toast('请填驳回原因', 'warn'); inp.focus(); return; } review(a, false, cfm, c); };
              card.appendChild(row); try { inp.focus(); } catch (e) {}
            };
            bar.appendChild(ok); bar.appendChild(no);
            card.appendChild(bar);
          } else if (a.reviewComment) {
            card.appendChild(DN.h('div', { text: '意见: ' + a.reviewComment, style: 'font-size:12px;color:var(--text-regular);' }));
          }
          listBox.appendChild(card);
        });
      }).catch(function () {
        listBox.innerHTML = '';
        listBox.appendChild(DN.errorBox ? DN.errorBox('审批加载失败', load) : DN.h('div', { text: '加载失败', style: 'color:var(--error);padding:16px;' }));
      });
    }
    load();
  };
  // 顶栏徽标: 载入后拉一次待审数(轻量)
  try { setTimeout(function () { DN.approvalBadgeRefresh(); }, 2500); } catch (e) {}

  global.DN = DN;
  // 治理模块渲染器注册表：各 js/gov-<key>.js 注册 render 到此，governance.html 据此渲染
  global.GOV_RENDERERS = global.GOV_RENDERERS || {};
})(window);
