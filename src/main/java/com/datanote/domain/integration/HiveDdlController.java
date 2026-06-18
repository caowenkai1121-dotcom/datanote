package com.datanote.domain.integration;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.metadata.model.ColumnInfo;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.domain.orchestration.model.DnTaskExecution;
import com.datanote.common.model.R;
import com.datanote.domain.integration.dto.HiveCreateTableRequest;
import com.datanote.domain.integration.dto.HiveExecuteRequest;
import com.datanote.domain.integration.HiveService;
import com.datanote.domain.integration.util.SqlTableReferenceExtractor;
import com.datanote.common.LogBroadcastService;
import com.datanote.domain.governance.MaskingService;
import com.datanote.domain.datasource.MetadataService;
import com.datanote.platform.iam.DataAclService;
import com.datanote.platform.iam.RbacService;
import com.datanote.domain.governance.SqlMaskRewriter;
import com.datanote.common.util.CryptoUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hive DDL 管理 Controller
 */
@Slf4j
@RestController
@RequestMapping({"/api/doris", "/api/hive"})
@RequiredArgsConstructor
@Tag(name = "Doris DDL", description = "Doris 建表语句预览与执行")
public class HiveDdlController {

    private final HiveService hiveService;
    private final MetadataService metadataService;
    private final SimpMessagingTemplate messagingTemplate;
    private final DnTaskExecutionMapper taskExecutionMapper;
    private final DnDatasourceMapper datasourceMapper;
    private final DnSyncTaskMapper syncTaskMapper;
    private final MaskingService maskingService;
    private final RbacService rbacService;
    private final DataAclService dataAclService;

    @Value("${datanote.crypto.key:}")
    private String cryptoKey;

    /** 脱敏改写时为裸表名（不带库名）兜底补全的会话默认库（Doris 连接库） */
    @Value("${doris.database:}")
    private String defaultDb;

    /**
     * 修改 Hive 表字段（ALTER TABLE CHANGE COLUMN）
     */
    @Operation(summary = "修改 Hive 表字段")
    @PostMapping("/alter-column")
    public R<Void> alterColumn(@RequestBody Map<String, String> body) {
        String db = body.get("db");
        String table = body.get("table");
        String oldName = body.get("oldName");
        String newName = body.get("newName");
        String newType = body.get("newType");
        String newComment = body.get("newComment");

        if (db == null || table == null || oldName == null) {
            return R.fail("缺少必要参数");
        }
        if (newName == null || newName.isEmpty()) newName = oldName;
        if (newType == null || newType.isEmpty()) return R.fail("类型不能为空");
        // 全站#10 服务端兜底: 此端点仅服务于数据地图"维护注释", 改名/改类型一律拒绝(直调也拦)——
        // 表结构变更须走数据开发 DDL 流程, 防绕过前端收敛裸改生产表结构
        if (!newName.equals(oldName)) {
            return R.fail("此接口仅支持维护字段注释, 不允许修改字段名(请走数据开发 DDL 流程)");
        }
        // 防 SQL 注入:库/表/列名直拼入 ALTER TABLE,仅允许标识符;类型仅允许类型定义字符
        if (!db.matches("[a-zA-Z0-9_]+") || !table.matches("[a-zA-Z0-9_]+")
                || !oldName.matches("[a-zA-Z0-9_]+") || !newName.matches("[a-zA-Z0-9_]+")) {
            return R.fail("库名/表名/列名含非法字符");
        }
        if (!newType.matches("[a-zA-Z0-9_(), ]+")) {
            return R.fail("字段类型含非法字符");
        }

        try {
            // 构建 ALTER TABLE 语句
            // Hive 语法：ALTER TABLE db.table CHANGE COLUMN old_col new_col type COMMENT 'xxx'
            StringBuilder ddl = new StringBuilder();
            ddl.append("ALTER TABLE ").append(db).append(".").append(table);
            ddl.append(" CHANGE COLUMN ").append(oldName);
            ddl.append(" ").append(newName);
            ddl.append(" ").append(newType);
            if (newComment != null && !newComment.isEmpty()) {
                ddl.append(" COMMENT '").append(com.datanote.domain.integration.util.DorisSqlUtil.escapeSqlLiteral(newComment)).append("'"); // 统一用加固转义工具
            }

            log.info("执行字段修改: {}", ddl);
            hiveService.executeDDL(ddl.toString());
            return R.ok();
        } catch (Exception e) {
            log.error("字段修改失败", e);
            return R.fail("字段修改失败: " + e.getMessage());
        }
    }

    /**
     * 预览 Hive DDL（不执行）
     */
    @Operation(summary = "预览建表 DDL")
    @GetMapping("/preview-ddl")
    public R<Map<String, String>> previewDDL(@RequestParam String db, @RequestParam String table,
                                              @RequestParam(required = false, defaultValue = "df") String syncMode,
                                              @RequestParam(required = false) String datasourceId,
                                              @RequestParam(required = false) Long syncTaskId) {
        try {
            List<ColumnInfo> columns = resolveColumns(datasourceId, syncTaskId, db, table);
            String ddl = hiveService.generateDDL(db, table, columns, syncMode);
            String odsTable = hiveService.getOdsTableName(db, table, syncMode);

            Map<String, String> result = new HashMap<>();
            result.put("ddl", ddl);
            result.put("odsTable", odsTable);
            return R.ok(result);
        } catch (Exception e) {
            log.error("预览 Hive DDL 失败, db={}, table={}", db, table, e);
            return R.fail("预览建表语句失败");
        }
    }

    /**
     * 在线执行 HiveSQL
     */
    @Operation(summary = "执行 HiveSQL")
    @PostMapping("/execute")
    public R<Map<String, Object>> executeSQL(@RequestBody HiveExecuteRequest body) {
        try {
            String sql = body.getSql();
            if (sql == null || sql.trim().isEmpty()) {
                throw new BusinessException("SQL 不能为空");
            }

            // 按分号拆分多条语句，逐条执行，实时推送日志
            String[] statements = sql.split(";");
            // 预先统计有效语句数
            java.util.List<String> validStmts = new java.util.ArrayList<>();
            for (String s : statements) {
                String c = s.replaceAll("--[^\\n]*", "").trim();
                if (!c.isEmpty()) validStmts.add(s.trim());
            }
            int totalStmts = validStmts.size();
            if (totalStmts == 0) throw new BusinessException("没有可执行的 SQL 语句");

            Map<String, Object> lastResult = null;
            int executed = 0;
            List<String> allLogs = new java.util.ArrayList<>();

            // M9：按当前用户装配脱敏/行策略（绕过用户不装配，保证原 SQL 行为）
            boolean bypassMask = isUnmaskedUser();
            List<SqlMaskRewriter.ColumnMask> masks = bypassMask
                    ? java.util.Collections.emptyList() : maskingService.resolveColumnMasks();
            List<SqlMaskRewriter.RowFilter> rowFilters = bypassMask
                    ? java.util.Collections.emptyList() : maskingService.resolveRowFilters(currentUsername());

            for (int i = 0; i < totalStmts; i++) {
                String stmt = validStmts.get(i);
                requireSqlTableAccess(stmt);
                // M9：执行前脱敏改写（绕过用户为原样，受限用户改写失败 fail-closed 抛 BusinessException）
                if (!bypassMask) {
                    stmt = applyMasking(stmt, masks, rowFilters);
                }
                // 实时推送：开始执行第 N 条
                String preview = stmt.length() > 80 ? stmt.substring(0, 80) + "..." : stmt;
                pushSqlLog("INFO", "执行第 " + (i + 1) + "/" + totalStmts + " 条语句: " + preview);

                long start = System.currentTimeMillis();
                Map<String, Object> result = hiveService.executeSQL(stmt);
                long dur = System.currentTimeMillis() - start;
                Boolean success = (Boolean) result.get("success");

                // 实时推送 Hive 日志
                @SuppressWarnings("unchecked")
                List<String> logs = (List<String>) result.get("hiveLogs");
                if (logs != null) {
                    for (String line : logs) pushSqlLog("DORIS", line);
                    allLogs.addAll(logs);
                }

                if (success == null || !success) {
                    pushSqlLog("ERROR", "第 " + (i + 1) + " 条语句失败: " + result.get("error"));
                    result.put("failedAt", "第 " + (i + 1) + "/" + totalStmts + " 条语句");
                    result.put("hiveLogs", allLogs);
                    R<Map<String, Object>> failResp = R.ok(result);
                    failResp.setCode(-1);
                    failResp.setMsg("SQL 执行失败：" + (result.get("error") != null ? result.get("error") : "未知错误"));
                    return failResp;
                }

                pushSqlLog("OK", "第 " + (i + 1) + " 条语句完成，耗时 " + dur + "ms");
                lastResult = result;
                executed++;
            }

            pushSqlLog("OK", "全部 " + executed + " 条语句执行完成");
            lastResult.put("hiveLogs", allLogs);
            lastResult.put("executedCount", executed);
            return R.ok(lastResult);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("执行 HiveSQL 失败", e);
            // 把异常信息塞进 data 返回给前端
            Map<String, Object> errData = new java.util.HashMap<>();
            errData.put("error", "SQL 执行失败，请查看服务端日志");
            R<Map<String, Object>> resp = R.ok(errData);
            resp.setCode(-1);
            resp.setMsg("执行 SQL 失败");
            return resp;
        }
    }

    // 存储待执行的 SQL（POST 提交 → GET SSE 订阅）。executionId 是自增 DB id(可枚举),
    // 故 pending 值绑定提交者 owner: 仅提交者本人可消费/观察, 防他人按 id 越权消费(P0)。
    private static final class PendingExec {
        final String sql;
        final String owner;   // 提交者(开放模式为 null)
        final long createdAt;
        PendingExec(String sql, String owner) { this.sql = sql; this.owner = owner; this.createdAt = System.currentTimeMillis(); }
    }
    private static final long PENDING_TTL_MS = 10 * 60 * 1000L;
    private final java.util.concurrent.ConcurrentHashMap<Long, PendingExec> pendingSql = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 提交 SQL 执行请求（POST），返回 executionId，前端用 executionId 订阅 SSE
     */
    @PostMapping("/submit-execute")
    public R<Map<String, Object>> submitExecute(@RequestBody Map<String, Object> body) {
        String sql = (String) body.get("sql");
        if (sql == null || sql.trim().isEmpty()) {
            return R.fail("SQL 不能为空");
        }
        try {
            requireSqlTableAccess(sql);
        } catch (BusinessException e) {
            return R.fail(e.getMessage());
        }
        Long scriptId = body.get("scriptId") != null ? Long.valueOf(body.get("scriptId").toString()) : null;
        Long syncTaskId = body.get("syncTaskId") != null ? Long.valueOf(body.get("syncTaskId").toString()) : null;

        DnTaskExecution exec = new DnTaskExecution();
        exec.setScriptId(scriptId);
        exec.setSyncTaskId(syncTaskId);
        exec.setTaskType(scriptId != null ? "script" : syncTaskId != null ? "syncTask" : "manual");
        exec.setTriggerType("manual");
        exec.setStatus("RUNNING");
        exec.setStartTime(java.time.LocalDateTime.now());
        taskExecutionMapper.insert(exec);

        // 清理过期未消费项, 防泄漏堆积
        long now = System.currentTimeMillis();
        pendingSql.entrySet().removeIf(en -> now - en.getValue().createdAt > PENDING_TTL_MS);
        pendingSql.put(exec.getId(), new PendingExec(sql, currentUsername()));

        Map<String, Object> result = new HashMap<>();
        result.put("executionId", exec.getId());
        return R.ok(result);
    }

    /**
     * SSE 订阅执行日志（GET，只传 executionId，不传 SQL）
     */
    @GetMapping("/stream-execute")
    public SseEmitter streamExecute(@RequestParam(required = false) String sql,
                                     @RequestParam(required = false) Long executionId,
                                     @RequestParam(required = false) Long scriptId,
                                     @RequestParam(required = false) Long syncTaskId) {
        SseEmitter emitter = new SseEmitter(600000L);

        // 安全: 只接受由 POST /submit-execute(已过写权限校验)生成的 executionId。
        // 删除旧的 ?sql=... 直传分支 —— GET 直传 SQL 可绕过写权限拦截执行任意 DDL/DML(含 DROP), 且易被 CSRF/预取触发。
        final String execSql;
        final Long execId;
        PendingExec pe = executionId == null ? null : pendingSql.get(executionId);
        if (pe == null) {
            try { emitter.send(SseEmitter.event().name("error").data(
                    java.util.Collections.singletonMap("message", "没有可执行的 SQL（请先通过提交执行获取 executionId）"))); emitter.complete(); } catch (Exception ignored) {}
            return emitter;
        }
        // owner 绑定: 仅提交者本人可消费(开放模式 owner 均为 null 相等); 非本人越权 → 拒绝, 条目保留给真正提交者
        if (!java.util.Objects.equals(pe.owner, currentUsername())) {
            try { emitter.send(SseEmitter.event().name("error").data(
                    java.util.Collections.singletonMap("message", "无权消费该执行会话"))); emitter.complete(); } catch (Exception ignored) {}
            return emitter;
        }
        pendingSql.remove(executionId);   // 一次性消费
        execSql = pe.sql;
        execId = executionId;

        // M9：执行前脱敏改写。绕过用户原样；受限用户逐条改写，失败则 fail-closed 拒绝（发送 error 并结束）。
        try {
            requireSqlTableAccess(execSql);
        } catch (BusinessException e) {
            updateExecution(execId, "FAILED", "[ERROR] " + e.getMessage() + "\n", System.currentTimeMillis());
            try {
                emitter.send(SseEmitter.event().name("error").data(
                        java.util.Collections.singletonMap("message", e.getMessage())));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        final String maskedSql;
        if (isUnmaskedUser()) {
            maskedSql = execSql;
        } else {
            try {
                List<SqlMaskRewriter.ColumnMask> masks = maskingService.resolveColumnMasks();
                List<SqlMaskRewriter.RowFilter> rowFilters = maskingService.resolveRowFilters(currentUsername());
                maskedSql = rewriteMultiStatement(execSql, masks, rowFilters);
            } catch (BusinessException e) {
                updateExecution(execId, "FAILED", "[ERROR] " + e.getMessage() + "\n", System.currentTimeMillis());
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                            java.util.Collections.singletonMap("message", e.getMessage())));
                    emitter.complete();
                } catch (Exception ignored) {}
                return emitter;
            }
        }

        final StringBuilder logBuffer = new StringBuilder();
        final long startMs = System.currentTimeMillis();

        new Thread(new Runnable() {
            public void run() {
                try {
                    hiveService.executeSQLWithStream(maskedSql, new HiveService.LogCallback() {
                        public void onLog(String level, String message) {
                            try {
                                logBuffer.append("[").append(level).append("] ").append(message).append("\n");
                                Map<String, Object> data = new HashMap<>();
                                data.put("level", level);
                                data.put("message", message);
                                data.put("time", java.time.LocalDateTime.now().format(
                                    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
                                emitter.send(SseEmitter.event().data(data));
                            } catch (Exception ignored) {}
                        }
                        public void onResult(Map<String, Object> result) {
                            try {
                                // 更新执行记录为成功
                                updateExecution(execId, "SUCCESS", logBuffer.toString(), startMs);
                                emitter.send(SseEmitter.event().name("result").data(result));
                                emitter.complete();
                            } catch (Exception ignored) {}
                        }
                        public void onError(String error) {
                            try {
                                logBuffer.append("[ERROR] ").append(error).append("\n");
                                updateExecution(execId, "FAILED", logBuffer.toString(), startMs);
                                Map<String, Object> data = new HashMap<>();
                                data.put("level", "ERROR");
                                data.put("message", error);
                                emitter.send(SseEmitter.event().name("error").data(data));
                                emitter.complete();
                            } catch (Exception ignored) {}
                        }
                    });
                } catch (Exception e) {
                    try {
                        logBuffer.append("[ERROR] ").append(e.getMessage()).append("\n");
                        updateExecution(execId, "FAILED", logBuffer.toString(), startMs);
                        emitter.send(SseEmitter.event().name("error").data(
                            java.util.Collections.singletonMap("message", e.getMessage())));
                        emitter.complete();
                    } catch (Exception ignored) {}
                }
            }
        }).start();

        return emitter;
    }

    private void updateExecution(Long execId, String status, String log, long startMs) {
        DnTaskExecution update = new DnTaskExecution();
        update.setId(execId);
        update.setStatus(status);
        update.setEndTime(java.time.LocalDateTime.now());
        update.setDuration((int)((System.currentTimeMillis() - startMs) / 1000));
        update.setLog(log.length() > 50000 ? log.substring(log.length() - 50000) : log);
        taskExecutionMapper.updateById(update);
    }

    // ==================== M9 查询期动态脱敏 + 行列权限 ====================

    /**
     * 当前用户是否完全绕过脱敏（原 SQL 执行）：
     *  1) 认证未启用（单用户态，anonymous）—— 保证既有工作台行为零变化；
     *  2) admin（authorities 含 ROLE_ADMIN，或 RBAC 权限集含 '*'）；
     *  3) RBAC 权限集含 'data:unmask'。
     * admin / 单用户态据此短路，永不进入改写、永不被 fail-closed 拒绝。
     */
    private boolean isUnmaskedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // 未启用认证 / 匿名 → 单用户态，完全绕过
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            return true;
        }
        boolean isAdminRole = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_ADMIN".equals(a) || "*".equals(a));
        if (isAdminRole) return true;
        try {
            java.util.Set<String> perms = rbacService.getUserPermsByUsername(auth.getName());
            return perms.contains("*") || perms.contains("data:unmask");
        } catch (Exception e) {
            // RBAC 不可用：保守不绕过（受限态由下游 no-op / fail-closed 处理）
            return false;
        }
    }

    /** 取当前登录用户名（匿名/未登录返回 null）。 */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            return null;
        }
        return auth.getName();
    }

    private void requireSqlTableAccess(String sql) {
        requireSqlDangerPerm(sql);   // P1-04: 破坏性语句(DROP/TRUNCATE/GRANT 等)默认拒绝, 须 sql:danger
        List<SqlTableReferenceExtractor.TableRef> refs;
        try {
            refs = SqlTableReferenceExtractor.extract(sql);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        for (SqlTableReferenceExtractor.TableRef ref : refs) {
            String db = ref.getDb();
            if (db == null || db.trim().isEmpty()) {
                db = defaultDb;
            }
            if (db == null || db.trim().isEmpty()) {
                throw new BusinessException("SQL 引用表缺少库名, 无法校验数据权限");
            }
            String resourceId = db.trim() + "." + ref.getTable().trim();
            if (!dataAclService.canAccess("TABLE", resourceId)) {
                throw new BusinessException("无权访问数据表: " + resourceId);
            }
        }
    }

    /** P1-04: 破坏性 SQL(DROP/TRUNCATE/GRANT/REVOKE/ALTER..DROP)默认拒绝, 仅 sql:danger 或超管(*)可执行。开放模式放行。 */
    private void requireSqlDangerPerm(String sql) {
        String me = currentUsername();
        if (me == null) return;   // 开放模式(无鉴权)放行
        if (!isDangerousSql(sql)) return;
        java.util.Set<String> perms;
        try { perms = rbacService.getUserPermsByUsername(me); } catch (Exception e) { perms = java.util.Collections.emptySet(); }
        if (!com.datanote.platform.iam.RbacService.hasPermission(perms, "sql:danger")) {
            throw new BusinessException("破坏性 SQL(DROP/TRUNCATE/GRANT/REVOKE 等)需 sql:danger 权限, 已拒绝");
        }
    }

    /** 是否含破坏性语句: 去注释后逐语句查首关键字。包级静态便于单测。 */
    static boolean isDangerousSql(String sql) {
        if (sql == null) return false;
        String cleaned = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
        for (String stmt : cleaned.split(";")) {
            String u = stmt.trim().toUpperCase();
            if (u.isEmpty()) continue;
            if (u.matches("(?s)^(DROP|TRUNCATE|GRANT|REVOKE)\\b.*")) return true;
            if (u.matches("(?s)^ALTER\\s+TABLE\\b.*\\bDROP\\b.*")) return true;
        }
        return false;
    }

    /**
     * 脱敏改写网关：对单条 SQL 应用列脱敏 + 行过滤。
     *  - 绕过用户（单用户态/admin/data:unmask）→ 原 SQL；
     *  - 无任何策略命中 → 原 SQL（no-op）；
     *  - 改写失败（解析失败 / SELECT * 降级）→ 抛 BusinessException（fail-closed，拒绝执行）。
     * 任何非改写类异常都不应放行明文，故统一在改写阶段 fail-closed。
     */
    private String applyMasking(String sql,
                                List<SqlMaskRewriter.ColumnMask> masks,
                                List<SqlMaskRewriter.RowFilter> rowFilters) {
        if ((masks == null || masks.isEmpty()) && (rowFilters == null || rowFilters.isEmpty())) {
            return sql; // 无策略，no-op
        }
        try {
            // 注入会话默认库，使不带库名的裸表（SELECT x FROM users）也能匹配元数据/策略
            return SqlMaskRewriter.rewrite(sql, defaultDb, masks, rowFilters);
        } catch (SqlMaskRewriter.MaskRewriteException e) {
            throw new BusinessException("查询被安全策略拦截：" + e.getMessage());
        }
    }

    /**
     * 多语句脱敏：按分号拆分逐条改写后拼回。无策略命中的语句保持原样（rewriter 内部 no-op）。
     * 仅供 streamExecute 使用（executeSQL 自身已逐条改写）。
     */
    private String rewriteMultiStatement(String sql,
                                         List<SqlMaskRewriter.ColumnMask> masks,
                                         List<SqlMaskRewriter.RowFilter> rowFilters) {
        if ((masks == null || masks.isEmpty()) && (rowFilters == null || rowFilters.isEmpty())) {
            return sql; // 无策略，整段原样
        }
        String[] parts = sql.split(";");
        List<String> valid = new java.util.ArrayList<>();
        for (String s : parts) {
            if (!s.replaceAll("--[^\\n]*", "").trim().isEmpty()) valid.add(s.trim());
        }
        if (valid.size() <= 1) {
            // 单条：直接改写整段，保留最大原样性
            return applyMasking(sql.trim(), masks, rowFilters);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < valid.size(); i++) {
            if (i > 0) sb.append(";\n");
            sb.append(applyMasking(valid.get(i), masks, rowFilters));
        }
        return sb.toString();
    }

    /** 通过 WebSocket 实时推送 SQL 执行日志。私有化(P0): 鉴权下推执行者私有队列, 开放/匿名回退全局。
     *  本方法在同步 /execute 请求线程调用, currentUsername() 即执行者。 */
    private void pushSqlLog(String level, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("level", level);
        payload.put("message", message);
        payload.put("time", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        try {
            String user = currentUsername();
            if (user != null && !user.isEmpty()) {
                messagingTemplate.convertAndSendToUser(user, "/queue/sql-log", payload);
            } else {
                messagingTemplate.convertAndSend("/topic/sql-log", payload);   // 开放/匿名模式回退
            }
        } catch (Exception e) {
            log.warn("SQL 日志广播失败: {}", e.getMessage());
        }
    }

    private List<ColumnInfo> resolveColumns(String datasourceId, Long syncTaskId, String db, String table) throws Exception {
        DnDatasource ds = resolveDatasource(datasourceId, syncTaskId);
        if (ds == null) {
            return metadataService.getColumns(db, table);
        }
        return metadataService.getColumnsByConnection(
                ds.getHost(), ds.getPort(), ds.getUsername(), ds.getPassword(), db, table);
    }

    private DnDatasource resolveDatasource(String datasourceId, Long syncTaskId) {
        String dsId = datasourceId;
        if ((dsId == null || dsId.isEmpty()) && syncTaskId != null) {
            DnSyncTask task = syncTaskMapper.selectById(syncTaskId);
            if (task != null && task.getSourceDsId() != null) {
                dsId = String.valueOf(task.getSourceDsId());
            }
        }
        if (dsId == null || dsId.isEmpty()) {
            return null;
        }
        DnDatasource ds = datasourceMapper.selectById(Long.valueOf(dsId));
        if (ds != null) {
            ds.setPassword(CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey));
        }
        return ds;
    }

    /**
     * 执行 Hive 建表
     */
    @Operation(summary = "创建 Hive 表")
    @PostMapping("/create-table")
    public R<Map<String, String>> createTable(@RequestBody HiveCreateTableRequest body) {
        try {
            String db = body.getDb();
            String table = body.getTable();
            String syncMode = body.getSyncMode() != null ? body.getSyncMode() : "df";

            List<ColumnInfo> columns = resolveColumns(body.getDatasourceId(), body.getSyncTaskId(), db, table);
            String ddl = hiveService.generateDDL(db, table, columns, syncMode);
            hiveService.executeDDL(ddl);

            String odsTable = hiveService.getOdsTableName(db, table, syncMode);
            Map<String, String> result = new HashMap<>();
            result.put("odsTable", odsTable);
            result.put("ddl", ddl);
            return R.ok(result);
        } catch (Exception e) {
            log.error("创建 Hive 表失败, db={}, table={}", body.getDb(), body.getTable(), e);
            return R.fail("创建 Hive 表失败");
        }
    }
}
