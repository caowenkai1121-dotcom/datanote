package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.governance.mapper.DnMaturityAssessmentMapper;
import com.datanote.domain.governance.model.DnMaturityAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DCMM 成熟度自评服务 —— 八大域问卷录入(同域覆盖最新) + 列表(供雷达)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaturityService {

    private final DnMaturityAssessmentMapper assessmentMapper;

    /** DCMM 八大能力域 */
    public static final List<String> DCMM_DOMAINS = Arrays.asList(
            "数据战略", "数据治理", "数据架构", "数据应用",
            "数据安全", "数据质量", "数据标准", "数据生存周期");

    public List<String> domains() {
        // 返回只读视图，防止调用方误改共享静态域列表
        return Collections.unmodifiableList(DCMM_DOMAINS);
    }

    /** 八大域最新自评列表（每域取最近一次） */
    public List<DnMaturityAssessment> latest() {
        QueryWrapper<DnMaturityAssessment> qw = new QueryWrapper<>();
        qw.orderByDesc("assessed_at");
        List<DnMaturityAssessment> all = assessmentMapper.selectList(qw);
        // selectList 在无数据/异常边界可能返回 null，统一兜底为空列表
        if (all == null || all.isEmpty()) {
            return new ArrayList<>();
        }
        // 同域只保留最新（已按时间倒序，首次出现即最新）
        Map<String, DnMaturityAssessment> byDomain = new LinkedHashMap<>();
        for (DnMaturityAssessment a : all) {
            // 域为空的脏数据不纳入雷达，避免污染聚合与产生 null 键
            if (a == null || a.getDomain() == null) {
                continue;
            }
            byDomain.putIfAbsent(a.getDomain(), a);
        }
        return new ArrayList<>(byDomain.values());
    }

    /** 录入自评：同域覆盖最新（updateById 或 insert）。校验域合法、分 0-100、等级 1-5。 */
    @Transactional(rollbackFor = Exception.class)
    public DnMaturityAssessment assess(DnMaturityAssessment a) {
        if (a == null) {
            throw new BusinessException("自评数据不能为空");
        }
        if (a.getDomain() == null || a.getDomain().trim().isEmpty() || !DCMM_DOMAINS.contains(a.getDomain())) {
            throw new BusinessException("非法 DCMM 域: " + a.getDomain());
        }
        if (a.getScore() == null) {
            throw new BusinessException("分数不能为空，应在 0-100");
        }
        double score = a.getScore().doubleValue();
        if (score < 0 || score > 100) {
            throw new BusinessException("分数应在 0-100，当前: " + score);
        }
        if (a.getLevel() == null) {
            throw new BusinessException("等级不能为空，应在 1-5");
        }
        int level = a.getLevel();
        if (level < 1 || level > 5) {
            throw new BusinessException("等级应在 1-5，当前: " + level);
        }
        a.setLevel(level);
        a.setAssessedAt(LocalDateTime.now());

        // 同域已有记录则更新，否则新增（读-改-写置于同一事务，避免并发产生重复行）
        QueryWrapper<DnMaturityAssessment> qw = new QueryWrapper<>();
        qw.eq("domain", a.getDomain()).orderByDesc("assessed_at").last("LIMIT 1");
        DnMaturityAssessment exist = assessmentMapper.selectOne(qw);
        if (exist != null) {
            a.setId(exist.getId());
            int rows = assessmentMapper.updateById(a);
            if (rows <= 0) {
                throw new BusinessException("更新 DCMM 自评失败: " + a.getDomain());
            }
        } else {
            a.setId(null);
            int rows = assessmentMapper.insert(a);
            if (rows <= 0) {
                throw new BusinessException("新增 DCMM 自评失败: " + a.getDomain());
            }
        }
        return a;
    }
}
