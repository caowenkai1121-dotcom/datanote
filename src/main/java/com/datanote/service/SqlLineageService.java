package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnLineageEdgeMapper;
import com.datanote.mapper.DnScriptMapper;
import com.datanote.model.DnLineageEdge;
import com.datanote.model.DnScript;
import com.datanote.service.SqlLineageParser.ColumnMapping;
import com.datanote.service.SqlLineageParser.ParseResult;
import com.datanote.service.SqlLineageParser.TableRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SQL 解析血缘服务 — 遍历 dn_script，用 Druid 解析 SQL 写入 dn_lineage_edge（source='SQL'）。
 * 重建时先删 source='SQL' 旧边，保留 MAPPING/MANUAL 等其它来源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlLineageService {

    private static final int COLUMN_CONFIDENCE = 80; // 列级 best-effort，低于映射来源的 100

    private final DnScriptMapper scriptMapper;
    private final DnLineageEdgeMapper edgeMapper;

    /** 解析所有脚本 SQL，重建 source='SQL' 血缘边。返回写入边数。 */
    @Transactional(rollbackFor = Exception.class)
    public int rebuildFromScripts() {
        QueryWrapper<DnLineageEdge> del = new QueryWrapper<>();
        del.eq("source", "SQL");
        edgeMapper.delete(del);

        List<DnScript> scripts = scriptMapper.selectList(null);
        int count = 0;
        for (DnScript script : scripts) {
            ParseResult r = SqlLineageParser.parse(script.getContent(), script.getDatabaseName());
            if (r.getWriteTable() == null) continue;
            count += insertEdges(r);
        }
        return count;
    }

    /** 把单条解析结果写入血缘边（表级 + 列级）。唯一键冲突忽略。 */
    private int insertEdges(ParseResult r) {
        TableRef dst = r.getWriteTable();
        int count = 0;
        for (TableRef src : r.getReadTables()) {
            count += tryInsert(edge("TABLE", src.getDb(), src.getTable(), "",
                    dst.getDb(), dst.getTable(), "", 100));
        }
        for (ColumnMapping cm : r.getColumnMappings()) {
            count += tryInsert(edge("COLUMN", cm.getSrcDb(), cm.getSrcTable(), cm.getSrcColumn(),
                    dst.getDb(), dst.getTable(), cm.getTargetColumn(), COLUMN_CONFIDENCE));
        }
        return count;
    }

    private int tryInsert(DnLineageEdge e) {
        try {
            edgeMapper.insert(e);
            return 1;
        } catch (Exception ex) {
            // 唯一键冲突（多脚本产生同一边）忽略
            return 0;
        }
    }

    private static DnLineageEdge edge(String level, String sdb, String stab, String scol,
                                      String ddb, String dtab, String dcol, int confidence) {
        DnLineageEdge e = new DnLineageEdge();
        e.setLevelType(level);
        e.setSrcDb(sdb); e.setSrcTable(stab); e.setSrcColumn(scol);
        e.setDstDb(ddb); e.setDstTable(dtab); e.setDstColumn(dcol);
        e.setTransformType("DIRECT");
        e.setSource("SQL");
        e.setConfidence(confidence);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }
}
