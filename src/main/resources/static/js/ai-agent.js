/* 天工·自由意志数据智能体（天工司辰）—— M1 最小对话面板。零框架，纯 DN.* 组件。
   一问一闭环：发送→后端 agent 自主串只读工具→返回 trace(工具卡)+终答。 */
(function (global) {
  'use strict';

  var sessionId = null;
  var sending = false;
  var _epoch = 0;          // 会话纪元: 新会话自增; 在途异步回调(轮询/发送响应/审批结果)凭发起时纪元识别陈旧并丢弃
  var pendingCtx = null;   // 情境入口透传的业务上下文, 随下一次 chat 上送
  var selectedModel = '';  // 模型热切档位(空=默认), 随 chat 上送 model 覆盖
  var fileListEl = null;   // 数据中心文件列表容器
  var histListEl = null;   // 左侧历史会话列表容器
  var _sessionList = [];   // 历史会话原始列表(供搜索过滤)
  var _sessionFilter = ''; // 历史搜索关键词
  var _kbBound = false;    // 全局快捷键是否已绑定(防重复)
  var _previewW = '46%';   // 预览面板上次宽度(跨打开记忆)
  var _lastSent = '';      // 上一条发送的消息(↑ 调出)
  var _userFiles = [];     // 已上传文件名(供输入框 @ 补全)
  var _mentionDD = null;   // 当前 @ 候选弹层(回车选首项用)
  var flowEl = null, inputEl = null, sendBtn = null, inputBarEl = null, built = false;

  function groupColor(g) {
    return ({ gov: 'var(--chart-1)', quality: 'var(--chart-6)', lineage: 'var(--chart-3)', sync: 'var(--chart-4)', metadata: 'var(--chart-2)' })[g] || 'var(--chart-5)';
  }

  // 用户气泡(含"↻ 重问": 把该问题填回输入框, 可编辑后重发)
  function userBubble(text) {
    var b = DN.h('div', { class: 'dn-ai-bubble user', style: 'width:fit-content;white-space:pre-wrap;' });
    b.appendChild(document.createTextNode(text == null ? '' : text));
    if (text && String(text).trim().length > 6) {
      b.appendChild(DN.h('div', { style: 'text-align:right;margin-top:4px;' }, [
        DN.h('a', { href: 'javascript:void(0)', text: '↻ 重问', title: '把这条问题填回输入框, 可编辑后重新发送', style: 'font-size:11px;color:var(--text-inverse);opacity:.85;text-decoration:underline;', onclick: function () { if (inputEl) { inputEl.value = text; inputEl.style.height = 'auto'; inputEl.style.height = Math.min(inputEl.scrollHeight, 160) + 'px'; inputEl.focus(); inputEl.dispatchEvent(new Event('input')); } } })
      ]));
    }
    return b;
  }

  // 助手终答气泡（识别 [表:库.表]/[规则:#id]/[任务:#id] token 渲染可点深链 chip, XSS 安全用 DN.h text）
  function assistantBubble(text, tone) {
    var raw = String(text == null ? '' : text);
    var inner = DN.h('div', { class: 'ai-md dn-ai-bubble assistant' + (tone === 'err' ? ' err' : ''), style: 'width:fit-content;max-width:min(100%,820px);overflow-x:auto;' });
    renderMarkdown(inner, raw);
    // 网页深链拦截: 答复里指向 /files/{id}/view 的链接, 点击改为右侧预览(不跳新页, 与 artifact 体验一致)
    try {
      inner.querySelectorAll('a[href*="/files/"]').forEach(function (a) {
        var href = a.getAttribute('href') || '';
        if (/\/files\/\d+\/view/.test(href)) a.addEventListener('click', function (e) { e.preventDefault(); openPreview(href, a.textContent || '网页预览'); });
      });
    } catch (e) {}
    // 实质终答加"复制"(复制原始 markdown 文本, DN.copy 带 execCommand 降级)
    if (tone !== 'err' && raw.trim().length > 12) {
      inner.appendChild(DN.h('div', { style: 'text-align:right;margin-top:6px;' }, [
        DN.h('a', { href: 'javascript:void(0)', text: '⧉ 复制', title: '复制此回答', style: 'font-size:11px;color:var(--text-muted);text-decoration:none;', onclick: function () { if (window.DN && DN.copy) DN.copy(raw); } })
      ]));
    }
    // 错误答复加"重试"(重发上条, 应对瞬时失败)
    if (tone === 'err' && _lastSent) {
      inner.appendChild(DN.h('div', { style: 'text-align:right;margin-top:6px;' }, [
        DN.h('a', { href: 'javascript:void(0)', text: '↻ 重试', title: '重新发送上一条', style: 'font-size:11px;color:var(--primary);text-decoration:none;', onclick: function () { if (inputEl && !sending) { inputEl.value = _lastSent; send(); } } })
      ]));
    }
    return inner;
  }

  function linkChip(label, onclick) {
    return DN.h('a', { href: 'javascript:void(0)', text: label, onclick: onclick,
      style: 'display:inline-block;margin:0 3px;padding:1px 9px;border-radius:var(--radius-lg);background:var(--primary);color:var(--text-inverse);font-size:12px;text-decoration:none;' });
  }

  // ===== 轻量 Markdown → DOM 渲染(零依赖, XSS 安全: 全程 DOM 构造不用 innerHTML 拼 LLM 文本) =====
  // 支持: # 标题 / **粗体** / `行内码` / ```代码块``` / - 与 1. 列表 / | 表格 | / > 引用 / 段落换行 + 深链 chip
  // 追加: [文本](url) markdown 链接(7,8) 与 裸下载链接(9) → 渲染为可点 <a>(修复下载链接不可点)
  var INLINE_RE = /\*\*([^*]+?)\*\*|`([^`]+?)`|\[表:([^\]\.]+)\.([^\]]+)\]|\[规则:#?(\d+)\]|\[任务:#?(\d+)\]|\[([^\]]+)\]\(([^)\s]+)\)|(\/api\/ai\/agent\/files\/\d+\/download)/g;
  function appendInline(el, str) {
    var last = 0, m, re = new RegExp(INLINE_RE.source, 'g'); // 独立实例: 防递归(粗体内联)共享 lastIndex 串台
    while ((m = re.exec(str)) !== null) {
      if (m.index > last) el.appendChild(document.createTextNode(str.slice(last, m.index)));
      if (m[1] != null) { var _b = DN.h('strong', { style: 'font-weight:650;' }); appendInline(_b, m[1]); el.appendChild(_b); } // 递归: 粗体内的[链接](url)也要可点(agent常写**[文件](url)**)
      else if (m[2] != null) el.appendChild(DN.h('code', { text: m[2], style: 'background:var(--bg-main);border-radius:var(--radius-sm);padding:1px 5px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;' }));
      else if (m[3] != null) (function (db, tb) { el.appendChild(linkChip('🔗 ' + db + '.' + tb, function () { go('catalog', { openTable: { db: db, table: tb } }); })); })(m[3], m[4]);
      else if (m[5] != null) (function (id) { el.appendChild(linkChip('🔗 规则#' + id, function () { go('governance', { gov: 'quality', ruleId: Number(id) }); })); })(m[5]);
      else if (m[6] != null) (function (id) { el.appendChild(linkChip('🔗 任务#' + id, function () { go('dbsync', { openDetail: Number(id) }); })); })(m[6]);
      else if (m[7] != null) el.appendChild(urlChip(m[7], m[8]));
      else if (m[9] != null) el.appendChild(urlChip('下载文件', m[9]));
      last = m.index + m[0].length;
    }
    if (last < str.length) el.appendChild(document.createTextNode(str.slice(last)));
  }

  // URL scheme 白名单: 仅 http(s):// 与站内相对路径(/...)可点; javascript:/data:/vbscript: 等伪协议降级纯文本防 XSS
  function isSafeUrl(url) {
    var u = String(url == null ? '' : url).trim();
    if (/^\//.test(u)) return true;                 // 站内相对路径
    return /^https?:\/\//i.test(u);                 // 仅 http/https 绝对地址
  }
  // 真实 <a href> 链接(下载/外链可点); 下载类加 download 属性, 其余新标签页打开
  function urlChip(label, url) {
    if (!isSafeUrl(url)) {
      // 非白名单 scheme(javascript: 等)不渲染为可点链接, 降级为纯文本(原文+url)
      return document.createTextNode(label + '(' + String(url == null ? '' : url) + ')');
    }
    var isDl = /\/api\/ai\/agent\/files\//.test(url) || /\/download(\?|$)/.test(url);
    var a = DN.h('a', { href: url, text: (isDl && !/^[⬇↓]/.test(label) ? '⬇ ' : '') + label,
      style: 'display:inline-block;margin:0 3px;padding:2px 10px;border-radius:var(--radius-lg);background:var(--primary);color:var(--text-inverse);font-size:12px;text-decoration:none;' });
    if (isDl) a.setAttribute('download', ''); else a.setAttribute('target', '_blank');
    return a;
  }
  function renderMarkdown(container, text) {
    var lines = String(text == null ? '' : text).replace(/\r\n/g, '\n').split('\n');
    var i = 0;
    function isBlank(s) { return s.trim() === ''; }
    while (i < lines.length) {
      var line = lines[i];
      // 代码块 ```
      if (/^```/.test(line.trim())) {
        var lang = line.trim().replace(/^`+/, '').trim(); // fence 语言(```sql → sql)
        var buf = []; i++;
        while (i < lines.length && !/^```/.test(lines[i].trim())) { buf.push(lines[i]); i++; }
        i++; // 跳过收尾 ```
        var _code = buf.join('\n');
        var pre = DN.h('pre', { style: 'position:relative;background:var(--bg-main);border:1px solid var(--divider);border-radius:var(--radius-lg);padding:' + (lang ? '24px' : '10px') + ' 12px 10px;overflow-x:auto;margin:8px 0;' });
        if (lang) pre.appendChild(DN.h('span', { text: lang.toUpperCase(), style: 'position:absolute;top:5px;left:10px;font-size:10px;letter-spacing:.5px;color:var(--text-muted);font-family:ui-monospace,monospace;' }));
        pre.appendChild(DN.h('code', { text: _code, style: 'font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;line-height:1.6;white-space:pre;' }));
        // 代码块右上角复制按钮(ChatGPT 式, 复制纯代码; DN.copy execCommand 降级 HTTP 安全)
        pre.appendChild(DN.h('button', { text: '复制', title: '复制代码', style: 'position:absolute;top:6px;right:6px;font-size:11px;padding:1px 7px;border:1px solid var(--border);border-radius:var(--radius-sm);background:var(--bg-card);color:var(--text-muted);cursor:pointer;opacity:.85;', onclick: (function (c) { return function (e) { e.stopPropagation(); if (window.DN && DN.copy) DN.copy(c); }; })(_code) }));
        container.appendChild(pre); continue;
      }
      // 标题 #..######
      var hm = line.match(/^(#{1,6})\s+(.*)$/);
      if (hm) {
        var lv = hm[1].length;
        var sz = lv <= 1 ? 18 : lv === 2 ? 16 : lv === 3 ? 14.5 : 13.5;
        var h = DN.h('div', { style: 'font-weight:650;font-size:' + sz + 'px;color:var(--text-primary);margin:' + (container.childNodes.length ? 12 : 2) + 'px 0 6px;' });
        appendInline(h, hm[2]); container.appendChild(h); i++; continue;
      }
      // 表格: 当前行含 | 且下一行是分隔行 |---|
      if (line.indexOf('|') >= 0 && i + 1 < lines.length && /^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$/.test(lines[i + 1]) && lines[i + 1].indexOf('-') >= 0) {
        var rows = [splitRow(line)]; i += 2;
        while (i < lines.length && lines[i].indexOf('|') >= 0 && !isBlank(lines[i])) { rows.push(splitRow(lines[i])); i++; }
        var tbl = DN.h('table', { style: 'border-collapse:collapse;width:100%;margin:8px 0;font-size:12.5px;' });
        rows.forEach(function (cells, ri) {
          var tr = DN.h('tr', {});
          cells.forEach(function (cell) {
            var td = DN.h(ri === 0 ? 'th' : 'td', { style: 'border:1px solid var(--border);padding:5px 9px;text-align:left;' + (ri === 0 ? 'background:var(--bg-main);font-weight:600;' : '') });
            appendInline(td, cell); tr.appendChild(td);
          });
          tbl.appendChild(tr);
        });
        container.appendChild(DN.h('div', { style: 'overflow-x:auto;margin:8px 0;' }, [tbl])); continue; // 宽表横向滚动
      }
      // 无序列表
      if (/^\s*[-*+]\s+/.test(line)) {
        var ul = DN.h('ul', { style: 'margin:6px 0;padding-left:20px;' });
        while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
          var li = DN.h('li', { style: 'margin:2px 0;' }); appendInline(li, lines[i].replace(/^\s*[-*+]\s+/, '')); ul.appendChild(li); i++;
        }
        container.appendChild(ul); continue;
      }
      // 有序列表
      if (/^\s*\d+\.\s+/.test(line)) {
        var ol = DN.h('ol', { style: 'margin:6px 0;padding-left:22px;' });
        while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
          var oli = DN.h('li', { style: 'margin:2px 0;' }); appendInline(oli, lines[i].replace(/^\s*\d+\.\s+/, '')); ol.appendChild(oli); i++;
        }
        container.appendChild(ol); continue;
      }
      // 引用
      if (/^>\s?/.test(line)) {
        var bq = DN.h('div', { style: 'border-left:3px solid var(--border);padding:2px 0 2px 10px;margin:6px 0;color:var(--text-muted);' });
        appendInline(bq, line.replace(/^>\s?/, '')); container.appendChild(bq); i++; continue;
      }
      // 分隔线
      if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
        container.appendChild(DN.h('div', { style: 'border-top:1px solid var(--divider);margin:10px 0;' })); i++; continue;
      }
      // 空行
      if (isBlank(line)) { i++; continue; }
      // 段落: 收集连续普通行, 行内换行用 <br>
      var para = DN.h('div', { style: 'margin:4px 0;' });
      var first = true;
      while (i < lines.length && !isBlank(lines[i]) && !/^(#{1,6})\s|^```|^\s*[-*+]\s|^\s*\d+\.\s|^>\s?/.test(lines[i]) && !(lines[i].indexOf('|') >= 0 && i + 1 < lines.length && /^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$/.test(lines[i + 1]))) {
        if (!first) para.appendChild(DN.h('br', {}));
        appendInline(para, lines[i]); first = false; i++;
      }
      container.appendChild(para);
    }
  }
  function splitRow(line) {
    var s = line.trim().replace(/^\|/, '').replace(/\|$/, '');
    return s.split('|').map(function (c) { return c.trim(); });
  }

  // 统一深链跳转（复用全局 navigateTo, 自动压栈可一键返回 AI助手）
  function go(route, ctx) {
    try { if (window.navigateTo) window.navigateTo(route, ctx || {}); else if (window.location) window.location.hash = '#/' + route; }
    catch (e) { if (DN && DN.toast) DN.toast('跳转失败', 'err'); }
  }

  // 工具调用卡（天工开物·逐道工序详记）
  function toolCard(step) {
    var col = groupColor(step.skillGroup);
    var card = DN.card({ title: (step.skillName ? (TOOL_LABEL[step.skillName] || step.skillName) : '工具'), icon: 'layers' });
    card.el.style.cssText = (card.el.style.cssText || '') + ';margin:8px 0 8px 18px;border-left:3px solid ' + col + ';';
    // 头部标签
    var tags = DN.h('span', { style: 'margin-left:8px;display:inline-flex;gap:6px;vertical-align:middle;' });
    if (step.skillGroup) tags.appendChild(DN.h('span', { text: step.skillGroup, class: 'dn-ai-badge', style: 'background:' + col + ';color:var(--text-inverse);' }));
    if (Number(step.readOnly) === 1) tags.appendChild(DN.pill('只读', 'info'));
    var ok = step.resultStatus === 'ok';
    tags.appendChild(DN.pill(ok ? '成功' : (step.resultStatus || '-'), ok ? 'ok' : 'err'));
    // 把标签塞进卡头
    try { card.el.querySelector('.gov-card-hd').appendChild(tags); } catch (e) {}

    // 决策推理留痕(天工开物·逐道工序透明; 后端从 <think> 抽取, 不混入终答)
    if (step.thinkContent) {
      card.body.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);font-style:italic;margin-bottom:6px;padding:6px 10px;border-left:2px solid ' + col + ';background:var(--bg-body);border-radius:var(--radius);max-height:140px;overflow:auto;white-space:pre-wrap;', text: '💭 ' + step.thinkContent }));
    }

    if (step.argsJson && step.argsJson !== '{}' && step.argsJson !== 'null') {
      card.body.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);margin-bottom:6px;', text: '参数：' + step.argsJson }));
    }
    var resultText = summarizeResult(step.resultData, ok);
    var pre = DN.h('div', { style: 'font-size:12px;color:var(--text-regular);white-space:pre-wrap;word-break:break-word;max-height:180px;overflow:auto;background:var(--bg-body);border-radius:var(--radius);padding:8px 10px;', text: resultText });
    card.body.appendChild(pre);
    // 工具结果若带 _deeplink, 渲染"跳转到模块"按钮(天工开物·一图可复核 + 自由意志·人机协同回链)
    var dl = extractDeeplink(step.resultData);
    if (dl && dl.route) {
      card.body.appendChild(DN.h('a', { class: 'btn', href: 'javascript:void(0)', text: '↗ 在' + routeName(dl.route) + '中查看', style: 'margin-top:8px;font-size:12px;',
        onclick: function () { go(dl.route, dl.ctx || {}); } }));
    }
    return card.el;
  }

  function extractDeeplink(resultData) {
    if (!resultData) return null;
    try {
      var obj = JSON.parse(String(resultData));
      var data = obj && obj.data != null ? obj.data : obj;
      return data && data._deeplink ? data._deeplink : null;
    } catch (e) { return null; }
  }

  // 从工具结果里抽取 _preview(table_data 样例行), 供会话内直接渲染数据表格
  function extractPreview(resultData) {
    if (!resultData) return null;
    try {
      var obj = JSON.parse(String(resultData));
      var data = obj && obj.data != null ? obj.data : obj;
      var pv = data && data._preview;
      if (pv && pv.columns && pv.rows) return { pv: pv, db: data.db, table: data.table };
      return null;
    } catch (e) { return null; }
  }

  // 数据表格卡(会话内直接看表数据, 醒目常显不折叠): 表头 sticky + 横纵滚动
  function dataGridCard(p) {
    var pv = p.pv;
    var rows = pv.rows || [], cols = pv.columns || [];
    var view = rows.slice(); // 当前显示顺序(随排序变), 导出跟随所见
    var title = '📋 ' + (p.db ? p.db + '.' : '') + (p.table || '表') + ' 数据预览（' + (pv.returned != null ? pv.returned : rows.length) + ' 行 × ' + cols.length + ' 列）';
    var card = DN.h('div', { style: 'margin:8px 0;border:1px solid var(--border);border-radius:var(--radius-lg);background:var(--bg-card);overflow:hidden;' });
    var hdr = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:8px 12px;background:var(--bg-main);border-bottom:1px solid var(--divider);' }, [
      DN.h('div', { text: title, style: 'flex:1;font-weight:600;font-size:12.5px;color:var(--text-primary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' })
    ]);
    if (rows.length) {
      var exp = DN.h('span', { text: '⬇ 导出CSV', title: '下载当前预览为CSV', style: 'flex:0 0 auto;cursor:pointer;font-size:11px;color:var(--primary);' });
      exp.onclick = function () {
        function esc(v) { v = (v == null ? '' : String(v)); return /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v; }
        var lines = [cols.map(esc).join(',')];
        view.forEach(function (r) { lines.push((Array.isArray(r) ? r : cols.map(function (c) { return r[c]; })).map(esc).join(',')); });
        var blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
        var a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = (p.table || 'data') + '.csv'; a.click(); setTimeout(function () { URL.revokeObjectURL(a.href); }, 2000);
      };
      hdr.appendChild(exp);
      var md = DN.h('span', { text: '⧉ 复制MD', title: '复制为 Markdown 表格(粘进报告/wiki)', style: 'flex:0 0 auto;cursor:pointer;font-size:11px;color:var(--primary);' });
      md.onclick = function () {
        function c(v) { return String(v == null ? '' : v).replace(/\|/g, '\\|').replace(/\n/g, ' '); }
        var L = ['| ' + cols.map(c).join(' | ') + ' |', '| ' + cols.map(function () { return '---'; }).join(' | ') + ' |'];
        view.forEach(function (r) { L.push('| ' + (Array.isArray(r) ? r : cols.map(function (k) { return r[k]; })).map(c).join(' | ') + ' |'); });
        if (window.DN && DN.copy) DN.copy(L.join('\n')); // 带 execCommand 降级, 兼容旧浏览器
      };
      hdr.appendChild(md);
    }
    card.appendChild(hdr);
    if (!rows.length) { card.appendChild(DN.h('div', { text: '（表中无数据行）', style: 'padding:10px 12px;color:var(--text-muted);font-size:12px;' })); return card; }
    var scroll = DN.h('div', { style: 'overflow:auto;max-height:340px;' });
    var tbl = DN.h('table', { style: 'border-collapse:collapse;width:100%;font-size:12px;' });
    var thr = DN.h('tr', {});
    var sort = { idx: -1, dir: 1 }; // 点列头排序: idx=列, dir=1升/-1降
    var ths = [];
    thr.appendChild(DN.h('th', { text: '#', style: 'border:1px solid var(--border);padding:5px 8px;background:var(--bg-main);font-weight:600;position:sticky;top:0;left:0;z-index:2;color:var(--text-muted);' }));
    cols.forEach(function (c, ci) {
      var th = DN.h('th', { title: '点击按此列排序', style: 'border:1px solid var(--border);padding:5px 9px;text-align:left;background:var(--bg-main);font-weight:600;white-space:nowrap;position:sticky;top:0;z-index:1;cursor:pointer;user-select:none;' });
      th.textContent = String(c);
      th.onclick = function () { if (sort.idx === ci) sort.dir = -sort.dir; else { sort.idx = ci; sort.dir = 1; } renderBody(); };
      ths.push(th); thr.appendChild(th);
    });
    tbl.appendChild(thr);
    var tbody = DN.h('tbody', {});
    tbl.appendChild(tbody);
    function cmp(a, b) {
      var na = parseFloat(a), nb = parseFloat(b);
      if (!isNaN(na) && !isNaN(nb) && a !== '' && a != null && b !== '' && b != null) return na - nb;
      return String(a == null ? '' : a).localeCompare(String(b == null ? '' : b), 'zh');
    }
    function renderBody() {
      ths.forEach(function (th, i) { var base = String(cols[i]); th.textContent = base + (sort.idx === i ? (sort.dir > 0 ? ' ▲' : ' ▼') : ''); });
      function cellAt(r, i) { return Array.isArray(r) ? (r || [])[i] : (r ? r[cols[i]] : undefined); } // 兼容数组行与对象行(按列名取)
      function cells(r) { return Array.isArray(r) ? (r || []) : cols.map(function (c) { return r ? r[c] : undefined; }); }
      var data = rows.slice();
      if (sort.idx >= 0) data.sort(function (r1, r2) { return cmp(cellAt(r1, sort.idx), cellAt(r2, sort.idx)) * sort.dir; });
      view = data; // 导出跟随当前排序
      tbody.innerHTML = '';
      data.forEach(function (row, ri) {
        var tr = DN.h('tr', {});
        tr.appendChild(DN.h('td', { text: String(ri + 1), style: 'border:1px solid var(--border);padding:5px 8px;color:var(--text-muted);text-align:right;position:sticky;left:0;background:var(--bg-card);z-index:1;' }));
        cells(row).forEach(function (v) {
          var isNull = v == null, isEmpty = v === '';
          var disp = isNull ? 'NULL' : (isEmpty ? '(空串)' : String(v)); // 区分 NULL/空串/0, 防误判
          var muted = isNull || isEmpty;
          tr.appendChild(DN.h('td', { text: disp, title: isNull ? 'NULL' : String(v), style: 'border:1px solid var(--border);padding:5px 9px;white-space:nowrap;max-width:280px;overflow:hidden;text-overflow:ellipsis;' + (muted ? 'color:var(--text-muted);font-style:italic;' : '') }));
        });
        tbody.appendChild(tr);
      });
    }
    renderBody();
    scroll.appendChild(tbl); card.appendChild(scroll);
    return card;
  }
  // 图表卡: 把 chart 工具返回的 _chart spec 渲染成 DN.* 图表
  function chartCard(spec) {
    spec = spec || {};
    var type = (spec.type || 'bar').toLowerCase();
    var data = spec.data;
    var inner;
    try {
      if (type === 'line') {
        var vals = Array.isArray(data) ? data.map(function (x) {
          return (x && typeof x === 'object') ? Number(x.value) : Number(x);
        }) : (data && Array.isArray(data.values) ? data.values.map(Number) : []);
        vals = vals.filter(function (n) { return !isNaN(n); });
        inner = vals.length >= 2 ? DN.line(vals, { area: true }) : DN.empty('折线数据不足(需≥2点)', 'chart');
      } else if (type === 'pie') {
        var segs = (Array.isArray(data) ? data : []).map(function (d) {
          return { label: String(d.label != null ? d.label : ''), value: Number(d.value) || 0 };
        });
        inner = segs.length ? DN.donut(segs, { legend: true }) : DN.empty('暂无饼图数据', 'chart');
      } else if (type === 'radar') {
        var dims = (Array.isArray(data) ? data : []).map(function (d) {
          return { label: String(d.label != null ? d.label : ''), value: Math.max(0, Math.min(100, Number(d.value) || 0)) };
        });
        inner = dims.length ? DN.radar(dims) : DN.empty('暂无雷达数据', 'chart');
      } else {
        var items = (Array.isArray(data) ? data : []).map(function (d) {
          return { label: String(d.label != null ? d.label : ''), value: Number(d.value) || 0 };
        });
        inner = items.length ? DN.bars(items) : DN.empty('暂无柱状数据', 'chart');
      }
    } catch (e) {
      inner = DN.empty('图表渲染失败: ' + (e && e.message ? e.message : e), 'chart');
    }
    var title = spec.title ? DN.h('div', { class: 'dn-chart-title', style: 'font-weight:600;margin-bottom:8px;color:var(--text-primary)', text: String(spec.title) }) : null;
    var card = DN.h('div', { class: 'dn-chart-card', style: 'margin:8px 0;padding:14px;border:1px solid var(--border);border-radius:var(--radius-lg);background:var(--bg-card)' }, [title, inner].filter(Boolean));
    return card;
  }

  // 网页 artifact 卡片(create_page 产物): 点击在右侧面板渲染, 像 Codex/Claude artifact
  function pageCard(pg) {
    if (!pg) return DN.h('span');
    var card = DN.h('div', { class: 'dn-ai-pagecard', style: 'margin:4px 0 8px;border:1px solid var(--border);border-radius:var(--radius-lg);padding:10px 12px;background:var(--bg-body);display:flex;align-items:center;gap:10px;cursor:pointer;' });
    card.onclick = function () { openPreview(pg.previewUrl, pg.title || pg.fileName); }; // 整卡可点预览(大点击区)
    var icon = ({ markdown: '📝', mermaid: '🧩', code: '💻', csv: '📊', json: '🔧', svg: '🖼', html: '🌐' })[pg.artifactType] || '🌐';
    card.appendChild(DN.h('div', { text: icon, style: 'font-size:22px;flex:0 0 auto;' }));
    card.appendChild(DN.h('div', { style: 'flex:1;min-width:0;' }, [
      DN.h('div', { text: pg.title || pg.fileName || '网页', style: 'font-weight:600;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' }),
      DN.h('div', { text: (pg.artifactType ? String(pg.artifactType).toUpperCase() + ' · ' : '') + '点击预览，在右侧渲染', style: 'font-size:12px;color:var(--text-muted);' })
    ]));
    var open = DN.h('button', { class: 'btn btn-sm btn-primary', text: '预览', style: 'flex:0 0 auto;background:var(--primary);color:var(--text-inverse);border-color:var(--primary);' });
    open.onclick = function () { openPreview(pg.previewUrl, pg.title || pg.fileName); };
    card.appendChild(open);
    if (pg.downloadUrl) { var dla = DN.h('a', { href: pg.downloadUrl, title: '下载', text: '↓', style: 'flex:0 0 auto;color:var(--primary);text-decoration:none;font-size:17px;font-weight:700;padding:0 4px;' }); dla.onclick = function (e) { e.stopPropagation(); }; card.appendChild(dla); }
    return card;
  }

  // 右侧网页预览面板(Codex 式分屏): iframe 沙箱渲染(no allow-same-origin → 不透明源, 配合后端 CSP sandbox 双重隔离)
  function openPreview(url, title) {
    if (!url) return;
    var root = document.getElementById('aiAgentRoot'); if (!root) return;
    var side = root.querySelector('.dn-ai-side'); if (side) side.style.display = 'none'; // 预览时收起左数据中心栏, 让聊天+预览有空间(防三列挤垮)
    var panel = document.getElementById('aiPreviewPanel');
    if (!panel) {
      panel = DN.h('div', { id: 'aiPreviewPanel', style: 'flex:0 0 ' + _previewW + ';min-width:340px;max-width:80%;display:flex;flex-direction:column;border-left:1px solid var(--border);background:var(--bg-body);min-height:0;' });
      root.appendChild(panel);
    }
    panel.innerHTML = '';
    panel.appendChild(DN.h('div', { style: 'display:flex;align-items:center;gap:8px;padding:8px 12px;border-bottom:1px solid var(--border);flex:0 0 auto;' }, [
      DN.h('div', { text: '🌐 ' + (title || '网页预览'), style: 'flex:1;font-size:13px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--text-primary);' }),
      DN.h('span', { text: '⛶', title: '全屏/还原', style: 'cursor:pointer;font-size:14px;color:var(--text-muted);padding:0 2px;flex:0 0 auto;', onclick: function () {
        var full = panel.getAttribute('data-full') === '1';
        if (full) { panel.style.flex = '0 0 46%'; panel.style.maxWidth = '62%'; panel.setAttribute('data-full', '0'); var sd = root.querySelector('.dn-ai-side'); /* 保持隐藏 */ }
        else { panel.style.flex = '1 1 100%'; panel.style.maxWidth = 'none'; panel.setAttribute('data-full', '1'); }
      } }),
      DN.h('span', { text: '⧉ 链接', title: '复制分享链接', style: 'cursor:pointer;font-size:12px;color:var(--primary);flex:0 0 auto;', onclick: function () { try { if (window.DN && DN.copy) DN.copy(location.origin + url, true); DN.toast('已复制链接', 'ok'); } catch (e) {} } }),
      DN.h('a', { href: url, target: '_blank', rel: 'noopener', text: '↗ 新窗口', title: '在新标签打开', style: 'font-size:12px;color:var(--primary);text-decoration:none;flex:0 0 auto;' }),
      DN.h('span', { text: '✕', title: '关闭预览', style: 'cursor:pointer;font-size:15px;color:var(--text-muted);padding:0 4px;flex:0 0 auto;', onclick: closePreview })
    ]));
    var wrap = DN.h('div', { style: 'flex:1;position:relative;min-height:0;' });
    var loading = DN.h('div', { text: '加载中…', style: 'position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:var(--text-muted);font-size:13px;background:#fff;' });
    var frame = DN.h('iframe', { src: url, sandbox: 'allow-scripts allow-popups allow-modals', style: 'border:0;width:100%;height:100%;background:#fff;' });
    var _lt = setTimeout(function () { loading.textContent = '加载较慢…可点右上 ↗ 新窗口打开'; }, 8000); // 超时兜底
    frame.addEventListener('load', function () { clearTimeout(_lt); loading.style.display = 'none'; });
    wrap.appendChild(frame); wrap.appendChild(loading);
    panel.appendChild(wrap);
    // 左边缘拖拽调宽(artifact 体验)
    panel.style.position = 'relative';
    var grip = DN.h('div', { style: 'position:absolute;left:-3px;top:0;bottom:0;width:6px;cursor:col-resize;z-index:6;' });
    grip.onmousedown = function (e) {
      e.preventDefault();
      function mv(ev) { var r = root.getBoundingClientRect(); var w = r.right - ev.clientX; if (w > 300 && w < r.width - 300) { panel.style.flex = '0 0 ' + w + 'px'; panel.style.maxWidth = 'none'; _previewW = w + 'px'; } }
      function up() { document.removeEventListener('mousemove', mv); document.removeEventListener('mouseup', up); }
      document.addEventListener('mousemove', mv); document.addEventListener('mouseup', up);
    };
    panel.appendChild(grip);
  }
  function closePreview() {
    var p = document.getElementById('aiPreviewPanel'); if (p) p.remove();
    var root = document.getElementById('aiAgentRoot');
    var side = root && root.querySelector('.dn-ai-side');
    var userHid = false; try { userHid = localStorage.getItem('aiSideHidden') === '1'; } catch (e) {}
    if (side && !userHid) side.style.display = ''; // 恢复左数据中心栏(除非用户手动折叠了)
  }
  function extractPage(resultData) {
    try { var o = JSON.parse(resultData); var data = (o && o.data) ? o.data : o; return (data && data._page) ? data._page : null; }
    catch (e) { return null; }
  }

  function renderPreviews(steps) {
    (steps || []).forEach(function (s) {
      if (!s || !s.resultData) return;
      var p = extractPreview(s.resultData); if (p) flowEl.appendChild(dataGridCard(p));
      var pg = extractPage(s.resultData); if (pg) flowEl.appendChild(pageCard(pg));
    });
  }

  function routeName(r) {
    return ({ catalog: '数据地图', governance: '数据治理', dbsync: '数据同步', operations: '数据运维', metrics: '指标管理', mdm: '主数据', project: '项目管理', quality: '数据质量', home: '首页' })[r] || r;
  }

  // 结果摘要：JSON 解析后取关键字段，过长折叠
  function summarizeResult(resultData, ok) {
    if (resultData == null || resultData === '') return ok ? '（无返回数据）' : '（无错误信息）';
    var s = String(resultData);
    try {
      var obj = JSON.parse(s);
      if (obj && typeof obj === 'object') {
        if (obj.message && obj.status === 'error') return '错误[' + (obj.type || '') + ']：' + obj.message;
        var data = obj.data != null ? obj.data : obj;
        s = JSON.stringify(data, null, 1);
      }
    } catch (e) { /* 非 JSON 原样展示 */ }
    if (s.length > 1200) s = s.slice(0, 1200) + '\n…（已折叠，完整轨迹见会话记录）';
    return s;
  }

  // 工具→简略友好名(进度副标题用, 不暴露参数/结果)
  // 工具→人性化进度短语(接在"正在…"后, 简短告诉用户在干嘛)
  var TOOL_LABEL = {
    semantic_search: '检索相关的表', gov_overview: '查看治理总览', quality_score: '分析质量分',
    lineage_impact: '梳理血缘影响', graph_impact: '梳理血缘影响', graph_trace: '溯源数据来路', graph_neighbors: '看血缘邻居',
    file_read: '阅读你上传的文件', export_file: '生成可下载文件', sync_dashboard: '查看同步态势', sync_job_detail: '查看同步任务',
    sched_today_status: '查看今日调度', sched_run_log: '翻看调度日志', table_profile: '翻看字段明细', asset_detail: '查看表的详情',
    todo: '拆解任务计划', delegate_task: '并行分派子任务', read_tool_result: '取回完整结果', metric_detail: '查看指标口径', table_data: '查看表的数据',
    project_overview: '查看项目', issue_stats: '统计工单', health_trend: '看健康趋势', skill_library: '翻阅操作技能', tool_search: '寻找合适工具',
    create_ods_table: '新建ODS同步任务(抽到数仓)', create_sync_job: '准备建同步任务', create_quality_rule: '准备建质量规则', create_metric: '准备建指标',
    create_project: '准备建项目', create_script: '准备建脚本',
    create_artifact: '生成预览内容', create_page: '生成网页', create_dev_folder: '准备建开发目录', run_ods_task: '运行ODS拉数',
    run_script: '运行脚本', run_sync_job: '运行同步', run_quality_rule: '运行质量规则', chart: '生成图表', ask_user: '向你确认',
    classify_column: '字段分类分级', calc_metric: '计算指标值', create_subject: '准备建主题域', save_wiki_page: '保存Wiki',
    rebuild_lineage: '重建血缘', run_backfill: '运行回填', datamodel_generate: '生成数据模型', cron_job: '排程定时任务'
  };
  function toolLabel(n) { return TOOL_LABEL[n] || (n ? n + ' 处理中' : '处理'); }

  function thinking() {
    var box = DN.h('div', { style: 'background:var(--bg-card);border:1px solid var(--border);padding:10px 16px;border-radius:var(--radius-lg);font-size:13px;color:var(--text-regular);display:flex;align-items:center;gap:10px;box-shadow:var(--shadow-sm);' });
    box.appendChild(DN.h('span', { class: 'dn-ai-spin' }));
    box.appendChild(DN.h('span', { text: '天工司辰运行中' }));
    box.appendChild(DN.h('span', { id: 'aiThinkSub', style: 'color:var(--text-muted);font-size:12px;', text: '' }));
    return DN.h('div', { id: 'aiThinking', style: 'display:flex;justify-content:flex-start;margin:10px 0;' }, [box]);
  }

  // 旧会话止损: 纪元更替后对旧会话补发协作式中断(fail-safe, 失败静默不打扰)
  function interruptSession(sid) {
    if (!sid) return;
    try { DN.post('/api/ai/agent/' + sid + '/interrupt', {}).catch(function () {}); } catch (e) {}
  }

  // 统一带动画进度的请求执行器(send 与 ask_user 提交共用): 动画 + 运行中轮询简略进度 + 结果优先渲染
  function runRequest(url, body) {
    sessionId = sessionId || genSid();
    body.sessionId = body.sessionId || sessionId;
    saveSid(sessionId);
    var ep = _epoch, sid = sessionId, staled = false; // 捕获发起时纪元与会话: 新会话后旧回调据此丢弃
    function dropStale() { if (!staled) { staled = true; interruptSession(sid); } } // 止损只发一次中断
    setSending(true); // 运行中: 按钮变"⏹ 停止", 回车改走 steer 插话
    var th = thinking(); flowEl.appendChild(th); scrollBottom(true); // 用户刚发送, 强制滚到底显示思考中
    var s0 = document.getElementById('aiThinkSub'); if (s0) s0.textContent = '· 正在理解你的问题…';
    var stopped = false;
    var t0 = Date.now();
    // SSE(特性C): 实时步骤 + 终答打字机; 打开成功则停轮询, 不支持/失败回退轮询(兜底铁律)
    var sseOk = false, es = null, streamEl = null, streamBuf = '';
    function closeSse() { try { if (es) es.close(); } catch (e) {} es = null; if (streamEl && streamEl.parentNode) streamEl.parentNode.removeChild(streamEl); streamEl = null; }
    function streamClean() { return streamBuf.replace(/<think>[\s\S]*?<\/think>/g, '').replace(/<tool_call>[\s\S]*?<\/tool_call>/g, '').replace(/<\/?(think|tool_call)>/g, '').trim(); }
    try {
      if (window.EventSource) {
        es = new EventSource('/api/ai/agent/stream/' + sid);
        es.addEventListener('open', function () { sseOk = true; });
        es.addEventListener('step', function (ev) {
          sseOk = true; var d = {}; try { d = JSON.parse(ev.data); } catch (e) {}
          if (d.skill) { if (streamEl && streamEl.parentNode) streamEl.parentNode.removeChild(streamEl); streamEl = null; streamBuf = ''; } // 工具步: 清 token 预览(该轮非终答)
          var sub = document.getElementById('aiThinkSub');
          if (sub) { var el = Math.round((Date.now() - t0) / 1000); sub.textContent = d.skill ? ('· 正在' + toolLabel(d.skill) + '（已' + el + 's）') : ('· 思考中…（已' + el + 's）'); }
        });
        es.addEventListener('running', function (ev) {
          sseOk = true; var d = {}; try { d = JSON.parse(ev.data); } catch (e) {}
          var sub = document.getElementById('aiThinkSub'); if (sub && d.skill) sub.textContent = '· 正在' + toolLabel(d.skill) + '…';
        });
        es.addEventListener('token', function (ev) {
          sseOk = true; var d = {}; try { d = JSON.parse(ev.data); } catch (e) {}
          if (!d.t) return;
          streamBuf += d.t;
          var clean = streamClean();
          if (!clean) return; // 仅 think/tool_call 内容: 不展示
          if (!streamEl) {
            streamEl = DN.h('div', { style: 'display:flex;justify-content:flex-start;margin:6px 0;' },
              [DN.h('div', { style: 'max-width:80%;background:var(--bg-card);border:1px solid var(--border);padding:8px 13px;border-radius:var(--radius-lg);font-size:13.5px;color:var(--text-regular);white-space:pre-wrap;word-break:break-word;' })]);
            flowEl.insertBefore(streamEl, th);
          }
          streamEl.firstChild.textContent = clean;
          scrollBottom();
        });
        es.onerror = function () { /* 回退轮询: 不置 sseOk, poll 继续 */ };
      }
    } catch (e) {}
    function poll() {
      if (stopped || sseOk) return;
      if (ep !== _epoch) { stopped = true; dropStale(); return; } // 已开新会话: 旧轮询立即终止
      DN.get('/api/ai/agent/session/' + sid).then(function (d) {
        if (stopped || ep !== _epoch) return;
        var sub = document.getElementById('aiThinkSub');
        if (!sub) return;
        var steps = (d && d.steps) || [];
        // 已完成工具步 + 当前正在跑的工具(RUNNING 标记, 后端调用前埋点); 取最近一个动作展示
        var done = steps.filter(function (s) { return s && s.stepType === 'SKILL_CALL' && s.skillName; });
        var running = null;
        for (var i = steps.length - 1; i >= 0; i--) { if (steps[i] && steps[i].stepType === 'RUNNING' && steps[i].skillName) { running = steps[i]; break; } }
        var el = Math.round((Date.now() - t0) / 1000);
        if (running) sub.textContent = '· 正在' + toolLabel(running.skillName) + '（第' + (done.length + 1) + '步 · 已' + el + 's）';
        else if (done.length) sub.textContent = '· 正在' + toolLabel(done[done.length - 1].skillName) + '（第' + done.length + '步 · 已' + el + 's）';
        else sub.textContent = '· 正在理解你的问题…（已' + el + 's）'; // 始终带计时, 让用户知道仍在运行不是卡死
      }).catch(function () {}).then(function () { if (!stopped && !sseOk) setTimeout(poll, 1500); });
    }
    setTimeout(poll, 1200);
    return DN.post(url, body).then(function (res) {
      stopped = true; closeSse(); if (th.parentNode) th.parentNode.removeChild(th);
      if (ep !== _epoch) { dropStale(); return res; } // 旧会话响应: 丢弃渲染, 不写 saveSid, 不碰锁
      setSending(false);
      renderTurn(res || {}, Math.round((Date.now() - t0) / 1000)); loadFiles(); scrollBottom();
      return res;
    }).catch(function (e) {
      stopped = true; closeSse(); if (th.parentNode) th.parentNode.removeChild(th);
      if (ep !== _epoch) { dropStale(); throw e; } // 旧会话失败: 同样静默丢弃
      setSending(false);
      flowEl.appendChild(assistantBubble('请求失败：' + (e && e.message ? e.message : e), 'err')); scrollBottom();
      throw e;
    });
  }

  // 终答前的决策推理气泡(从 FINAL 步 thinkContent 渲染; 过程透明, 与结论分离)
  function thinkBubble(text) {
    return DN.h('div', { style: 'display:flex;justify-content:flex-start;margin:6px 0;' },
      [DN.h('div', { style: 'max-width:80%;background:var(--bg-body);border:1px dashed var(--border);padding:7px 12px;border-radius:var(--radius-full);font-size:12px;font-style:italic;color:var(--text-muted);white-space:pre-wrap;word-break:break-word;', text: '💭 ' + text })]);
  }

  // 任务计划清单卡(自主规划透明化, 天工开物·逐道工序): 渲染 plan_json 的有序步骤+状态
  function planCard(planJson) {
    var steps = null;
    try { var o = (typeof planJson === 'string') ? JSON.parse(planJson) : planJson; steps = Array.isArray(o) ? o : (o && o.steps); } catch (e) { return null; }
    if (!steps || !steps.length) return null;
    var done = steps.filter(function (s) { return s && s.status === 'done'; }).length;
    var card = DN.h('div', { style: 'margin:8px 0 8px 18px;border:1px solid var(--border);border-left:3px solid var(--primary);border-radius:var(--radius-lg);padding:10px 14px;background:var(--bg-card);' });
    card.appendChild(DN.h('div', { style: 'font-weight:600;font-size:12.5px;color:var(--text-primary);margin-bottom:6px;display:flex;align-items:center;gap:8px;' }, [
      DN.h('span', { text: '🧭 任务计划' }),
      DN.h('span', { text: done + '/' + steps.length, style: 'font-size:var(--fs-xs);color:var(--text-inverse);background:var(--primary);padding:1px 8px;border-radius:var(--radius-full);' })
    ]));
    steps.forEach(function (s, i) {
      var status = s.status || 'pending';
      var mark = status === 'done' ? '✓' : (status === 'doing' ? '▶' : '○');
      var col = status === 'done' ? 'var(--success)' : (status === 'doing' ? 'var(--primary)' : 'var(--text-muted)');
      var deco = status === 'done' ? 'text-decoration:line-through;opacity:.7;' : '';
      card.appendChild(DN.h('div', { style: 'font-size:12.5px;color:var(--text-regular);line-height:1.9;' + deco }, [
        DN.h('span', { text: mark + ' ', style: 'color:' + col + ';font-weight:700;' }),
        DN.h('span', { text: (i + 1) + '. ' + (s.step || '') })
      ]));
    });
    return card;
  }

  // ===== ask_user 决策/协助卡片(人在环交互, 复刻 AskUserQuestion 卡片提示框) =====
  function askingPreview(questions) {
    var wrap = DN.h('div', { style: 'margin:6px 0 2px 0;font-size:13px;color:var(--text-muted);' });
    wrap.appendChild(DN.h('div', { text: '需要你确认：', style: 'font-weight:600;color:var(--text-regular);margin-bottom:2px;' }));
    questions.forEach(function (q) { wrap.appendChild(DN.h('div', { text: '· ' + (q.question || ''), style: 'line-height:1.7;' })); });
    return wrap;
  }

  function questionCard(res) {
    var questions = (res.questions || []).slice(0, 4);
    var sid = res.sessionId;
    var picks = questions.map(function () { return { set: {}, custom: '' }; }); // 每问的选择态
    var idx = 0;
    var card = DN.h('div', { style: 'margin:8px 0 8px 0;border:1px solid var(--border);border-radius:var(--radius-xl,14px);padding:14px 16px;background:var(--bg-card);box-shadow:var(--shadow-sm);' });

    // ✕ 不销毁卡片: 折叠并在输入条上方挂常驻找回条, 点击恢复(防误关后问题无法回答、会话卡死)
    function collapseCard() {
      card.style.display = 'none';
      var old = document.getElementById('aiPendingAsk'); if (old) old.remove();
      var bar = DN.h('div', { id: 'aiPendingAsk', text: '⏸ 有问题待回答（点击展开）',
        style: 'flex:0 0 auto;margin:0 16px 6px;padding:7px 14px;border:1px solid var(--border);border-radius:var(--radius-lg);background:var(--bg-card);color:var(--warning-text);font-size:12.5px;cursor:pointer;box-shadow:var(--shadow-sm);' });
      bar.onclick = function () { bar.remove(); card.style.display = ''; scrollBottom(); };
      if (inputBarEl && inputBarEl.parentNode) inputBarEl.parentNode.insertBefore(bar, inputBarEl);
    }

    function answerOf(i) {
      var q = questions[i], p = picks[i];
      var labels = (q.options || []).filter(function (o) { return p.set[o.label]; }).map(function (o) { return o.label; });
      if (p.custom && p.custom.trim()) labels.push(p.custom.trim());
      return labels.join('、');
    }
    function hasPick(i) { var p = picks[i]; return Object.keys(p.set).length > 0 || (p.custom && p.custom.trim()); }

    function finish(skipAll) {
      if (sending) return; // 共用全局发送锁: 与 send/resume 互斥, 防重复提交
      var answers = questions.map(function (q, i) {
        return { header: q.header || '', question: q.question || '', answer: skipAll ? '(跳过)' : (hasPick(i) ? answerOf(i) : '(跳过)') };
      });
      card.remove(); // 移除卡片, 由 runRequest 显示运行动画+进度, 答后渲染结果(修复提交后无反馈)
      var pb = document.getElementById('aiPendingAsk'); if (pb) pb.remove(); // 找回条随提交一并清掉
      runRequest('/api/ai/agent/' + sid + '/answer', { answers: answers }).catch(function () {});
    }

    function render() {
      card.innerHTML = '';
      var q = questions[idx];
      var multi = !!q.multiSelect;
      // 头部: N/M 徽标 + 问题 + 关闭
      var hd = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;margin-bottom:12px;' });
      hd.appendChild(DN.h('span', { text: (idx + 1) + '/' + questions.length, class: 'dn-ai-badge warn', style: 'flex:0 0 auto;' }));
      hd.appendChild(DN.h('span', { text: q.question || '', style: 'flex:1;font-weight:700;font-size:14.5px;color:var(--text-primary);' }));
      var x = DN.h('span', { text: '✕', style: 'flex:0 0 auto;cursor:pointer;color:var(--text-muted);font-size:14px;padding:2px 6px;' });
      x.onclick = collapseCard; // 改为折叠+找回条, 不再直接销毁
      hd.appendChild(x);
      card.appendChild(hd);

      // 选项行
      (q.options || []).forEach(function (o, oi) {
        var selected = !!picks[idx].set[o.label];
        var row = DN.h('div', { class: 'dn-ai-opt' + (selected ? ' selected' : ''), tabindex: '0', role: 'button' });
        var txt = DN.h('div', { style: 'flex:1;min-width:0;' }, [
          DN.h('div', { text: o.label, style: 'font-size:14px;color:var(--text-primary);font-weight:500;' })
        ]);
        if (o.desc) txt.appendChild(DN.h('div', { text: o.desc, style: 'font-size:12px;color:var(--text-muted);margin-top:2px;' }));
        row.appendChild(txt);
        // 右侧: 单选=序号键, 多选=复选框
        if (multi) {
          row.appendChild(DN.h('span', { text: selected ? '☑' : '☐', style: 'flex:0 0 auto;font-size:16px;color:' + (selected ? 'var(--primary)' : 'var(--text-muted)') + ';' }));
        } else {
          row.appendChild(DN.h('span', { text: String(oi + 1), style: 'flex:0 0 auto;font-size:12px;color:var(--text-muted);background:var(--bg-card);border:1px solid var(--border);border-radius:var(--radius-xs);padding:1px 8px;' }));
        }
        function choose() {
          if (multi) { if (picks[idx].set[o.label]) delete picks[idx].set[o.label]; else picks[idx].set[o.label] = true; }
          else { picks[idx].set = {}; picks[idx].set[o.label] = true; }
          render();
        }
        row.onclick = choose;
        row.onkeydown = function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(); } }; // 键盘可达
        row.style.cursor = 'pointer';
        card.appendChild(row);
      });

      // Other 自填
      var otherWrap = DN.h('div', { style: 'background:var(--bg-body);border-radius:var(--radius,8px);padding:10px 14px;margin-bottom:4px;' });
      otherWrap.appendChild(DN.h('div', { text: 'Other', style: 'font-size:14px;color:var(--text-primary);font-weight:500;margin-bottom:6px;' }));
      var inp = DN.h('input', { type: 'text', placeholder: '在此填写你自己的答案…', value: picks[idx].custom || '',
        style: 'width:100%;box-sizing:border-box;height:36px;padding:0 12px;border:1px solid var(--border);border-radius:var(--radius,8px);background:var(--bg-card);font-size:13px;color:var(--text-primary);outline:none;' });
      inp.oninput = function () { picks[idx].custom = inp.value; };
      inp.onfocus = function () { inp.style.boxShadow = '0 0 0 2px var(--ring)'; inp.style.borderColor = 'var(--primary)'; };
      inp.onblur = function () { inp.style.boxShadow = 'none'; inp.style.borderColor = 'var(--border)'; };
      otherWrap.appendChild(inp);
      card.appendChild(otherWrap);

      // 底栏: Back / Skip / Next|Submit
      var foot = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;margin-top:12px;' });
      if (idx > 0) {
        var back = DN.h('button', { class: 'btn btn-sm', text: '上一题' });
        back.onclick = function () { idx--; render(); };
        foot.appendChild(back);
      }
      foot.appendChild(DN.h('div', { style: 'flex:1;' }));
      var skip = DN.h('button', { class: 'btn btn-sm', text: '跳过' });
      skip.onclick = function () { if (idx < questions.length - 1) { idx++; render(); } else { finish(false); } };
      foot.appendChild(skip);
      var isLast = idx === questions.length - 1;
      var next = DN.h('button', { class: 'btn btn-primary btn-sm', text: isLast ? '提交' : '下一题', 'data-perm': 'assistant:use',
        style: 'background:var(--primary);color:var(--text-inverse);border-color:var(--primary);' });
      next.onclick = function () { if (isLast) finish(false); else { idx++; render(); } };
      foot.appendChild(next);
      card.appendChild(foot);
      scrollBottom();
    }

    render();
    return card;
  }

  // 模型热切档位选择(空=默认; 同 provider 下切 model 档位, 随 chat 上送 model 覆盖); 选择持久化到 localStorage, 刷新不丢
  function modelPicker() {
    try { var saved = localStorage.getItem('aiModel'); if (saved != null) selectedModel = saved; } catch (e) {}
    var sel = DN.h('select', { title: '模型档位(快速省时/高质量更强)', style: 'margin-left:auto;flex:0 0 auto;height:30px;border:1px solid var(--border);border-radius:var(--radius,6px);background:var(--bg-card);color:var(--text-regular);font-size:12px;padding:0 6px;cursor:pointer;' });
    [['', '默认模型'], ['deepseek-v4-flash', '⚡ 快速'], ['deepseek-v4-pro', '🎯 高质量']].forEach(function (o) {
      var op = document.createElement('option'); op.value = o[0]; op.textContent = o[1]; if (o[0] === selectedModel) op.selected = true; sel.appendChild(op);
    });
    sel.onchange = function () { selectedModel = sel.value; try { localStorage.setItem('aiModel', selectedModel); } catch (e) {} };
    return sel;
  }

  function scrollBottom(force) { if (!flowEl) return; if (force || flowEl.scrollHeight - flowEl.scrollTop - flowEl.clientHeight < 140) flowEl.scrollTop = flowEl.scrollHeight; } // 默认仅近底才滚, 不打断用户上翻阅读; force=用户主动操作强制滚

  // 输入框 @ 补全已上传文件名: 输入 @ + 关键词 → 弹文件名候选, 点选插入(agent 据 filesText 解析 fileId)
  function setupMention() {
    if (!inputEl || !inputBarEl) return;
    function closeDD() { if (_mentionDD) { _mentionDD.remove(); _mentionDD = null; } }
    inputEl.addEventListener('input', function () {
      var val = inputEl.value, pos = inputEl.selectionStart;
      var m = val.slice(0, pos).match(/@([^\s@]*)$/);
      if (!m || !_userFiles.length) { closeDD(); return; }
      var q = m[1].toLowerCase();
      var matches = _userFiles.filter(function (n) { return n.toLowerCase().indexOf(q) >= 0; }).slice(0, 8);
      closeDD();
      if (!matches.length) return;
      var dd = DN.h('div', { style: 'position:absolute;bottom:100%;left:0;margin-bottom:4px;background:var(--bg-card);border:1px solid var(--border);border-radius:var(--radius-md);box-shadow:0 4px 16px rgba(0,0,0,.15);max-height:220px;overflow:auto;z-index:20;min-width:220px;max-width:340px;' });
      function insert(n) {
        var start = pos - m[0].length;
        inputEl.value = val.slice(0, start) + '@' + n + ' ' + val.slice(pos);
        closeDD(); inputEl.focus();
        var np = start + n.length + 2; try { inputEl.setSelectionRange(np, np); } catch (e2) {}
      }
      dd._first = function () { insert(matches[0]); }; // 回车选首项
      matches.forEach(function (n) {
        var it = DN.h('div', { text: '📎 ' + n, title: n, style: 'padding:6px 10px;cursor:pointer;font-size:12.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;' });
        it.onmouseenter = function () { it.style.background = 'var(--bg-hover, rgba(0,0,0,.05))'; };
        it.onmouseleave = function () { it.style.background = ''; };
        it.onmousedown = function (e) { e.preventDefault(); insert(n); }; // mousedown 先于 blur
        dd.appendChild(it);
      });
      inputBarEl.style.position = inputBarEl.style.position || 'relative';
      inputBarEl.appendChild(dd);
      _mentionDD = dd;
    });
    inputEl.addEventListener('blur', function () { setTimeout(closeDD, 160); });
    inputEl.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeDD(); });
  }

  // 标签页隐藏时任务完成 → 标题闪提示(用户切走也能感知完成); 回到页面自动还原
  var _origTitle = null;
  function flashTitle() { try { if (document.hidden) { if (_origTitle == null) _origTitle = document.title; document.title = '✅ 天工司辰已完成'; } } catch (e) {} }

  // 导出当前会话为 Markdown(用户/助手轮 + 工具步留痕)
  function exportChat() {
    if (!sessionId) { DN.toast('当前无会话', 'warn'); return; }
    DN.get('/api/ai/agent/session/' + sessionId).then(function (d) {
      var steps = (d && d.steps) || [];
      if (!steps.length) { DN.toast('无内容可导出', 'warn'); return; }
      var md = ['# 天工司辰 对话记录', ''];
      steps.forEach(function (s) {
        if (!s) return;
        if (s.stepType === 'USER') md.push('', '## 🧑 我', '', s.content || '');
        else if (s.stepType === 'FINAL') md.push('', '## 🤖 天工司辰', '', s.content || '');
        else if (s.stepType === 'ASK_USER') md.push('', '## 🤖 天工司辰（询问）', '', s.content || '');
        else if (s.stepType === 'SKILL_CALL' && s.skillName) md.push('', '> 🔧 调用 ' + s.skillName);
      });
      var blob = new Blob([md.join('\n')], { type: 'text/markdown;charset=utf-8' });
      var a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = '对话记录.md'; a.click();
      setTimeout(function () { URL.revokeObjectURL(a.href); }, 2000);
    }).catch(function (e) { DN.toast('导出失败：' + (e && e.message ? e.message : e), 'err'); });
  }

  function genSid() {
    try { if (window.crypto && crypto.randomUUID) return crypto.randomUUID().replace(/-/g, ''); } catch (e) {}
    var s = ''; for (var i = 0; i < 32; i++) s += Math.floor(Math.random() * 16).toString(16); return s;
  }
  function saveSid(id) { try { if (id) localStorage.setItem('aiSessionId', id); } catch (e) {} }

  // 刷新后恢复历史对话: 拉上次会话所有步骤, 按"用户气泡→执行过程折叠→终答气泡"重建(只读, 不重跑)
  function restoreHistory() {
    var id; try { id = localStorage.getItem('aiSessionId'); } catch (e) { id = null; }
    if (!id) return;
    sessionId = id;
    renderHistory(id);
  }

  // 点击左侧历史会话: 切到该会话(仅本人, 后端 /session/{id} 带归属校验), 重建对话并可继续
  function openSession(id) {
    if (!id || id === sessionId) return;
    _epoch++;                 // 纪元自增: 旧会话在途回调失效
    setSending(false);
    _autoWatch = null; removeAutoBanner(); closePreview(); // 停旧会话自主监视+关预览(切到的新会话若在自主中, renderHistory 会重启)
    var pb = document.getElementById('aiPendingAsk'); if (pb) pb.remove();
    sessionId = id; saveSid(id);
    if (flowEl) flowEl.innerHTML = '';
    renderHistory(id);
    loadSessions();           // 刷新列表高亮
  }

  // 按 sessionId 拉取并重建一段会话(restoreHistory / openSession 共用)
  function renderHistory(id) {
    var ep = _epoch; // 捕获发起纪元: 恢复期间若已开新会话, 放弃重建旧历史
    DN.get('/api/ai/agent/session/' + id).then(function (d) {
      if (ep !== _epoch) return;
      var steps = (d && d.steps) || [];
      if (!steps.length) return;
      if (flowEl.firstChild) flowEl.innerHTML = ''; // 移除欢迎语
      var sess = (d && d.session) || {};
      var pend = [], lastAsk = null;
      function flush() { if (pend.length) { flowEl.appendChild(processToggle(pend.slice(), null)); pend = []; } }
      steps.forEach(function (s) {
        if (!s) return;
        if (s.stepType === 'USER') { flush(); if (s.content) flowEl.appendChild(userBubble(s.content)); }
        else if (s.stepType === 'SKILL_CALL' && s.skillName) { pend.push(s); }
        else if (s.stepType === 'FINAL') { var pv = pend.slice(); flush(); renderPreviews(pv); flowEl.appendChild(assistantBubble(s.content || '（无答复）')); }
        else if (s.stepType === 'ASK_USER') { flush(); if (s.content) flowEl.appendChild(assistantBubble(s.content)); lastAsk = s; }
      });
      flush();
      // 待用户输入: 重建决策/协助卡片(修复刷新后卡片消失无法继续); 从最后一条 ASK_USER 的 argsJson 取问题
      if (sess.status === 'wait_input' && lastAsk) {
        var qs = null;
        try { var a = JSON.parse(lastAsk.argsJson || '{}'); qs = a.questions; } catch (e) {}
        if (qs && qs.length) {
          flowEl.appendChild(askingPreview(qs));
          flowEl.appendChild(questionCard({ sessionId: id, questions: qs }));
        }
      }
      // 待人工审批: 重建审批卡(修复刷新后批准/拒绝卡消失致写操作审批闭环断开)
      else if (sess.status === 'wait_approval') {
        loadApproval(id);
      }
      // 刷新/切回时若【当前活动会话】仍在无人值守自主执行 → 恢复监视轮询(限活动会话+幂等, 防非活动会话误起轮询)
      if (id === sessionId && Number(sess.autonomous) === 1 && (sess.status === 'running' || sess.status === 'wait_approval')) {
        watchAutonomous(id);
      }
      scrollBottom();
    }).catch(function () {});
  }

  // 开新会话: 清历史, 重置 flow 与 sessionId(下一次发送生成新 id)
  function newSession() {
    _epoch++;          // 纪元自增: 在途旧回调(轮询/发送响应/审批结果)全部失效, 由各回调自行丢弃并止损
    sessionId = null;
    setSending(false); // 释放发送锁, 按钮复原为"发送"
    var pb = document.getElementById('aiPendingAsk'); if (pb) pb.remove(); // 旧会话的待回答找回条一并清掉
    try { localStorage.removeItem('aiSessionId'); } catch (e) {}
    if (flowEl) { flowEl.innerHTML = ''; flowEl.appendChild(welcome()); }
    _autoWatch = null; removeAutoBanner(); // 停掉旧会话的自主监视
    closePreview(); // 关右侧网页预览
    _sessionFilter = ''; var _hs = document.getElementById('aiHistSearch'); if (_hs) _hs.value = ''; // 重置历史搜索
    loadSessions(); // 刷新列表(清除高亮)
  }

  // 拉取并渲染左侧历史会话列表(仅本人; 后端 /sessions 严格按 user_name 过滤)
  function loadSessions() {
    if (!histListEl) return;
    DN.get('/api/ai/agent/sessions?limit=50').then(function (list) {
      _sessionList = list || [];
      renderSessionRows();
    }).catch(function () {});
  }
  function renderSessionRows() {
    if (!histListEl) return;
    histListEl.innerHTML = '';
    var q = (_sessionFilter || '').trim().toLowerCase();
    var arr = q ? _sessionList.filter(function (s) { return s && (s.title || '').toLowerCase().indexOf(q) >= 0; }) : _sessionList;
    if (!arr.length) {
      histListEl.appendChild(DN.h('div', { text: q ? '无匹配会话' : '暂无历史会话', style: 'color:var(--text-muted);font-size:12px;padding:6px 2px;' }));
      return;
    }
    if (q) histListEl.appendChild(DN.h('div', { text: '找到 ' + arr.length + ' / ' + _sessionList.length + ' 条', style: 'color:var(--text-muted);font-size:11px;padding:2px 2px 4px;' })); // 搜索命中计数
    arr.forEach(function (s) {
        if (!s || !s.sessionId) return;
        var active = s.sessionId === sessionId;
        var title = (s.title && String(s.title).trim()) ? String(s.title).trim() : '未命名会话';
        if (title.length > 28) title = title.slice(0, 28) + '…';
        var row = DN.h('div', {
          title: (s.title || '') + '  ·  ' + (s.status || ''),
          style: 'cursor:pointer;border-radius:var(--radius-md);padding:6px 8px;margin-bottom:3px;font-size:12.5px;line-height:1.5;display:flex;align-items:center;gap:6px;'
            + (active ? 'background:var(--primary-bg, rgba(64,128,255,.12));color:var(--primary);font-weight:600;' : 'color:var(--text-regular);')
        }, [
          DN.h('div', { style: 'flex:1;min-width:0;' }, [
            DN.h('div', { text: (Number(s.autonomous) === 1 ? '🚀 ' : '') + title, style: 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' }),
            DN.h('div', { style: 'font-size:11px;color:var(--text-muted);margin-top:1px;display:flex;align-items:center;gap:4px;' }, [
              DN.h('span', { style: 'flex:0 0 auto;width:6px;height:6px;border-radius:50%;background:' + ({ running: '#22c55e', wait_approval: '#f59e0b', wait_input: '#f59e0b', blocked: '#ef4444', cancelled: '#9ca3af' }[s.status] || '#9ca3af') + ';' }),
              DN.h('span', { text: statusLabel(s.status) + ' · ' + fmtTime(s.updatedAt) })
            ])
          ])
        ]);
        var ren = DN.h('span', { text: '✎', title: '重命名', style: 'flex:0 0 auto;color:var(--text-muted);font-size:12px;padding:0 2px;visibility:hidden;' });
        ren.onclick = function (e) {
          e.stopPropagation();
          var t = window.prompt('重命名会话', s.title || title);
          if (t == null || !t.trim()) return;
          DN.post('/api/ai/agent/' + s.sessionId + '/rename', { title: t.trim() }).then(function () { loadSessions(); }).catch(function (er) { DN.toast('重命名失败：' + (er && er.message ? er.message : er), 'err'); });
        };
        var del = DN.h('span', { text: '✕', title: '删除该会话', style: 'flex:0 0 auto;color:var(--text-muted);font-size:12px;padding:0 2px;visibility:hidden;' });
        del.onclick = function (e) {
          e.stopPropagation();
          DN.post('/api/ai/agent/' + s.sessionId + '/delete', {}).then(function () {
            if (s.sessionId === sessionId) newSession(); // 删的是当前会话 → 清空
            loadSessions();
          }).catch(function (er) { DN.toast('删除失败：' + (er && er.message ? er.message : er), 'err'); });
        };
        row.appendChild(ren); row.appendChild(del);
        row.onmouseenter = function () { if (!active) row.style.background = 'var(--bg-hover, rgba(0,0,0,.04))'; ren.style.visibility = 'visible'; del.style.visibility = 'visible'; };
        row.onmouseleave = function () { if (!active) row.style.background = ''; ren.style.visibility = 'hidden'; del.style.visibility = 'hidden'; };
        row.onclick = function () { openSession(s.sessionId); };
        histListEl.appendChild(row);
      });
  }

  function statusLabel(st) {
    return ({ done: '完成', running: '运行中', wait_approval: '待审批', wait_input: '待回答', blocked: '中止', cancelled: '已取消', paused: '已暂停' })[st] || (st || '');
  }
  function fmtTime(t) {
    if (!t) return '';
    var s = String(t).replace('T', ' ');
    var d = new Date(s.replace(/-/g, '/')); // -→/ 兼容 Safari 解析
    if (!isNaN(d.getTime())) {
      var diff = (Date.now() - d.getTime()) / 1000;
      if (diff >= 0 && diff < 60) return '刚刚';
      if (diff < 3600) return Math.floor(diff / 60) + '分钟前';
      if (diff < 86400) return Math.floor(diff / 3600) + '小时前';
      if (diff < 172800) return '昨天';
    }
    return s.length >= 16 ? s.slice(5, 16) : s; // 更早: MM-DD HH:mm
  }

  // 无人值守自主执行: 后台监视轮询(进度横幅 + 步数变化时重建对话 + 高危写挂起时弹审批 + 终态收尾)
  var _autoWatch = null;
  function autoBanner() {
    var msg = document.getElementById('aiAutoMsg');
    if (!msg && inputBarEl && inputBarEl.parentNode) {
      var b = DN.h('div', { id: 'aiAutoBanner', style: 'display:flex;align-items:center;gap:8px;margin:0 0 6px;padding:7px 12px;border-radius:var(--radius-md);background:var(--primary-bg, rgba(64,128,255,.12));color:var(--primary);font-size:12.5px;font-weight:600;' });
      msg = DN.h('span', { id: 'aiAutoMsg', style: 'flex:1;' });
      var stop = DN.h('button', { class: 'btn btn-sm', text: '⏹ 停止', title: '停止无人值守自主执行', style: 'flex:0 0 auto;' });
      stop.onclick = function () { var sid = _autoWatch; if (sid) DN.post('/api/ai/agent/' + sid + '/interrupt', {}).then(function () { DN.toast('已请求停止，本轮结束后停下', 'ok'); }).catch(function () {}); _autoWatch = null; removeAutoBanner(); };
      b.appendChild(msg); b.appendChild(stop);
      inputBarEl.parentNode.insertBefore(b, inputBarEl);
    }
    return msg;
  }
  function removeAutoBanner() { var b = document.getElementById('aiAutoBanner'); if (b) b.remove(); }
  function watchAutonomous(id) {
    if (!id || _autoWatch === id) return; // 幂等: 已在监视该会话则不重复启动(防 renderHistory/autoBtn 多入口重复起轮询)
    _autoWatch = id;
    var ep = _epoch, lastLen = -1, fails = 0;
    (function poll() {
      if (ep !== _epoch || _autoWatch !== id) { removeAutoBanner(); return; } // 切会话/开新会话 → 停止监视
      DN.get('/api/ai/agent/session/' + id).then(function (d) {
        if (ep !== _epoch || _autoWatch !== id) { removeAutoBanner(); return; }
        fails = 0; // 成功一次清零
        var sess = (d && d.session) || {}, steps = (d && d.steps) || [];
        if (steps.length !== lastLen) { lastLen = steps.length; renderHistory(id); } // 仅步数变化才重建, 防闪烁
        var st = sess.status, auton = Number(sess.autonomous) === 1;
        var used = sess.budgetStepsUsed || 0, mx = sess.autoMaxSteps || 0;
        var b = autoBanner();
        if (st === 'wait_approval') {
          if (b) b.textContent = '⏸ 高危写操作待你批准（批准后自动继续无人值守）';
          loadApproval(id); setTimeout(poll, 4000); return;
        }
        if (st === 'running' || auton) {
          if (b) b.textContent = '🚀 无人值守自主执行中… 已 ' + used + (mx ? ' / ' + mx : '') + ' 步（可点⏹停止）';
          setTimeout(poll, 4000); return;
        }
        // 终态: 收尾
        removeAutoBanner(); _autoWatch = null;
        DN.toast(st === 'done' ? '✅ 自主任务已完成，成品见上方' : ('自主任务结束：' + statusLabel(st)), st === 'done' ? 'ok' : 'warn');
        flashTitle(); // 标签页隐藏时提示自主任务完成
        loadSessions();
      }).catch(function () { if (ep === _epoch && _autoWatch === id) { if (++fails >= 6) { removeAutoBanner(); _autoWatch = null; return; } setTimeout(poll, 5000); } }); // 连续失败(会话被删/服务异常)≥6次停止, 防空转
    })();
  }

  // 统一发送锁: send / 决策卡answer / 审批resume 三入口共用; 运行中按钮变"⏹ 停止"(保持可点), 输入框可用走插话
  function setSending(on) {
    sending = !!on;
    if (inputEl) inputEl.placeholder = sending
      ? '运行中…回车可补充指引(中途转向)，或点 ⏹ 停止'
      : '问我：看下治理总览；某表质量为什么下降… (输入 @ 可引用已上传文件)';
    if (!sendBtn) return;
    sendBtn.textContent = sending ? '⏹ 停止' : '发送';
    sendBtn.disabled = sending ? false : !(inputEl && (inputEl.value || '').trim()); // 非运行态: 输入为空则禁用发送(即时反馈)
    sendBtn.style.opacity = sendBtn.disabled ? '.55' : '';
  }

  // 发送按钮统一入口: 空闲=发送, 运行中=请求停止(协作式中断, agent 在下一工序边界停下)
  function onSendClick() { if (sending) doInterrupt(); else send(); }

  function doInterrupt() {
    if (!sessionId) return;
    sendBtn.disabled = true; sendBtn.textContent = '停止中…'; // 防连点; 待运行结束由 setSending(false) 复原
    DN.post('/api/ai/agent/' + sessionId + '/interrupt', {})
      .then(function () { DN.toast('已请求停止, 将在当前步骤完成后停下', 'ok'); })
      .catch(function (e) { DN.toast('停止失败：' + (e && e.message ? e.message : e), 'err'); })
      .then(function () { if (sending) { sendBtn.disabled = false; sendBtn.textContent = '⏹ 停止'; } });
  }

  // 运行中回车: 不发新消息, 把输入作为插话引导 POST /steer(下一工序边界并入上下文)
  function steer() {
    if (!inputEl || !sessionId) return;
    var msg = (inputEl.value || '').trim();
    if (!msg) return;
    DN.post('/api/ai/agent/' + sessionId + '/steer', { text: msg })
      .then(function () { if (inputEl) { inputEl.value = ''; inputEl.style.height = ''; } DN.toast('已插话', 'ok'); })
      .catch(function (e) { DN.toast('插话失败：' + (e && e.message ? e.message : e), 'err'); });
  }

  function send() {
    if (sending || !inputEl) return; // 共用发送锁: 锁定期间不可重复提交
    var msg = (inputEl.value || '').trim();
    if (!msg) { DN.toast('请输入问题', 'warn'); return; }
    _lastSent = msg; // 记上一条供 ↑ 调出
    flowEl.appendChild(userBubble(msg));
    inputEl.value = ''; inputEl.style.height = '';   // 发送后高度复位(配合自动增高)
    if (_mentionDD) { _mentionDD.remove(); _mentionDD = null; } // 显式关 @候选(发送后聚焦不触发 blur, 防遗留)
    runRequest('/api/ai/agent/chat', { message: msg, ctx: pendingCtx, model: selectedModel || undefined })
      .then(function () {}).catch(function () {}).then(function () { if (inputEl) inputEl.focus(); });
  }

  // 渲染一轮结果(对话/恢复共用): 工具卡 + 终答; 若挂起待审批则出审批卡
  function renderTurn(res, elapsed) {
    sessionId = res.sessionId || sessionId;
    saveSid(sessionId);
    loadSessions(); // 新会话/状态变化后刷新左侧历史列表与高亮
    // 等待用户输入: 渲染决策/协助卡片(人在环交互), 优先, 不出普通终答气泡
    if (res.status === 'wait_input' && res.questions && res.questions.length) {
      flowEl.appendChild(askingPreview(res.questions));
      flowEl.appendChild(questionCard(res));
      return;
    }
    // 结果优先: 执行过程(工具步)默认折叠为一行, 只突出最终结果; 不默认铺思考过程/计划数据
    var toolSteps = (res.steps || []).filter(function (s) { return s && s.stepType === 'SKILL_CALL' && s.skillName; });
    if (toolSteps.length) flowEl.appendChild(processToggle(toolSteps, res.plan, elapsed));
    // 表数据预览常显(不折叠): 优先用未截断的 previews 通道(宽表完整); 回退到步骤结果解析
    var firstPage = null;
    if (res.previews && res.previews.length) {
      res.previews.forEach(function (d) {
        if (d && d._chart) flowEl.appendChild(chartCard(d._chart));
        else if (d && d._page) { flowEl.appendChild(pageCard(d._page)); if (!firstPage) firstPage = d._page; }
        else if (d && d._preview && d._preview.columns) flowEl.appendChild(dataGridCard({ pv: d._preview, db: d.db, table: d.table }));
      });
    } else {
      renderPreviews(res.steps);
    }
    if (firstPage) openPreview(firstPage.previewUrl, firstPage.title || firstPage.fileName); // 生成网页即自动右侧预览(Codex 式)
    var tone = res.status === 'blocked' ? 'err' : null;
    flowEl.appendChild(assistantBubble(res.finalAnswer || '（无答复）', tone));
    flashTitle(); // 标签页隐藏时提示完成
    if (res.status === 'wait_approval') loadApproval(res.sessionId);
  }

  function fmtDur(sec) {
    sec = Number(sec) || 0;
    if (sec < 60) return sec + 's';
    return Math.floor(sec / 60) + 'm ' + (sec % 60) + 's';
  }
  // 执行过程折叠(默认收起"已处理 Xs · 执行了N步"一行; 点开看工具卡+计划), 实现"最后只展示结果数据"
  function processToggle(steps, plan, elapsed) {
    var wrap = DN.h('div', { style: 'margin:4px 0 8px 0;' });
    var bar = DN.h('div', { class: 'dn-ai-fold', style: 'user-select:none;' });
    var names = []; steps.forEach(function (s) { var nm = s.skillName ? (TOOL_LABEL[s.skillName] || s.skillName) : null; if (nm && names.indexOf(nm) < 0) names.push(nm); }); // 用到的工具(去重)
    var tip = names.length ? '（' + names.slice(0, 4).join('、') + (names.length > 4 ? '…' : '') + '）' : '';
    var head = (elapsed != null ? '✅ 已处理 ' + fmtDur(elapsed) + ' · ' : '🔧 ') + '执行了 ' + steps.length + ' 步' + tip + ' · 点击查看过程';
    bar.appendChild(DN.h('span', { text: head }));
    var caret = DN.h('span', { text: '▾' });
    bar.appendChild(caret);
    var detail = DN.h('div', { style: 'display:none;margin-top:8px;' });
    var loaded = false;
    bar.onclick = function () {
      var open = detail.style.display === 'none';
      detail.style.display = open ? 'block' : 'none'; caret.textContent = open ? '▴' : '▾';
      if (open && !loaded) {
        if (plan) { var pc = planCard(plan); if (pc) detail.appendChild(pc); }
        steps.forEach(function (s) { detail.appendChild(toolCard(s)); });
        loaded = true; scrollBottom();
      }
    };
    wrap.appendChild(bar); wrap.appendChild(detail);
    return wrap;
  }

  // 写操作待审批: 拉本会话待审项, 出批准/拒绝卡; 批准→decide+resume, 拒绝→decide
  function loadApproval(sid) {
    DN.get('/api/ai/agent/approvals?status=pending').then(function (list) {
      list = list || [];
      var ap = null;
      for (var i = 0; i < list.length; i++) { if (list[i] && list[i].sessionId === sid) { ap = list[i]; break; } }
      if (!ap) return;
      var card = DN.h('div', { class: 'dn-ai-approval', style: 'margin-left:18px;' });
      card.appendChild(DN.h('div', { style: 'font-weight:600;color:var(--warning-text);margin-bottom:6px;', text: '⚠ 待审批: ' + (ap.actionSummary || ap.skillName || '') + '  (风险 ' + (ap.riskLevel || '') + ')' }));
      if (ap.riskLevel === 'HIGH') card.appendChild(DN.h('div', { text: '🔴 高危操作，可能不可逆，请仔细核对参数后再批准', style: 'font-size:12px;color:var(--danger,#e5484d);margin-bottom:6px;font-weight:600;' }));
      if (ap.argsJson) { var dt = DN.h('details', { style: 'margin-bottom:8px;' }, [DN.h('summary', { text: '查看参数', style: 'font-size:12px;color:var(--text-muted);cursor:pointer;' }), DN.h('div', { style: 'font-size:12px;color:var(--text-muted);word-break:break-all;margin-top:4px;', text: ap.argsJson })]); card.appendChild(dt); }
      var btns = DN.h('div', { style: 'display:flex;gap:8px;' });
      var okBtn = DN.h('button', { class: 'btn btn-primary btn-sm', text: '批准并继续', 'data-perm': 'assistant:approve', style: 'background:var(--primary);color:var(--text-inverse);border-color:var(--primary);' });
      var allBtn = DN.h('button', { class: 'btn btn-sm', text: '批准并自动批准后续', title: '本任务剩余写操作免逐个审批, 一路执行到底(仍受功能/数据权限拦截)', 'data-perm': 'assistant:approve' });
      var autoBtn = DN.h('button', { class: 'btn btn-sm', text: '🚀 自主执行', title: '无人值守: 批准并让 AI 按计划自主跑到交付(常规写自动, 高危写仍会挂起等批)', 'data-perm': 'assistant:approve', style: 'border-color:var(--primary);color:var(--primary);' });
      var noBtn = DN.h('button', { class: 'btn btn-sm', text: '拒绝', 'data-perm': 'assistant:approve' });
      function doApprove() {
        if (sending) { DN.toast('正在处理中，请稍候', 'warn'); return; } // 共用全局发送锁
        var ep = _epoch; setSending(true); // 捕获发起纪元: 新会话后丢弃旧审批结果
        okBtn.disabled = true; noBtn.disabled = true; okBtn.textContent = '执行中…';
        DN.post('/api/ai/agent/approval/' + ap.id + '/decide', { decision: 'approved' })
          .then(function () { return DN.post('/api/ai/agent/' + sid + '/resume', {}); })
          .then(function (res) {
            if (ep !== _epoch) { interruptSession(sid); return; } // 旧会话: 丢弃渲染并止损, 不碰锁
            setSending(false);
            card.remove(); renderTurn(res || {}); scrollBottom();
          })
          .catch(function (e) { if (ep === _epoch) setSending(false); DN.toast('审批/恢复失败：' + (e && e.message ? e.message : e), 'err'); okBtn.disabled = false; noBtn.disabled = false; okBtn.textContent = '批准并继续'; });
      }
      // 高危操作: 批准前二次确认(可能不可逆), 防误点
      okBtn.onclick = function () {
        if (okBtn.disabled) return;   // 防重复点击触发多个确认框/多次提交
        if (ap.riskLevel === 'HIGH') {
          okBtn.disabled = true;
          DN.confirm('高危操作「' + (ap.actionSummary || ap.skillName || '') + '」可能不可逆。已核对参数, 确认执行？', { title: '⚠ 确认高危操作', danger: true })
            .then(function (ok) { if (ok) { doApprove(); } else { okBtn.disabled = false; } });
        } else { okBtn.disabled = true; doApprove(); }
      };
      allBtn.onclick = function () {
        if (sending) { DN.toast('正在处理中，请稍候', 'warn'); return; } // 共用全局发送锁
        var ep = _epoch; setSending(true);
        okBtn.disabled = true; allBtn.disabled = true; noBtn.disabled = true; allBtn.textContent = '执行中…';
        // 一步到位: 后端批准本会话待审项 + 开 auto_approve + 续跑剩余所有步骤
        DN.post('/api/ai/agent/' + sid + '/approve-all', {})
          .then(function (res) {
            if (ep !== _epoch) { interruptSession(sid); return; }
            setSending(false);
            card.remove(); renderTurn(res || {}); scrollBottom();
          })
          .catch(function (e) { if (ep === _epoch) setSending(false); DN.toast('批量执行失败：' + (e && e.message ? e.message : e), 'err'); okBtn.disabled = false; allBtn.disabled = false; noBtn.disabled = false; allBtn.textContent = '批准并自动批准后续'; });
      };
      autoBtn.onclick = function () {
        if (sending) { DN.toast('正在处理中，请稍候', 'warn'); return; }
        var ep = _epoch; // 捕获纪元: 启动期间若切了会话则丢弃, 不在旧会话起监视
        okBtn.disabled = true; allBtn.disabled = true; autoBtn.disabled = true; noBtn.disabled = true; autoBtn.textContent = '启动中…';
        // 进入无人值守自主执行: 后端置 autonomous=1, 后台驱动器接管; 前端转为监视轮询
        DN.post('/api/ai/agent/' + sid + '/autonomous', {})
          .then(function (res) {
            if (ep !== _epoch) return; // 已切会话: 丢弃
            card.remove();
            flowEl.appendChild(assistantBubble((res && res.finalAnswer) ? res.finalAnswer : '已进入无人值守自主执行模式。', null));
            scrollBottom();
            watchAutonomous(sid);
          })
          .catch(function (e) { DN.toast('启动自主执行失败：' + (e && e.message ? e.message : e), 'err'); okBtn.disabled = allBtn.disabled = autoBtn.disabled = noBtn.disabled = false; autoBtn.textContent = '🚀 自主执行'; });
      };
      noBtn.onclick = function () {
        var ep = _epoch; // 捕获发起纪元: 新会话后不再往新消息流写旧拒绝提示
        okBtn.disabled = true; allBtn.disabled = true; autoBtn.disabled = true; noBtn.disabled = true;
        DN.post('/api/ai/agent/approval/' + ap.id + '/decide', { decision: 'rejected' })
          .then(function () { card.remove(); if (ep !== _epoch) return; flowEl.appendChild(assistantBubble('已拒绝该写操作。', 'err')); scrollBottom(); })
          .catch(function (e) { DN.toast('拒绝失败：' + (e && e.message ? e.message : e), 'err'); okBtn.disabled = false; allBtn.disabled = false; autoBtn.disabled = false; noBtn.disabled = false; });
      };
      btns.appendChild(okBtn); btns.appendChild(allBtn); btns.appendChild(autoBtn); btns.appendChild(noBtn); card.appendChild(btns);
      flowEl.appendChild(card); scrollBottom();
    }).catch(function () {});
  }

  // ===== 经验/审批 抽屉(全站统一: 改用 DN.drawer 工厂, 与 gov/mdm 抽屉同一套视觉/动画/Esc·遮罩关闭/焦点陷阱/closeAllDrawers 管理) =====
  function closeDrawer() { if (window.DN && DN.closeAllDrawers) DN.closeAllDrawers(); }
  function openDrawer(kind) {
    var title = kind === 'memory' ? '🧠 AI 自学习记忆' : (kind === 'cron' ? '⏰ 定时自治任务' : '🛡️ 待审批写操作');
    var body = DN.h('div', {});
    body.appendChild(DN.h('div', { text: '加载中…', style: 'color:var(--text-muted);font-size:13px;' }));
    DN.drawer(title, body);   // 统一抽屉外壳(.gov-drawer): 滑入/滑出动画 + Esc/遮罩点击/×关闭 + a11y
    if (kind === 'memory') renderMemories(body); else if (kind === 'cron') renderCrons(body); else renderApprovals(body);
  }
  function renderCrons(body) {
    DN.get('/api/ai/agent/crons').then(function (list) {
      body.innerHTML = '';
      var arr = list || [];
      if (!arr.length) { body.appendChild(DN.h('div', { text: '暂无定时任务。可对 AI 说“每天9点生成治理简报”等让它自动排程。', style: 'color:var(--text-muted);font-size:13px;line-height:1.8;' })); return; }
      body.appendChild(DN.h('div', { text: '共 ' + arr.length + ' 个定时任务', style: 'font-size:12px;color:var(--text-muted);margin-bottom:8px;' }));
      arr.forEach(function (j) {
        var on = Number(j.enabled) === 1;
        var card = DN.h('div', { style: 'border:1px solid var(--border);border-radius:var(--radius-lg);padding:10px 12px;margin-bottom:10px;background:var(--bg-body);' + (on ? '' : 'opacity:.62;') });
        card.appendChild(DN.h('div', { style: 'display:flex;align-items:center;gap:8px;margin-bottom:4px;' }, [
          DN.h('span', { text: '⏰ ' + (j.name || '(未命名)'), style: 'font-weight:600;font-size:13px;color:var(--text-primary);flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' }),
          DN.h('span', { text: on ? '启用' : '停用', class: 'dn-ai-badge' + (on ? ' ok' : ''), style: 'flex:0 0 auto;' })
        ]));
        card.appendChild(DN.h('div', { text: '计划: ' + (j.scheduleCron || ''), style: 'font-size:12px;color:var(--text-secondary);' }));
        card.appendChild(DN.h('div', { text: '下次 ' + (j.nextRun || '-') + '  ·  上次 ' + (j.lastStatus || '未运行') + ' (' + (j.runCount || 0) + '次)', style: 'font-size:var(--fs-xs);color:var(--text-muted);margin:3px 0 7px;' }));
        var btns = DN.h('div', { style: 'display:flex;gap:8px;' });
        var toggle = DN.h('button', { class: 'btn btn-sm', text: on ? '停用' : '启用', 'data-perm': 'assistant:use' });
        toggle.onclick = function () { toggle.disabled = true; DN.post('/api/ai/agent/cron/' + j.id + '/toggle', { enabled: on ? 0 : 1 }).then(function () { renderCrons(body); }).catch(function (e) { DN.toast('失败：' + (e && e.message ? e.message : e), 'err'); toggle.disabled = false; }); };
        var del = DN.h('button', { class: 'btn btn-sm', text: '删除', 'data-perm': 'assistant:use' });
        del.onclick = function () { DN.confirm('确认删除该定时任务？删除后不再自动执行。', { title: '删除定时任务', danger: true }).then(function (ok) { if (!ok) return; del.disabled = true; DN.post('/api/ai/agent/cron/' + j.id + '/remove', {}).then(function () { renderCrons(body); }).catch(function (e) { DN.toast('失败：' + (e && e.message ? e.message : e), 'err'); del.disabled = false; }); }); };
        btns.appendChild(toggle); btns.appendChild(del);
        card.appendChild(btns);
        body.appendChild(card);
      });
    }).catch(function (e) { body.innerHTML = ''; body.appendChild(DN.h('div', { text: '加载失败：' + (e && e.message ? e.message : e), style: 'color:var(--danger);font-size:13px;' })); });
  }
  // 自动生成的画像(长久记忆): 用户画像(隔离) + 项目画像(全局), 经验抽屉顶部展示
  function renderProfiles(box) {
    function sec(title) {
      var c = DN.h('div', { style: 'border:1px solid var(--border);border-radius:var(--radius-lg);padding:10px 12px;margin-bottom:10px;background:var(--primary-bg, rgba(99,102,241,.06));' });
      c.appendChild(DN.h('div', { text: title, style: 'font-weight:600;font-size:13px;margin-bottom:4px;color:var(--text-primary);' }));
      var b = DN.h('div', { text: '（暂未生成，每日汇总后出现）', style: 'font-size:12.5px;color:var(--text-secondary);line-height:1.7;white-space:pre-wrap;word-break:break-word;' });
      c.appendChild(b); box.appendChild(c); return b;
    }
    var uB = sec('👤 用户画像 · AI 对你的了解');
    var pB = sec('📦 项目画像 · 全局积累');
    function setProfile(b, d) {
      if (!d) return;
      if (d.content) b.textContent = d.content;
      if (d.updatedAt && b.parentNode) b.parentNode.appendChild(DN.h('div', { text: '更新于 ' + fmtTime(d.updatedAt), style: 'font-size:11px;color:var(--text-muted);margin-top:4px;' }));
    }
    DN.get('/api/ai/agent/user-profile').then(function (d) { setProfile(uB, d); }).catch(function () {});
    DN.get('/api/ai/agent/project-profile').then(function (d) { setProfile(pB, d); }).catch(function () {});
    var gen = DN.h('a', { href: 'javascript:void(0)', text: '↻ 立即生成/更新画像', title: '从近期经验蒸馏画像(异步, 稍候刷新)', style: 'font-size:12px;color:var(--primary);text-decoration:none;display:inline-block;margin-bottom:8px;' });
    gen.onclick = function () { DN.post('/api/ai/agent/profile-digest/run', {}).then(function () { DN.toast('已触发画像汇总, 约 30s 后重开本面板查看', 'ok'); }).catch(function (e) { DN.toast('触发失败：' + (e && e.message ? e.message : e), 'err'); }); };
    box.appendChild(gen);
    renderIndustry(box); // 行业经验: 业务域画像 + 业务流程SOP
  }

  var SOP_TYPE = { flow: '业务流程', report: '报表开发', caliber: '指标口径', pitfall: '坑/注意', glossary: '术语' };

  // 行业经验区: 行业画像(按业务域) + 业务流程SOP管理(增改/历史/归档) + 教学/归纳入口
  function renderIndustry(box) {
    var wrap = DN.h('div', { style: 'border:1px solid var(--border);border-radius:var(--radius-lg);padding:10px 12px;margin-bottom:10px;background:rgba(16,185,129,.07);' });
    var hd = DN.h('div', { style: 'display:flex;align-items:center;gap:10px;margin-bottom:6px;flex-wrap:wrap;' });
    hd.appendChild(DN.h('div', { text: '🏭 行业经验 · 业务域知识 + 流程SOP', style: 'flex:1;font-weight:600;font-size:13px;color:var(--text-primary);' }));
    var teach = DN.h('a', { href: 'javascript:void(0)', text: '+ 教AI业务知识', style: 'font-size:12px;color:var(--primary);text-decoration:none;flex:0 0 auto;' });
    var digest = DN.h('a', { href: 'javascript:void(0)', text: '↻ 归纳画像', title: '从平台元数据(指标/主题域/血缘/字段描述)归纳行业画像(异步, 约30s后重开查看)', style: 'font-size:12px;color:var(--primary);text-decoration:none;flex:0 0 auto;' });
    hd.appendChild(teach); hd.appendChild(digest);
    wrap.appendChild(hd);
    var pBox = DN.h('div', {}); wrap.appendChild(pBox);
    wrap.appendChild(DN.h('div', { text: '业务流程 SOP', style: 'font-weight:600;font-size:12.5px;margin:8px 0 4px;color:var(--text-regular);' }));
    var sBox = DN.h('div', {}); wrap.appendChild(sBox);
    box.appendChild(wrap);
    function loadIndustry() {
      pBox.innerHTML = '';
      DN.get('/api/ai/agent/industry/profiles').then(function (list) {
        list = list || [];
        if (!list.length) { pBox.appendChild(DN.h('div', { text: '（暂无行业画像，点"归纳画像"或每日自动生成）', style: 'font-size:12px;color:var(--text-muted);' })); return; }
        list.forEach(function (p) {
          var dom = p.domain === 'global' ? '全局概览' : p.domain;
          pBox.appendChild(DN.h('details', { style: 'margin-bottom:4px;' }, [
            DN.h('summary', { text: '🗂 ' + dom, style: 'cursor:pointer;font-size:12.5px;font-weight:600;color:var(--text-primary);' }),
            DN.h('div', { text: p.content || '', style: 'font-size:12px;color:var(--text-secondary);line-height:1.7;white-space:pre-wrap;margin:4px 0 6px;' })
          ]));
        });
      }).catch(function () {});
      sBox.innerHTML = '';
      DN.get('/api/ai/agent/industry/sops').then(function (list) {
        list = list || [];
        if (!list.length) { sBox.appendChild(DN.h('div', { text: '（暂无SOP，点"+教AI业务知识"录入，或AI完成业务流后自动沉淀）', style: 'font-size:12px;color:var(--text-muted);' })); return; }
        list.forEach(function (s) { sBox.appendChild(sopRow(s, loadIndustry)); });
      }).catch(function () {});
    }
    digest.onclick = function () { DN.post('/api/ai/agent/industry/digest/run', {}).then(function () { DN.toast('已触发行业画像归纳, 约30s后重开查看', 'ok'); }).catch(function (e) { DN.toast('触发失败：' + (e && e.message ? e.message : e), 'err'); }); };
    teach.onclick = function () { sopEditor(null, loadIndustry); };
    loadIndustry();
  }

  function sopRow(s, reload) {
    var row = DN.h('div', { style: 'border:1px solid var(--divider);border-radius:var(--radius);padding:6px 8px;margin-bottom:5px;background:var(--bg-card);' });
    var top = DN.h('div', { style: 'display:flex;align-items:center;gap:6px;' });
    top.appendChild(DN.h('span', { text: SOP_TYPE[s.sopType] || '经验', style: 'flex:0 0 auto;font-size:10px;padding:1px 6px;border-radius:8px;background:rgba(16,185,129,.15);color:#0a7d54;' }));
    top.appendChild(DN.h('span', { text: (s.domain && s.domain !== 'global' ? s.domain + ' · ' : '') + (s.title || ''), title: s.title, style: 'flex:1;font-size:12.5px;color:var(--text-primary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' }));
    if (s.status === 'draft') top.appendChild(DN.h('span', { text: '草稿', style: 'flex:0 0 auto;font-size:10px;color:var(--warning-text,#b97e00);' }));
    row.appendChild(top);
    var act = DN.h('div', { style: 'display:flex;gap:10px;margin-top:4px;' });
    function lnk(t, fn) { var a = DN.h('a', { href: 'javascript:void(0)', text: t, style: 'font-size:11px;color:var(--text-muted);text-decoration:none;' }); a.onclick = fn; return a; }
    act.appendChild(lnk('查看/编辑', function () { sopEditor(s, reload); }));
    act.appendChild(lnk('历史', function () { sopHistory(s.id); }));
    act.appendChild(lnk('归档', function () { DN.confirm('归档该SOP？归档后不再注入。', { title: '归档SOP' }).then(function (ok) { if (!ok) return; DN.post('/api/ai/agent/industry/sop/' + s.id + '/archive', {}).then(function () { DN.toast('已归档', 'ok'); reload && reload(); }).catch(function (e) { DN.toast('失败：' + (e && e.message ? e.message : e), 'err'); }); }); }));
    row.appendChild(act);
    return row;
  }

  // SOP 编辑/新建浮层
  function sopEditor(s, onSaved) {
    var isNew = !s;
    var ov = DN.h('div', { style: 'position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:9999;display:flex;align-items:center;justify-content:center;' });
    var panel = DN.h('div', { style: 'background:var(--bg-card);border-radius:var(--radius-lg);padding:16px;width:min(560px,92vw);max-height:88vh;overflow:auto;box-shadow:0 8px 32px rgba(0,0,0,.25);' });
    panel.appendChild(DN.h('div', { text: isNew ? '教 AI 业务知识 · 新建SOP' : '编辑业务流程SOP', style: 'font-weight:700;font-size:15px;margin-bottom:12px;' }));
    function field(label, el) { var w = DN.h('div', { style: 'margin-bottom:10px;' }); w.appendChild(DN.h('div', { text: label, style: 'font-size:12px;color:var(--text-muted);margin-bottom:3px;' })); w.appendChild(el); return w; }
    var inDomain = DN.h('input', { class: 'dn-input', value: s ? (s.domain || '') : '', placeholder: '业务域，如 销售/库存/财务/会员 (留空=通用)', style: 'width:100%;box-sizing:border-box;' });
    var inType = DN.h('select', { class: 'dn-input', style: 'width:100%;box-sizing:border-box;' });
    Object.keys(SOP_TYPE).forEach(function (k) { var o = document.createElement('option'); o.value = k; o.textContent = SOP_TYPE[k]; if (s && s.sopType === k) o.selected = true; inType.appendChild(o); });
    var inTitle = DN.h('input', { class: 'dn-input', value: s ? (s.title || '') : '', placeholder: '标题，如 "日销售汇总报表开发流程"', style: 'width:100%;box-sizing:border-box;' });
    var inTrig = DN.h('input', { class: 'dn-input', value: s ? (s.triggerHint || '') : '', placeholder: '触发词/适用场景(便于AI召回)，如 销售日报 GMV 同比', style: 'width:100%;box-sizing:border-box;' });
    var inContent = DN.h('textarea', { class: 'dn-input', placeholder: '标准步骤/口径/SQL模板/注意事项(Markdown)。\n例:\n1. 源表: ods_xxx\n2. 加工: 关联维表→按日聚合→写 dws_xxx\n3. 口径: GMV=实付金额求和, 含退款剔除\n4. 坑: 时区/退款剔除', style: 'width:100%;box-sizing:border-box;min-height:180px;font-family:ui-monospace,monospace;font-size:12px;' });
    if (s && s.content) inContent.value = s.content;
    if (isNew) { panel.appendChild(field('业务域', inDomain)); panel.appendChild(field('类型', inType)); }
    panel.appendChild(field('标题', inTitle));
    panel.appendChild(field('内容(标准步骤/口径)', inContent));
    panel.appendChild(field('触发词(召回用)', inTrig));
    var bar = DN.h('div', { style: 'display:flex;gap:8px;justify-content:flex-end;margin-top:8px;' });
    var cancel = DN.h('button', { class: 'btn btn-sm', text: '取消' });
    var save = DN.h('button', { class: 'btn btn-sm btn-primary', text: '保存', style: 'background:var(--primary);color:var(--text-inverse);border-color:var(--primary);' });
    function closeOv() { ov.remove(); document.removeEventListener('keydown', escH, true); }
    function escH(e) { if (e.key === 'Escape') { e.stopPropagation(); closeOv(); } }
    cancel.onclick = closeOv;
    ov.onclick = function (e) { if (e.target === ov) closeOv(); };
    save.onclick = function () {
      var title = (inTitle.value || '').trim(), content = (inContent.value || '').trim();
      if (!title || !content) { DN.toast('标题与内容必填', 'warn'); return; }
      save.disabled = true; save.textContent = '保存中…';
      var p = isNew
        ? DN.post('/api/ai/agent/industry/sop', { domain: (inDomain.value || '').trim(), type: inType.value, title: title, content: content, trigger: (inTrig.value || '').trim() })
        : DN.post('/api/ai/agent/industry/sop/' + s.id, { title: title, content: content, trigger: (inTrig.value || '').trim(), op: 'edit' });
      p.then(function () { DN.toast('已保存', 'ok'); closeOv(); onSaved && onSaved(); })
       .catch(function (e) { DN.toast('保存失败：' + (e && e.message ? e.message : e), 'err'); save.disabled = false; save.textContent = '保存'; });
    };
    bar.appendChild(cancel); bar.appendChild(save); panel.appendChild(bar);
    ov.appendChild(panel); document.body.appendChild(ov);
    document.addEventListener('keydown', escH, true); // Esc 关闭(capture, 不波及抽屉)
    setTimeout(function () { try { (isNew ? inTitle : inContent).focus(); } catch (e) {} }, 60);
  }

  // SOP 版本历史浮层
  function sopHistory(id) {
    DN.get('/api/ai/agent/industry/sop/' + id + '/history').then(function (list) {
      list = list || [];
      var ov = DN.h('div', { style: 'position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:9999;display:flex;align-items:center;justify-content:center;' });
      var panel = DN.h('div', { style: 'background:var(--bg-card);border-radius:var(--radius-lg);padding:16px;width:min(560px,92vw);max-height:88vh;overflow:auto;box-shadow:0 8px 32px rgba(0,0,0,.25);' });
      panel.appendChild(DN.h('div', { text: '版本历史', style: 'font-weight:700;font-size:15px;margin-bottom:12px;' }));
      if (!list.length) panel.appendChild(DN.h('div', { text: '（无历史）', style: 'font-size:12px;color:var(--text-muted);' }));
      list.forEach(function (h) {
        var row = DN.h('div', { style: 'border:1px solid var(--divider);border-radius:var(--radius);padding:6px 8px;margin-bottom:6px;' });
        row.appendChild(DN.h('div', { style: 'display:flex;gap:8px;align-items:center;', }, [
          DN.h('span', { text: 'v' + h.version + ' · ' + (h.op || '') + ' · ' + fmtTime(h.snapshotAt), style: 'flex:1;font-size:11px;color:var(--text-muted);' }),
          (function () { var a = DN.h('a', { href: 'javascript:void(0)', text: '回滚到此版', style: 'font-size:11px;color:var(--primary);text-decoration:none;' }); a.onclick = function () { DN.post('/api/ai/agent/industry/sop/' + id + '/rollback', { version: h.version }).then(function () { DN.toast('已回滚', 'ok'); closeOv(); }).catch(function (e) { DN.toast('失败：' + (e && e.message ? e.message : e), 'err'); }); }; return a; })()
        ]));
        row.appendChild(DN.h('div', { text: h.content || '', style: 'font-size:12px;color:var(--text-secondary);white-space:pre-wrap;line-height:1.6;max-height:120px;overflow:auto;margin-top:4px;' }));
        panel.appendChild(row);
      });
      function closeOv() { ov.remove(); document.removeEventListener('keydown', escH, true); }
      function escH(e) { if (e.key === 'Escape') { e.stopPropagation(); closeOv(); } }
      var close = DN.h('button', { class: 'btn btn-sm', text: '关闭', style: 'margin-top:6px;' }); close.onclick = closeOv;
      panel.appendChild(close); ov.onclick = function (e) { if (e.target === ov) closeOv(); };
      ov.appendChild(panel); document.body.appendChild(ov);
      document.addEventListener('keydown', escH, true);
    }).catch(function (e) { DN.toast('加载历史失败：' + (e && e.message ? e.message : e), 'err'); });
  }
  function renderMemories(body) {
    body.innerHTML = '';
    renderProfiles(body); // 顶部: 自动生成的用户/项目画像
    var memBox = DN.h('div', {}); body.appendChild(memBox);
    DN.get('/api/ai/agent/memories').then(function (list) {
      var arr = list || [];
      if (!arr.length) { memBox.appendChild(DN.h('div', { text: '暂无沉淀经验。AI 完成带工具调用的任务后会自动学习。', style: 'color:var(--text-muted);font-size:13px;line-height:1.8;' })); return; }
      memBox.appendChild(DN.h('div', { text: '共 ' + arr.length + ' 条沉淀经验', style: 'font-size:12px;color:var(--text-muted);margin:4px 0 8px;' }));
      arr.forEach(function (m) {
        var isSkill = m.type === 'skill';
        var isReview = m.type === 'review';
        var icon = isSkill ? '🛠 ' : (isReview ? '🔍 ' : '🧠 ');
        var label = isSkill ? '操作技能' : (isReview ? '复盘改进' : '经验');
        var badgeCls = isSkill ? 'dn-ai-badge info' : (isReview ? 'dn-ai-badge warn' : 'dn-ai-badge');
        var card = DN.h('div', { style: 'border:1px solid var(--border);border-radius:var(--radius-lg);padding:10px 12px;margin-bottom:10px;background:var(--bg-body);' });
        var hd = DN.h('div', { style: 'display:flex;align-items:center;gap:8px;margin-bottom:4px;' }, [
          DN.h('span', { text: icon + (m.title || '(无标题)'), style: 'font-weight:600;font-size:13px;color:var(--text-primary);' }),
          DN.h('span', { text: label, class: badgeCls, style: 'flex:0 0 auto;' })
        ]);
        card.appendChild(hd);
        // 技能含有序步骤 → 等宽 pre 保留换行/缩进, 便于照做; 经验普通文本
        card.appendChild(isSkill
          ? DN.h('pre', { text: m.content || '', style: 'font-size:12px;color:var(--text-secondary);line-height:1.7;white-space:pre-wrap;word-break:break-word;margin:0;font-family:inherit;' })
          : DN.h('div', { text: m.content || '', style: 'font-size:12.5px;color:var(--text-secondary);line-height:1.7;' }));
        var meta = [];
        if (m.triggerHint) meta.push('适用: ' + m.triggerHint);
        meta.push('命中 ' + (m.hitCount || 0));
        card.appendChild(DN.h('div', { text: meta.join(' · '), style: 'font-size:var(--fs-xs);color:var(--text-muted);margin-top:6px;' }));
        memBox.appendChild(card);
      });
    }).catch(function (e) { memBox.innerHTML = ''; memBox.appendChild(DN.h('div', { text: '加载失败：' + (e && e.message ? e.message : e), style: 'color:var(--danger);font-size:13px;' })); });
  }
  function renderApprovals(body) {
    DN.get('/api/ai/agent/approvals?status=pending').then(function (list) {
      body.innerHTML = '';
      var arr = list || [];
      if (!arr.length) { body.appendChild(DN.h('div', { text: '没有待审批的写操作。', style: 'color:var(--text-muted);font-size:13px;' })); return; }
      body.appendChild(DN.h('div', { text: '共 ' + arr.length + ' 项待审批', style: 'font-size:12px;color:var(--text-muted);margin-bottom:8px;' }));
      arr.forEach(function (a) {
        var card = DN.h('div', { style: 'border:1px solid var(--border);border-radius:var(--radius-lg);padding:10px 12px;margin-bottom:10px;' });
        card.appendChild(DN.h('div', {}, [
          DN.h('span', { text: a.actionSummary || a.skillName || '?', style: 'font-weight:600;font-size:13px;color:var(--text-primary);' }),
          DN.h('span', { text: a.riskLevel || '', class: 'dn-ai-badge ' + (a.riskLevel === 'HIGH' ? 'err' : 'warn'), style: 'margin-left:8px;' })
        ]));
        card.appendChild(DN.h('details', { style: 'margin:6px 0;' }, [
          DN.h('summary', { text: '查看参数 (' + (a.skillName || '') + ')', style: 'font-size:var(--fs-xs);color:var(--text-muted);cursor:pointer;' }),
          DN.h('pre', { text: a.argsJson || '{}', style: 'font-size:var(--fs-xs);color:var(--text-secondary);background:var(--bg-body);border-radius:var(--radius);padding:6px 8px;margin:4px 0;white-space:pre-wrap;word-break:break-all;max-height:120px;overflow:auto;' })
        ]));
        var ok = DN.h('button', { class: 'btn btn-primary btn-sm', text: '批准并执行', 'data-perm': 'assistant:approve', style: 'background:var(--primary);color:var(--text-inverse);border-color:var(--primary);' });
        var no = DN.h('button', { class: 'btn btn-sm', text: '拒绝', 'data-perm': 'assistant:approve', style: 'margin-left:8px;' });
        ok.onclick = function () {
          if (sending) { DN.toast('正在处理中，请稍候', 'warn'); return; } // 共用全局发送锁
          var ep = _epoch; setSending(true); // 捕获发起纪元
          ok.disabled = no.disabled = true; ok.textContent = '执行中…';
          DN.post('/api/ai/agent/approval/' + a.id + '/decide', { decision: 'approved' })
            .then(function () { return DN.post('/api/ai/agent/' + a.sessionId + '/resume', {}); })
            .then(function (res) {
              if (ep === _epoch) setSending(false); // 旧纪元不碰锁(newSession 已复原)
              res = res || {};
              // 跨会话隔离: 仅当结果属于当前会话才渲染进消息流; 否则提示并把摘要写在审批卡底部, 不污染当前对话
              if (ep === _epoch && flowEl && res.sessionId === sessionId) {
                card.remove(); renderTurn(res); scrollBottom(); DN.toast('已执行', 'ok');
              } else {
                DN.toast('该审批属于其他会话, 已在后台执行', 'info');
                ok.textContent = '已执行';
                card.appendChild(DN.h('div', { text: '执行结果: ' + String(res.finalAnswer || res.status || '已完成').slice(0, 80), style: 'font-size:var(--fs-xs);color:var(--text-muted);margin-top:6px;word-break:break-all;' }));
              }
            })
            .catch(function (e) { if (ep === _epoch) setSending(false); DN.toast('执行失败：' + (e && e.message ? e.message : e), 'err'); ok.disabled = no.disabled = false; ok.textContent = '批准并执行'; });
        };
        no.onclick = function () {
          ok.disabled = no.disabled = true;
          DN.post('/api/ai/agent/approval/' + a.id + '/decide', { decision: 'rejected' })
            .then(function () { card.remove(); }).catch(function (e) { DN.toast('拒绝失败：' + (e && e.message ? e.message : e), 'err'); ok.disabled = no.disabled = false; });
        };
        card.appendChild(DN.h('div', { style: 'display:flex;align-items:center;' }, [ok, no]));
        body.appendChild(card);
      });
    }).catch(function (e) { body.innerHTML = ''; body.appendChild(DN.h('div', { text: '加载失败：' + (e && e.message ? e.message : e), style: 'color:var(--danger);font-size:13px;' })); });
  }

  // ===== 数据中心: 文件上传/下载/列表(参考企业智脑布局) =====
  function dataCenter() {
    var panel = DN.h('div', { class: 'dn-ai-side' });
    var hd = DN.h('div', { style: 'padding:16px 18px 10px;flex:0 0 auto;' });
    hd.appendChild(DN.h('div', { text: '📚 数据中心', style: 'font-size:15px;font-weight:650;color:var(--text-primary);' }));
    hd.appendChild(DN.h('div', { text: '上传文件留存, 可随时下载', style: 'font-size:12px;color:var(--text-muted);margin-top:3px;' }));
    panel.appendChild(hd);

    // 历史会话(常驻左侧, 仅本人; 点击切换并可继续)
    var histHd = DN.h('div', { style: 'display:flex;align-items:center;gap:6px;padding:2px 18px 6px;flex:0 0 auto;' }, [
      DN.h('div', { text: '🕘 历史会话', style: 'font-size:12.5px;font-weight:600;color:var(--text-regular);flex:1;' }),
      DN.h('span', { text: '✚ 新', title: '开始新会话', style: 'cursor:pointer;font-size:12px;color:var(--primary);', onclick: newSession })
    ]);
    panel.appendChild(histHd);
    var histSearch = DN.h('input', { id: 'aiHistSearch', type: 'text', placeholder: '搜索历史…', style: 'margin:0 18px 6px;padding:4px 8px;font-size:12px;border:1px solid var(--border);border-radius:var(--radius-md);background:var(--bg-body);color:var(--text-primary);flex:0 0 auto;' });
    var _histSearchTm;
    histSearch.addEventListener('input', function () { _sessionFilter = histSearch.value; clearTimeout(_histSearchTm); _histSearchTm = setTimeout(renderSessionRows, 250); });   // 防抖, 避免每键重渲会话列表
    panel.appendChild(histSearch);
    histListEl = DN.h('div', { id: 'aiHistList', style: 'flex:0 0 auto;max-height:240px;overflow-y:auto;padding:0 18px 8px;border-bottom:1px solid var(--border);margin-bottom:6px;' });
    panel.appendChild(histListEl);

    // 拖拽/点击上传区
    var dz = DN.h('div', { class: 'dn-ai-drop' });
    dz.appendChild(DN.h('div', { html: DN.icon('upload') || '⬆', style: 'font-size:26px;color:var(--primary);opacity:.8;margin-bottom:8px;display:flex;justify-content:center;' }));
    dz.appendChild(DN.h('div', { text: '点击或拖拽文件到此区域', style: 'font-size:13px;color:var(--text-regular);' }));
    dz.appendChild(DN.h('div', { text: '支持 xlsx/pdf/csv/txt/图片 等, ≤20MB', style: 'font-size:var(--fs-xs);color:var(--text-muted);margin-top:4px;' }));
    var fileInput = DN.h('input', { type: 'file', multiple: 'multiple', accept: '.xlsx,.xls,.csv,.pdf,.txt,.json,.docx,.doc,.md,.png,.jpg,.jpeg,.gif,.html,.htm', style: 'display:none;' });
    fileInput.onchange = function () { uploadFiles(fileInput.files); fileInput.value = ''; };
    dz.onclick = function () { fileInput.click(); };
    function dzOn() { dz.classList.add('is-drag'); }
    function dzOff() { dz.classList.remove('is-drag'); }
    dz.ondragover = function (e) { e.preventDefault(); dzOn(); };
    dz.ondragleave = function () { dzOff(); };
    dz.ondrop = function (e) { e.preventDefault(); dzOff(); if (e.dataTransfer && e.dataTransfer.files) uploadFiles(e.dataTransfer.files); };
    panel.appendChild(DN.h('div', { style: 'padding:0 18px;flex:0 0 auto;' }, [dz, fileInput]));

    panel.appendChild(DN.h('div', { id: 'aiFileTitle', text: '已上传文件', style: 'padding:14px 18px 6px;font-size:12.5px;font-weight:600;color:var(--text-regular);flex:0 0 auto;' }));
    fileListEl = DN.h('div', { id: 'aiFileList', style: 'flex:1;min-height:0;overflow-y:auto;padding:0 18px 16px;' });
    panel.appendChild(fileListEl);
    return panel;
  }

  function uploadFiles(files) {
    if (!files || !files.length) return;
    var fd = new FormData();
    for (var i = 0; i < files.length; i++) fd.append('files', files[i]);
    if (sessionId) fd.append('sessionId', sessionId);
    if (DN.toast) DN.toast('上传中…', 'info');
    fetch('/api/ai/agent/files/upload', { method: 'POST', body: fd, credentials: 'same-origin' })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (res) {
        var data = res && res.data ? res.data : [];
        var errs = data.filter(function (x) { return x && x.error; });
        if (errs.length) DN.toast('部分未成功: ' + errs[0].error, 'warn');
        else DN.toast('上传成功', 'ok');
        loadFiles();
      })
      .catch(function (e) { DN.toast('上传失败: ' + (e && e.message ? e.message : e), 'err'); });
  }

  var _filesPollN = 0;   // 索引轮询计数, 防卡死无限轮询
  function loadFiles(isPoll) {
    if (!fileListEl) return;
    if (!isPoll) _filesPollN = 0;   // 非轮询触发(上传/打开)重置计数
    DN.get('/api/ai/agent/files').then(function (list) {
      fileListEl.innerHTML = '';
      var arr = (list || []).filter(function (f) { return f && f.source !== 'agent'; }); // 只展示用户上传, AI生成的artifact不混入(在对话卡片里看)
      _userFiles = arr.map(function (f) { return f.fileName; }).filter(Boolean); // 供 @ 补全
      var ttl = document.getElementById('aiFileTitle'); if (ttl) ttl.textContent = arr.length ? '已上传文件 (' + arr.length + ')' : '已上传文件';
      if (!arr.length) { fileListEl.appendChild(DN.h('div', { text: '暂无文件。上传后在此查看与下载。', style: 'color:var(--text-muted);font-size:12px;line-height:1.8;' })); return; }
      arr.forEach(function (f) { fileListEl.appendChild(fileRow(f)); });
      // 文档异步索引中: 单次延迟刷新拿到最终状态(终态后不再轮询)
      if (arr.some(function (f) { return f && (f.indexStatus === 'indexing' || f.indexStatus === 'pending'); })) {
        if (_filesPollN++ < 20) setTimeout(function () { loadFiles(true); }, 2500);   // 上限~50s, 防索引卡死无限轮询耗资源
      } else { _filesPollN = 0; }
    }).catch(function () {});
  }

  // 文档知识库索引状态徽标(已索引/索引中/失败); 非文档类无状态返 null
  function idxBadge(f) {
    var s = f && f.indexStatus;
    if (!s || s === 'none') return null;
    var map = {
      indexing: ['索引中…', 'var(--text-muted)'],
      pending: ['待索引', 'var(--text-muted)'],
      indexed: ['📚 已索引' + (f.chunkCount ? ' ' + f.chunkCount + '块' : ''), 'var(--success, #16a34a)'],
      failed: ['索引失败', 'var(--danger, #dc2626)']
    };
    var t = map[s]; if (!t) return null;
    return DN.h('span', { text: t[0], style: 'font-size:var(--fs-xs);color:' + t[1] + ';margin-left:6px;white-space:nowrap;' });
  }

  function fileIcon(name) {
    var e = String(name || '').toLowerCase().split('.').pop();
    if (/^(csv|xlsx|xls)$/.test(e)) return '📊';
    if (e === 'pdf') return '📕';
    if (/^(doc|docx)$/.test(e)) return '📘';
    if (/^(txt|md)$/.test(e)) return '📝';
    if (/^(png|jpg|jpeg|gif)$/.test(e)) return '🖼';
    if (e === 'json') return '🔧';
    if (/^(html|htm)$/.test(e)) return '🌐';
    return '📄';
  }
  function fileRow(f) {
    var agent = f.source === 'agent';
    var row = DN.h('div', { class: 'dn-ai-file' });
    var meta2 = DN.h('div', { style: 'display:flex;align-items:center;flex-wrap:wrap;margin-top:2px;' }, [
      DN.h('span', { text: fmtSize(f.size) + (agent ? ' · AI生成' : ''), style: 'font-size:var(--fs-xs);color:var(--text-muted);' })
    ]);
    var badge = idxBadge(f); if (badge) meta2.appendChild(badge);
    var info = DN.h('div', { style: 'flex:1;min-width:0;' }, [
      DN.h('div', { text: (agent ? '🤖 ' : fileIcon(f.fileName) + ' ') + (f.fileName || ''), title: f.fileName, style: 'font-size:12.5px;color:var(--text-primary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' }),
      meta2
    ]);
    var dl = DN.h('a', { href: '/api/ai/agent/files/' + f.id + '/download', title: '下载', text: '↓', style: 'flex:0 0 auto;text-decoration:none;color:var(--primary);font-size:17px;font-weight:700;padding:0 5px;' });
    var del = DN.h('span', { title: '删除', text: '✕', 'data-perm': 'assistant:use', style: 'flex:0 0 auto;cursor:pointer;color:var(--text-muted);font-size:13px;padding:0 4px;' });
    del.onclick = function () { DN.confirm('确认删除文件「' + (f.fileName || '') + '」？', { title: '删除文件', danger: true }).then(function (ok) { if (!ok) return; DN.post('/api/ai/agent/files/' + f.id + '/remove', {}).then(function () { loadFiles(); }).catch(function (e) { DN.toast('删除失败：' + (e && e.message ? e.message : e), 'err'); }); }); };
    row.appendChild(info); row.appendChild(dl); row.appendChild(del);
    return row;
  }

  function fmtSize(b) {
    b = Number(b) || 0;
    if (b < 1024) return b + ' B';
    if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
    return (b / 1048576).toFixed(1) + ' MB';
  }

  function build() {
    var root = document.getElementById('aiAgentRoot');
    if (!root) return;
    root.innerHTML = '';

    root.classList.add('dn-ai-wrap'); // 左数据中心 + 右聊天

    root.appendChild(dataCenter());

    var rightCol = DN.h('div', { class: 'dn-ai-main' });
    // 顶栏
    var head = DN.h('div', { class: 'dn-ai-topbar', style: 'flex-wrap:wrap;' }, [
      DN.h('div', { html: DN.icon('layers'), style: 'width:26px;height:26px;font-size:26px;color:var(--primary);display:flex;' }),
      DN.h('div', { style: 'min-width:0;' }, [
        DN.h('div', { text: '天工·自由意志数据智能体', style: 'font-size:16px;font-weight:650;color:var(--text-primary);' }),
        DN.h('div', { id: 'aiToolsHint', text: '在护栏内自主编排工具，写操作经人工审批，逐道工序可复核', style: 'font-size:12px;color:var(--text-muted);margin-top:2px;' })
      ]),
      modelPicker(),
      DN.h('button', { id: 'aiSideToggle', class: 'btn btn-sm', text: '📚', title: '收起/展开左侧数据中心(更宽聊天)', style: 'flex:0 0 auto;', onclick: function () { var el = document.querySelector('#aiAgentRoot .dn-ai-side'); if (el) { var hide = el.style.display !== 'none'; el.style.display = hide ? 'none' : ''; this.textContent = hide ? '📖' : '📚'; try { localStorage.setItem('aiSideHidden', hide ? '1' : '0'); } catch (e) {} } } }),
      DN.h('button', { class: 'btn btn-sm', text: '✚ 新会话', title: '清空当前对话, 开始新会话', style: 'flex:0 0 auto;', onclick: newSession }),
      DN.h('button', { class: 'btn btn-sm', text: '⤓ 导出', title: '导出当前对话为 Markdown', style: 'flex:0 0 auto;', onclick: exportChat }),
      DN.h('button', { class: 'btn btn-sm', text: '🧠 经验', title: 'AI 自学习记忆', style: 'flex:0 0 auto;', onclick: function () { openDrawer('memory'); } }),
      DN.h('button', { class: 'btn btn-sm', text: '⏰ 定时', title: '定时自治任务', style: 'flex:0 0 auto;', onclick: function () { openDrawer('cron'); } }),
      DN.h('button', { class: 'btn btn-sm', text: '🛡️ 审批', title: '待审批写操作', style: 'flex:0 0 auto;', onclick: function () { openDrawer('approval'); } })
    ]);
    rightCol.appendChild(head);

    // 对话流
    flowEl = DN.h('div', { class: 'dn-ai-chat' });
    flowEl.appendChild(welcome());
    rightCol.appendChild(flowEl);
    // 回到底部浮钮: 用户上滚查看历史时出现, 点击回到最新
    rightCol.style.position = rightCol.style.position || 'relative';
    var toBottom = DN.h('div', { text: '↓', title: '回到最新', style: 'position:absolute;right:22px;bottom:96px;width:34px;height:34px;border-radius:50%;background:var(--primary);color:var(--text-inverse);display:none;align-items:center;justify-content:center;cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.18);font-size:18px;z-index:5;' });
    toBottom.onclick = function () { scrollBottom(true); };
    rightCol.appendChild(toBottom);
    flowEl.addEventListener('scroll', function () {
      var nearBottom = flowEl.scrollHeight - flowEl.scrollTop - flowEl.clientHeight < 120;
      toBottom.style.display = nearBottom ? 'none' : 'flex';
    });

    // 输入区
    inputEl = DN.h('textarea', { placeholder: '问我：看下治理总览；某表质量为什么下降… (输入 @ 可引用已上传文件)', rows: '2' });
    inputEl.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && _mentionDD && _mentionDD._first) { e.preventDefault(); _mentionDD._first(); return; } // @候选开着: 回车选首项, 不发送
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); if (sending) steer(); else send(); } // 运行中回车=插话引导
      else if (e.key === 'ArrowUp' && !(inputEl.value || '').trim() && _lastSent) { e.preventDefault(); if (_mentionDD) { _mentionDD.remove(); _mentionDD = null; } inputEl.value = _lastSent; inputEl.style.height = 'auto'; inputEl.style.height = Math.min(inputEl.scrollHeight, 160) + 'px'; inputEl.dispatchEvent(new Event('input')); } // ↑ 调出上条(先关 @候选)
    });
    inputEl.addEventListener('input', function () { inputEl.style.height = 'auto'; inputEl.style.height = Math.min(inputEl.scrollHeight, 160) + 'px'; if (sendBtn && !sending) { sendBtn.disabled = !(inputEl.value || '').trim(); sendBtn.style.opacity = sendBtn.disabled ? '.55' : ''; } });   // 聊天输入随内容自动增高(≤160px)+空则禁用发送
    sendBtn = DN.h('button', { class: 'btn btn-primary', text: '发送', 'data-perm': 'assistant:use', style: 'flex:0 0 auto;height:40px;padding:0 22px;background:var(--primary);color:var(--text-inverse);border-color:var(--primary);opacity:.55;', disabled: 'disabled', onclick: onSendClick });
    inputBarEl = DN.h('div', { class: 'dn-ai-inputbar' }, [inputEl, sendBtn]);
    rightCol.appendChild(inputBarEl);
    setupMention(); // 输入框 @ 补全已上传文件
    setTimeout(function () { try { inputEl.focus(); } catch (e) {} }, 80);   // 聊天UI: 进入即聚焦输入, 直接开问
    if (!_kbBound) { // Ctrl/Cmd+K 快速聚焦 + 回到页面还原标题(一次性绑定)
      _kbBound = true;
      document.addEventListener('keydown', function (e) {
        if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) { var el = document.getElementById('aiAgentRoot'); if (el && el.offsetParent !== null && inputEl) { e.preventDefault(); inputEl.focus(); } }
        if ((e.ctrlKey || e.metaKey) && (e.key === 'b' || e.key === 'B')) { var rt2 = document.getElementById('aiAgentRoot'); if (rt2 && rt2.offsetParent !== null) { var tg = document.getElementById('aiSideToggle'); if (tg) { e.preventDefault(); tg.click(); } } } // Ctrl+B 收起/展开数据中心
        if (e.key === '?' && !/^(INPUT|TEXTAREA)$/.test((e.target && e.target.tagName) || '')) { var rt = document.getElementById('aiAgentRoot'); if (rt && rt.offsetParent !== null) DN.toast('快捷键: Ctrl+K 聚焦输入 · Ctrl+B 收展数据中心 · ↑ 调出上条 · @ 引用文件 · Esc 关预览', 'info'); }
        else if (e.key === 'Escape' && document.getElementById('aiPreviewPanel')) { closePreview(); } // Esc 关预览
      });
      document.addEventListener('visibilitychange', function () { if (!document.hidden && _origTitle != null) { document.title = _origTitle; _origTitle = null; } });
    }

    root.appendChild(rightCol);

    built = true;
    loadToolsHint();
    loadFiles();
    loadSessions();   // 左侧历史会话列表(仅本人)
    restoreHistory(); // 刷新后恢复上次会话历史
    try { if (localStorage.getItem('aiSideHidden') === '1') { var sd = root.querySelector('.dn-ai-side'); if (sd) sd.style.display = 'none'; var tg = document.getElementById('aiSideToggle'); if (tg) tg.textContent = '📖'; } } catch (e) {} // 恢复数据中心折叠偏好
  }

  function welcome() {
    var box = DN.h('div', { style: 'text-align:center;color:var(--text-muted);padding:36px 16px;font-size:13px;line-height:1.9;' });
    box.appendChild(DN.h('div', { html: DN.icon('layers'), style: 'width:40px;height:40px;font-size:40px;margin:0 auto 10px;color:var(--primary);opacity:.8;display:flex;align-items:center;justify-content:center;' }));
    var greet = DN.h('div', { text: '你好，我是天工司辰', style: 'font-size:15px;font-weight:600;color:var(--text-primary);margin-bottom:4px;' });
    box.appendChild(greet);
    DN.get('/api/rbac/me').then(function (u) { var n = u && (u.nickname || u.name || u.username); if (n) greet.textContent = '你好，' + n + '，我是天工司辰'; }).catch(function () {});
    box.appendChild(DN.h('div', { text: '可自主调用治理/质量/血缘等工具排障评估，也能建项目/同步任务/表/规则/指标/脚本（写操作需你审批）。' }));
    box.appendChild(DN.h('div', { text: '试试 (点击直接问):', style: 'color:var(--text-muted);margin-top:10px;' }));
    var chips = DN.h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap;justify-content:center;margin-top:6px;' });
    ['看下治理总览', '把某张表用 HTML ER 图展示', '某表质量为什么下降', '用 markdown 出一份数据分析报告', '帮我把某表做成 ODS→DWD→DWS→ADS 分层', '我是新手，带我开发一张销售报表'].forEach(function (t) {
      var needTable = /某表|某张表/.test(t); // 含占位需用户填具体表 → 填入待编辑; 其余直接发送
      chips.appendChild(DN.h('button', { class: 'btn btn-sm', text: t, title: needTable ? '点击填入, 把"某表"换成你的表名再发送' : '点击直接发送', style: 'font-size:12px;', onclick: function () { if (!inputEl || sending) return; inputEl.value = t; if (needTable) { inputEl.focus(); inputEl.style.height = 'auto'; inputEl.style.height = Math.min(inputEl.scrollHeight, 160) + 'px'; inputEl.dispatchEvent(new Event('input')); var p = t.indexOf('某'); try { inputEl.setSelectionRange(p, p + (t.indexOf('某张表') >= 0 ? 3 : 2)); } catch (e) {} } else send(); } }));
    });
    box.appendChild(chips);
    return box;
  }

  function loadToolsHint() {
    DN.get('/api/ai/agent/tools').then(function (d) {
      var hint = document.getElementById('aiToolsHint');
      if (hint && d && d.count != null) hint.textContent = '已装备 ' + d.count + ' 个工具 · 写操作经人工审批 · 逐道工序可复核';
    }).catch(function () {});
  }

  global.initAiAssistant = function (opts) {
    if (!built) build();
    if (opts) {
      if (opts.prefill && inputEl) { inputEl.value = opts.prefill; inputEl.dispatchEvent(new Event('input')); }
      if (opts.ctx) pendingCtx = opts.ctx;
    }
    setTimeout(function () { if (inputEl) try { inputEl.focus(); } catch (e) {} }, 60);
    // 情境入口可要求进入后自动发起一轮(全局唤起器恒不自动发, 由调用方控制)
    if (opts && opts.autoSend && inputEl && (inputEl.value || '').trim()) setTimeout(send, 150);
  };

})(window);
