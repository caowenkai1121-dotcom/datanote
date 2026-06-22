package com.datanote.platform.ai.agent.engine;

import com.datanote.platform.ai.AiAssistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 上下文压缩(借鉴 hermes ContextCompressor): trace 超阈值时, 保护近期步骤逐字保留, 早期步骤经辅助 LLM
 * 压成结构化摘要替换, 防长任务/多轮续跑 context 膨胀超窗。
 *
 * 安全/健壮:
 *  - 摘要落库前 redactSecrets 脱敏(禁写凭据红线)。
 *  - 防抖动: 节省不足 10% 则放弃本次压缩(避免空转烧 token)。
 *  - 摘要失败(LLM 抖/限流)静默退回原文, 绝不丢中段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextCompressorService {

    private final AiAssistService aiAssistService;

    /** trace 超此字符数触发压缩(可配, 默认 120000): 放到模型量级再压, 让上下文尽量大、保留更多语义; 真超窗有 forceCompress 兜底 */
    @Value("${datanote.ai.compress-threshold:120000}")
    private int compressThreshold;
    /** 保护近期比例: 保后 45% 逐字, 压前 55% 为摘要 */
    private static final double PROTECT_TAIL_RATIO = 0.45;
    /** 保护最早 K 字符逐字(初始计划/首批发现, primacy; OpenHands keep_first): 只摘要"中段"被遗忘部分 */
    private static final int HEAD_KEEP_CHARS = 1500;
    /** 早期段短于此字符不值得摘要 */
    private static final int MIN_HEAD_CHARS = 1200;
    /** 防抖动: 距上次压缩新增不足此字符数则跳过 */
    private static final int MIN_GROWTH = 3000;
    /** 压缩结果须至少省到原文此比例以下才采纳(否则视为无效压缩, 防抖动) */
    private static final double MAX_KEEP_RATIO = 0.9;

    /** 按需压缩 st.trace(就地替换)。返回是否实际压缩。 */
    public boolean maybeCompress(AgentState st, String goal) {
        if (st == null || st.trace == null) return false;
        String t = st.trace.toString();
        if (t.length() < compressThreshold) return false;
        if (!aiAssistService.isAvailable()) return false;
        // 防抖动: 上次压完后没怎么增长, 不再压
        if (st.lastCompressedLen > 0 && (t.length() - st.lastCompressedLen) < MIN_GROWTH) return false;

        String compressed = compress(t, goal);
        if (compressed == null) {
            st.lastCompressedLen = t.length(); // 摘要失败: 标记防立即重试, 保留原文
            return false;
        }
        if (compressed.length() >= (int) (t.length() * MAX_KEEP_RATIO)) {
            st.lastCompressedLen = t.length(); // 节省<10%: 放弃, 防抖动
            return false;
        }
        st.trace = new StringBuilder(compressed);
        st.compressionCount++;
        st.lastCompressedLen = compressed.length();
        log.info("[compress] trace {} → {} 字符 (第{}次, session={})",
                t.length(), compressed.length(), st.compressionCount,
                st.session == null ? "?" : st.session.getSessionId());
        return true;
    }

    /** 强制压缩(反应式超窗恢复用): 无视阈值与防抖动, 尽力压一次。返回是否压缩。 */
    public boolean forceCompress(AgentState st, String goal) {
        if (st == null || st.trace == null || !aiAssistService.isAvailable()) return false;
        String t = st.trace.toString();
        String compressed = compress(t, goal);
        if (compressed == null || compressed.length() >= t.length()) return false;
        st.trace = new StringBuilder(compressed);
        st.compressionCount++;
        st.lastCompressedLen = compressed.length();
        log.info("[compress] 强制压缩 trace {} → {} 字符 (session={})", t.length(), compressed.length(),
                st.session == null ? "?" : st.session.getSessionId());
        return true;
    }

    /**
     * 三段式(OpenHands keep_first + lost-in-the-middle): 保首 K 逐字(初始计划/发现) + 摘要中段 + 保尾逐字(近期)。
     * 中段太短退化为"保尾 + 摘要其余"。不可压/失败返 null。
     */
    private String compress(String trace, String goal) {
        int tailCut = (int) (trace.length() * (1.0 - PROTECT_TAIL_RATIO));
        if (tailCut < MIN_HEAD_CHARS) return null; // 待压段太短, 不值得
        int nlT = trace.indexOf('\n', tailCut);
        if (nlT > 0 && nlT < trace.length() - 1) tailCut = nlT + 1;
        String tail = trace.substring(tailCut);

        // 首段保护: 取 min(HEAD_KEEP_CHARS, 待压段) 逐字; 仅当中段(headEnd..tailCut)够长才单独保首
        int headEnd = Math.min(HEAD_KEEP_CHARS, tailCut);
        int nlH = trace.indexOf('\n', headEnd);
        if (nlH > 0 && nlH < tailCut) headEnd = nlH + 1;
        boolean keepHead = (tailCut - headEnd) >= MIN_HEAD_CHARS;

        String middle = keepHead ? trace.substring(headEnd, tailCut) : trace.substring(0, tailCut);
        String summary = summarize(middle, goal);
        if (summary == null || summary.trim().isEmpty()) return null;
        summary = AgentTextUtil.redactSecrets(AgentTextUtil.sanitize(summary)).trim();

        StringBuilder sb = new StringBuilder();
        if (keepHead) {
            sb.append("（以下为任务最初的计划与首批发现，原文保留）\n").append(trace, 0, headEnd).append('\n');
        }
        sb.append("（以下为中段执行步骤的压缩摘要，保留关键结论与已知事实）\n").append(summary)
                .append("\n\n（以下为近期步骤原文）\n").append(tail);
        return sb.toString();
    }

    /** 辅助 LLM 把早期步骤压成简明摘要; 失败返 null。 */
    private String summarize(String head, String goal) {
        try {
            String prompt = "你是 agent 的『上下文压缩器』(caveman 式: 去废话留事实, 最少字承载最大语义)。"
                    + "下面是某次任务早期已执行的步骤与工具结果。请压成 caveman 式中文摘要, 严格遵守:\n"
                    + "1. 【完整保留全部技术事实, 不得丢失语义】: 库名/表名/字段名(及类型/注释)、已创建对象的ID与名称、关联键、数值、口径、已确认结论、待办与卡点——这些一个都不能少。\n"
                    + "2. 只砍【客套/铺垫/重复/过程性废话/语气词】, 用短句与符号(→ / | 表形式)压缩表达; 同类信息合并但不丢条目。\n"
                    + "3. 字段清单等结构化信息用『表名: f1(类型)[注释], f2…』紧凑形式完整列出, 不得只留前N个或写『等若干字段』。\n"
                    + "4. 绝不包含任何密钥/密码/口令/token/连接串等敏感信息。\n"
                    + "5. 只输出摘要正文, 不要前后缀解释。\n\n"
                    + "【任务目标】" + cap(goal, 400) + "\n\n【早期步骤】\n" + cap(head, 60000);
            String raw = aiAssistService.chat(prompt, "");
            if (ErrorClassifier.classify(raw) != ErrorClassifier.Action.RETRY && isErr(raw)) return null;
            return isErr(raw) ? null : raw;
        } catch (Exception e) {
            log.warn("[compress] 摘要失败: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isErr(String raw) {
        return raw == null || raw.startsWith("AI 功能未配置") || raw.startsWith("AI 请求失败") || raw.equals("AI 返回格式异常");
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…(截断)" : s;
    }
}
