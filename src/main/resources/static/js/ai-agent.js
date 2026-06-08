/* 天工·自由意志数据智能体（天工司辰）—— M1 最小对话面板。零框架，纯 DN.* 组件。
   一问一闭环：发送→后端 agent 自主串只读工具→返回 trace(工具卡)+终答。 */
(function (global) {
  'use strict';

  var sessionId = null;
  var sending = false;
  var pendingCtx = null;   // 情境入口透传的业务上下文, 随下一次 chat 上送
  var flowEl = null, inputEl = null, sendBtn = null, built = false;

  function groupColor(g) {
    return ({ gov: '#1d6fff', quality: '#52c41a', lineage: '#fa8c16', sync: '#722ed1', metadata: '#13c2c2' })[g] || '#86909c';
  }

  // 用户气泡
  function userBubble(text) {
    return DN.h('div', { style: 'display:flex;justify-content:flex-end;margin:10px 0;' },
      [DN.h('div', { style: 'max-width:78%;background:var(--primary,#1890ff);color:#fff;padding:9px 13px;border-radius:12px 12px 2px 12px;white-space:pre-wrap;word-break:break-word;font-size:13px;', text: text })]);
  }

  // 助手终答气泡（识别 [表:库.表]/[规则:#id]/[任务:#id] token 渲染可点深链 chip, XSS 安全用 DN.h text）
  function assistantBubble(text, tone) {
    var bg = tone === 'err' ? 'var(--bg-body,#fff2f0)' : 'var(--bg-card,#fff)';
    var bd = tone === 'err' ? '#ffccc7' : 'var(--border,#e5e6eb)';
    var inner = DN.h('div', { style: 'max-width:84%;background:' + bg + ';border:1px solid ' + bd + ';padding:10px 14px;border-radius:12px 12px 12px 2px;white-space:pre-wrap;word-break:break-word;font-size:13px;line-height:1.7;color:var(--text-primary);' });
    renderRich(inner, String(text == null ? '' : text));
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
      style: 'display:inline-block;margin:0 3px;padding:1px 9px;border-radius:10px;background:var(--primary,#1890ff);color:#fff;font-size:12px;text-decoration:none;' });
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
      var card = DN.h('div', { style: 'margin:8px 0 8px 18px;border:1px solid #faad14;background:var(--bg-body,#fffbe6);border-radius:10px;padding:12px 14px;' });
      card.appendChild(DN.h('div', { style: 'font-weight:600;color:#d48806;margin-bottom:6px;', text: '⚠ 写操作待人工审批: ' + (ap.skillName || '') + '  (风险 ' + (ap.riskLevel || '') + ')' }));
      if (ap.argsJson) card.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);margin-bottom:8px;word-break:break-all;', text: '参数: ' + ap.argsJson }));
      var btns = DN.h('div', { style: 'display:flex;gap:8px;' });
      var okBtn = DN.h('button', { class: 'btn btn-primary btn-sm', text: '批准并继续' });
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

  function build() {
    var root = document.getElementById('aiAgentRoot');
    if (!root) return;
    root.innerHTML = '';

    // 顶栏
    var head = DN.h('div', { style: 'padding:16px 24px;border-bottom:1px solid var(--border,#e5e6eb);display:flex;align-items:center;gap:12px;flex:0 0 auto;' }, [
      DN.h('div', { html: DN.icon('layers'), style: 'width:26px;height:26px;color:var(--primary,#1890ff);' }),
      DN.h('div', {}, [
        DN.h('div', { text: '天工·自由意志数据智能体', style: 'font-size:16px;font-weight:650;color:var(--text-primary);' }),
        DN.h('div', { id: 'aiToolsHint', text: '在护栏内自主串联只读工具，逐道工序可复核', style: 'font-size:12px;color:var(--text-muted);margin-top:2px;' })
      ])
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
    sendBtn = DN.h('button', { class: 'btn btn-primary', text: '发送', style: 'flex:0 0 auto;height:40px;padding:0 22px;', onclick: send });
    var inputBar = DN.h('div', { style: 'flex:0 0 auto;padding:12px 24px;border-top:1px solid var(--border,#e5e6eb);display:flex;gap:10px;align-items:flex-end;background:var(--bg-card,#fff);' }, [inputEl, sendBtn]);
    root.appendChild(inputBar);

    built = true;
    loadToolsHint();
  }

  function welcome() {
    var box = DN.h('div', { style: 'text-align:center;color:var(--text-muted);padding:36px 16px;font-size:13px;line-height:1.9;' });
    box.appendChild(DN.h('div', { html: DN.icon('layers'), style: 'width:40px;height:40px;margin:0 auto 10px;color:var(--primary,#1890ff);opacity:.8;' }));
    box.appendChild(DN.h('div', { text: '我是天工司辰，可自主调用治理/质量/血缘等只读工具帮你排障与评估。' }));
    box.appendChild(DN.h('div', { text: '试试：「看下治理总览，再查 dwd_order 的下游影响」', style: 'color:var(--primary,#1890ff);margin-top:6px;' }));
    return box;
  }

  function loadToolsHint() {
    DN.get('/api/ai/agent/tools').then(function (d) {
      var hint = document.getElementById('aiToolsHint');
      if (hint && d && d.count != null) hint.textContent = '已装备 ' + d.count + ' 个只读工具 · 在护栏内自主编排、逐道工序可复核';
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
