package com.datanote.platform.ai.agent.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.platform.ai.AiAssistService;
import com.datanote.platform.ai.agent.mapper.DnAiMemorySkillMapper;
import com.datanote.platform.ai.agent.model.DnAiMemorySkill;
import com.datanote.platform.ai.vector.EmbeddingService;
import com.datanote.platform.ai.vector.VectorStoreClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 自学习记忆服务（M-W3）：
 *  - learn(): 每会话结束后【异步】把本次会话蒸馏为一条可复用经验, 入库 dn_ai_memory_skill, 并向量化 upsert(kind=memory)。
 *  - recall(): 下次任务前语义召回相关经验, 渲染文本供 PromptBuilder 注入。
 *
 * 安全铁律: 记忆只是【只读上下文】, 绝不参与 Guardrail/ApprovalGate 判定, 不能据此跳过任何审批或放宽护栏(防记忆投毒提权)。
 * 任何异常/降级都静默返回, 绝不拖垮主循环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMemoryService {

    private final DnAiMemorySkillMapper memoryMapper;
    private final EmbeddingService embeddingService;
    private final VectorStoreClient vectorStoreClient;
    private final AiAssistService aiAssistService;
    private final ObjectMapper objectMapper;

    private static final int CONTENT_CAP = 600;
    private static final int RECALL_TEXT_CAP = 900; // 容纳技能的有序步骤(R89)

    // ===================== 召回 =====================

    /** 语义召回相关经验, 渲染为 prompt 文本; 无命中/异常返 null(降级)。优先向量, 退化为 owner+近因。 */
    public String recall(String query, String owner, int topK) {
        try {
            List<DnAiMemorySkill> hits = recallVector(query, owner, topK);
            if (hits == null || hits.isEmpty()) {
                hits = recallFallback(owner, topK); // 向量不可用/无命中 → 近因兜底
            }
            if (hits == null || hits.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            List<Long> hitIds = new ArrayList<>();
            for (DnAiMemorySkill m : hits) {
                if (m == null || m.getContent() == null) continue;
                boolean isSkill = "skill".equals(m.getType());
                boolean isReview = "review".equals(m.getType());
                sb.append(isSkill ? "- 【操作技能" : (isReview ? "- 【复盘改进" : "- 【经验"));
                if (m.getTitle() != null) sb.append("·").append(m.getTitle());
                sb.append("】");
                if (m.getTriggerHint() != null && !m.getTriggerHint().trim().isEmpty()) {
                    sb.append("(适用: ").append(cap(m.getTriggerHint(), 60)).append(") ");
                }
                // 技能=可照做的有序步骤(放宽呈现长度); 经验=简短事实
                sb.append(cap(m.getContent(), isSkill ? 320 : 200));
                sb.append('\n');
                if (m.getId() != null) hitIds.add(m.getId());
                if (sb.length() >= RECALL_TEXT_CAP) break;
            }
            bumpHits(hitIds);
            String s = sb.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            log.warn("[memory] recall 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 向量召回(kind=memory), 命中 payload 取 mysqlId 回查实体; 同租户(owner/全局)优先。 */
    private List<DnAiMemorySkill> recallVector(String query, String owner, int topK) {
        if (!embeddingService.isAvailable() || !vectorStoreClient.available()) return null;
        float[] vec = embeddingService.embed(query);
        if (vec == null) return null;
        List<Map<String, Object>> raw = vectorStoreClient.search(vec, "memory", Math.max(topK, 5));
        if (raw == null || raw.isEmpty()) return null;
        List<DnAiMemorySkill> out = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            Object pObj = r.get("payload");
            if (!(pObj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) pObj;
            Object po = p.get("owner");
            // 同租户隔离: 仅召回本人或全局经验(匿名单用户态放行全部)
            if (!ownerVisible(po, owner)) continue;
            Object mid = p.get("mysqlId");
            DnAiMemorySkill m = null;
            if (mid != null) {
                try { m = memoryMapper.selectById(Long.valueOf(String.valueOf(mid))); } catch (Exception ignore) {}
            }
            if (m == null) { // 向量在但行被删 → 用 payload 兜底渲染
                m = new DnAiMemorySkill();
                m.setTitle(str(p.get("title")));
                m.setContent(str(p.get("content")));
                m.setTriggerHint(str(p.get("trigger")));
            }
            if (m.getContent() != null && !"archived".equals(m.getStatus())) out.add(m);
            if (out.size() >= topK) break;
        }
        return out;
    }

    /** 兜底召回: 本人(或全局)active 经验, 按命中次数+近因排序。 */
    private List<DnAiMemorySkill> recallFallback(String owner, int topK) {
        QueryWrapper<DnAiMemorySkill> qw = new QueryWrapper<DnAiMemorySkill>()
                .eq("status", "active");
        if (owner != null && !owner.trim().isEmpty() && !"anonymous".equals(owner)) {
            qw.and(w -> w.eq("owner", owner).or().isNull("owner"));
        }
        qw.orderByDesc("hit_count").orderByDesc("updated_at").last("LIMIT " + Math.max(topK, 1));
        return memoryMapper.selectList(qw);
    }

    private boolean ownerVisible(Object payloadOwner, String owner) {
        if (owner == null || owner.trim().isEmpty() || "anonymous".equals(owner)) return true;
        if (payloadOwner == null) return true; // 全局经验
        return owner.equals(String.valueOf(payloadOwner));
    }

    private void bumpHits(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        try {
            // 一条批量 UPDATE 自增命中次数, 替代逐条 select+update(消除 N+1, 在对话首字节前的同步路径上)
            memoryMapper.update(null, new UpdateWrapper<DnAiMemorySkill>()
                    .in("id", ids)
                    .setSql("hit_count = COALESCE(hit_count, 0) + 1")
                    .set("updated_at", LocalDateTime.now()));
        } catch (Exception ignore) {
        }
    }

    // ===================== 沉淀(异步) =====================

    /** 异步: 把一次完整会话蒸馏成一条可复用经验并入库+向量化。失败静默。走有界执行器防堆积。 */
    @Async("aiLearnExecutor")
    public void learn(String sessionId, String goal, String owner, String traceSummary, String finalAnswer) {
        try {
            if (!aiAssistService.isAvailable()) return;
            if (goal == null || goal.trim().isEmpty()) return;
            String prompt = buildDistillPrompt(goal, traceSummary, finalAnswer);
            String raw = aiAssistService.chat(prompt, "");
            if (raw == null) return;
            JsonNode j = extractJson(raw);
            if (j == null) return;
            if (!j.path("worthSaving").asBoolean(false)) {
                log.info("[memory] 会话无沉淀价值, 跳过: session={}", sessionId);
                return;
            }
            String type = clean(j.path("type").asText(null));
            type = "skill".equals(type) ? "skill" : "memory"; // 仅 memory/skill, 默认 memory
            String title = clean(j.path("title").asText(null));
            String content = clean(j.path("content").asText(null));
            String trigger = clean(j.path("trigger").asText(null));
            if (content == null || content.trim().isEmpty()) return; // 空/空白 content → 不入库垃圾记忆
            int contentCap = "skill".equals(type) ? 900 : CONTENT_CAP; // 技能含有序步骤, 放宽上限

            // 去重: 同 owner 同 title 已有 active → 仅 bump, 不重复入库(防 100 轮迭代刷屏)
            if (title != null) {
                DnAiMemorySkill dup = memoryMapper.selectOne(new QueryWrapper<DnAiMemorySkill>()
                        .eq("title", title).eq("status", "active")
                        .apply(owner != null, "owner = {0}", owner).last("LIMIT 1"));
                if (dup != null) {
                    dup.setHitCount((dup.getHitCount() == null ? 0 : dup.getHitCount()) + 1);
                    dup.setUpdatedAt(LocalDateTime.now());
                    memoryMapper.updateById(dup);
                    return;
                }
            }

            DnAiMemorySkill m = new DnAiMemorySkill();
            m.setType(type);
            // 落库前确定性脱敏(防模型把 trace 中凭据回带进持久化记忆 + 向量化扩散)
            m.setTitle(cap(AgentTextUtil.redactSecrets(title), 240));
            m.setContent(cap(AgentTextUtil.redactSecrets(content), contentCap));
            m.setTriggerHint(cap(AgentTextUtil.redactSecrets(trigger), 240));
            m.setOwner(owner);
            m.setStatus("active");
            m.setHitCount(0);
            m.setCreatedAt(LocalDateTime.now());
            m.setUpdatedAt(LocalDateTime.now());
            memoryMapper.insert(m); // 入库入表(业主: AI 数据都需入库)

            indexVector(m); // 向量化(可用才做, 不可用纯靠 MySQL 近因召回)
            log.info("[memory] 沉淀经验: id={}, title={}", m.getId(), m.getTitle());
        } catch (Exception e) {
            log.warn("[memory] learn 失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 后台复盘(W6 自我改进): 复杂会话异步复盘执行质量, 找可改进点(绕路/冗余/遗漏/更优路径),
     * 沉淀为 type=review 记忆, 未来 recall 注入提醒"下次更好"。区别于 learn(沉淀成功经验)。失败静默。
     */
    @Async("aiLearnExecutor")
    public void reviewAsync(String sessionId, String goal, String owner, String traceSummary, String finalAnswer) {
        try {
            if (!aiAssistService.isAvailable() || goal == null || goal.trim().isEmpty()) return;
            String raw = aiAssistService.chat(buildReviewPrompt(goal, traceSummary, finalAnswer), "");
            if (raw == null) return;
            JsonNode j = extractJson(raw);
            if (j == null || !j.path("worthSaving").asBoolean(false)) return;
            String title = clean(j.path("title").asText(null));
            String pitfall = clean(j.path("pitfall").asText(null));
            if (pitfall == null || pitfall.isEmpty()) return;
            // 去重: 同 owner 同 title 的 review 已存 → 仅 bump
            if (title != null) {
                DnAiMemorySkill dup = memoryMapper.selectOne(new QueryWrapper<DnAiMemorySkill>()
                        .eq("title", title).eq("type", "review").eq("status", "active")
                        .apply(owner != null, "owner = {0}", owner).last("LIMIT 1"));
                if (dup != null) {
                    dup.setHitCount((dup.getHitCount() == null ? 0 : dup.getHitCount()) + 1);
                    dup.setUpdatedAt(LocalDateTime.now());
                    memoryMapper.updateById(dup);
                    return;
                }
            }
            DnAiMemorySkill m = new DnAiMemorySkill();
            m.setType("review");
            m.setTitle(cap(AgentTextUtil.redactSecrets(title), 240));
            m.setContent(cap(AgentTextUtil.redactSecrets(pitfall), CONTENT_CAP));
            m.setTriggerHint(cap(AgentTextUtil.redactSecrets(clean(j.path("trigger").asText(null))), 240));
            m.setOwner(owner);
            m.setStatus("active");
            m.setHitCount(0);
            m.setCreatedAt(LocalDateTime.now());
            m.setUpdatedAt(LocalDateTime.now());
            memoryMapper.insert(m);
            indexVector(m);
            log.info("[review] 复盘改进沉淀: id={}, title={}", m.getId(), m.getTitle());
        } catch (Exception e) {
            log.warn("[review] 复盘失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    private String buildReviewPrompt(String goal, String trace, String finalAnswer) {
        return "你是 DataNote 智能体的『执行复盘员』。复盘本次任务执行质量, 找出【可改进点】(绕路/冗余或重复工具调用/遗漏关键步骤/有更优路径), 提炼一条下次可迁移的改进建议。\n"
                + "只输出一个 JSON, 不要多余文字: "
                + "{\"worthSaving\":true/false,\"title\":\"简短改进点标题\",\"trigger\":\"什么场景下注意\",\"pitfall\":\"下次怎样做更好(中文,120字内,可迁移)\"}\n"
                + "要求:\n1. 若本次执行已高效合理、无明显可改进处, worthSaving=false(不要硬挑)。\n"
                + "2. pitfall 写可迁移的改进做法, 不复述具体数值, 绝不含任何密钥/口令/token。\n\n"
                + "【会话目标】" + cap(AgentTextUtil.redactSecrets(goal), 500) + "\n"
                + "【执行轨迹(工具与结果摘要)】\n" + cap(trace == null ? "(无)" : AgentTextUtil.redactSecrets(trace), 2500) + "\n"
                + "【最终答复】" + cap(finalAnswer == null ? "" : AgentTextUtil.redactSecrets(finalAnswer), 600);
    }

    /** 向量化 upsert 到共享集合(kind=memory), 与资产同库异 kind, 召回时按 kind 过滤。 */
    private void indexVector(DnAiMemorySkill m) {
        try {
            if (!embeddingService.isAvailable() || !vectorStoreClient.available()) return;
            if (!vectorStoreClient.ensureCollection(embeddingService.dim())) return;
            String text = (m.getTitle() == null ? "" : m.getTitle() + " ")
                    + (m.getTriggerHint() == null ? "" : m.getTriggerHint() + " ")
                    + (m.getContent() == null ? "" : m.getContent());
            float[] vec = embeddingService.embed(text);
            if (vec == null) return;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", "memory");
            payload.put("type", m.getType());
            payload.put("mysqlId", m.getId());
            payload.put("title", m.getTitle());
            payload.put("content", m.getContent());
            payload.put("trigger", m.getTriggerHint());
            payload.put("owner", m.getOwner());
            List<Float> vlist = new ArrayList<>(vec.length);
            for (float f : vec) vlist.add(f);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", UUID.nameUUIDFromBytes(("memory:" + m.getId()).getBytes()).toString());
            point.put("vector", vlist);
            point.put("payload", payload);
            vectorStoreClient.upsert(java.util.Collections.singletonList(point));
        } catch (Exception e) {
            log.warn("[memory] 向量化失败 id={}: {}", m.getId(), e.getMessage());
        }
    }

    private String buildDistillPrompt(String goal, String trace, String finalAnswer) {
        return "你是 DataNote 智能体的『经验沉淀器』。请从本次会话中提炼【一条】对未来同类任务可复用的经验或操作技能。\n"
                + "只输出一个 JSON, 不要任何多余文字, 格式: "
                + "{\"worthSaving\":true/false,\"type\":\"memory|skill\",\"title\":\"简短标题\",\"trigger\":\"什么场景下用得上\",\"content\":\"...\"}\n"
                + "要求:\n"
                + "1. 若本次会话琐碎/无信息量/纯闲聊/纯失败无结论, worthSaving=false。\n"
                + "2. type=skill: 本次体现了一套【可复用的多步操作流程】(固定套路/标准做法, 如『建ODS表→建同步任务→配质量规则』),"
                + " content 写成有序步骤(1) 2) 3)…), 每步含关键动作与注意点 —— 可被未来直接照做的操作手册。\n"
                + "   type=memory: 只是一条结论/事实/坑, content 写一两句可迁移的方法或注意点即可。\n"
                + "3. content 写可迁移的内容, 不要复述用户原话, 不要写一次性的具体数值。\n"
                + "4. 绝不包含任何密钥/密码/口令/token 等敏感信息。\n\n"
                // 确定性脱敏: 不让原文凭据进 LLM(纵深防御, 不只靠上面第4条文字约束)
                + "【会话目标】" + cap(AgentTextUtil.redactSecrets(goal), 500) + "\n"
                + "【执行轨迹(工具与结果摘要)】\n" + cap(trace == null ? "(无工具调用)" : AgentTextUtil.redactSecrets(trace), 2500) + "\n"
                + "【最终答复】" + cap(finalAnswer == null ? "" : AgentTextUtil.redactSecrets(finalAnswer), 600);
    }

    /** 从 LLM 回复中抽取首个完整 JSON 对象。 */
    private JsonNode extractJson(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return objectMapper.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.trim();
        return (s.isEmpty() || "null".equals(s)) ? null : s;
    }

    private static String cap(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
