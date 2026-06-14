package com.datanote.domain.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.orchestration.mapper.DnLineageEdgeMapper;
import com.datanote.domain.integration.mapper.DnSyncJobMapper;
import com.datanote.domain.orchestration.model.DnLineageEdge;
import com.datanote.domain.integration.model.DnSyncJob;
import com.datanote.domain.integration.dto.FieldMapping;
import com.datanote.domain.integration.dto.TableSyncConfig;
import com.datanote.domain.integration.service.SyncJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 血缘边服务 — 从同步任务字段映射构建表级/字段级血缘边，并提供查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageEdgeService {

    private final DnLineageEdgeMapper edgeMapper;
    private final DnSyncJobMapper syncJobMapper;
    private final SyncJobService syncJobService;
    private final com.datanote.platform.ai.graph.GraphMirrorService graphMirrorService;

    /** 从所有同步任务重建 MAPPING 来源的血缘边（保留 MANUAL 边）。返回重建的边数。 */
    @Transactional(rollbackFor = Exception.class)
    public int rebuildFromSyncJobs() {
        QueryWrapper<DnLineageEdge> del = new QueryWrapper<>();
        del.eq("source", "MAPPING");
        edgeMapper.delete(del);

        List<DnSyncJob> jobs = syncJobMapper.selectList(null);
        int count = 0;
        if (jobs == null) return count;   // selectList 理论可返回 null,无任务直接返回
        for (DnSyncJob job : jobs) {
            if (job == null) continue;
            List<TableSyncConfig> tables;
            try {
                tables = syncJobService.parseTables(job);
            } catch (Exception e) {
                log.warn("解析任务表配置失败 jobId={}: {}", job.getId(), e.getMessage());
                continue;
            }
            for (DnLineageEdge edge : buildEdgesForJob(job, tables)) {
                LocalDateTime now = LocalDateTime.now();
                edge.setCreatedAt(now);
                edge.setUpdatedAt(now);
                try {
                    edgeMapper.insert(edge);
                    count++;
                } catch (Exception e) {
                    // 唯一键冲突（同一边多任务产生）忽略
                }
            }
        }
        // 派生镜像: 血缘 → 图数据库(图库不可用/失败仅 warn, 绝不回滚 MySQL SoT)
        try { graphMirrorService.fullSync(); } catch (Exception e) { log.warn("血缘图库镜像失败(不影响主流程): {}", e.getMessage()); }
        return count;
    }

    /** 表级上下游邻居：{upstream:[...], downstream:[...]} */
    public Map<String, List<DnLineageEdge>> tableNeighbors(String db, String table) {
        Map<String, List<DnLineageEdge>> result = new HashMap<>();
        QueryWrapper<DnLineageEdge> down = new QueryWrapper<>();
        down.eq("level_type", "TABLE").eq("src_db", db).eq("src_table", table);
        result.put("downstream", edgeMapper.selectList(down));
        QueryWrapper<DnLineageEdge> up = new QueryWrapper<>();
        up.eq("level_type", "TABLE").eq("dst_db", db).eq("dst_table", table);
        result.put("upstream", edgeMapper.selectList(up));
        return result;
    }

    /** 字段入边：目标为该表的字段级边（这些列从哪来） */
    public List<DnLineageEdge> columnEdgesInto(String db, String table) {
        QueryWrapper<DnLineageEdge> qw = new QueryWrapper<>();
        qw.eq("level_type", "COLUMN").eq("dst_db", db).eq("dst_table", table).orderByAsc("dst_column");
        return edgeMapper.selectList(qw);
    }

    private static final int MAX_DEPTH = 10;

    /** 下游影响清单：从 (db.table) 出发沿表级边 src→dst 做 BFS，返回去重的下游节点。 */
    public List<Map<String, Object>> impact(String db, String table) {
        return bfs(db, table, true);
    }

    /** 上游溯源清单：沿表级边 dst→src 做 BFS，返回去重的上游节点。 */
    public List<Map<String, Object>> trace(String db, String table) {
        return bfs(db, table, false);
    }

    /** BFS over 表级边。原每访问一节点查一次库(N 节点=N 次往返), 改为一次性加载全部表级边后内存 BFS(与字段级 computeColumnBfs 同款)。 */
    private List<Map<String, Object>> bfs(String db, String table, boolean downstream) {
        List<DnLineageEdge> all = edgeMapper.selectList(new QueryWrapper<DnLineageEdge>().eq("level_type", "TABLE"));
        return computeTableBfs(all, db, table, downstream, MAX_DEPTH);
    }

    /** 纯函数: 在表级边集合上做 BFS, limit 深度防环。downstream=true 顺向(src→dst), 否则逆向(dst→src)。返回去重节点(db/table/depth/source)。 */
    static List<Map<String, Object>> computeTableBfs(List<DnLineageEdge> all, String db, String table, boolean downstream, int maxDepth) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (all == null) return result;
        // 邻接表: from 节点键 → 出边列表(按方向取 from 端)
        Map<String, List<DnLineageEdge>> adj = new HashMap<>();
        for (DnLineageEdge e : all) {
            if (e == null) continue;
            String fromKey = downstream ? key(e.getSrcDb(), e.getSrcTable()) : key(e.getDstDb(), e.getDstTable());
            adj.computeIfAbsent(fromKey, k -> new ArrayList<>()).add(e);
        }
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Deque<String[]> queue = new java.util.ArrayDeque<>(); // [db, table, depth]
        visited.add(key(db, table));
        queue.add(new String[]{db, table, "0"});
        while (!queue.isEmpty()) {
            String[] cur = queue.poll();
            int depth = Integer.parseInt(cur[2]);
            if (depth >= maxDepth) continue;
            List<DnLineageEdge> edges = adj.get(key(cur[0], cur[1]));
            if (edges == null) continue;
            for (DnLineageEdge e : edges) {
                String nDb = downstream ? e.getDstDb() : e.getSrcDb();
                String nTab = downstream ? e.getDstTable() : e.getSrcTable();
                String k = key(nDb, nTab);
                if (!visited.add(k)) continue;
                Map<String, Object> node = new HashMap<>();
                node.put("db", nDb);
                node.put("table", nTab);
                node.put("depth", depth + 1);
                node.put("source", e.getSource());
                result.add(node);
                queue.add(new String[]{nDb, nTab, String.valueOf(depth + 1)});
            }
        }
        return result;
    }

    private static String key(String db, String table) {
        return (db == null ? "" : db) + "." + (table == null ? "" : table);
    }

    // ========== 字段级(列)血缘穿透 ==========

    /** 字段下游影响: 从 (db.table.column) 沿字段级边 src→dst 多跳 BFS, 返回去重的下游字段。 */
    public List<Map<String, Object>> columnImpact(String db, String table, String column) {
        List<DnLineageEdge> all = edgeMapper.selectList(new QueryWrapper<DnLineageEdge>().eq("level_type", "COLUMN"));
        return computeColumnBfs(all, db, table, column, true, MAX_DEPTH);
    }

    /** 字段上游溯源: 沿字段级边 dst→src 多跳 BFS, 返回去重的上游来源字段。 */
    public List<Map<String, Object>> columnTrace(String db, String table, String column) {
        List<DnLineageEdge> all = edgeMapper.selectList(new QueryWrapper<DnLineageEdge>().eq("level_type", "COLUMN"));
        return computeColumnBfs(all, db, table, column, false, MAX_DEPTH);
    }

    private static String colKey(String db, String table, String col) {
        return (nz(db) + "." + nz(table) + "." + nz(col)).toLowerCase();
    }

    /** 纯函数: 在字段级边集合上做 BFS, downstream=true 顺向(src→dst), 否则逆向。返回去重节点(db/table/column/depth/transformType/source)。 */
    static List<Map<String, Object>> computeColumnBfs(List<DnLineageEdge> all, String db, String table, String column,
                                                      boolean downstream, int maxDepth) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (all == null) return result;
        // 按起始端(顺向取 src, 逆向取 dst)建邻接表
        Map<String, List<DnLineageEdge>> adj = new HashMap<>();
        for (DnLineageEdge e : all) {
            if (e == null) continue;
            String fromKey = downstream ? colKey(e.getSrcDb(), e.getSrcTable(), e.getSrcColumn())
                    : colKey(e.getDstDb(), e.getDstTable(), e.getDstColumn());
            adj.computeIfAbsent(fromKey, k -> new ArrayList<>()).add(e);
        }
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Deque<Object[]> queue = new java.util.ArrayDeque<>(); // [db, table, column, depth]
        visited.add(colKey(db, table, column));
        queue.add(new Object[]{db, table, column, 0});
        while (!queue.isEmpty()) {
            Object[] cur = queue.poll();
            int depth = (Integer) cur[3];
            if (depth >= maxDepth) continue;
            List<DnLineageEdge> edges = adj.get(colKey((String) cur[0], (String) cur[1], (String) cur[2]));
            if (edges == null) continue;
            for (DnLineageEdge e : edges) {
                String nDb = downstream ? e.getDstDb() : e.getSrcDb();
                String nTab = downstream ? e.getDstTable() : e.getSrcTable();
                String nCol = downstream ? e.getDstColumn() : e.getSrcColumn();
                if (!visited.add(colKey(nDb, nTab, nCol))) continue;
                Map<String, Object> node = new HashMap<>();
                node.put("db", nDb);
                node.put("table", nTab);
                node.put("column", nCol);
                node.put("depth", depth + 1);
                node.put("transformType", e.getTransformType());
                node.put("source", e.getSource());
                result.add(node);
                queue.add(new Object[]{nDb, nTab, nCol, depth + 1});
            }
        }
        return result;
    }

    // ========== 纯函数（可单测） ==========

    /** 把一个同步任务的表/字段映射转成血缘边（源 MAPPING，置信度 100）。 */
    static List<DnLineageEdge> buildEdgesForJob(DnSyncJob job, List<TableSyncConfig> tables) {
        List<DnLineageEdge> edges = new ArrayList<>();
        if (tables == null) return edges;
        String srcDb = nz(job.getSourceDb());
        String dstDb = nz(job.getTargetDb());
        for (TableSyncConfig tc : tables) {
            if (tc == null || isBlank(tc.getSourceTable()) || isBlank(tc.getTargetTable())) continue;
            edges.add(edge("TABLE", srcDb, tc.getSourceTable(), "", dstDb, tc.getTargetTable(), "",
                    "DIRECT", job.getId()));
            List<FieldMapping> fields = tc.getFields();
            if (fields == null) continue;
            for (FieldMapping fm : fields) {
                if (fm == null || isBlank(fm.getSource()) || isBlank(fm.getTarget())) continue;
                if (Boolean.FALSE.equals(fm.getSync())) continue; // 不同步字段排除
                edges.add(edge("COLUMN", srcDb, tc.getSourceTable(), fm.getSource(),
                        dstDb, tc.getTargetTable(), fm.getTarget(), transformType(fm), job.getId()));
            }
        }
        return edges;
    }

    static String transformType(FieldMapping fm) {
        if (!isBlank(fm.getMaskingType())) return "MASK";
        if (!isBlank(fm.getTransformExpression())) return "TRANSFORM";
        return "DIRECT";
    }

    private static DnLineageEdge edge(String level, String sdb, String stab, String scol,
                                      String ddb, String dtab, String dcol, String transform, Long jobId) {
        DnLineageEdge e = new DnLineageEdge();
        e.setLevelType(level);
        e.setSrcDb(sdb); e.setSrcTable(stab); e.setSrcColumn(scol);
        e.setDstDb(ddb); e.setDstTable(dtab); e.setDstColumn(dcol);
        e.setTransformType(transform);
        e.setSource("MAPPING");
        e.setConfidence(100);
        e.setJobId(jobId);
        return e;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s; }

    // ========== F1：血缘图谱（多跳子图） ==========

    /** 表级边轻量四元组（src/dst 为 "db.table"），供 buildSubgraph 纯函数使用。 */
    public static class TableEdge {
        public final String src;
        public final String dst;
        public final String level;
        public final String source;
        public TableEdge(String src, String dst, String level, String source) {
            this.src = src; this.dst = dst; this.level = level; this.source = source;
        }
    }

    /** 子图结果：节点集合（含起点，保序）+ 去重后的边列表。 */
    public static class SubGraph {
        public final java.util.Set<String> nodes = new java.util.LinkedHashSet<>();
        public final List<TableEdge> edges = new ArrayList<>();
    }

    /**
     * 纯函数：以 (db.table) 为中心做双向 BFS，返回子图。
     * 每跳同时扩展下游(边 src==当前) 与上游(边 dst==当前)；visited 防环；节点达 maxNodes 即停止扩展（起点必含）。
     */
    static SubGraph buildSubgraph(List<TableEdge> allEdges, String db, String table, int depth, int maxNodes) {
        return buildSubgraphFromKey(allEdges, key(db, table), depth, maxNodes);
    }

    /** 纯函数(键无关): 以任意起点键(表="db.table" 或 列="db.table.column")双向 BFS 取子图。 */
    static SubGraph buildSubgraphFromKey(List<TableEdge> allEdges, String start, int depth, int maxNodes) {
        SubGraph g = new SubGraph();
        g.nodes.add(start);
        if (allEdges == null || depth <= 0) return g;

        java.util.Set<String> edgeKeys = new java.util.HashSet<>();
        java.util.Deque<String[]> queue = new java.util.ArrayDeque<>(); // [node, depth]
        queue.add(new String[]{start, "0"});
        while (!queue.isEmpty()) {
            String[] cur = queue.poll();
            String node = cur[0];
            int d = Integer.parseInt(cur[1]);
            if (d >= depth) continue;
            for (TableEdge e : allEdges) {
                String neighbor;
                if (node.equals(e.src)) neighbor = e.dst;        // 下游
                else if (node.equals(e.dst)) neighbor = e.src;   // 上游
                else continue;
                // 命中相关边：去重后收录
                String ek = e.src + "|" + e.dst + "|" + (e.source == null ? "" : e.source);
                boolean firstSeenEdge = edgeKeys.add(ek);
                boolean neighborKnown = g.nodes.contains(neighbor);
                if (!neighborKnown) {
                    if (g.nodes.size() >= maxNodes) continue; // 满了不再纳入新节点（及其边）
                    g.nodes.add(neighbor);
                    queue.add(new String[]{neighbor, String.valueOf(d + 1)});
                }
                if (firstSeenEdge) g.edges.add(e);
            }
        }
        return g;
    }

    private static final int GRAPH_MAX_NODES = 100;
    private static final int GRAPH_MAX_DEPTH = 6;

    /** 以 (db.table) 为中心的 N 跳血缘子图：{nodes:[{id,db,table}], edges:[{src,dst,level,source}]}。 */
    public Map<String, Object> graph(String db, String table, int depth) {
        int d = depth <= 0 ? 2 : Math.min(depth, GRAPH_MAX_DEPTH);
        QueryWrapper<DnLineageEdge> qw = new QueryWrapper<>();
        qw.eq("level_type", "TABLE");
        List<TableEdge> edges = new ArrayList<>();
        List<DnLineageEdge> rawEdges = edgeMapper.selectList(qw);
        if (rawEdges != null) {
            for (DnLineageEdge e : rawEdges) {
                if (e == null) continue;
                edges.add(new TableEdge(key(e.getSrcDb(), e.getSrcTable()),
                        key(e.getDstDb(), e.getDstTable()), "TABLE", e.getSource()));
            }
        }
        SubGraph sub = buildSubgraph(edges, db, table, d, GRAPH_MAX_NODES);

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String id : sub.nodes) {
            int dot = id.indexOf('.');
            Map<String, Object> n = new HashMap<>();
            n.put("id", id);
            n.put("db", dot >= 0 ? id.substring(0, dot) : "");
            n.put("table", dot >= 0 ? id.substring(dot + 1) : id);
            nodes.add(n);
        }
        List<Map<String, Object>> edgeList = new ArrayList<>();
        for (TableEdge e : sub.edges) {
            Map<String, Object> m = new HashMap<>();
            m.put("src", e.src);
            m.put("dst", e.dst);
            m.put("level", e.level);
            m.put("source", e.source);
            edgeList.add(m);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edgeList);
        return result;
    }

    private static final int COL_GRAPH_MAX_NODES = 120;

    /** 字段级 N 跳血缘子图(以 db.table.column 为中心双向 BFS): {nodes:[{id,db,table,column}], edges:[{src,dst,level,source}]}。 */
    public Map<String, Object> columnGraph(String db, String table, String column, int depth) {
        int d = depth <= 0 ? 2 : Math.min(depth, GRAPH_MAX_DEPTH);
        List<DnLineageEdge> raw = edgeMapper.selectList(new QueryWrapper<DnLineageEdge>().eq("level_type", "COLUMN"));
        List<TableEdge> edges = new ArrayList<>();
        if (raw != null) {
            for (DnLineageEdge e : raw) {
                if (e == null) continue;
                edges.add(new TableEdge(colKey(e.getSrcDb(), e.getSrcTable(), e.getSrcColumn()),
                        colKey(e.getDstDb(), e.getDstTable(), e.getDstColumn()), "COLUMN", e.getSource()));
            }
        }
        SubGraph sub = buildSubgraphFromKey(edges, colKey(db, table, column), d, COL_GRAPH_MAX_NODES);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String id : sub.nodes) {
            Map<String, Object> n = new HashMap<>();
            String[] p = splitColKey(id);
            n.put("id", id);
            n.put("db", p[0]); n.put("table", p[1]); n.put("column", p[2]);
            nodes.add(n);
        }
        List<Map<String, Object>> edgeList = new ArrayList<>();
        for (TableEdge e : sub.edges) {
            Map<String, Object> m = new HashMap<>();
            m.put("src", e.src); m.put("dst", e.dst); m.put("level", "COLUMN"); m.put("source", e.source);
            edgeList.add(m);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edgeList);
        return result;
    }

    /** "db.table.column" 切分为 [db, table, column](按前两个点切, 余下归 column)。 */
    static String[] splitColKey(String id) {
        if (id == null) return new String[]{"", "", ""};
        int d1 = id.indexOf('.');
        if (d1 < 0) return new String[]{"", "", id};
        int d2 = id.indexOf('.', d1 + 1);
        if (d2 < 0) return new String[]{id.substring(0, d1), id.substring(d1 + 1), ""};
        return new String[]{id.substring(0, d1), id.substring(d1 + 1, d2), id.substring(d2 + 1)};
    }
}
