package com.datanote.platform.ai.graph;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.orchestration.mapper.DnLineageEdgeMapper;
import com.datanote.domain.orchestration.model.DnLineageEdge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 血缘 → 图库 镜像（MySQL 为 SoT，图库为派生）。
 * fullSync 先删 source='MAPPING' 边再 UNWIND 批量 MERGE，幂等重跑无重复。
 * 全程 try/catch 隔离：图写失败仅 warn，绝不回滚 MySQL、绝不进 @Transactional。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphMirrorService {

    private final GraphStoreClient graph;
    private final DnLineageEdgeMapper edgeMapper;

    /** 全量镜像表级 MAPPING 血缘边到图库。返回镜像边数（图库不可用返 0）。 */
    public int fullSync() {
        if (!graph.available()) return 0;
        try {
            graph.run("MATCH ()-[r:FLOWS_TO {source:'MAPPING'}]->() DELETE r", null);
            List<DnLineageEdge> edges = edgeMapper.selectList(new QueryWrapper<DnLineageEdge>()
                    .eq("level_type", "TABLE").eq("source", "MAPPING"));
            if (edges == null || edges.isEmpty()) {
                log.info("[graph] 无 MAPPING 血缘边可镜像");
                return 0;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (DnLineageEdge e : edges) {
                if (e == null) continue;
                String src = fqn(e.getSrcDb(), e.getSrcTable());
                String dst = fqn(e.getDstDb(), e.getDstTable());
                if (src == null || dst == null) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("src", src);
                m.put("dst", dst);
                m.put("sdb", nz(e.getSrcDb()));
                m.put("stab", nz(e.getSrcTable()));
                m.put("ddb", nz(e.getDstDb()));
                m.put("dtab", nz(e.getDstTable()));
                m.put("jobId", e.getJobId() == null ? 0L : e.getJobId());
                rows.add(m);
            }
            if (rows.isEmpty()) return 0;
            Map<String, Object> params = new HashMap<>();
            params.put("edges", rows);
            String cy = "UNWIND $edges AS e "
                    + "MERGE (s:Table {fqn:e.src}) ON CREATE SET s.db=e.sdb, s.name=e.stab "
                    + "MERGE (d:Table {fqn:e.dst}) ON CREATE SET d.db=e.ddb, d.name=e.dtab "
                    + "MERGE (s)-[r:FLOWS_TO {jobId:e.jobId}]->(d) SET r.source='MAPPING'";
            graph.run(cy, params);
            log.info("[graph] 血缘镜像 fullSync 完成: {} 条边", rows.size());
            return rows.size();
        } catch (Exception ex) {
            log.warn("[graph] fullSync 失败(不影响 MySQL): {}", ex.getMessage());
            return 0;
        }
    }

    private static String fqn(String db, String t) {
        if (t == null || t.trim().isEmpty()) return null;
        return (db == null ? "" : db) + "." + t;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
