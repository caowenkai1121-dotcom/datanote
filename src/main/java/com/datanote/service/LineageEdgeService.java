package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnLineageEdgeMapper;
import com.datanote.mapper.DnSyncJobMapper;
import com.datanote.model.DnLineageEdge;
import com.datanote.model.DnSyncJob;
import com.datanote.sync.dto.FieldMapping;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.service.SyncJobService;
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

    /** 从所有同步任务重建 MAPPING 来源的血缘边（保留 MANUAL 边）。返回重建的边数。 */
    @Transactional(rollbackFor = Exception.class)
    public int rebuildFromSyncJobs() {
        QueryWrapper<DnLineageEdge> del = new QueryWrapper<>();
        del.eq("source", "MAPPING");
        edgeMapper.delete(del);

        List<DnSyncJob> jobs = syncJobMapper.selectList(null);
        int count = 0;
        for (DnSyncJob job : jobs) {
            List<TableSyncConfig> tables;
            try {
                tables = syncJobService.parseTables(job);
            } catch (Exception e) {
                log.warn("解析任务表配置失败 jobId={}: {}", job.getId(), e.getMessage());
                continue;
            }
            for (DnLineageEdge edge : buildEdgesForJob(job, tables)) {
                edge.setCreatedAt(LocalDateTime.now());
                edge.setUpdatedAt(LocalDateTime.now());
                try {
                    edgeMapper.insert(edge);
                    count++;
                } catch (Exception e) {
                    // 唯一键冲突（同一边多任务产生）忽略
                }
            }
        }
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
}
