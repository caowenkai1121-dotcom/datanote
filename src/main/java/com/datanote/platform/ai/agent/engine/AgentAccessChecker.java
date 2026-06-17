package com.datanote.platform.ai.agent.engine;

import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.iam.DataAclService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 数据级访问闸门: 表作用域工具按 db.table 调 DataAclService.canAccessAs(发起人身份)。
 * 返回 null=放行; 非 null=拒绝原因。非表作用域工具一律放行(功能权限由 PermGate 兜)。
 */
@Component
@RequiredArgsConstructor
public class AgentAccessChecker {

    private final DataAclService dataAclService;

    /** 涉及具体库表的工具(读源库/元数据/血缘): 需对 db.table 做数据级校验。 */
    // 注意: 工具名须与各工具 AiTool.name() 返回值一致, 否则该工具的数据级校验会被静默跳过。
    private static final Set<String> TABLE_SCOPED = new HashSet<>(Arrays.asList(
            "asset_detail", "table_data", "table_profile",
            "lineage_impact", "lineage_trace", "lineage_graph",
            "graph_impact", "graph_trace", "graph_neighbors"));

    /** @return null 放行; 否则拒绝原因。 */
    public String dataDeny(String toolName, JsonNode args, AgentContext ctx) {
        if (toolName == null || !TABLE_SCOPED.contains(toolName)) return null;
        String db = pick(args, ctx, "db");
        String table = pick(args, ctx, "table");
        if (db == null || db.isEmpty() || table == null || table.isEmpty()) return null; // 无从判定→不拦
        String resourceId = db + "." + table; // 大小写须与 dn_data_grant.resource_id 存储一致(同 DataMapService.tableKey, 按源大小写)
        String caller = ctx == null ? null : ctx.getUserName();
        boolean ok = dataAclService.canAccessAs(caller,
                ctx == null ? null : ctx.getRoles(),
                ctx == null ? null : ctx.getPerms(),
                "TABLE", resourceId);
        return ok ? null : ("当前用户无数据资源 " + resourceId + " 的访问权限(请联系管理员在数据授权中开放)");
    }

    /** 先从 args 取, 缺则从 bizCtx 回退(与 AgentArgs 缺参回退一致)。 */
    private String pick(JsonNode args, AgentContext ctx, String key) {
        if (args != null && args.hasNonNull(key)) {
            String v = args.get(key).asText(null);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        if (ctx != null && ctx.getBizCtx() != null) {
            Object v = ctx.getBizCtx().get(key);
            if (v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v).trim();
        }
        return null;
    }
}
