package com.datanote.platform.ai.agent.engine;

import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.vector.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool RAG(借鉴 Tool RAG / MCP-Zero): 把工具 {name+description} 向量化, 按 query 语义打分,
 * 让 tool_search 按"意图"而非"关键词子串"发现工具(关键字搜索易漏语义匹配, 是 agent 选错工具的根因之一)。
 * 降级铁律: EmbeddingService 不可用 / 嵌入失败 → 返回 null, 调用方回退关键字打分, 绝不因此中断。
 * 工具集启动后基本不变, 嵌入懒加载构建一次后内存缓存(71 工具量级, 不入 Qdrant 避免污染 dn_meta 集合)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRetrievalService {

    private final EmbeddingService embeddingService;

    private volatile Map<String, float[]> toolVecs;   // toolName -> 嵌入向量
    private volatile boolean built = false;

    /**
     * query 与各候选工具的余弦相似度(0~1)。EmbeddingService 不可用或构建失败 → 返回 null(调用方回退关键字)。
     */
    public Map<String, Double> semanticScores(String query, Collection<AiTool> tools) {
        if (query == null || query.trim().isEmpty() || tools == null || tools.isEmpty()) return null;
        if (!embeddingService.isAvailable()) return null;
        ensureBuilt(tools);
        Map<String, float[]> vecs = toolVecs;
        if (vecs == null || vecs.isEmpty()) return null;
        float[] q;
        try {
            q = embeddingService.embed(query);
        } catch (Exception e) {
            return null;
        }
        if (q == null) return null;
        Map<String, Double> out = new HashMap<>();
        for (AiTool t : tools) {
            if (t == null) continue;
            float[] v = vecs.get(t.name());
            if (v != null) out.put(t.name(), cosine(q, v));
        }
        return out.isEmpty() ? null : out;
    }

    /** 懒加载构建工具嵌入(一次, 内存缓存)。批量嵌入降调用数; 任一异常则放弃(留 null 回退关键字)。 */
    private void ensureBuilt(Collection<AiTool> tools) {
        if (built && toolVecs != null) return;
        synchronized (this) {
            if (built && toolVecs != null) return;
            try {
                List<AiTool> list = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                for (AiTool t : tools) {
                    if (t == null) continue;
                    list.add(t);
                    // 工具名下划线转空格 + 描述, 提升语义可分性
                    texts.add(t.name().replace('_', ' ') + ". " + (t.description() == null ? "" : t.description()));
                }
                List<float[]> embs = embeddingService.embedBatch(texts);
                if (embs == null || embs.size() != list.size()) {
                    log.warn("Tool RAG 工具嵌入数量不匹配, 回退关键字检索");
                    return;
                }
                Map<String, float[]> m = new HashMap<>();
                for (int i = 0; i < list.size(); i++) {
                    if (embs.get(i) != null) m.put(list.get(i).name(), embs.get(i));
                }
                toolVecs = m;
                built = true;
                log.info("Tool RAG 工具嵌入构建完成: {} 个工具", m.size());
            } catch (Exception e) {
                log.warn("Tool RAG 工具嵌入构建失败, 回退关键字检索: {}", e.getMessage());
            }
        }
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
