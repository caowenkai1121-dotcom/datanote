package com.datanote.domain.governance;

import com.datanote.platform.iam.RbacService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnColumnMetaMapper;
import com.datanote.mapper.DnMaskingPolicyMapper;
import com.datanote.mapper.DnRowPolicyMapper;
import com.datanote.mapper.DnTableMetaMapper;
import com.datanote.model.DnColumnMeta;
import com.datanote.model.DnMaskingPolicy;
import com.datanote.model.DnRowPolicy;
import com.datanote.model.DnTableMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        return maskingPolicyMapper.selectList(qw);
    }

    public DnMaskingPolicy saveMaskingPolicy(DnMaskingPolicy p) {
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
        maskingPolicyMapper.deleteById(id);
    }

    // ========== 行策略 CRUD ==========

    public List<DnRowPolicy> listRowPolicies() {
        QueryWrapper<DnRowPolicy> qw = new QueryWrapper<>();
        qw.orderByAsc("id");
        return rowPolicyMapper.selectList(qw);
    }

    public DnRowPolicy saveRowPolicy(DnRowPolicy p) {
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
        rowPolicyMapper.deleteById(id);
    }

    // ========== 装配当前用户可见性 ==========

    /** 装配该用户的列脱敏清单（库.表.列 → 脱敏函数）。 */
    public List<SqlMaskRewriter.ColumnMask> resolveColumnMasks() {
        List<SqlMaskRewriter.ColumnMask> masks = new ArrayList<>();
        QueryWrapper<DnMaskingPolicy> qw = new QueryWrapper<>();
        qw.eq("enabled", 1);
        List<DnMaskingPolicy> policies = maskingPolicyMapper.selectList(qw);
        if (policies.isEmpty()) return masks;

        // sensitive_type → maskingFunc（多策略命中同类型时，后者覆盖）
        Map<String, String> typeFunc = new HashMap<>();
        for (DnMaskingPolicy p : policies) {
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
            Map<Long, DnTableMeta> tableCache = new HashMap<>();
            for (DnColumnMeta cm : cols) {
                String st = cm.getSensitiveType() == null ? null : cm.getSensitiveType().toUpperCase();
                if (st == null || !typeFunc.containsKey(st)) continue;
                DnTableMeta tm = tableCache.computeIfAbsent(cm.getTableMetaId(), tableMetaMapper::selectById);
                if (tm == null || tm.getDatabaseName() == null || tm.getTableName() == null
                        || cm.getColumnName() == null || cm.getColumnName().isEmpty()) continue;
                masks.add(new SqlMaskRewriter.ColumnMask(
                        tm.getDatabaseName(), tm.getTableName(), cm.getColumnName(), typeFunc.get(st)));
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
            roleCodes = new ArrayList<>();
        }
        if (roleCodes.isEmpty()) return filters;

        QueryWrapper<DnRowPolicy> qw = new QueryWrapper<>();
        qw.eq("enabled", 1).in("role_code", roleCodes);
        for (DnRowPolicy rp : rowPolicyMapper.selectList(qw)) {
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
