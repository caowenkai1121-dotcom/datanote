package com.datanote.platform.ai.agent.engine;

/**
 * LLM 错误分类(借鉴 hermes error_classifier 的 FailoverReason taxonomy, 收敛"到处 if str(e).contains")。
 * 把 AiAssistService.chat 返回的错误串映射为恢复动作: 限流退避 / 上下文超长压缩 / 鉴权失败 / 一般重试。
 */
public final class ErrorClassifier {

    private ErrorClassifier() {}

    public enum Action {
        /** 一般瞬时错误: 退避后重试 */
        RETRY,
        /** 限流/配额: 加长退避后重试 */
        RATE_LIMIT,
        /** 上下文超长: 先压缩上下文再重试 */
        CONTEXT_OVERFLOW,
        /** 鉴权/密钥失效: 重试无意义, 直接中止 */
        AUTH,
        /** AI 未配置/空: 直接中止 */
        ABORT
    }

    /** 按错误串分类。null/空 → ABORT(未配置或空响应不值得重试压缩)。 */
    public static Action classify(String raw) {
        if (raw == null) return ABORTorRetry(raw);
        String s = raw.toLowerCase();
        if (raw.startsWith("AI 功能未配置")) return Action.ABORT;
        if (raw.equals("AI 返回格式异常")) return Action.RETRY;
        // 限流/配额
        if (s.contains("429") || s.contains("rate limit") || s.contains("rate_limit")
                || s.contains("too many requests") || s.contains("限流") || s.contains("quota")
                || s.contains("requests per") || s.contains("overloaded")) {
            return Action.RATE_LIMIT;
        }
        // 上下文超长(各家措辞)
        if ((s.contains("context") && (s.contains("length") || s.contains("window") || s.contains("exceed") || s.contains("maximum")))
                || s.contains("maximum context") || s.contains("context_length_exceeded")
                || s.contains("too long") || s.contains("token") && s.contains("exceed")
                || (s.contains("上下文") && (s.contains("超") || s.contains("过长")))) {
            return Action.CONTEXT_OVERFLOW;
        }
        // 鉴权/密钥
        if (s.contains("401") || s.contains("403") || s.contains("unauthorized")
                || s.contains("authentication") || s.contains("invalid api key") || s.contains("api key")
                || s.contains("permission") || s.contains("forbidden")) {
            return Action.AUTH;
        }
        return Action.RETRY;
    }

    private static Action ABORTorRetry(String raw) {
        // 空响应: 一次性重试有意义(瞬时), 归 RETRY
        return Action.RETRY;
    }
}
