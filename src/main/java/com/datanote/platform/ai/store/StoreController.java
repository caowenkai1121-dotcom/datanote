package com.datanote.platform.ai.store;

import com.datanote.common.model.R;
import com.datanote.platform.ai.graph.GraphMirrorService;
import com.datanote.platform.ai.graph.GraphStoreClient;
import com.datanote.platform.ai.vector.EmbeddingService;
import com.datanote.platform.ai.vector.SemanticSearchService;
import com.datanote.platform.ai.vector.VectorIndexService;
import com.datanote.platform.ai.vector.VectorStoreClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 向量库/图库 健康检查与手动同步(运维 + 降级自检)。 */
@RestController
@RequestMapping("/api/ai/store")
@RequiredArgsConstructor
public class StoreController {

    private final VectorStoreClient vector;
    private final GraphStoreClient graph;
    private final EmbeddingService embedding;
    private final GraphMirrorService graphMirror;
    private final VectorIndexService vectorIndex;
    private final SemanticSearchService semanticSearch;

    /** 健康检查: 向量库/图库/嵌入 三态。 */
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ready", vector.available());
        v.put("collection", vector.collection());
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("ready", graph.available());
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("available", embedding.isAvailable());
        e.put("dim", embedding.dim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vector", v);
        out.put("graph", g);
        out.put("embedding", e);
        return R.ok(out);
    }

    /** 语义检索(向量库,降级关键字): q=检索词, kind=table/column/glossary/metric(可选), limit(默认10)。 */
    @GetMapping("/search")
    public R<Map<String, Object>> search(@RequestParam("q") String q,
                                         @RequestParam(value = "kind", required = false) String kind,
                                         @RequestParam(value = "limit", required = false, defaultValue = "10") int limit) {
        return R.ok(semanticSearch.search(q, kind, limit));
    }

    /** 手动全量同步: 血缘→图库 + 元数据→向量库。各自降级不可用则返 0。 */
    @PostMapping("/sync")
    public R<Map<String, Object>> sync() {
        int g = graphMirror.fullSync();
        int v = vectorIndex.fullReindex();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graphEdges", g);
        out.put("vectorPoints", v);
        return R.ok(out);
    }
}
