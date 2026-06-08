package com.datanote.domain.integration.schema;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.integration.mapper.DnSyncSchemaSnapshotMapper;
import com.datanote.domain.integration.model.DnSyncSchemaSnapshot;
import com.datanote.domain.integration.connector.ColumnDef;
import com.datanote.domain.integration.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DS-M7：源表 schema 漂移检测与快照维护。
 * 危险漂移（删列/改类型/改主键）→ 告警 + 返回 true（执行器跳过该表，暂停待人工确认）；
 * 安全（仅新增列/无变化）→ 更新快照、返回 false（放行）。首次见到的表只建基线，不阻断。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaDriftService {

    private final DnSyncSchemaSnapshotMapper snapshotMapper;
    private final AlertService alertService;

    /** @return true=危险漂移需阻断该表。 */
    public boolean checkAndTrack(Long jobId, String jobName, String sourceTable, List<ColumnDef> cols) {
        Map<String, String> cur = new LinkedHashMap<>();
        List<String> curPk = new ArrayList<>();
        if (cols != null) for (ColumnDef c : cols) {
            if (c == null) continue;
            cur.put(c.getName(), c.getColumnType());
            if (c.isPrimaryKey()) curPk.add(c.getName());
        }
        DnSyncSchemaSnapshot snap = snapshotMapper.selectOne(new LambdaQueryWrapper<DnSyncSchemaSnapshot>()
                .eq(DnSyncSchemaSnapshot::getJobId, jobId)
                .eq(DnSyncSchemaSnapshot::getSourceTable, sourceTable));
        if (snap == null) {
            save(null, jobId, sourceTable, cur, curPk);
            return false;
        }
        Map<String, String> prev;
        List<String> prevPk;
        try {
            prev = JSON.parseObject(snap.getColumnsJson() == null ? "{}" : snap.getColumnsJson(),
                    new TypeReference<LinkedHashMap<String, String>>() {});
            prevPk = JSON.parseArray(snap.getPkJson() == null ? "[]" : snap.getPkJson(), String.class);
            if (prev == null) prev = new LinkedHashMap<>();
            if (prevPk == null) prevPk = new ArrayList<>();
        } catch (Exception ex) {
            // 快照损坏（截断/非法JSON）：以当前 schema 重建基线，不阻断、不崩溃
            log.warn("schema 快照解析失败，以当前 schema 重建基线 jobId={} table={}: {}", jobId, sourceTable, ex.getMessage());
            save(snap.getId(), jobId, sourceTable, cur, curPk);
            return false;
        }
        SchemaDriftClassifier.Result r = SchemaDriftClassifier.classify(prev, prevPk, cur, curPk);
        if (r.dangerous()) {
            StringBuilder sb = new StringBuilder("源表 ").append(sourceTable).append(" schema 危险漂移:");
            if (!r.dropped.isEmpty()) sb.append(" 删列").append(r.dropped);
            if (!r.typeChanged.isEmpty()) sb.append(" 改类型").append(r.typeChanged);
            if (r.pkChanged) sb.append(" 主键变更");
            sb.append(" —— 该表已暂停，人工确认后重置快照恢复");
            alertService.alert(jobId, jobName, "SCHEMA_DRIFT", sb.toString());
            log.warn("schema 危险漂移 jobId={} table={}: {}", jobId, sourceTable, sb);
            return true; // 危险：不更新快照，保持阻断直到人工重置
        }
        // 安全（仅新增列或无变化）→ 更新快照基线
        save(snap.getId(), jobId, sourceTable, cur, curPk);
        return false;
    }

    /** 人工确认后重置某表快照（下次运行以当前 schema 重新建立基线）。 */
    public int reset(Long jobId, String sourceTable) {
        return snapshotMapper.delete(new LambdaQueryWrapper<DnSyncSchemaSnapshot>()
                .eq(DnSyncSchemaSnapshot::getJobId, jobId)
                .eq(DnSyncSchemaSnapshot::getSourceTable, sourceTable));
    }

    private void save(Long id, Long jobId, String sourceTable, Map<String, String> cols, List<String> pk) {
        DnSyncSchemaSnapshot s = new DnSyncSchemaSnapshot();
        s.setId(id);
        s.setJobId(jobId);
        s.setSourceTable(sourceTable);
        s.setColumnsJson(JSON.toJSONString(cols));
        s.setPkJson(JSON.toJSONString(pk));
        if (id == null) snapshotMapper.insert(s);
        else snapshotMapper.updateById(s);
    }
}
