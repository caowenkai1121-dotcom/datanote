package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnGovernanceIssueMapper;
import com.datanote.model.DnGovernanceIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 治理工单服务 —— CRUD + 状态流转(OPEN→FIXING→RESOLVED→VERIFIED→CLOSED) + owner 路由 + 排行榜。
 * isLegalTransition 为纯函数（状态机，可单测）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueService {

    private final DnGovernanceIssueMapper issueMapper;

    /** 状态机：源态 -> 合法目标态集合 */
    private static final Map<String, Set<String>> FLOW = new HashMap<>();

    static {
        FLOW.put("OPEN", new HashSet<>(Arrays.asList("FIXING", "CLOSED")));
        FLOW.put("FIXING", new HashSet<>(Arrays.asList("RESOLVED", "OPEN")));
        FLOW.put("RESOLVED", new HashSet<>(Arrays.asList("VERIFIED", "FIXING")));
        FLOW.put("VERIFIED", new HashSet<>(Arrays.asList("CLOSED", "FIXING")));
        FLOW.put("CLOSED", new HashSet<>(Collections.singletonList("OPEN")));
    }

    // ========== 纯函数：状态机 ==========

    /** 判断从 from 流转到 to 是否合法 */
    public static boolean isLegalTransition(String from, String to) {
        if (from == null || to == null) return false;
        Set<String> next = FLOW.get(from);
        return next != null && next.contains(to);
    }

    /** 当前状态可流转到的目标态（供前端给按钮） */
    public static Set<String> nextStatuses(String from) {
        Set<String> s = FLOW.get(from);
        return s == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(s);
    }

    // ========== CRUD ==========

    /** 列表，可按 status/owner/dimension 过滤 */
    public List<DnGovernanceIssue> list(String status, String owner, String dimension) {
        QueryWrapper<DnGovernanceIssue> qw = new QueryWrapper<>();
        if (notBlank(status)) qw.eq("status", status);
        if (notBlank(owner)) qw.eq("owner", owner);
        if (notBlank(dimension)) qw.eq("dimension", dimension);
        qw.orderByDesc("updated_at");
        return issueMapper.selectList(qw);
    }

    public DnGovernanceIssue create(DnGovernanceIssue issue) {
        if (issue.getStatus() == null || issue.getStatus().isEmpty()) issue.setStatus("OPEN");
        if (issue.getSeverity() == null || issue.getSeverity().isEmpty()) issue.setSeverity("MEDIUM");
        issue.setId(null);
        issue.setCreatedAt(LocalDateTime.now());
        issue.setUpdatedAt(LocalDateTime.now());
        issueMapper.insert(issue);
        return issue;
    }

    public void delete(Long id) {
        issueMapper.deleteById(id);
    }

    /** 状态流转：校验合法性，非法抛 IllegalStateException */
    public DnGovernanceIssue transition(Long id, String toStatus, String operator) {
        DnGovernanceIssue issue = issueMapper.selectById(id);
        if (issue == null) throw new IllegalStateException("工单不存在: " + id);
        if (!isLegalTransition(issue.getStatus(), toStatus)) {
            throw new IllegalStateException("非法流转: " + issue.getStatus() + " -> " + toStatus);
        }
        issue.setStatus(toStatus);
        issue.setUpdatedAt(LocalDateTime.now());
        issueMapper.updateById(issue);
        return issue;
    }

    /** 按 owner 路由：指派负责人 */
    public DnGovernanceIssue assign(Long id, String owner) {
        DnGovernanceIssue issue = issueMapper.selectById(id);
        if (issue == null) throw new IllegalStateException("工单不存在: " + id);
        issue.setOwner(owner);
        issue.setUpdatedAt(LocalDateTime.now());
        issueMapper.updateById(issue);
        return issue;
    }

    /** 排行榜：按 owner 统计 总数/未关单/已关单 */
    public List<Map<String, Object>> leaderboard() {
        List<DnGovernanceIssue> all = issueMapper.selectList(null);
        Map<String, long[]> agg = new LinkedHashMap<>(); // owner -> [total, open, closed]
        for (DnGovernanceIssue i : all) {
            String owner = notBlank(i.getOwner()) ? i.getOwner() : "未分配";
            long[] a = agg.computeIfAbsent(owner, k -> new long[3]);
            a[0]++;
            if ("CLOSED".equals(i.getStatus())) a[2]++;
            else a[1]++;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("owner", e.getKey());
            m.put("total", e.getValue()[0]);
            m.put("open", e.getValue()[1]);
            m.put("closed", e.getValue()[2]);
            list.add(m);
        }
        list.sort((x, y) -> Long.compare((Long) y.get("open"), (Long) x.get("open")));
        return list;
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
