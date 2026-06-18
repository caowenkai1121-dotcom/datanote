package com.datanote.platform.iam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全站权限点清单（单一事实来源）。
 * <p>命名约定: {@code 模块:功能}。{@code 模块:view} 控制顶部菜单能否进入；
 * 其余控制模块内的关键写操作。{@code *} 为超级管理员（拥有全部）。
 * <p>前端「角色-权限」配置据此渲染树形勾选；后端鉴权拦截器据此映射 URL→所需权限点。
 */
public final class PermCatalog {

    private PermCatalog() {}

    /** 一个权限点：编码 + 中文名 + 所属模块 + 是否为"进入该模块"的 view 权限。 */
    public static final class Perm {
        public final String code;
        public final String name;
        public final String module;     // 模块编码(与顶部 data-route 对齐)
        public final String moduleName; // 模块中文名
        public final boolean isView;
        Perm(String code, String name, String module, String moduleName, boolean isView) {
            this.code = code; this.name = name; this.module = module; this.moduleName = moduleName; this.isView = isView;
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code); m.put("name", name);
            m.put("module", module); m.put("moduleName", moduleName); m.put("isView", isView);
            return m;
        }
    }

    /** 全站权限点(顺序即展示顺序)。 */
    public static final List<Perm> ALL = Arrays.asList(
            // 首页
            new Perm("home:view", "进入首页", "home", "首页", true),
            // 数据开发
            new Perm("develop:view", "进入数据开发", "develop", "数据开发", true),
            new Perm("develop:edit", "脚本/任务增删改", "develop", "数据开发", false),
            new Perm("develop:run", "运行脚本/调试", "develop", "数据开发", false),
            new Perm("develop:approve", "审批脚本上线", "develop", "数据开发", false),
            // 数据运维
            new Perm("operations:view", "进入数据运维", "operations", "数据运维", true),
            new Perm("operations:schedule", "调度管理(上下线/重跑)", "operations", "数据运维", false),
            new Perm("operations:backfill", "补数据", "operations", "数据运维", false),
            new Perm("operations:baseline", "基线管理", "operations", "数据运维", false),
            // 数据地图
            new Perm("catalog:view", "进入数据地图", "catalog", "数据地图", true),
            new Perm("catalog:edit", "元数据/DDL 维护", "catalog", "数据地图", false),
            new Perm("sql:danger", "执行破坏性SQL(DROP/TRUNCATE/GRANT)", "catalog", "数据地图", false),
            // 数据模型(三层建模 + L1-L5)
            new Perm("datamodel:view", "进入数据模型", "datamodel", "数据模型", true),
            new Perm("datamodel:edit", "模型/实体/属性建模", "datamodel", "数据模型", false),
            new Perm("datamodel:approve", "模型发布审批", "datamodel", "数据模型", false),
            // 数据治理
            new Perm("governance:view", "进入数据治理", "governance", "数据治理", true),
            new Perm("governance:quality", "质量规则管理", "governance", "数据治理", false),
            new Perm("governance:issue", "治理工单处理", "governance", "数据治理", false),
            new Perm("governance:standard", "数据标准管理", "governance", "数据治理", false),
            new Perm("governance:manage", "治理配置维护(脱敏/分级/生命周期/血缘)", "governance", "数据治理", false),
            new Perm("governance:audit", "审计中心查看/导出", "governance", "数据治理", false),
            // 主数据
            new Perm("mdm:view", "进入主数据管理", "mdm", "主数据管理", true),
            new Perm("mdm:manage", "域/实体/属性/黄金记录维护", "mdm", "主数据管理", false),
            new Perm("mdm:approve", "主数据变更审批", "mdm", "主数据管理", false),
            // 数据同步
            new Perm("dbsync:view", "进入数据同步", "dbsync", "数据同步", true),
            new Perm("dbsync:edit", "同步任务增删改", "dbsync", "数据同步", false),
            new Perm("dbsync:run", "同步运行/上下线", "dbsync", "数据同步", false),
            // 指标管理
            new Perm("metrics:view", "进入指标管理", "metrics", "指标管理", true),
            new Perm("metrics:edit", "指标增删改", "metrics", "指标管理", false),
            // 项目管理
            new Perm("project:view", "进入项目管理", "project", "项目管理", true),
            new Perm("project:manage", "项目/任务/成员维护", "project", "项目管理", false),
            new Perm("project:approve", "项目发布审批", "project", "项目管理", false),
            new Perm("project:all-data", "查看全部项目(数据范围, 不限本人参与)", "project", "项目管理", false),
            // 数据源管理(读/写, 跨数据开发/同步/治理复用, 独立管控连接信息)
            new Perm("datasource:view", "查看数据源(读)", "datasource", "数据源管理", false),
            new Perm("datasource:edit", "数据源增删改(写)", "datasource", "数据源管理", false),
            // 数据权限(资源级访问控制: 表/项目/模型等授权给角色/用户)
            new Perm("data:grant", "数据授权管理(设资源可见范围)", "data", "数据权限", false),
            new Perm("data:all", "查看全部数据(绕过资源级限制)", "data", "数据权限", false),
            // 系统管理
            new Perm("settings:view", "进入系统管理", "settings", "系统管理", true),
            new Perm("settings:user", "用户/角色/权限管理", "settings", "系统管理", false),
            new Perm("settings:config", "系统配置/数据源/环境", "settings", "系统管理", false),
            // AI 助手
            new Perm("assistant:view", "进入 AI 助手", "assistant", "AI 助手", true),
            new Perm("assistant:use", "AI 对话/调用工具", "assistant", "AI 助手", false),
            new Perm("assistant:approve", "AI 写操作审批", "assistant", "AI 助手", false)
    );

    /** 按模块分组(给前端树形勾选)。 */
    public static List<Map<String, Object>> grouped() {
        Map<String, Map<String, Object>> byModule = new LinkedHashMap<>();
        for (Perm p : ALL) {
            Map<String, Object> g = byModule.computeIfAbsent(p.module, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("module", p.module);
                m.put("moduleName", p.moduleName);
                m.put("perms", new ArrayList<Map<String, Object>>());
                return m;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> perms = (List<Map<String, Object>>) g.get("perms");
            perms.add(p.toMap());
        }
        return new ArrayList<>(byModule.values());
    }

    /** 某模块的 view 权限码(如 develop → develop:view)。 */
    public static String viewPermOf(String module) {
        return module + ":view";
    }
}
