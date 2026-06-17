package com.datanote.platform.ai.agent.engine;

import com.datanote.platform.ai.agent.mapper.DnAiFileMapper;
import com.datanote.platform.ai.agent.model.DnAiFile;
import com.datanote.platform.ai.vector.EmbeddingService;
import com.datanote.platform.ai.vector.VectorStoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 特性B: PDF/Word 文档 → 向量库 RAG。
 * 上传 pdf/docx/txt/md → 异步提文本 → 切块(重叠) → 嵌入 → upsert kind="doc"(owner 作用域)。
 * 检索按发起人 owner 隔离(复用 AiFileService 文件 ACL 单一事实来源)。删除文件级联清向量块。
 * 降级: 向量库/embedding 不可用 → 状态置 null(不适用), 上传/删除主流程零感知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocIngestService {

    private final VectorStoreClient vector;
    private final EmbeddingService embedding;
    private final AiFileService aiFileService;
    private final DnAiFileMapper fileMapper;

    /** 可索引为文档知识库的扩展名(纯文本/文档类; 数据类 csv/xlsx 走分析不入文档库)。 */
    private static final Set<String> INDEXABLE = new java.util.HashSet<>(java.util.Arrays.asList("pdf", "docx", "txt", "md"));
    private static final int MAX_DOC_CHARS = 200_000; // 单文档提取字符上限(防超大文档)
    private static final int CHUNK_SIZE = 800;        // 切块大小(字符)
    private static final int CHUNK_OVERLAP = 100;     // 块间重叠(保留跨块上下文)
    private static final int MAX_CHUNKS = 400;        // 单文档最多入库块数(防爆向量库)
    private static final int DOC_FETCH_CAP = 50;      // 检索时拉取上限(owner 过滤前)

    public static boolean isIndexable(String ext) {
        return ext != null && INDEXABLE.contains(ext.toLowerCase());
    }

    /** 异步索引上传的文档(@Async, 不阻塞上传响应)。非文档类/向量库不可用则跳过。 */
    @Async("aiIndexExecutor")
    public void ingestAsync(DnAiFile m) {
        if (m == null || m.getId() == null) return;
        String ext = ext(m.getFileName());
        if (!isIndexable(ext)) return; // 非文档类: 不参与文档知识库, 状态留空
        if (!vector.available() || !embedding.isAvailable()) {
            log.info("[doc] 向量库/embedding 未就绪, 跳过文档索引 id={}", m.getId());
            return;
        }
        updateStatus(m.getId(), "indexing", null);
        try {
            Object[] r = aiFileService.resolve(m.getId(), m.getOwner());
            if (r == null) { updateStatus(m.getId(), "failed", 0); return; }
            Path p = (Path) r[1];
            String text = extract(p, ext);
            List<String> chunks = chunk(text, CHUNK_SIZE, CHUNK_OVERLAP);
            if (chunks.isEmpty()) { updateStatus(m.getId(), "failed", 0); return; }
            if (chunks.size() > MAX_CHUNKS) chunks = chunks.subList(0, MAX_CHUNKS);
            if (!vector.ensureCollection(embedding.dim())) { updateStatus(m.getId(), "failed", 0); return; }

            List<float[]> vecs = embedding.embedBatch(chunks);
            if (vecs == null || vecs.size() != chunks.size()) { updateStatus(m.getId(), "failed", 0); return; }
            List<Map<String, Object>> points = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("kind", "doc");
                payload.put("file_id", m.getId());
                payload.put("file_name", m.getFileName());
                payload.put("chunk_idx", i);
                payload.put("text", chunks.get(i));
                payload.put("owner", m.getOwner());
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("id", pointId(m.getId(), i));
                pt.put("vector", vecs.get(i));
                pt.put("payload", payload);
                points.add(pt);
            }
            boolean ok = vector.upsert(points);
            updateStatus(m.getId(), ok ? "indexed" : "failed", ok ? points.size() : 0);
            log.info("[doc] 文档索引{} id={} name={} 块={}", ok ? "完成" : "失败", m.getId(), m.getFileName(), points.size());
        } catch (Exception e) {
            log.warn("[doc] 文档索引异常 id={}: {}", m.getId(), e.getMessage());
            updateStatus(m.getId(), "failed", 0);
        }
    }

    /** 删除文件时级联清其向量块(best-effort)。 */
    public void deletePoints(Long fileId) {
        if (fileId == null || !vector.available()) return;
        try {
            vector.deleteByFilter("file_id", fileId);
        } catch (Exception e) {
            log.warn("[doc] 删除文档向量块异常 id={}: {}", fileId, e.getMessage());
        }
    }

    /**
     * 按发起人 owner 隔离检索文档块。返回 {query, engine, count, results:[{file_id,file_name,chunk_idx,text,score}]}。
     * 向量库不可用 → engine=none, 空结果(降级)。
     */
    public Map<String, Object> searchDocs(String query, String caller, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        int lim = limit < 1 ? 5 : Math.min(limit, 20);
        if (query == null || query.trim().isEmpty() || !vector.available() || !embedding.isAvailable()) {
            out.put("engine", "none");
            out.put("count", 0);
            out.put("results", new ArrayList<>());
            return out;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            float[] v = embedding.embed(query.trim());
            if (v != null) {
                List<Map<String, Object>> hits = vector.search(v, "doc", Math.min(DOC_FETCH_CAP, lim * 4));
                if (hits != null) {
                    for (Map<String, Object> h : hits) {
                        if (h == null) continue;
                        Object pl = h.get("payload");
                        if (!(pl instanceof Map)) continue;
                        Map<?, ?> p = (Map<?, ?>) pl;
                        String owner = p.get("owner") == null ? null : String.valueOf(p.get("owner"));
                        if (!AiFileService.ownerCanAccess(caller, owner)) continue; // owner 隔离: 只检索本人可见文档
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("file_id", p.get("file_id"));
                        m.put("file_name", p.get("file_name"));
                        m.put("chunk_idx", p.get("chunk_idx"));
                        m.put("text", p.get("text"));
                        m.put("score", h.get("score"));
                        results.add(m);
                        if (results.size() >= lim) break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[doc] 文档检索异常: {}", e.getMessage());
        }
        out.put("engine", "vector");
        out.put("count", results.size());
        out.put("results", results);
        return out;
    }

    /** 按扩展名提取纯文本(供索引)。 */
    private String extract(Path p, String ext) throws Exception {
        if ("pdf".equals(ext)) return PdfTextExtractor.extract(p, 0, MAX_DOC_CHARS);
        if ("docx".equals(ext)) return DocxTextExtractor.extract(p, MAX_DOC_CHARS);
        // txt/md: 直接读 UTF-8
        byte[] b = Files.readAllBytes(p);
        String s = new String(b, StandardCharsets.UTF_8);
        return s.length() > MAX_DOC_CHARS ? s.substring(0, MAX_DOC_CHARS) : s;
    }

    /** 纯函数: 文本切块(size 字符/块, 块间 overlap 重叠), 去空白块。可单测。 */
    static List<String> chunk(String text, int size, int overlap) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String t = text.trim();
        if (t.isEmpty()) return out;
        if (size <= 0) size = CHUNK_SIZE;
        if (overlap < 0 || overlap >= size) overlap = 0;
        int step = size - overlap;
        for (int i = 0; i < t.length(); i += step) {
            int end = Math.min(i + size, t.length());
            String c = t.substring(i, end).trim();
            if (!c.isEmpty()) out.add(c);
            if (end >= t.length()) break;
        }
        return out;
    }

    private void updateStatus(Long id, String status, Integer chunkCount) {
        try {
            DnAiFile u = new DnAiFile();
            u.setId(id);
            u.setIndexStatus(status);
            if (chunkCount != null) u.setChunkCount(chunkCount);
            fileMapper.updateById(u);
        } catch (Exception e) {
            log.warn("[doc] 更新索引状态失败 id={}: {}", id, e.getMessage());
        }
    }

    /** 稳定 point id(更新而非重插): UUID(doc:fileId:chunkIdx)。 */
    private static String pointId(Long fileId, int idx) {
        return UUID.nameUUIDFromBytes(("doc:" + fileId + ":" + idx).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
