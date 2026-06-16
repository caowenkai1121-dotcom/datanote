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
  var flowEl = null, inputEl = null, sendBtn = null, inputBarEl = null, built = false;

  function groupColor(g) {
    return ({ gov: 'var(--chart-1)', quality: 'var(--chart-6)', lineage: 'var(--chart-3)', sync: 'var(--chart-4)', metadata: 'var(--chart-2)' })[g] || 'var(--chart-5)';
  }

  // 用户气泡
  function userBubble(text) {
    return DN.h('div', { class: 'dn-ai-bubble user', style: 'width:fit-content;white-space:pre-wrap;', text: text });
  }

  // 助手终答气泡（识别 [表:库.表]/[规则:#id]/[任务:#id] token 渲染可点深链 chip, XSS 安全用 DN.h text）
  function assistantBubble(text, tone) {
    var inner = DN.h('div', { class: 'ai-md dn-ai-bubble assistant' + (tone === 'err' ? ' err' : ''), style: 'width:fit-content;overflow-x:auto;' });
    renderMarkdown(inner, String(text == null ? '' : text));
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
        var buf = []; i++;
        while (i < lines.length && !/^```/.test(lines[i].trim())) { buf.push(lines[i]); i++; }
        i++; // 跳过收尾 ```
        var pre = DN.h('pre', { style: 'background:var(--bg-main);border:1px solid var(--divider);border-radius:var(--radius-lg);padding:10px 12px;overflow-x:auto;margin:8px 0;' });
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
            var td = DN.h(ri === 0 ? 'th' : 'td', { style: 'border:1px solid var(--border);padding:5px 9px;text-align:left;' + (ri === 0 ? 'background:var(--bg-main);font-weight:600;' : '') });
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
    var card = DN.card({ title: (step.skillName || '工具') , icon: 'layers' });
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
      card.body.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);font-style:italic;margin-bottom:6px;padding:6px 10px;border-left:2px solid ' + col + ';background:var(--bg-body);border-radius:var(--radius);', text: '💭 ' + step.thinkContent }));
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
    var title = '📋 ' + (p.db ? p.db + '.' : '') + (p.table || '表') + ' 数据预览（' + (pv.returned != null ? pv.returned : rows.length) + ' 行）';
    var card = DN.h('div', { style: 'margin:8px 0;border:1px solid var(--border);border-radius:var(--radius-lg);background:var(--bg-card);overflow:hidden;' });
    card.appendChild(DN.h('div', { text: title, style: 'padding:8px 12px;font-weight:600;font-size:12.5px;color:var(--text-primary);background:var(--bg-main);border-bottom:1px solid var(--divider);' }));
    if (!rows.length) { card.appendChild(DN.h('div', { text: '（表中无数据行）', style: 'padding:10px 12px;color:var(--text-muted);font-size:12px;' })); return card; }
    var scroll = DN.h('div', { style: 'overflow:auto;max-height:340px;' });
    var tbl = DN.h('table', { style: 'border-collapse:collapse;width:100%;font-size:12px;' });
    var thr = DN.h('tr', {});
    thr.appendChild(DN.h('th', { text: '#', style: 'border:1px solid var(--border);padding:5px 8px;background:var(--bg-main);font-weight:600;position:sticky;top:0;z-index:1;color:var(--text-muted);' }));
    cols.forEach(function (c) { thr.appendChild(DN.h('th', { text: String(c), style: 'border:1px solid var(--border);padding:5px 9px;text-align:left;background:var(--bg-main);font-weight:600;white-space:nowrap;position:sticky;top:0;z-index:1;' })); });
    tbl.appendChild(thr);
    rows.forEach(function (row, ri) {
      var tr = DN.h('tr', {});
      tr.appendChild(DN.h('td', { text: String(ri + 1), style: 'border:1px solid var(--border);padding:5px 8px;color:var(--text-muted);text-align:right;' }));
      (row || []).forEach(function (v) { tr.appendChild(DN.h('td', { text: v == null ? '∅' : String(v), title: v == null ? '' : String(v), style: 'border:1px solid var(--border);padding:5px 9px;white-space:nowrap;max-width:280px;overflow:hidden;text-overflow:ellipsis;' + (v == null ? 'color:var(--text-muted);' : '') })); });
      tbl.appendChild(tr);
    });
    scroll.appendChild(tbl); card.appendChild(scroll);
    return card;
  }
  function renderPreviews(steps) {
    (steps || []).forEach(function (s) { if (s && s.resultData) { var p = extractPreview(s.resultData); if (p) flowEl.appendChild(dataGridCard(p)); } });
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
    create_project: '准备建项目', create_script: '准备建脚本'
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
    var th = thinking(); flowEl.appendChild(th); scrollBottom();
    var s0 = document.getElementById('aiThinkSub'); if (s0) s0.textContent = '· 正在理解你的问题…';
    var stopped = false;
    var t0 = Date.now();
    function poll() {
      if (stopped) return;
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
      }).catch(function () {}).then(function () { if (!stopped) setTimeout(poll, 1500); });
    }
    setTimeout(poll, 1200);
    return DN.post(url, body).then(function (res) {
      stopped = true; if (th.parentNode) th.parentNode.removeChild(th);
      if (ep !== _epoch) { dropStale(); return res; } // 旧会话响应: 丢弃渲染, 不写 saveSid, 不碰锁
      setSending(false);
      renderTurn(res || {}, Math.round((Date.now() - t0) / 1000)); loadFiles(); scrollBottom();
      return res;
    }).catch(function (e) {
      stopped = true; if (th.parentNode) th.parentNode.removeChild(th);
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
        var row = DN.h('div', { class: 'dn-ai-opt' + (selected ? ' selected' : '') });
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
        row.onclick = function () {
          if (multi) { if (picks[idx].set[o.label]) delete picks[idx].set[o.label]; else picks[idx].set[o.label] = true; }
          else { picks[idx].set = {}; picks[idx].set[o.label] = true; }
          render();
        };
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

  function scrollBottom() { if (flowEl) flowEl.scrollTop = flowEl.scrollHeight; }

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
  }

  // 统一发送锁: send / 决策卡answer / 审批resume 三入口共用; 运行中按钮变"⏹ 停止"(保持可点), 输入框可用走插话
  function setSending(on) {
    sending = !!on;
    if (!sendBtn) return;
    sendBtn.disabled = false;
    sendBtn.textContent = sending ? '⏹ 停止' : '发送';
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
    flowEl.appendChild(userBubble(msg));
    inputEl.value = ''; inputEl.style.height = '';   // 发送后高度复位(配合自动增高)
    runRequest('/api/ai/agent/chat', { message: msg, ctx: pendingCtx, model: selectedModel || undefined })
      .then(function () {}).catch(function () {}).then(function () { if (inputEl) inputEl.focus(); });
  }

  // 渲染一轮结果(对话/恢复共用): 工具卡 + 终答; 若挂起待审批则出审批卡
  function renderTurn(res, elapsed) {
    sessionId = res.sessionId || sessionId;
    saveSid(sessionId);
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
    if (res.previews && res.previews.length) {
      res.previews.forEach(function (d) { if (d && d._preview && d._preview.columns) flowEl.appendChild(dataGridCard({ pv: d._preview, db: d.db, table: d.table })); });
    } else {
      renderPreviews(res.steps);
    }
    var tone = res.status === 'blocked' ? 'err' : null;
    flowEl.appendChild(assistantBubble(res.finalAnswer || '（无答复）', tone));
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
    var head = (elapsed != null ? '✅ 已处理 ' + fmtDur(elapsed) + ' · ' : '🔧 ') + '执行了 ' + steps.length + ' 步 · 点击查看过程';
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
      card.appendChild(DN.h('div', { style: 'font-weight:600;color:var(--warning-text);margin-bottom:6px;', text: '⚠ 写操作待人工审批: ' + (ap.skillName || '') + '  (风险 ' + (ap.riskLevel || '') + ')' }));
      if (ap.argsJson) card.appendChild(DN.h('div', { style: 'font-size:12px;color:var(--text-muted);margin-bottom:8px;word-break:break-all;', text: '参数: ' + ap.argsJson }));
      var btns = DN.h('div', { style: 'display:flex;gap:8px;' });
      var okBtn = DN.h('button', { class: 'btn btn-primary btn-sm', text: '批准并继续', 'data-perm': 'assistant:approve', style: 'background:var(--primary);color:var(--text-inverse);border-color:var(--primary);' });
      var noBtn = DN.h('button', { class: 'btn btn-sm', text: '拒绝', 'data-perm': 'assistant:approve' });
      okBtn.onclick = function () {
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
      };
      noBtn.onclick = function () {
        var ep = _epoch; // 捕获发起纪元: 新会话后不再往新消息流写旧拒绝提示
        okBtn.disabled = true; noBtn.disabled = true;
        DN.post('/api/ai/agent/approval/' + ap.id + '/decide', { decision: 'rejected' })
          .then(function () { card.remove(); if (ep !== _epoch) return; flowEl.appendChild(assistantBubble('已拒绝该写操作。', 'err')); scrollBottom(); })
          .catch(function (e) { DN.toast('拒绝失败：' + (e && e.message ? e.message : e), 'err'); okBtn.disabled = false; noBtn.disabled = false; });
      };
      btns.appendChild(okBtn); btns.appendChild(noBtn); card.appendChild(btns);
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
  function renderMemories(body) {
    DN.get('/api/ai/agent/memories').then(function (list) {
      body.innerHTML = '';
      var arr = list || [];
      if (!arr.length) { body.appendChild(DN.h('div', { text: '暂无沉淀经验。AI 完成带工具调用的任务后会自动学习。', style: 'color:var(--text-muted);font-size:13px;line-height:1.8;' })); return; }
      body.appendChild(DN.h('div', { text: '共 ' + arr.length + ' 条沉淀经验', style: 'font-size:12px;color:var(--text-muted);margin-bottom:8px;' }));
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
        body.appendChild(card);
      });
    }).catch(function (e) { body.innerHTML = ''; body.appendChild(DN.h('div', { text: '加载失败：' + (e && e.message ? e.message : e), style: 'color:var(--danger);font-size:13px;' })); });
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
          DN.h('span', { text: a.skillName || '?', style: 'font-weight:600;font-size:13px;color:var(--text-primary);' }),
          DN.h('span', { text: a.riskLevel || '', class: 'dn-ai-badge ' + (a.riskLevel === 'HIGH' ? 'err' : 'warn'), style: 'margin-left:8px;' })
        ]));
        card.appendChild(DN.h('pre', { text: a.argsJson || '{}', style: 'font-size:var(--fs-xs);color:var(--text-secondary);background:var(--bg-body);border-radius:var(--radius);padding:6px 8px;margin:6px 0;white-space:pre-wrap;word-break:break-all;max-height:120px;overflow:auto;' }));
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

    // 拖拽/点击上传区
    var dz = DN.h('div', { class: 'dn-ai-drop' });
    dz.appendChild(DN.h('div', { html: DN.icon('upload') || '⬆', style: 'font-size:26px;color:var(--primary);opacity:.8;margin-bottom:8px;display:flex;justify-content:center;' }));
    dz.appendChild(DN.h('div', { text: '点击或拖拽文件到此区域', style: 'font-size:13px;color:var(--text-regular);' }));
    dz.appendChild(DN.h('div', { text: '支持 xlsx/pdf/csv/txt/图片 等, ≤20MB', style: 'font-size:var(--fs-xs);color:var(--text-muted);margin-top:4px;' }));
    var fileInput = DN.h('input', { type: 'file', multiple: 'multiple', style: 'display:none;' });
    fileInput.onchange = function () { uploadFiles(fileInput.files); fileInput.value = ''; };
    dz.onclick = function () { fileInput.click(); };
    function dzOn() { dz.classList.add('is-drag'); }
    function dzOff() { dz.classList.remove('is-drag'); }
    dz.ondragover = function (e) { e.preventDefault(); dzOn(); };
    dz.ondragleave = function () { dzOff(); };
    dz.ondrop = function (e) { e.preventDefault(); dzOff(); if (e.dataTransfer && e.dataTransfer.files) uploadFiles(e.dataTransfer.files); };
    panel.appendChild(DN.h('div', { style: 'padding:0 18px;flex:0 0 auto;' }, [dz, fileInput]));

    panel.appendChild(DN.h('div', { text: '已上传文件', style: 'padding:14px 18px 6px;font-size:12.5px;font-weight:600;color:var(--text-regular);flex:0 0 auto;' }));
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

  function loadFiles() {
    if (!fileListEl) return;
    DN.get('/api/ai/agent/files').then(function (list) {
      fileListEl.innerHTML = '';
      var arr = list || [];
      if (!arr.length) { fileListEl.appendChild(DN.h('div', { text: '暂无文件。上传后在此查看与下载。', style: 'color:var(--text-muted);font-size:12px;line-height:1.8;' })); return; }
      arr.forEach(function (f) { fileListEl.appendChild(fileRow(f)); });
    }).catch(function () {});
  }

  function fileRow(f) {
    var agent = f.source === 'agent';
    var row = DN.h('div', { class: 'dn-ai-file' });
    var info = DN.h('div', { style: 'flex:1;min-width:0;' }, [
      DN.h('div', { text: (agent ? '🤖 ' : '📄 ') + (f.fileName || ''), title: f.fileName, style: 'font-size:12.5px;color:var(--text-primary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' }),
      DN.h('div', { text: fmtSize(f.size) + (agent ? ' · AI生成' : ''), style: 'font-size:var(--fs-xs);color:var(--text-muted);margin-top:2px;' })
    ]);
    var dl = DN.h('a', { href: '/api/ai/agent/files/' + f.id + '/download', title: '下载', text: '↓', style: 'flex:0 0 auto;text-decoration:none;color:var(--primary);font-size:17px;font-weight:700;padding:0 5px;' });
    var del = DN.h('span', { title: '删除', text: '✕', 'data-perm': 'assistant:use', style: 'flex:0 0 auto;cursor:pointer;color:var(--text-muted);font-size:13px;padding:0 4px;' });
    del.onclick = function () { DN.confirm('确认删除文件「' + (f.name || '') + '」？', { title: '删除文件', danger: true }).then(function (ok) { if (!ok) return; DN.post('/api/ai/agent/files/' + f.id + '/remove', {}).then(function () { loadFiles(); }).catch(function (e) { DN.toast('删除失败：' + (e && e.message ? e.message : e), 'err'); }); }); };
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
    var head = DN.h('div', { class: 'dn-ai-topbar' }, [
      DN.h('div', { html: DN.icon('layers'), style: 'width:26px;height:26px;font-size:26px;color:var(--primary);display:flex;' }),
      DN.h('div', { style: 'min-width:0;' }, [
        DN.h('div', { text: '天工·自由意志数据智能体', style: 'font-size:16px;font-weight:650;color:var(--text-primary);' }),
        DN.h('div', { id: 'aiToolsHint', text: '在护栏内自主编排工具，写操作经人工审批，逐道工序可复核', style: 'font-size:12px;color:var(--text-muted);margin-top:2px;' })
      ]),
      modelPicker(),
      DN.h('button', { class: 'btn btn-sm', text: '✚ 新会话', title: '清空当前对话, 开始新会话', style: 'flex:0 0 auto;', onclick: newSession }),
      DN.h('button', { class: 'btn btn-sm', text: '🧠 经验', title: 'AI 自学习记忆', style: 'flex:0 0 auto;', onclick: function () { openDrawer('memory'); } }),
      DN.h('button', { class: 'btn btn-sm', text: '⏰ 定时', title: '定时自治任务', style: 'flex:0 0 auto;', onclick: function () { openDrawer('cron'); } }),
      DN.h('button', { class: 'btn btn-sm', text: '🛡️ 审批', title: '待审批写操作', style: 'flex:0 0 auto;', onclick: function () { openDrawer('approval'); } })
    ]);
    rightCol.appendChild(head);

    // 对话流
    flowEl = DN.h('div', { class: 'dn-ai-chat' });
    flowEl.appendChild(welcome());
    rightCol.appendChild(flowEl);

    // 输入区
    inputEl = DN.h('textarea', { placeholder: '问我：看下治理总览；查 dwd_order 的下游影响；某表质量为什么下降…', rows: '2' });
    inputEl.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); if (sending) steer(); else send(); } // 运行中回车=插话引导
    });
    inputEl.addEventListener('input', function () { inputEl.style.height = 'auto'; inputEl.style.height = Math.min(inputEl.scrollHeight, 160) + 'px'; });   // 聊天输入随内容自动增高(≤160px)
    sendBtn = DN.h('button', { class: 'btn btn-primary', text: '发送', 'data-perm': 'assistant:use', style: 'flex:0 0 auto;height:40px;padding:0 22px;background:var(--primary);color:var(--text-inverse);border-color:var(--primary);', onclick: onSendClick });
    inputBarEl = DN.h('div', { class: 'dn-ai-inputbar' }, [inputEl, sendBtn]);
    rightCol.appendChild(inputBarEl);
    setTimeout(function () { try { inputEl.focus(); } catch (e) {} }, 80);   // 聊天UI: 进入即聚焦输入, 直接开问

    root.appendChild(rightCol);

    built = true;
    loadToolsHint();
    loadFiles();
    restoreHistory(); // 刷新后恢复上次会话历史
  }

  function welcome() {
    var box = DN.h('div', { style: 'text-align:center;color:var(--text-muted);padding:36px 16px;font-size:13px;line-height:1.9;' });
    box.appendChild(DN.h('div', { html: DN.icon('layers'), style: 'width:40px;height:40px;font-size:40px;margin:0 auto 10px;color:var(--primary);opacity:.8;display:flex;align-items:center;justify-content:center;' }));
    box.appendChild(DN.h('div', { text: '我是天工司辰，可自主调用治理/质量/血缘等工具排障评估，也能建项目/同步任务/表/规则/指标/脚本（写操作需你审批）。' }));
    box.appendChild(DN.h('div', { text: '试试：「看下治理总览，再查 dwd_order 的下游影响」 或 「建一个名为风控的项目」', style: 'color:var(--primary);margin-top:6px;' }));
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
