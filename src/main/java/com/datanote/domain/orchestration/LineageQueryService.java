package com.datanote.domain.orchestration;

import com.datanote.platform.ai.graph.GraphStoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 血缘查询(图优先 + MySQL 兜底)统一收口。
 * 图库可用且有命中 → Neo4j 多跳遍历(:Table FLOWS_TO*); 否则回退 LineageEdgeService 的 MySQL BFS。
 * 输出结构与 LineageEdgeService.impact/trace 一致(db/table/depth/source), 前端零感知。降级铁律。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageQueryService {

    private final GraphStoreClient graph;
    private final LineageEdgeService lineageEdgeService;

    private static final int MAX_DEPTH = 10;

    /** 下游影响清单(图优先, 兜底 MySQL)。 */
    public List<Map<String, Object>> impact(String db, String table) {
        List<Map<String, Object>> g = graphTraverse(db, table, true);
        return (g != null && !g.isEmpty()) ? g : lineageEdgeService.impact(db, table);
    }

    /** 上游溯源清单(图优先, 兜底 MySQL)。 */
    public List<Map<String, Object>> trace(String db, String table) {
        List<Map<String, Object>> g = graphTraverse(db, table, false);
        return (g != null && !g.isEmpty()) ? g : lineageEdgeService.trace(db, table);
    }

    /** 图库是否可用(供前端决定是否展示图特有能力如环检测)。 */
    public boolean graphAvailable() {
        return graph.available();
    }

    /**
     * 血缘环检测(循环依赖): 找存在 FLOWS_TO 回路的表。MySQL BFS 靠 visited 去重无法报环, 图独有能力。
     * 图不可用返空。最多 200 条防超大输出。
     */
    public List<String> detectCycles() {
        List<String> out = new ArrayList<>();
        if (!graph.available()) return out;
        try {
            List<Map<String, Object>> rows = graph.run(
                    "MATCH (t:Table)-[:FLOWS_TO*1..15]->(t) RETURN DISTINCT t.fqn AS fqn LIMIT 200", null);
            if (rows != null) for (Map<String, Object> r : rows) {
                Object f = r == null ? null : r.get("fqn");
                if (f != null) out.add(String.valueOf(f));
            }
        } catch (Exception e) {
            log.warn("[graph] 环检测失败: {}", e.getMessage());
        }
        return out;
    }

    /** 爆炸半径: 下游受影响表数(图优先, 兜底 MySQL)。 */
    public int blastRadius(String db, String table) {
        return impact(db, table).size();
    }

    /**
     * Neo4j 多跳遍历(downstream=顺向 FLOWS_TO*, 否则逆向)。图不可用/异常返 null(交调用方回退);
     * 图可用但无邻居返空 list(调用方据"空则回退 MySQL"再确认, 防图未镜像该表时漏报)。
     */
    private List<Map<String, Object>> graphTraverse(String db, String table, boolean downstream) {
        if (!graph.available() || db == null || table == null) return null;
        try {
            String fqn = db + "." + table;
            String cy = downstream
                    ? "MATCH (s:Table {fqn:$fqn})-[:FLOWS_TO*1.." + MAX_DEPTH + "]->(d:Table) RETURN DISTINCT d.db AS db, d.name AS name"
                    : "MATCH (s:Table {fqn:$fqn})<-[:FLOWS_TO*1.." + MAX_DEPTH + "]-(d:Table) RETURN DISTINCT d.db AS db, d.name AS name";
            List<Map<String, Object>> rows = graph.run(cy, Collections.singletonMap("fqn", fqn));
            if (rows == null) return null;   // 图执行失败 → 回退
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                if (r == null) continue;
                Object ndb = r.get("db"), nname = r.get("name");
                if (nname == null) continue;
                Map<String, Object> node = new HashMap<>();
                node.put("db", ndb);
                node.put("table", nname);
                node.put("source", "GRAPH");
                out.add(node);
            }
            return out;
        } catch (Exception e) {
            log.warn("[graph] 血缘遍历失败, 回退 MySQL: {}", e.getMessage());
            return null;
        }
    }
}
