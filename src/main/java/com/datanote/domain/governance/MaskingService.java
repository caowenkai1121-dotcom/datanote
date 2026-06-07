package com.datanote.domain.governance;

import com.datanote.platform.iam.RbacService;
import com.datanote.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.governance.mapper.DnMaskingPolicyMapper;
import com.datanote.domain.governance.mapper.DnRowPolicyMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnColumnMeta;
import com.datanote.domain.governance.model.DnMaskingPolicy;
import com.datanote.domain.governance.model.DnRowPolicy;
import com.datanote.domain.metadata.model.DnTableMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脱敏 / 行级权限服务 — 策略 CRUD + 按当前用户装配「可见性」（列脱敏 + 行过滤），
 * 供 {@link SqlMaskRewriter} 纯函数改写。
 *
 * 装配口径（保守，宁可多脱敏不少脱敏）：
 *  - 列脱敏：取所有启用脱敏策略。SENSITIVE_TYPE 维度联查 dn_column_meta.sensitive_type
 *    展开到具体 库.表.列；COLUMN 维度直接用策略自带的库表列。全集交给 rewriter 按 SQL 实际涉及表过滤。
 *  - 行过滤：按当前用户角色 role_code 查启用的 dn_row_policy。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MaskingService {

    private final DnMaskingPolicyMapper maskingPolicyMapper;
    private final DnRowPolicyMapper rowPolicyMapper;
    private final DnColumnMetaMapper columnMetaMapper;
    private final DnTableMetaMapper tableMetaMapper;
    private final RbacService rbacService;

    // ========== 脱敏策略 CRUD ==========

    public List<DnMaskingPolicy> listMaskingPolicies() {
        QueryWrapper<DnMaskingPolicy> qw = new QueryWrapper<>();
        qw.orderByAsc("id");
        List<DnMaskingPolicy> list = maskingPolicyMapper.selectList(qw);
        return list == null ? new ArrayList<>() : list;
    }

    public DnMaskingPolicy saveMaskingPolicy(DnMaskingPolicy p) {
        if (p == null) throw new BusinessException("脱敏策略不能为空");
        if (!notBlank(p.getMatchDim())) throw new BusinessException("匹配维度(matchDim)不能为空");
        p.setUpdatedAt(LocalDateTime.now());
        if (p.getEnabled() == null) p.setEnabled(1);
        if (p.getMaskingFunc() == null || p.getMaskingFunc().isEmpty()) p.setMaskingFunc("MASK");
        if (p.getId() == null) {
            p.setCreatedAt(LocalDateTime.now());
            maskingPolicyMapper.insert(p);
        } else {
            maskingPolicyMapper.updateById(p);
        }
        return p;
    }

    public void deleteMaskingPolicy(Long id) {
        if (id == null || id <= 0) throw new BusinessException("脱敏策略ID非法");
        maskingPolicyMapper.deleteById(id);
    }

    // ========== 行策略 CRUD ==========

    public List<DnRowPolicy> listRowPolicies() {
        QueryWrapper<DnRowPolicy> qw = new QueryWrapper<>();
        qw.orderByAsc("id");
        List<DnRowPolicy> list = rowPolicyMapper.selectList(qw);
        return list == null ? new ArrayList<>() : list;
    }

    public DnRowPolicy saveRowPolicy(DnRowPolicy p) {
        if (p == null) throw new BusinessException("行策略不能为空");
        if (!notBlank(p.getRoleCode())) throw new BusinessException("角色编码(roleCode)不能为空");
        if (!notBlank(p.getDbName()) || !notBlank(p.getTableName()) || !notBlank(p.getRowFilter())) {
            throw new BusinessException("库名/表名/行过滤条件不能为空");
        }
        p.setUpdatedAt(LocalDateTime.now());
        if (p.getEnabled() == null) p.setEnabled(1);
        if (p.getId() == null) {
            p.setCreatedAt(LocalDateTime.now());
            rowPolicyMapper.insert(p);
        } else {
            rowPolicyMapper.updateById(p);
        }
        return p;
    }

    public void deleteRowPolicy(Long id) {
        if (id == null || id <= 0) throw new BusinessException("行策略ID非法");
        rowPolicyMapper.deleteById(id);
    }

    // ========== 装配当前用户可见性 ==========

    /** 装配该用户的列脱敏清单（库.表.列 → 脱敏函数）。 */
    public List<SqlMaskRewriter.ColumnMask> resolveColumnMasks() {
        List<SqlMaskRewriter.ColumnMask> masks = new ArrayList<>();
        QueryWrapper<DnMaskingPolicy> qw = new QueryWrapper<>();
        qw.eq("enabled", 1);
        List<DnMaskingPolicy> policies = maskingPolicyMapper.selectList(qw);
        if (policies == null || policies.isEmpty()) return masks;

        // sensitive_type → maskingFunc（多策略命中同类型时，后者覆盖）
        Map<String, String> typeFunc = new HashMap<>();
        for (DnMaskingPolicy p : policies) {
            if (p == null) continue;
            if ("COLUMN".equalsIgnoreCase(p.getMatchDim())) {
                if (notBlank(p.getDbName()) && notBlank(p.getTableName()) && notBlank(p.getColumnName())) {
                    masks.add(new SqlMaskRewriter.ColumnMask(
                            p.getDbName(), p.getTableName(), p.getColumnName(), p.getMaskingFunc()));
                }
            } else if ("SENSITIVE_TYPE".equalsIgnoreCase(p.getMatchDim()) && notBlank(p.getSensitiveType())) {
                typeFunc.put(p.getSensitiveType().toUpperCase(), p.getMaskingFunc());
            }
        }

        // SENSITIVE_TYPE 维度：联查 dn_column_meta.sensitive_type 展开到具体列
        if (!typeFunc.isEmpty()) {
            QueryWrapper<DnColumnMeta> cq = new QueryWrapper<>();
            cq.isNotNull("sensitive_type").ne("sensitive_type", "");
            List<DnColumnMeta> cols = columnMetaMapper.selectList(cq);
            if (cols != null && !cols.isEmpty()) {
                // 先收集命中类型的列所属表ID，批量查表元数据，消除「每表一次 selectById」的 N+1
                Set<Long> tableIds = new LinkedHashSet<>();
                for (DnColumnMeta cm : cols) {
                    if (cm == null) continue;
                    String st = cm.getSensitiveType() == null ? null : cm.getSensitiveType().toUpperCase();
                    if (st == null || !typeFunc.containsKey(st)) continue;
                    if (cm.getTableMetaId() != null) tableIds.add(cm.getTableMetaId());
                }
                Map<Long, DnTableMeta> tableCache = new HashMap<>();
                if (!tableIds.isEmpty()) {
                    List<DnTableMeta> tables = tableMetaMapper.selectBatchIds(tableIds);
                    if (tables != null) {
                        for (DnTableMeta tm : tables) {
                            if (tm != null && tm.getId() != null) tableCache.put(tm.getId(), tm);
                        }
                    }
                }
                for (DnColumnMeta cm : cols) {
                    if (cm == null) continue;
                    String st = cm.getSensitiveType() == null ? null : cm.getSensitiveType().toUpperCase();
                    if (st == null || !typeFunc.containsKey(st)) continue;
                    DnTableMeta tm = cm.getTableMetaId() == null ? null : tableCache.get(cm.getTableMetaId());
                    if (tm == null || tm.getDatabaseName() == null || tm.getTableName() == null
                            || cm.getColumnName() == null || cm.getColumnName().isEmpty()) continue;
                    masks.add(new SqlMaskRewriter.ColumnMask(
                            tm.getDatabaseName(), tm.getTableName(), cm.getColumnName(), typeFunc.get(st)));
                }
            }
        }
        return masks;
    }

    /** 装配该用户（按角色）的行过滤清单（库.表 → WHERE 片段）。 */
    public List<SqlMaskRewriter.RowFilter> resolveRowFilters(String username) {
        List<SqlMaskRewriter.RowFilter> filters = new ArrayList<>();
        if (username == null || username.isEmpty()) return filters;
        List<String> roleCodes;
        try {
            roleCodes = rbacService.getUserRoleCodesByUsername(username);
        } catch (Exception e) {
            // fail-closed：角色解析失败不可静默放行（否则受限用户将拿到无行过滤的全量数据）
            log.error("行级权限角色解析失败,受限用户拒绝执行, username={}", username, e);
            throw new BusinessException("行级权限解析失败，已拒绝执行(fail-closed)");
        }
        if (roleCodes == null || roleCodes.isEmpty()) return filters;

        QueryWrapper<DnRowPolicy> qw = new QueryWrapper<>();
        qw.eq("enabled", 1).in("role_code", roleCodes);
        List<DnRowPolicy> rps = rowPolicyMapper.selectList(qw);
        if (rps == null) return filters;
        for (DnRowPolicy rp : rps) {
            if (rp == null) continue;
            if (notBlank(rp.getDbName()) && notBlank(rp.getTableName()) && notBlank(rp.getRowFilter())) {
                filters.add(new SqlMaskRewriter.RowFilter(rp.getDbName(), rp.getTableName(), rp.getRowFilter()));
            }
        }
        return filters;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
