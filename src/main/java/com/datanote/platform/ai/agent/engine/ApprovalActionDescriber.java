package com.datanote.platform.ai.agent.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批动作人话化: 把 skillName + argsJson(如 create_ods_table {"db":"xh_dms","table":"t_x","syncMode":"df"})
 * 转成用户一眼能懂的中文摘要(如 "同步接入 · xh_dms.t_x → 数仓ODS层(全量)")。纯静态, 失败回退工具名。
 */
public final class ApprovalActionDescriber {

    private ApprovalActionDescriber() {}

    /** 写工具 → 中文动作名 */
    private static final Map<String, String> LABEL = new LinkedHashMap<>();
    static {
        LABEL.put("create_ods_table", "同步接入到数仓ODS层");
        LABEL.put("run_ods_task", "运行ODS同步·拉数");
        LABEL.put("create_sync_job", "新建数据同步任务");
        LABEL.put("run_sync_job", "运行数据同步");
        LABEL.put("stop_sync_job", "停止数据同步");
        LABEL.put("create_project", "新建项目");
        LABEL.put("create_dev_folder", "新建开发目录");
        LABEL.put("create_script", "新建脚本");
        LABEL.put("run_script", "运行脚本");
        LABEL.put("create_quality_rule", "新建数据质量规则");
        LABEL.put("run_quality_rule", "运行质量规则");
        LABEL.put("run_standard_check", "运行数据标准检查");
        LABEL.put("create_metric", "新建指标");
        LABEL.put("calc_metric", "计算指标值");
        LABEL.put("create_subject", "新建主题域");
        LABEL.put("classify_column", "字段分类分级");
        LABEL.put("update_column_business_meta", "更新字段业务含义");
        LABEL.put("add_project_member", "添加项目成员");
        LABEL.put("create_project_task", "新建项目任务");
        LABEL.put("update_project_task", "更新项目任务");
        LABEL.put("create_project_milestone", "新建项目里程碑");
        LABEL.put("create_governance_issue", "新建治理问题");
        LABEL.put("assign_issue", "指派问题");
        LABEL.put("transition_issue", "流转问题状态");
        LABEL.put("submit_project_release", "提交项目发布");
        LABEL.put("approve_project_release", "审批项目发布");
        LABEL.put("save_wiki_page", "保存 Wiki 页面");
        LABEL.put("rebuild_lineage", "重建血缘");
        LABEL.put("run_backfill", "运行数据回填");
        LABEL.put("schedule_rerun", "排程重跑");
        LABEL.put("trigger_metadata_crawl", "触发元数据采集");
        LABEL.put("datamodel_generate", "生成数据模型");
        LABEL.put("datamodel_save_model", "保存数据模型");
        LABEL.put("datamodel_submit_for_approval", "提交模型审批");
        LABEL.put("datamodel_approve_model", "审批数据模型");
        LABEL.put("create_artifact", "生成内容页面");
        LABEL.put("create_page", "生成网页");
        LABEL.put("export_file", "导出文件");
    }

    /** 生成人话摘要; 失败兜底为 中文名/工具名。 */
    public static String describe(String skill, String argsJson, ObjectMapper om) {
        String label = LABEL.getOrDefault(skill, skill);
        JsonNode a = null;
        try { if (argsJson != null && !argsJson.isEmpty()) a = om.readTree(argsJson); } catch (Exception ignore) {}
        if (a == null || !a.isObject()) return label;
        try {
            switch (skill == null ? "" : skill) {
                case "create_ods_table": {
                    String db = txt(a, "db"), t = txt(a, "table");
                    String mode = "df".equalsIgnoreCase(txt(a, "syncMode")) ? "全量" : ("dt".equalsIgnoreCase(txt(a, "syncMode")) ? "增量" : txt(a, "syncMode"));
                    return "同步接入 · " + joinTbl(db, t) + " → 数仓ODS层" + (mode == null ? "" : "(" + mode + ")");
                }
                case "run_ods_task": return label + " · 任务#" + first(a, "taskId", "id");
                case "create_sync_job": return "新建同步任务 · " + nz(first(a, "sourceName", "source", "sourceTable")) + "→" + nz(first(a, "targetName", "target", "targetTable"));
                case "run_sync_job": case "stop_sync_job": return label + " · " + nz(first(a, "taskName", "name", "taskId", "id"));
                case "create_project": return "新建项目 · " + nz(first(a, "projectName", "name"));
                case "create_dev_folder": return "新建开发目录 · " + nz(first(a, "name", "folderName")) + paren(first(a, "layer", "layerType"));
                case "create_script": return "新建脚本 · " + nz(first(a, "name", "scriptName")) + paren(first(a, "type", "scriptType"));
                case "run_script": return "运行脚本 · " + nz(first(a, "name", "scriptName", "scriptId", "id"));
                case "create_quality_rule": return "新建质量规则 · " + nz(first(a, "tableName", "table")) + "/" + nz(first(a, "ruleName", "name"));
                case "create_metric": return "新建指标 · " + nz(first(a, "metricName", "name"));
                case "create_subject": return "新建主题域 · " + nz(first(a, "name", "subjectName"));
                case "add_project_member": return "添加项目成员 · " + nz(first(a, "userName", "member", "user")) + paren("项目#" + first(a, "projectId", "project"));
                case "save_wiki_page": return "保存 Wiki · " + nz(first(a, "title", "name"));
                case "create_artifact": return "生成内容页面 · " + nz(first(a, "title")) + paren(first(a, "type"));
                case "create_page": return "生成网页 · " + nz(first(a, "title"));
                default:
                    // 通用兜底: 中文名 · 取若干显著参数
                    String salient = salient(a);
                    return salient.isEmpty() ? label : (label + " · " + salient);
            }
        } catch (Exception e) {
            return label;
        }
    }

    private static String joinTbl(String db, String t) {
        if (db == null && t == null) return "";
        return (db == null ? "" : db + ".") + (t == null ? "" : t);
    }
    private static String txt(JsonNode a, String k) {
        JsonNode n = a.get(k);
        return (n == null || n.isNull()) ? null : n.asText();
    }
    private static String first(JsonNode a, String... keys) {
        for (String k : keys) { String v = txt(a, k); if (v != null && !v.isEmpty()) return v; }
        return null;
    }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String paren(String s) { return (s == null || s.isEmpty()) ? "" : "(" + s + ")"; }

    /** 通用: 取最多 3 个显著参数(优先名称/表/库类), 拼成 k=v。 */
    private static String salient(JsonNode a) {
        String[] pref = {"name", "title", "projectName", "tableName", "table", "db", "metricName", "ruleName", "folderName", "datasourceId", "id"};
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String k : pref) {
            String v = txt(a, k);
            if (v != null && !v.isEmpty()) { if (sb.length() > 0) sb.append(", "); sb.append(v); if (++n >= 3) break; }
        }
        if (n == 0) { // 退而取前几个字段值
            java.util.Iterator<String> it = a.fieldNames();
            while (it.hasNext() && n < 3) {
                String v = txt(a, it.next());
                if (v != null && !v.isEmpty() && v.length() < 40) { if (sb.length() > 0) sb.append(", "); sb.append(v); n++; }
            }
        }
        return sb.toString();
    }
}
