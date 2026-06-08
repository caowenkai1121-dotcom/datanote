/* 天工·自由意志数据智能体（天工司辰）—— M1 最小对话面板。零框架，纯 DN.* 组件。
   一问一闭环：发送→后端 agent 自主串只读工具→返回 trace(工具卡)+终答。 */
(function (global) {
  'use strict';

  var sessionId = null;
  var sending = false;
  var pendingCtx = null;   // 情境入口透传的业务上下文, 随下一次 chat 上送
  var flowEl = null, inputEl = null, sendBtn = null, built = false;

  function groupColor(g) {
    return ({ gov: '#1d6fff', quality: '#2f9e44', lineage: '#fa8c16', sync: '#722ed1', metadata: '#13c2c2' })[g] || '#86909c';
  }

  // 用户气泡
  function userBubble(text) {
    return DN.h('div', { style: 'display:flex;justify-content:flex-end;margin:10px 0;' },
      [DN.h('div', { style: 'max-width:78%;background:var(--primary,#3457d5);color:#fff;padding:9px 13px;border-radius:12px 12px 2px 12px;white-space:pre-wrap;word-break:break-word;font-size:13px;', text: text })]);
  }

  // 助手终答气泡（识别 [表:库.表]/[规则:#id]/[任务:#id] token 渲染可点深链 chip, XSS 安全用 DN.h text）
  function assistantBubble(text, tone) {
    var bg = tone === 'err' ? 'var(--bg-body,#fff2f0)' : 'var(--bg-card,#fff)';
    var bd = tone === 'err' ? '#ffccc7' : 'var(--border,#e5e6eb)';
    var inner = DN.h('div', { class: 'ai-md', style: 'max-width:84%;background:' + bg + ';border:1px solid ' + bd + ';padding:10px 15px;border-radius:12px 12px 12px 2px;word-break:break-word;font-size:13px;line-height:1.7;color:var(--text-primary);overflow-x:auto;' });
    renderMarkdown(inner, String(text == null ? '' : text));
    return DN.h('div', { style: 'display:flex;justify-content:flex-start;margin:10px 0;' }, [inner]);
  }

  // 富文本：把深链 token 转成可点 chip，其余按纯文本节点拼（绝不 innerHTML 拼 LLM 文本，防 XSS）
  var RICH_RE = /\[表:([^\]\.]+)\.([^\]]+)\]|\[规则:#?(\d+)\]|\[任务:#?(\d+)\]/g;
  function renderRich(container, text) {
    var last = 0, m;
    RICH_RE.lastIndex = 0;
    while ((m = RICH_RE.exec(text)) !== null) {
      if (m.index > last) container.appendChild(document.createTextNode(text.slice(last, m.index)));
      if (m[1] != null) container.appendChild(linkChip('🔗 ' + m[1] + '.' + m[2], function () { go('catalog', { openTable: { db: m[1], table: m[2] } }); }));
      else if (m[3] != null) container.appendChild(linkChip('🔗 规则#' + m[3], function () { go('governance', { gov: 'quality', ruleId: Number(m[3]) }); }));
      else if (m[4] != null) container.appendChild(linkChip('🔗 任务#' + m[4], function () { go('dbsync', { openDetail: Number(m[4]) }); }));
      last = m.index + m[0].length;
    }
    if (last < text.length) container.appendChild(document.createTextNode(text.slice(last)));
  }

  function linkChip(label, onclick) {
    return DN.h('a', { href: 'javascript:void(0)', text: label, onclick: onclick,
      style: 'display:inline-block;margin:0 3px;padding:1px 9px;border-radius:10px;background:var(--primary,#3457d5);color:#fff;font-size:12px;text-decoration:none;' });
  }

  // ===== 轻量 Markdown → DOM 渲染(零依赖, XSS 安全: 全程 DOM 构造不用 innerHTML 拼 LLM 文本) =====
  // 支持: # 标题 / **粗体** / `行内码` / ```代码块``` / - 与 1. 列表 / | 表格 | / > 引用 / 段落换行 + 深链 chip
  var INLINE_RE = /\*\*([^*]+?)\*\*|`([^`]+?)`|\[表:([^\]\.]+)\.([^\]]+)\]|\[规则:#?(\d+)\]|\[任务:#?(\d+)\]/g;
  function appendInline(el, str) {
    var last = 0, m; INLINE_RE.lastIndex = 0;
    while ((m = INLINE_RE.exec(str)) !== null) {
      if (m.index > last) el.appendChild(document.createTextNode(str.slice(last, m.index)));
      if (m[1] != null) el.appendChild(DN.h('strong', { text: m[1], style: 'font-weight:650;' }));
      else if (m[2] != null) el.appendChild(DN.h('code', { text: m[2], style: 'background:var(--bg-main,#f1f4fa);border-radius:4px;padding:1px 5px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;' }));
      else if (m[3] != null) (function (db, tb) { el.appendChild(linkChip('🔗 ' + db + '.' + tb, function () { go('catalog', { openTable: { db: db, table: tb } }); })); })(m[3], m[4]);
      else if (m[5] != null) (function (id) { el.appendChild(linkChip('🔗 规则#' + id, function () { go('governance', { gov: 'quality', ruleId: Number(id) }); })); })(m[5]);
      else if (m[6] != null) (function (id) { el.appendChild(linkChip('🔗 任务#' + id, function () { go('dbsync', { openDetail: Number(id) }); })); })(m[6]);
      last = m.index + m[0].length;
    }
    if (last < str.length) el.appendChild(document.createTextNode(str.slice(last)));
  }
  function renderMarkdown(container, text) {
    var lines = String(text == null ? '' : text).replace(/\r\n/g, '\n').split('\n');
    var i = 0;
    function isBlank(s) { return s.trim() === ''; }
    while (i < lines.length) {
      var line = lines[i];
      // 代码块 ```
      if (/^```/.test(line.trim())) {
        var buf = []; i++;
        while (i < lines.length && !/^```/.test(lines[i].trim())) { buf.push(lines[i]); i++; }
        i++; // 跳过收尾 ```
        var pre = DN.h('pre', { style: 'background:var(--bg-main,#f1f4fa);border:1px solid var(--divider,#eef1f7);border-radius:8px;padding:10px 12px;overflow-x:auto;margin:8px 0;' });
        pre.appendChild(DN.h('code', { text: buf.join('\n'), style: 'font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;line-height:1.6;white-space:pre;' }));
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
            var td = DN.h(ri === 0 ? 'th' : 'td', { style: 'border:1px solid var(--border,#e3e7f0);padding:5px 9px;text-align:left;' + (ri === 0 ? 'background:var(--bg-main,#f1f4fa);font-weight:600;' : '') });
            appendInline(td, cell); tr.appendChild(td);
          });
          tbl.appendChild(tr);
        });
        container.appendChild(tbl); continue;
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
        var bq = DN.h('div', { style: 'border-left:3px solid var(--border,#e3e7f0);padding:2px 0 2px 10px;margin:6px 0;color:var(--text-muted,#5b6472);' });
        appendInline(bq, line.replace(/^>\s?/, '')); container.appendChild(bq); i++; continue;
      }
      // 分隔线
      if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
        container.appendChild(DN.h('div', { style: 'border-top:1px solid var(--divider,#eef1f7);margin:10px 0;' })); i++; continue;
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
    var card = DN.card({ title: (step.skillName || '工具') , icon: 'layers' });
    card.el.style.cssText = (card.el.style.cssText || '') + ';margin:8px 0 8px 18px;border-left:3px solid ' + col + ';';
    // 头部标签
    var tags = DN.h('span', { style: 'margin-left:8px;display:inline-flex;gap:6px;vertical-align:middle;' });
    if (step.skillGroup) tags.appendChild(DN.h('span', { text: step.skillGroup, style: 'font-size:11px;color:#fff;background:' + col + ';padding:1px 7px;border-radius:9px;' }));
    if (Number(step.readOnly) === 1) tags.appendChild(DN.pill('只读', 'info'));
    var ok = step.resultStatus === 'ok';
    tags.appendChild(DN.pill(ok ? '成功' : (step.resultStatus || '-'), ok ? 'ok' : 'err'));
    // 把标签塞进卡头
    try { card.el.querySelector('.gov-card-hd').appendChild(tags); } catch (e) {}

    if (step.argsJson && step.argsJson !== '{}' && step.argsJson !== 'null') {
      card.body.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);margin-bottom:6px;', text: '参数：' + step.argsJson }));
    }
    var resultText = summarizeResult(step.resultData, ok);
    var pre = DN.h('div', { style: 'font-size:12px;color:var(--text-regular,#4e5969);white-space:pre-wrap;word-break:break-word;max-height:180px;overflow:auto;background:var(--bg-body,#f7f8fa);border-radius:6px;padding:8px 10px;', text: resultText });
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

  function thinking() {
    return DN.h('div', { id: 'aiThinking', style: 'display:flex;justify-content:flex-start;margin:10px 0;' },
      [DN.h('div', { style: 'background:var(--bg-card,#fff);border:1px solid var(--border,#e5e6eb);padding:9px 14px;border-radius:12px;font-size:13px;color:var(--text-muted);', text: '天工司辰思考中…（自主规划并调用只读工具）' })]);
  }

  function scrollBottom() { if (flowEl) flowEl.scrollTop = flowEl.scrollHeight; }

  function send() {
    if (sending || !inputEl) return;
    var msg = (inputEl.value || '').trim();
    if (!msg) { DN.toast('请输入问题', 'warn'); return; }
    sending = true; sendBtn.disabled = true; sendBtn.textContent = '思考中…';
    flowEl.appendChild(userBubble(msg));
    inputEl.value = '';
    var th = thinking(); flowEl.appendChild(th); scrollBottom();

    DN.post('/api/ai/agent/chat', { sessionId: sessionId, message: msg, ctx: pendingCtx }).then(function (res) {
      if (th.parentNode) th.parentNode.removeChild(th);
      renderTurn(res || {});
      scrollBottom();
    }).catch(function (e) {
      if (th.parentNode) th.parentNode.removeChild(th);
      flowEl.appendChild(assistantBubble('请求失败：' + (e && e.message ? e.message : e), 'err'));
      scrollBottom();
    }).then(function () {
      sending = false; sendBtn.disabled = false; sendBtn.textContent = '发送'; if (inputEl) inputEl.focus();
    });
  }

  // 渲染一轮结果(对话/恢复共用): 工具卡 + 终答; 若挂起待审批则出审批卡
  function renderTurn(res) {
    sessionId = res.sessionId || sessionId;
    (res.steps || []).forEach(function (st) { if (st && st.stepType === 'SKILL_CALL') flowEl.appendChild(toolCard(st)); });
    var tone = res.status === 'blocked' ? 'err' : null;
    flowEl.appendChild(assistantBubble(res.finalAnswer || '（无答复）', tone));
    if (res.status === 'wait_approval') loadApproval(res.sessionId);
  }

  // 写操作待审批: 拉本会话待审项, 出批准/拒绝卡; 批准→decide+resume, 拒绝→decide
  function loadApproval(sid) {
    DN.get('/api/ai/agent/approvals?status=pending').then(function (list) {
      list = list || [];
      var ap = null;
      for (var i = 0; i < list.length; i++) { if (list[i] && list[i].sessionId === sid) { ap = list[i]; break; } }
      if (!ap) return;
      var card = DN.h('div', { style: 'margin:8px 0 8px 18px;border:1px solid #e8930c;background:var(--bg-body,#fffbe6);border-radius:10px;padding:12px 14px;' });
      card.appendChild(DN.h('div', { style: 'font-weight:600;color:#9a5b00;margin-bottom:6px;', text: '⚠ 写操作待人工审批: ' + (ap.skillName || '') + '  (风险 ' + (ap.riskLevel || '') + ')' }));
      if (ap.argsJson) card.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);margin-bottom:8px;word-break:break-all;', text: '参数: ' + ap.argsJson }));
      var btns = DN.h('div', { style: 'display:flex;gap:8px;' });
      var okBtn = DN.h('button', { class: 'btn btn-primary btn-sm', text: '批准并继续', style: 'background:var(--primary,#3457d5);color:#fff;border-color:var(--primary,#3457d5);' });
      var noBtn = DN.h('button', { class: 'btn btn-sm', text: '拒绝' });
      okBtn.onclick = function () {
        okBtn.disabled = true; noBtn.disabled = true; okBtn.textContent = '执行中…';
        DN.post('/api/ai/agent/approval/' + ap.id + '/decide', { decision: 'approved' })
          .then(function () { return DN.post('/api/ai/agent/' + sid + '/resume', {}); })
          .then(function (res) { card.remove(); renderTurn(res || {}); scrollBottom(); })
          .catch(function (e) { DN.toast('审批/恢复失败：' + (e && e.message ? e.message : e), 'err'); okBtn.disabled = false; noBtn.disabled = false; okBtn.textContent = '批准并继续'; });
      };
      noBtn.onclick = function () {
        okBtn.disabled = true; noBtn.disabled = true;
        DN.post('/api/ai/agent/approval/' + ap.id + '/decide', { decision: 'rejected' })
          .then(function () { card.remove(); flowEl.appendChild(assistantBubble('已拒绝该写操作。', 'err')); scrollBottom(); })
          .catch(function (e) { DN.toast('拒绝失败：' + (e && e.message ? e.message : e), 'err'); okBtn.disabled = false; noBtn.disabled = false; });
      };
      btns.appendChild(okBtn); btns.appendChild(noBtn); card.appendChild(btns);
      flowEl.appendChild(card); scrollBottom();
    }).catch(function () {});
  }

  // ===== 经验/审批 抽屉 =====
  function closeDrawer() {
    var d = document.getElementById('aiDrawer'); if (d) d.remove();
    var m = document.getElementById('aiDrawerMask'); if (m) m.remove();
  }
  function openDrawer(kind) {
    var root = document.getElementById('aiAgentRoot'); if (!root) return;
    closeDrawer();
    var mask = DN.h('div', { id: 'aiDrawerMask', style: 'position:absolute;inset:0;background:rgba(0,0,0,.18);z-index:20;', onclick: closeDrawer });
    var panel = DN.h('div', { id: 'aiDrawer', style: 'position:absolute;top:0;right:0;bottom:0;width:400px;max-width:88%;background:var(--bg-card,#fff);border-left:1px solid var(--border,#e5e6eb);box-shadow:-8px 0 32px rgba(15,20,40,.14);z-index:21;display:flex;flex-direction:column;' });
    var title = kind === 'memory' ? '🧠 AI 自学习记忆' : '🛡️ 待审批写操作';
    panel.appendChild(DN.h('div', { style: 'padding:16px 20px;background:var(--bg-card,#fff);border-bottom:1px solid var(--divider,#eef1f7);display:flex;align-items:center;gap:10px;flex:0 0 auto;' }, [
      DN.h('div', { text: title, style: 'font-weight:600;font-size:16px;color:var(--text-primary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' }),
      DN.h('button', { text: '✕', title: '关闭', onclick: closeDrawer, style: 'margin-left:auto;flex-shrink:0;width:32px;height:32px;border:0;background:none;cursor:pointer;color:var(--text-muted,#86909c);font-size:20px;line-height:1;border-radius:var(--radius,6px);display:inline-flex;align-items:center;justify-content:center;' })
    ]));
    var body = DN.h('div', { style: 'flex:1;min-height:0;overflow-y:auto;padding:18px 20px;' });
    body.appendChild(DN.h('div', { text: '加载中…', style: 'color:var(--text-muted);font-size:13px;' }));
    panel.appendChild(body);
    root.appendChild(mask); root.appendChild(panel);
    if (kind === 'memory') renderMemories(body); else renderApprovals(body);
  }
  function renderMemories(body) {
    DN.get('/api/ai/agent/memories').then(function (list) {
      body.innerHTML = '';
      var arr = list || [];
      if (!arr.length) { body.appendChild(DN.h('div', { text: '暂无沉淀经验。AI 完成带工具调用的任务后会自动学习。', style: 'color:var(--text-muted);font-size:13px;line-height:1.8;' })); return; }
      arr.forEach(function (m) {
        var card = DN.h('div', { style: 'border:1px solid var(--border,#e5e6eb);border-radius:8px;padding:10px 12px;margin-bottom:10px;background:var(--bg-body,#f7f8fa);' });
        card.appendChild(DN.h('div', { text: m.title || '(无标题)', style: 'font-weight:600;font-size:13px;color:var(--text-primary);margin-bottom:4px;' }));
        card.appendChild(DN.h('div', { text: m.content || '', style: 'font-size:12.5px;color:var(--text-secondary,#4e5969);line-height:1.7;' }));
        var meta = [];
        if (m.triggerHint) meta.push('适用: ' + m.triggerHint);
        meta.push('命中 ' + (m.hitCount || 0));
        card.appendChild(DN.h('div', { text: meta.join(' · '), style: 'font-size:11px;color:var(--text-muted);margin-top:6px;' }));
        body.appendChild(card);
      });
    }).catch(function (e) { body.innerHTML = ''; body.appendChild(DN.h('div', { text: '加载失败：' + (e && e.message ? e.message : e), style: 'color:var(--danger,#f53f3f);font-size:13px;' })); });
  }
  function renderApprovals(body) {
    DN.get('/api/ai/agent/approvals?status=pending').then(function (list) {
      body.innerHTML = '';
      var arr = list || [];
      if (!arr.length) { body.appendChild(DN.h('div', { text: '没有待审批的写操作。', style: 'color:var(--text-muted);font-size:13px;' })); return; }
      arr.forEach(function (a) {
        var card = DN.h('div', { style: 'border:1px solid var(--border,#e5e6eb);border-radius:8px;padding:10px 12px;margin-bottom:10px;' });
        card.appendChild(DN.h('div', {}, [
          DN.h('span', { text: a.skillName || '?', style: 'font-weight:600;font-size:13px;color:var(--text-primary);' }),
          DN.h('span', { text: a.riskLevel || '', style: 'margin-left:8px;font-size:11px;padding:1px 7px;border-radius:9px;background:' + (a.riskLevel === 'HIGH' ? '#ffece8;color:#f53f3f' : '#fff7e8;color:#ff7d00') + ';' })
        ]));
        card.appendChild(DN.h('pre', { text: a.argsJson || '{}', style: 'font-size:11px;color:var(--text-secondary,#4e5969);background:var(--bg-body,#f7f8fa);border-radius:6px;padding:6px 8px;margin:6px 0;white-space:pre-wrap;word-break:break-all;max-height:120px;overflow:auto;' }));
        var ok = DN.h('button', { class: 'btn btn-primary btn-sm', text: '批准并执行', style: 'background:var(--primary,#3457d5);color:#fff;border-color:var(--primary,#3457d5);' });
        var no = DN.h('button', { class: 'btn btn-sm', text: '拒绝', style: 'margin-left:8px;' });
        ok.onclick = function () {
          ok.disabled = no.disabled = true; ok.textContent = '执行中…';
          DN.post('/api/ai/agent/approval/' + a.id + '/decide', { decision: 'approved' })
            .then(function () { return DN.post('/api/ai/agent/' + a.sessionId + '/resume', {}); })
            .then(function (res) { card.remove(); if (flowEl) { renderTurn(res || {}); scrollBottom(); } DN.toast('已执行', 'ok'); })
            .catch(function (e) { DN.toast('执行失败：' + (e && e.message ? e.message : e), 'err'); ok.disabled = no.disabled = false; ok.textContent = '批准并执行'; });
        };
        no.onclick = function () {
          ok.disabled = no.disabled = true;
          DN.post('/api/ai/agent/approval/' + a.id + '/decide', { decision: 'rejected' })
            .then(function () { card.remove(); }).catch(function (e) { DN.toast('拒绝失败：' + (e && e.message ? e.message : e), 'err'); ok.disabled = no.disabled = false; });
        };
        card.appendChild(DN.h('div', { style: 'display:flex;align-items:center;' }, [ok, no]));
        body.appendChild(card);
      });
    }).catch(function (e) { body.innerHTML = ''; body.appendChild(DN.h('div', { text: '加载失败：' + (e && e.message ? e.message : e), style: 'color:var(--danger,#f53f3f);font-size:13px;' })); });
  }

  function build() {
    var root = document.getElementById('aiAgentRoot');
    if (!root) return;
    root.innerHTML = '';

    root.style.position = 'relative'; // 供经验/审批抽屉绝对定位
    // 顶栏
    var head = DN.h('div', { style: 'padding:16px 24px;border-bottom:1px solid var(--border,#e5e6eb);display:flex;align-items:center;gap:12px;flex:0 0 auto;' }, [
      DN.h('div', { html: DN.icon('layers'), style: 'width:26px;height:26px;font-size:26px;color:var(--primary,#3457d5);display:flex;' }),
      DN.h('div', { style: 'min-width:0;' }, [
        DN.h('div', { text: '天工·自由意志数据智能体', style: 'font-size:16px;font-weight:650;color:var(--text-primary);' }),
        DN.h('div', { id: 'aiToolsHint', text: '在护栏内自主编排工具，写操作经人工审批，逐道工序可复核', style: 'font-size:12px;color:var(--text-muted);margin-top:2px;' })
      ]),
      DN.h('button', { class: 'btn btn-sm', text: '🧠 经验', title: 'AI 自学习记忆', style: 'margin-left:auto;flex:0 0 auto;', onclick: function () { openDrawer('memory'); } }),
      DN.h('button', { class: 'btn btn-sm', text: '🛡️ 审批', title: '待审批写操作', style: 'flex:0 0 auto;', onclick: function () { openDrawer('approval'); } })
    ]);
    root.appendChild(head);

    // 对话流
    flowEl = DN.h('div', { style: 'flex:1;min-height:0;overflow-y:auto;padding:16px 24px;background:var(--bg-body,#f7f8fa);' });
    flowEl.appendChild(welcome());
    root.appendChild(flowEl);

    // 输入区
    inputEl = DN.h('textarea', { placeholder: '问我：看下治理总览；查 dwd_order 的下游影响；某表质量为什么下降…', rows: '2',
      style: 'flex:1;resize:none;border:1px solid var(--border,#e5e6eb);border-radius:8px;padding:9px 12px;font-size:13px;font-family:inherit;outline:none;' });
    inputEl.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(); }
    });
    sendBtn = DN.h('button', { class: 'btn btn-primary', text: '发送', style: 'flex:0 0 auto;height:40px;padding:0 22px;background:var(--primary,#3457d5);color:#fff;border-color:var(--primary,#3457d5);', onclick: send });
    var inputBar = DN.h('div', { style: 'flex:0 0 auto;padding:12px 24px;border-top:1px solid var(--border,#e5e6eb);display:flex;gap:10px;align-items:flex-end;background:var(--bg-card,#fff);' }, [inputEl, sendBtn]);
    root.appendChild(inputBar);

    built = true;
    loadToolsHint();
  }

  function welcome() {
    var box = DN.h('div', { style: 'text-align:center;color:var(--text-muted);padding:36px 16px;font-size:13px;line-height:1.9;' });
    box.appendChild(DN.h('div', { html: DN.icon('layers'), style: 'width:40px;height:40px;font-size:40px;margin:0 auto 10px;color:var(--primary,#3457d5);opacity:.8;display:flex;align-items:center;justify-content:center;' }));
    box.appendChild(DN.h('div', { text: '我是天工司辰，可自主调用治理/质量/血缘等工具排障评估，也能建项目/同步任务/表/规则/指标/脚本（写操作需你审批）。' }));
    box.appendChild(DN.h('div', { text: '试试：「看下治理总览，再查 dwd_order 的下游影响」 或 「建一个名为风控的项目」', style: 'color:var(--primary,#3457d5);margin-top:6px;' }));
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
      if (opts.prefill && inputEl) inputEl.value = opts.prefill;
      if (opts.ctx) pendingCtx = opts.ctx;
    }
    setTimeout(function () { if (inputEl) try { inputEl.focus(); } catch (e) {} }, 60);
    // 情境入口可要求进入后自动发起一轮(全局唤起器恒不自动发, 由调用方控制)
    if (opts && opts.autoSend && inputEl && (inputEl.value || '').trim()) setTimeout(send, 150);
  };

})(window);
