package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.common.util.CryptoUtil;
import com.datanote.domain.datasource.MetadataService;
import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.integration.HiveService;
import com.datanote.domain.metadata.model.ColumnInfo;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写工具(HIGH): 按源库表建 ODS 表(在 Doris 执行 CREATE TABLE DDL)。映射 UI 同款建表流程
 * (读源表字段→generateDDL→executeDDL), 建成在数据地图 ODS 库可见。HIGH 风险每次强制审批。
 */
@Component
@RequiredArgsConstructor
public class CreateOdsTableTool implements AiTool {

    private final HiveService hiveService;
    private final MetadataService metadataService;
    private final DnDatasourceMapper datasourceMapper;
    private final com.datanote.domain.governance.AssetDetailService assetDetailService; // 自动补全 datasourceId(由库表查所属数据源)
    private final com.datanote.domain.develop.ScriptService scriptService;              // 在【数据开发 ODS层】落 DnSyncTask 任务
    private final com.datanote.domain.integration.mapper.DnSyncTaskMapper syncTaskMapper; // 按 taskName 去重(避免重复建任务)

    @Value("${datanote.crypto.key:}")
    private String cryptoKey;
    @Value("${doris.database:}")
    private String odsDb;

    @Override public String name() { return "create_ods_table"; }
    @Override public String group() { return "sync"; }
    @Override public String description() {
        return "【把源表抽到 Doris 数仓 ODS 层】的正确工具(写操作, 高风险, 每次需人工审批): "
                + "在『数据开发 → ODS 层』新建 ODS 同步任务(DnSyncTask)并一键建表(读源表字段→在 Doris 建 ods 表)。"
                + "用户说『新建ODS任务/把表抽到ODS层/拉数据到数仓/接入ODS』时用本工具(不要用 create_sync_job, 那是另一个『数据同步』模块)。"
                + "参数 db(源库)、table(源表) 必填; datasourceId 可不填(按 db.table 自动识别); syncMode(df 全量/di 增量, 默认 df) 可选。建成后可在数据开发 ODS 层点『运行』拉数。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"源库名\"},"
                + "\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"源表名\"},"
                + "\"datasourceId\":{\"type\":\"number\",\"required\":false,\"desc\":\"源数据源ID, 不填则按库表自动识别\"},"
                + "\"syncMode\":{\"type\":\"string\",\"required\":false,\"desc\":\"df=全量/di=增量,默认df\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.HIGH; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long dsId = AgentArgs.longVal(args, "datasourceId");
            String db = AgentArgs.str(args, "db");
            String table = AgentArgs.str(args, "table");
            String syncMode = AgentArgs.str(args, "syncMode");
            if (syncMode == null) syncMode = "df";
            if (db == null || table == null) return AiToolResult.fail("bad_arguments", "db/table 不能为空");
            // 标识符护栏: 库/表名仅允许 标识符字符, 杜绝 DDL 注入
            if (!db.matches("[a-zA-Z0-9_]+") || !table.matches("[a-zA-Z0-9_]+")) {
                return AiToolResult.fail("bad_arguments", "库名/表名含非法字符(仅允许字母数字下划线)");
            }
            if (!"df".equals(syncMode) && !"di".equals(syncMode)) {
                return AiToolResult.fail("bad_arguments", "syncMode 仅支持 df/di");
            }
            // 自动补全: datasourceId 未给则按源库表从元数据识别(免反问用户)
            if (dsId == null) dsId = assetDetailService.resolveDatasourceId(db, table);
            if (dsId == null) return AiToolResult.fail("need_datasource",
                    "无法自动识别 " + db + "." + table + " 的源数据源(该库/表可能未在数据源管理注册或未采集元数据)。请先采集该库, 或确认正确库表名后重试。");
            DnDatasource ds = datasourceMapper.selectById(dsId);
            if (ds == null) return AiToolResult.fail("not_found", "数据源不存在: " + dsId);
            if (ds.getPort() == null) return AiToolResult.fail("bad_arguments", "数据源端口为空: " + dsId);
            String pwd = CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey);
            List<ColumnInfo> columns = metadataService.getColumnsByConnection(
                    ds.getHost(), ds.getPort(), ds.getUsername(), pwd, db, table);
            if (columns == null || columns.isEmpty()) {
                return AiToolResult.fail("not_found", "源表无字段或不存在: " + db + "." + table);
            }
            String ddl = hiveService.generateDDL(db, table, columns, syncMode);
            hiveService.executeDDL(ddl); // 在 Doris 执行 CREATE TABLE
            String odsTable = hiveService.getOdsTableName(db, table, syncMode);
            String targetDb = (odsDb == null || odsDb.isEmpty()) ? "ods" : odsDb;
            // 在【数据开发 ODS层】落 ODS 同步任务(图三的任务), 按 taskName 去重; 之后用户可在该处『运行』把数据拉到数仓
            com.datanote.domain.integration.model.DnSyncTask task = syncTaskMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.datanote.domain.integration.model.DnSyncTask>()
                            .eq("task_name", odsTable).last("LIMIT 1"));
            if (task == null) task = new com.datanote.domain.integration.model.DnSyncTask();
            task.setTaskName(odsTable);
            task.setSourceDsId(dsId);
            task.setSourceDb(db);
            task.setSourceTable(table);
            task.setTargetDb(targetDb);
            task.setTargetTable(odsTable);
            task.setSyncMode(syncMode);
            task.setPartitionField("dt");
            task.setStatus(1);
            if (ctx != null && ctx.getUserName() != null) task.setCreatedBy(ctx.getUserName());
            scriptService.saveSyncTask(task);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("taskId", task.getId());
            out.put("taskName", odsTable);
            out.put("location", "数据开发 → ODS 层");
            out.put("odsTable", odsTable);
            out.put("columnCount", columns.size());
            out.put("note", "已在『数据开发 ODS层』新建同步任务并建好 Doris 表 ods." + odsTable + "; 可在该任务点『运行』把数据拉到数仓。");
            out.put("_deeplink", AgentArgs.openTableLink(targetDb, odsTable));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
