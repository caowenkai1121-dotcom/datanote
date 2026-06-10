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

    /** trace 超此字符数触发压缩(可配, 默认 12000) */
    @Value("${datanote.ai.compress-threshold:18000}")
    private int compressThreshold;
    /** 保护近期比例: 保后 45% 逐字, 压前 55% 为摘要 */
    private static final double PROTECT_TAIL_RATIO = 0.45;
    /** 早期段短于此字符不值得摘要 */
    private static final int MIN_HEAD_CHARS = 1200;
    /** 防抖动: 距上次压缩新增不足此字符数则跳过 */
    private static final int MIN_GROWTH = 3000;

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
        if (compressed.length() >= (int) (t.length() * 0.9)) {
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

    /** 按字符比例: 压前 ~55% 为摘要 + 保后 ~45% 逐字(适配"少量长行"的 trace 结构)。不可压/失败返 null。 */
    private String compress(String trace, String goal) {
        int cut = (int) (trace.length() * (1.0 - PROTECT_TAIL_RATIO));
        if (cut < MIN_HEAD_CHARS) return null; // 早期段太短, 不值得
        // 尽量在换行处切, 避免切断一行
        int nl = trace.indexOf('\n', cut);
        if (nl > 0 && nl < trace.length() - 1) cut = nl + 1;
        String head = trace.substring(0, cut);
        String tail = trace.substring(cut);

        String summary = summarize(head, goal);
        if (summary == null || summary.trim().isEmpty()) return null;
        summary = AgentTextUtil.redactSecrets(AgentTextUtil.sanitize(summary)).trim();

        return "（以下为早期执行步骤的压缩摘要，保留关键结论与已知事实）\n"
                + summary + "\n\n（以下为近期步骤原文）\n" + tail;
    }

    /** 辅助 LLM 把早期步骤压成简明摘要; 失败返 null。 */
    private String summarize(String head, String goal) {
        try {
            String prompt = "你是 agent 的『上下文压缩器』。下面是某次任务早期已执行的步骤与工具结果。"
                    + "请压成简明中文摘要, 严格遵守:\n"
                    + "1. 逐字保留对完成【任务目标】仍关键的: 已查明的事实/数据、已创建对象的ID与名称、已确认的结论、待办与卡点。\n"
                    + "2. 去重与冗余, 合并同类信息; 一次性的大段数据只留关键数值。\n"
                    + "3. 绝不包含任何密钥/密码/口令/token/连接串等敏感信息。\n"
                    + "4. 只输出摘要正文, 不要前后缀解释。\n\n"
                    + "【任务目标】" + cap(goal, 400) + "\n\n【早期步骤】\n" + cap(head, 6000);
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
