package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.governance.mapper.DnMaturityAssessmentMapper;
import com.datanote.domain.governance.model.DnMaturityAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

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
        return DCMM_DOMAINS;
    }

    /** 八大域最新自评列表（每域取最近一次） */
    public List<DnMaturityAssessment> latest() {
        QueryWrapper<DnMaturityAssessment> qw = new QueryWrapper<>();
        qw.orderByDesc("assessed_at");
        List<DnMaturityAssessment> all = assessmentMapper.selectList(qw);
        // 同域只保留最新（已按时间倒序，首次出现即最新）
        java.util.Map<String, DnMaturityAssessment> byDomain = new java.util.LinkedHashMap<>();
        for (DnMaturityAssessment a : all) {
            byDomain.putIfAbsent(a.getDomain(), a);
        }
        return new java.util.ArrayList<>(byDomain.values());
    }

    /** 录入自评：同域覆盖最新（updateById 或 insert）。校验域合法、分 0-100、等级 1-5。 */
    public DnMaturityAssessment assess(DnMaturityAssessment a) {
        if (a.getDomain() == null || !DCMM_DOMAINS.contains(a.getDomain())) {
            throw new IllegalArgumentException("非法 DCMM 域: " + a.getDomain());
        }
        double score = a.getScore() == null ? 0 : a.getScore().doubleValue();
        if (score < 0 || score > 100) throw new IllegalArgumentException("分数应在 0-100");
        int level = a.getLevel() == null ? 1 : a.getLevel();
        if (level < 1 || level > 5) throw new IllegalArgumentException("等级应在 1-5");
        a.setLevel(level);
        a.setAssessedAt(LocalDateTime.now());

        // 同域已有记录则更新，否则新增
        QueryWrapper<DnMaturityAssessment> qw = new QueryWrapper<>();
        qw.eq("domain", a.getDomain()).orderByDesc("assessed_at").last("LIMIT 1");
        DnMaturityAssessment exist = assessmentMapper.selectOne(qw);
        if (exist != null) {
            a.setId(exist.getId());
            assessmentMapper.updateById(a);
        } else {
            a.setId(null);
            assessmentMapper.insert(a);
        }
        return a;
    }
}
