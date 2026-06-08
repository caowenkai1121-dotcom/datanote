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

    @Value("${datanote.crypto.key:}")
    private String cryptoKey;
    @Value("${doris.database:}")
    private String odsDb;

    @Override public String name() { return "create_ods_table"; }
    @Override public String group() { return "sync"; }
    @Override public String description() {
        return "按源库表结构在数据仓库(Doris)建 ODS 表(写操作, 高风险, 每次需人工审批)。"
                + "自动读源表字段生成建表DDL并执行, 建成在数据地图 ODS 库可见。"
                + "参数 datasourceId(源数据源ID)、db(源库)、table(源表) 必填; syncMode(df 全量/di 增量, 默认 df) 可选。"
                + "需先用只读工具确认源库表存在。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"datasourceId\":{\"type\":\"number\",\"required\":true,\"desc\":\"源数据源ID\"},"
                + "\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"源库名\"},"
                + "\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"源表名\"},"
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
            if (dsId == null) return AiToolResult.fail("bad_arguments", "datasourceId 不能为空");
            if (db == null || table == null) return AiToolResult.fail("bad_arguments", "db/table 不能为空");
            // 标识符护栏: 库/表名仅允许 标识符字符, 杜绝 DDL 注入
            if (!db.matches("[a-zA-Z0-9_]+") || !table.matches("[a-zA-Z0-9_]+")) {
                return AiToolResult.fail("bad_arguments", "库名/表名含非法字符(仅允许字母数字下划线)");
            }
            if (!"df".equals(syncMode) && !"di".equals(syncMode)) {
                return AiToolResult.fail("bad_arguments", "syncMode 仅支持 df/di");
            }
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
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("odsTable", odsTable);
            out.put("ddl", ddl);
            out.put("columnCount", columns.size());
            out.put("_deeplink", AgentArgs.openTableLink(odsDb == null || odsDb.isEmpty() ? null : odsDb, odsTable));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
