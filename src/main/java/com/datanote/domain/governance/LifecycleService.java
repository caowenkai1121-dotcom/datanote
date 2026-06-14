package com.datanote.domain.governance;

import com.datanote.domain.governance.mapper.DnLifecyclePolicyMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.orchestration.mapper.DnLineageEdgeMapper;
import com.datanote.platform.config.mapper.DnSystemConfigMapper;
import com.datanote.platform.portal.mapper.DnAssetStatMapper;
import com.datanote.domain.governance.model.DnLifecyclePolicy;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.domain.orchestration.model.DnLineageEdge;
import com.datanote.platform.config.model.DnSystemConfig;
import com.datanote.platform.portal.model.DnAssetStat;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.config.HiveConfig;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.integration.util.DorisSqlUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 生命周期服务 —— 策略 CRUD + 应用下发 Doris DDL（容错降级）、资产快照采集、
 * 无用表识别（复用 dn_table_meta/dn_lineage_edge）、销毁三道护栏、成本估算与排行。
 * 打分/DDL 构造等纯逻辑全在 {@link LifecycleScorer}，本类只做编排与持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleService {

    private final DnLifecyclePolicyMapper policyMapper;
    private final DnAssetStatMapper assetStatMapper;
    private final DnTableMetaMapper tableMetaMapper;
    private final DnLineageEdgeMapper lineageEdgeMapper;
    private final DnSystemConfigMapper systemConfigMapper;
    private final HiveConfig hiveConfig;

    /** 成本排行无显式上限时的默认条数。 */
    private static final int DEFAULT_COST_LIMIT = 50;
    /** 成本排行硬上限，防止超大结果集一次性返回压垮内存/前端。 */
    private static final int MAX_COST_LIMIT = 1000;
    /** 血缘键分隔符：控制字符，库/表名不可能含此字符，杜绝名含点时的键碰撞。 */
    private static final char KEY_SEP = '';

    // ========== 可配项（dn_system_config 兜底默认） ==========

    private double unitPrice() {
        return configDouble("lifecycle.cost.unit_price", 0.05);
    }

    private int graceDays() {
        return (int) configDouble("lifecycle.drop.grace_days", 30);
    }

    private int idleDays() {
        return (int) configDouble("lifecycle.unused.access_days", LifecycleScorer.DEFAULT_IDLE_DAYS);
    }

    private double configDouble(String key, double dft) {
        try {
            DnSystemConfig cfg = systemConfigMapper.selectById(key);
            if (cfg != null && cfg.getConfigValue() != null && !cfg.getConfigValue().trim().isEmpty()) {
                return Double.parseDouble(cfg.getConfigValue().trim());
            }
        } catch (NumberFormatException e) {
            log.warn("生命周期配置项[{}]非法数值，回退默认值 {}: {}", key, dft, e.getMessage());
        } catch (Exception e) {
            // 配置读取异常不应影响主流程，降级为默认值
            log.warn("读取生命周期配置项[{}]失败，回退默认值 {}: {}", key, dft, e.getMessage());
        }
        return dft;
    }

    // ========== 策略 CRUD ==========

    public List<DnLifecyclePolicy> listPolicies() {
        QueryWrapper<DnLifecyclePolicy> qw = new QueryWrapper<>();
        qw.orderByDesc("updated_at");
        List<DnLifecyclePolicy> list = policyMapper.selectList(qw);
        return list == null ? new ArrayList<>() : list;
    }

    public DnLifecyclePolicy savePolicy(DnLifecyclePolicy p) {
        if (p == null) throw new BusinessException("策略对象不能为空");
        if (p.getDbName() == null || p.getDbName().trim().isEmpty()
                || p.getTableName() == null || p.getTableName().trim().isEmpty()) {
            throw new BusinessException("库名/表名不能为空");
        }
        if (p.getPolicyType() == null || p.getPolicyType().trim().isEmpty()) {
            throw new BusinessException("策略类型不能为空(HOT_COLD/TTL/ARCHIVE)");
        }
        p.setUpdatedAt(LocalDateTime.now());
        if (p.getId() == null) {
            if (p.getEnabled() == null) p.setEnabled(1);
            if (p.getStatus() == null) p.setStatus("NEW");
            p.setCreatedAt(LocalDateTime.now());
            policyMapper.insert(p);
        } else {
            policyMapper.updateById(p);
        }
        return p;
    }

    public void deletePolicy(Long id) {
        if (id == null) throw new BusinessException("策略ID不能为空");
        policyMapper.deleteById(id);
    }

    public void togglePolicy(Long id) {
        if (id == null) throw new BusinessException("策略ID不能为空");
        DnLifecyclePolicy p = policyMapper.selectById(id);
        if (p == null) return; // 策略不存在则静默忽略（行为保持）
        p.setEnabled(p.getEnabled() != null && p.getEnabled() == 1 ? 0 : 1);
        p.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(p);
    }

    /**
     * 应用策略：构造 Doris 原生 DDL 经 hiveConfig 连接执行。
     * 下发失败（如 Doris 未配对象存储冷后端）→ 捕获异常，status=PENDING + last_msg，绝不抛崩溃。
     */
    @Transactional(rollbackFor = Exception.class)
    public DnLifecyclePolicy applyPolicy(Long id) {
        if (id == null) throw new BusinessException("策略ID不能为空");
        DnLifecyclePolicy p = policyMapper.selectById(id);
        if (p == null) throw new BusinessException("策略不存在: " + id);

        String ddl;
        try {
            ddl = LifecycleScorer.buildDorisDdl(p.getDbName(), p.getTableName(),
                    p.getPolicyType(), p.getColdDays(), p.getTtlDays());
        } catch (IllegalArgumentException e) {
            p.setStatus("FAILED");
            p.setLastMsg("DDL 构造失败: " + e.getMessage());
            p.setUpdatedAt(LocalDateTime.now());
            policyMapper.updateById(p);
            return p;
        }
        p.setDdlText(ddl);

        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            p.setStatus("ACTIVE");
            p.setLastMsg("下发成功 @" + LocalDateTime.now());
        } catch (Exception e) {
            // 降级：Doris 不可用 / 冷后端未配 / 表无分区等 → 记 PENDING，等条件就绪再重试
            p.setStatus("PENDING");
            p.setLastMsg("下发失败(已降级待重试): " + trim(e.getMessage(), 400));
            log.warn("生命周期策略下发失败 id={} {}.{}: {}", id, p.getDbName(), p.getTableName(), e.getMessage());
        }
        p.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(p);
        return p;
    }

    // ========== 资产快照采集 ==========

    /** 遍历 dn_table_meta 生成 dn_asset_stat 快照（体量/行数复用元数据；成本=单价×体量）。返回采集条数。 */
    @Transactional(rollbackFor = Exception.class)
    public int collectStats() {
        double price = unitPrice();
        List<DnTableMeta> metas = tableMetaMapper.selectList(null);
        if (metas == null || metas.isEmpty()) return 0;
        int n = 0;
        for (DnTableMeta m : metas) {
            if (m == null) continue;
            DnAssetStat s = new DnAssetStat();
            s.setTableMetaId(m.getId());
            s.setDbName(m.getDatabaseName());
            s.setTableName(m.getTableName());
            s.setSizeBytes(m.getSizeBytes());
            s.setRowCount(m.getRowCount());
            // 最近访问无独立采集源，先以最近采集时间兜底
            s.setLastAccessAt(m.getLastCollectedAt());
            long size = m.getSizeBytes() == null ? 0 : m.getSizeBytes();
            s.setCostEstimate(BigDecimal.valueOf(LifecycleScorer.estimateCost(size, price)));
            s.setCollectedAt(LocalDateTime.now());
            assetStatMapper.insert(s);
            n++;
        }
        return n;
    }

    // ========== 无用表识别 ==========

    /**
     * 无用表清单：对每张表查四要素（久未访问 + 体量 + 下游血缘 + 任务引用）经纯函数打分，
     * 仅返回 candidate 候选，按分倒序。
     */
    public List<Map<String, Object>> unusedTables() {
        int idle = idleDays();
        List<DnTableMeta> metas = tableMetaMapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>();
        if (metas == null || metas.isEmpty()) return result;
        LocalDateTime now = LocalDateTime.now();
        // 一次性加载全部血缘边构建内存映射，消除原先每表 2 次 selectCount 的 N+1 查询（语义等价）
        List<DnLineageEdge> edges = lineageEdgeMapper.selectList(null);
        Set<String> downstreamSrc = new HashSet<>();   // level_type=TABLE 的源端键（下游血缘判定面）
        Set<String> taskRefKeys = new HashSet<>();      // job_id 非空时，源端键 + 目标端键
        if (edges != null) {
            for (DnLineageEdge e : edges) {
                if (e == null) continue;
                if ("TABLE".equals(e.getLevelType())) {
                    String srcKey = lineageKey(e.getSrcDb(), e.getSrcTable());
                    if (srcKey != null) downstreamSrc.add(srcKey);
                }
                if (e.getJobId() != null) {
                    String srcKey = lineageKey(e.getSrcDb(), e.getSrcTable());
                    if (srcKey != null) taskRefKeys.add(srcKey);
                    String dstKey = lineageKey(e.getDstDb(), e.getDstTable());
                    if (dstKey != null) taskRefKeys.add(dstKey);
                }
            }
        }
        for (DnTableMeta m : metas) {
            if (m == null) continue;
            String db = m.getDatabaseName();
            String table = m.getTableName();
            // 全站#9: 系统库不进无用表候选(mysql.user 等出现过"标记销毁"按钮)
            if (db != null && com.datanote.domain.datasource.DatasourceExploreService.SYS_DBS.contains(db.toLowerCase())) continue;
            String key = lineageKey(db, table);
            long lastAccessDays = m.getLastCollectedAt() == null
                    ? 36500 : ChronoUnit.DAYS.between(m.getLastCollectedAt(), now);
            long size = m.getSizeBytes() == null ? 0 : m.getSizeBytes();
            boolean hasDownstream = key != null && downstreamSrc.contains(key);
            boolean hasTaskRef = key != null && taskRefKeys.contains(key);
            LifecycleScorer.UnusedScore sc = LifecycleScorer.scoreUnusedTable(
                    lastAccessDays, size, hasDownstream, hasTaskRef);
            if (!sc.candidate) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tableMetaId", m.getId());
            row.put("db", db);
            row.put("table", table);
            row.put("sizeBytes", m.getSizeBytes());
            row.put("rowCount", m.getRowCount());
            row.put("lastAccessDays", lastAccessDays);
            row.put("hasDownstreamLineage", hasDownstream);
            row.put("hasTaskRef", hasTaskRef);
            row.put("score", sc.score);
            row.put("idleThreshold", idle);
            result.add(row);
        }
        result.sort((a, b) -> ((Integer) b.get("score")).compareTo((Integer) a.get("score")));
        return result;
    }

    /** 血缘键：库+表（控制字符分隔）；任一为空返回 null（与原 eq null 永不匹配语义一致）。 */
    private static String lineageKey(String db, String table) {
        if (db == null || table == null) return null;
        return db + KEY_SEP + table;
    }

    /** 是否存在以该表为源的下游血缘边（销毁第一道护栏的判定面）。 */
    private boolean hasDownstreamLineage(String db, String table) {
        QueryWrapper<DnLineageEdge> qw = new QueryWrapper<>();
        qw.eq("level_type", "TABLE").eq("src_db", db).eq("src_table", table);
        Long cnt = lineageEdgeMapper.selectCount(qw);
        return cnt != null && cnt > 0;
    }

    // ========== 销毁三道护栏 ==========

    /**
     * 标记销毁（进入软删宽限期）。绝不物理删表。
     * 护栏：①血缘影响校验——有下游边则禁止；②软删宽限期 drop_due_at=now+grace；③审批留痕 approver/reason。
     */
    @Transactional(rollbackFor = Exception.class)
    public DnLifecyclePolicy markForDrop(String db, String table, String approver, String reason) {
        if (db == null || db.trim().isEmpty() || table == null || table.trim().isEmpty()) {
            throw new BusinessException("库名/表名不能为空");
        }
        // 全站#9: 系统库禁止标记销毁
        if (com.datanote.domain.datasource.DatasourceExploreService.SYS_DBS.contains(db.trim().toLowerCase())) {
            throw new BusinessException("系统库不可标记销毁: " + db);
        }
        if (approver == null || approver.trim().isEmpty()) {
            throw new BusinessException("销毁必须填写审批人(留痕)");
        }
        // 护栏①：血缘影响校验
        if (hasDownstreamLineage(db, table)) {
            throw new BusinessException("存在下游血缘，禁止销毁: " + db + "." + table);
        }
        LocalDateTime now = LocalDateTime.now();
        // 销毁单独立用 policy_type=DROP 承载，不复用 ARCHIVE 归档/TTL 策略，避免覆写正常归档配置
        DnLifecyclePolicy p = findPolicy(db, table, "DROP");
        if (p == null) {
            p = new DnLifecyclePolicy();
            p.setDbName(db);
            p.setTableName(table);
            p.setPolicyType("DROP");
            p.setEnabled(1);
            p.setCreatedAt(now);
        }
        // 护栏②③：软删宽限期 + 审批留痕
        p.setStatus("DROP_PENDING");
        p.setDropDueAt(LifecycleScorer.dropDueAt(now, graceDays()));
        p.setApprover(approver);
        p.setReason(reason);
        p.setLastMsg("已标记销毁，宽限至 " + p.getDropDueAt());
        p.setUpdatedAt(now);
        if (p.getId() == null) policyMapper.insert(p);
        else policyMapper.updateById(p);
        return p;
    }

    /**
     * 执行到期销毁：仅 status=DROP_PENDING 且 drop_due_at≤now 的才真正 DROP TABLE（经 hiveConfig）。
     * 执行前再次复核血缘护栏。返回 [{db,table,result}]。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> executeDueDrops() {
        QueryWrapper<DnLifecyclePolicy> qw = new QueryWrapper<>();
        qw.eq("status", "DROP_PENDING").le("drop_due_at", LocalDateTime.now());
        List<DnLifecyclePolicy> due = policyMapper.selectList(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        if (due == null || due.isEmpty()) return out;
        for (DnLifecyclePolicy p : due) {
            if (p == null) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("db", p.getDbName());
            r.put("table", p.getTableName());
            // 执行前复核血缘护栏（宽限期内可能新增下游）
            if (hasDownstreamLineage(p.getDbName(), p.getTableName())) {
                p.setStatus("FAILED");
                p.setLastMsg("销毁中止：宽限期内新增下游血缘");
                p.setUpdatedAt(LocalDateTime.now());
                policyMapper.updateById(p);
                r.put("result", "中止(新增下游血缘)");
                out.add(r);
                continue;
            }
            try {
                dropTable(p.getDbName(), p.getTableName());
                p.setStatus("DROPPED");
                p.setLastMsg("已销毁 @" + LocalDateTime.now());
                r.put("result", "已销毁");
            } catch (Exception e) {
                p.setStatus("FAILED");
                p.setLastMsg("销毁失败: " + trim(e.getMessage(), 400));
                r.put("result", "失败: " + e.getMessage());
                log.warn("到期销毁失败 {}.{}: {}", p.getDbName(), p.getTableName(), e.getMessage());
            }
            p.setUpdatedAt(LocalDateTime.now());
            policyMapper.updateById(p);
            out.add(r);
        }
        return out;
    }

    private void dropTable(String db, String table) throws SQLException {
        if (db == null || !db.matches("[a-zA-Z0-9_]+") || table == null || !table.matches("[a-zA-Z0-9_]+")) {
            throw new SQLException("非法的库名或表名");
        }
        String sql = "DROP TABLE IF EXISTS " + DorisSqlUtil.quoteQualified(db, table);
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private DnLifecyclePolicy findPolicy(String db, String table, String type) {
        QueryWrapper<DnLifecyclePolicy> qw = new QueryWrapper<>();
        qw.eq("db_name", db).eq("table_name", table).eq("policy_type", type).last("LIMIT 1");
        return policyMapper.selectOne(qw);
    }

    // ========== 成本排行 ==========

    /** 成本排行：取每张表最近一次快照，按成本倒序。 */
    public List<Map<String, Object>> costRanking(int limit) {
        QueryWrapper<DnAssetStat> qw = new QueryWrapper<>();
        // 仅取近 90 天快照界定扫描量(快照随采集累积无限增长; 更老的本就过期、表多已停采集),
        // 仍保留所有活跃表的"最新一次"快照, 避免原先全表无界 selectList。
        qw.ge("collected_at", LocalDateTime.now().minusDays(90)).orderByDesc("collected_at");
        List<DnAssetStat> all = assetStatMapper.selectList(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        if (all == null || all.isEmpty()) return out;
        Map<String, DnAssetStat> latest = new LinkedHashMap<>();
        for (DnAssetStat s : all) {
            if (s == null) continue;
            String k = s.getDbName() + KEY_SEP + s.getTableName();
            if (!latest.containsKey(k)) latest.put(k, s); // 已按 collected_at 倒序，首条即最新
        }
        List<DnAssetStat> list = new ArrayList<>(latest.values());
        list.sort((a, b) -> nz(b.getCostEstimate()).compareTo(nz(a.getCostEstimate())));
        int cap = limit > 0 ? limit : DEFAULT_COST_LIMIT;
        if (cap > MAX_COST_LIMIT) cap = MAX_COST_LIMIT; // 上限守卫，防超大结果集
        for (DnAssetStat s : list) {
            if (out.size() >= cap) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("db", s.getDbName());
            row.put("table", s.getTableName());
            row.put("sizeBytes", s.getSizeBytes());
            row.put("rowCount", s.getRowCount());
            row.put("costEstimate", s.getCostEstimate());
            row.put("collectedAt", s.getCollectedAt());
            out.add(row);
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
