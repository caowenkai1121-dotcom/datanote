package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.project.mapper.DnProjectAssetMapper;
import com.datanote.domain.project.mapper.DnProjectMemberMapper;
import com.datanote.domain.project.mapper.DnProjectReleaseMapper;
import com.datanote.domain.project.model.DnProject;
import com.datanote.domain.project.model.DnProjectAsset;
import com.datanote.domain.project.model.DnProjectMember;
import com.datanote.domain.project.model.DnProjectRelease;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 项目发布版本：提交/审批/驳回/发布/回滚，状态机护栏。
 * N1: 审批/上线/回滚要求 release:approve 角色; 多人项目禁自批(单人项目放行, 不困死一人团队)。
 * N6: 上线门禁——核验资产清单(存在/仍绑定/最近运行非失败), 强规则失败硬拦; 四动作审计留痕。
 */
@Service
@RequiredArgsConstructor
public class ProjectReleaseService {

    private final DnProjectReleaseMapper releaseMapper;
    private final ProjectService projectService;
    private final DnProjectMemberMapper memberMapper;
    private final DnProjectAssetMapper assetMapper;
    private final com.datanote.domain.integration.mapper.DnSyncJobMapper syncJobMapper;
    private final com.datanote.domain.develop.mapper.DnScriptMapper scriptMapper;
    private final com.datanote.domain.governance.mapper.DnQualityRuleMapper qualityRuleMapper;
    private final com.datanote.domain.governance.mapper.DnQualityRunMapper qualityRunMapper;
    private final com.datanote.domain.governance.mapper.DnMetricMapper metricMapper;
    private final com.datanote.domain.consumption.mapper.DnMetricValueMapper metricValueMapper;
    private final com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper taskExecutionMapper;
    private final com.datanote.platform.audit.AuditService auditService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.datanote.platform.notify.NotificationService notificationService;   // IV-1 旁路通知(fail-safe)

    public List<DnProjectRelease> list(Long projectId) {
        projectService.getById(projectId);
        return releaseMapper.selectList(new LambdaQueryWrapper<DnProjectRelease>()
                .eq(DnProjectRelease::getProjectId, projectId)
                .orderByDesc(DnProjectRelease::getVersionNo));
    }

    /** 跨项目发布中心：status 非空则过滤，按 id 倒序，最多 200。回填 projectName(已删项目标注)。 */
    public List<Map<String, Object>> listAll(String status) {
        LambdaQueryWrapper<DnProjectRelease> w = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) w.eq(DnProjectRelease::getStatus, status.trim());
        w.orderByDesc(DnProjectRelease::getId).last("LIMIT 200");
        List<DnProjectRelease> rows = releaseMapper.selectList(w);
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) return out;
        Set<Long> pids = new LinkedHashSet<>();
        for (DnProjectRelease r : rows) if (r != null && r.getProjectId() != null) pids.add(r.getProjectId());
        Map<Long, DnProject> byId = new HashMap<>();
        for (DnProject p : projectService.list(null)) if (p != null) byId.put(p.getId(), p);
        com.fasterxml.jackson.databind.ObjectMapper om = objectMapper;
        for (DnProjectRelease r : rows) {
            if (r == null) continue;
            Map<String, Object> m = om.convertValue(r, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            DnProject p = byId.get(r.getProjectId());
            m.put("projectName", p != null ? p.getProjectName() : "项目已删除");
            out.add(m);
        }
        return out;
    }

    public synchronized DnProjectRelease submit(Long projectId, String title, String content, String targetEnv, String assetJson) {
        DnProject proj = projectService.getById(projectId);
        if ("ARCHIVED".equals(proj.getStatus())) throw new IllegalArgumentException("项目已归档, 仅可查看, 不能提交发布");
        validateAssetJson(projectId, assetJson);   // N6: 清单项必须是本项目绑定资产
        DnProjectRelease r = new DnProjectRelease();
        r.setProjectId(projectId);
        r.setVersionNo(nextVersion(projectId));
        r.setTitle(title == null || title.trim().isEmpty() ? ("发布 v" + r.getVersionNo()) : title.trim());
        r.setContent(content);
        r.setAssetJson(assetJson);
        r.setTargetEnv(targetEnv == null || targetEnv.trim().isEmpty() ? "PROD" : targetEnv.trim());
        r.setStatus("PENDING");
        r.setSubmittedBy(ProjectService.currentUser());
        r.setSubmittedAt(LocalDateTime.now());
        releaseMapper.insert(r);
        return r;
    }

    public void approve(Long releaseId, String comment) {
        DnProjectRelease r = get(releaseId);
        requireApprover(r, true);
        ReleaseState.require(r.getStatus(), "APPROVED");
        r.setStatus("APPROVED");
        r.setApprover(ProjectService.currentUser());
        r.setApprovedAt(LocalDateTime.now());
        r.setApproveComment(comment);
        releaseMapper.updateById(r);
        audit(r, "approve", "审批通过 v" + r.getVersionNo());
        notifySubmitter(r, "[发布通过] v" + r.getVersionNo() + " " + nvl(r.getTitle()) + " 已审批通过, 可发布上线");
    }

    public void reject(Long releaseId, String comment) {
        DnProjectRelease r = get(releaseId);
        requireApprover(r, false);
        ReleaseState.require(r.getStatus(), "REJECTED");
        r.setStatus("REJECTED");
        r.setApprover(ProjectService.currentUser());
        r.setApprovedAt(LocalDateTime.now());
        r.setApproveComment(comment);
        releaseMapper.updateById(r);
        audit(r, "reject", "驳回 v" + r.getVersionNo());
        notifySubmitter(r, "[发布驳回] v" + r.getVersionNo() + " " + nvl(r.getTitle())
                + (comment == null || comment.trim().isEmpty() ? "" : " — 原因: " + comment.trim()));
    }

    /** IV-1 埋点②: 审批结果通知提交人(自批不扰) */
    private void notifySubmitter(DnProjectRelease r, String title) {
        String approver = ProjectService.currentUser();
        if (r.getSubmittedBy() == null || r.getSubmittedBy().equals(approver)) return;
        notificationService.notify(r.getSubmittedBy(), "RELEASE_RESULT", title, "project", r.getProjectId(), "release");
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    public void release(Long releaseId, boolean force) {
        DnProjectRelease r = get(releaseId);
        requireApprover(r, false);
        ReleaseState.require(r.getStatus(), "RELEASED");
        // N6 上线门禁: 核验资产清单
        Map<String, List<String>> verdict = verifyAssets(r);
        List<String> hard = verdict.get("hard"), soft = verdict.get("soft");
        if (!hard.isEmpty()) throw new IllegalArgumentException("上线被拦截(强规则失败, 不可强制): " + String.join("; ", hard));
        if (!soft.isEmpty() && !force) throw new IllegalArgumentException("FORCEABLE:" + String.join("; ", soft));
        r.setStatus("RELEASED");
        r.setReleasedAt(LocalDateTime.now());
        releaseMapper.updateById(r);
        audit(r, "release", "发布上线 v" + r.getVersionNo()
                + (soft.isEmpty() ? "" : " [强制放行: " + String.join("; ", soft) + "]"));
    }

    public void rollback(Long releaseId) {
        DnProjectRelease r = get(releaseId);
        requireApprover(r, false);
        ReleaseState.require(r.getStatus(), "ROLLED_BACK");
        r.setStatus("ROLLED_BACK");
        releaseMapper.updateById(r);
        audit(r, "rollback", "回滚 v" + r.getVersionNo());
    }

    // ========== N1 权限 ==========

    /** P5: 当前用户拥有 release:approve 的项目 id 集合(发布中心按钮角色化, 一次查询防 N+1) */
    public List<Long> myApprovableProjects() {
        String user = ProjectService.currentUser();
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        List<DnProjectMember> ms = memberMapper.selectList(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getUsername, user));
        if (ms != null) {
            for (DnProjectMember m : ms) {
                if (m != null && ProjectRoles.can(m.getProjectRole(), "release:approve")) out.add(m.getProjectId());
            }
        }
        for (DnProject p : projectService.list(null)) {
            if (p != null && user.equals(p.getOwner())) out.add(p.getId());
        }
        return new ArrayList<>(out);
    }

    /** 当前用户在该项目的角色(member 表; 项目 owner 兜底 OWNER; 非成员 null) */
    String roleOf(Long projectId, String username) {
        DnProjectMember m = memberMapper.selectOne(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, projectId)
                .eq(DnProjectMember::getUsername, username).last("LIMIT 1"));
        if (m != null) return m.getProjectRole();
        DnProject p = projectService.getById(projectId);
        return username.equals(p.getOwner()) ? "OWNER" : null;
    }

    /**
     * 审批/上线/回滚须 release:approve; checkSelf=true 时多人项目禁自批
     * (项目中存在其他可审批成员才拦, 单人项目放行——不困死一人团队)。
     */
    private void requireApprover(DnProjectRelease r, boolean checkSelf) {
        String user = ProjectService.currentUser();
        String role = roleOf(r.getProjectId(), user);
        if (!ProjectRoles.can(role, "release:approve"))
            throw new IllegalArgumentException("无审批权限(需项目负责人/管理员)");
        if (checkSelf && user.equals(r.getSubmittedBy()) && hasOtherApprover(r.getProjectId(), user))
            throw new IllegalArgumentException("不能审批自己提交的发布单, 请由其他负责人/管理员审批");
    }

    private boolean hasOtherApprover(Long projectId, String exceptUser) {
        List<DnProjectMember> ms = memberMapper.selectList(new LambdaQueryWrapper<DnProjectMember>()
                .eq(DnProjectMember::getProjectId, projectId));
        if (ms != null) {
            for (DnProjectMember m : ms) {
                if (m == null || exceptUser.equals(m.getUsername())) continue;
                if (ProjectRoles.can(m.getProjectRole(), "release:approve")) return true;
            }
        }
        DnProject p = projectService.getById(projectId);
        return p.getOwner() != null && !exceptUser.equals(p.getOwner());
    }

    // ========== N6 门禁 ==========

    private List<Map<String, Object>> parseAssets(String assetJson) {
        if (assetJson == null || assetJson.trim().isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(assetJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("资产清单格式非法");
        }
    }

    /** 提交时: 清单项必须是本项目已绑定资产(防伪造清单) */
    private void validateAssetJson(Long projectId, String assetJson) {
        for (Map<String, Object> a : parseAssets(assetJson)) {
            String type = String.valueOf(a.get("assetType"));
            Long aid = a.get("assetId") instanceof Number ? ((Number) a.get("assetId")).longValue() : null;
            if (aid == null) throw new IllegalArgumentException("资产清单缺少 assetId");
            Long n = assetMapper.selectCount(new LambdaQueryWrapper<DnProjectAsset>()
                    .eq(DnProjectAsset::getProjectId, projectId)
                    .eq(DnProjectAsset::getAssetType, type)
                    .eq(DnProjectAsset::getAssetId, aid));
            if (n == null || n == 0) throw new IllegalArgumentException("清单含非本项目绑定资产: " + type + "#" + aid);
        }
    }

    /** 上线前核验: hard=强规则失败(blockDownstream=1 质量规则 failed, 不可 force); soft=可 force 警示项 */
    private Map<String, List<String>> verifyAssets(DnProjectRelease r) {
        List<String> hard = new ArrayList<>(), soft = new ArrayList<>();
        for (Map<String, Object> a : parseAssets(r.getAssetJson())) {
            String type = String.valueOf(a.get("assetType"));
            Long aid = a.get("assetId") instanceof Number ? ((Number) a.get("assetId")).longValue() : null;
            String name = a.get("assetName") == null ? ("#" + aid) : String.valueOf(a.get("assetName"));
            if (aid == null) continue;
            // 仍绑定本项目
            Long bound = assetMapper.selectCount(new LambdaQueryWrapper<DnProjectAsset>()
                    .eq(DnProjectAsset::getProjectId, r.getProjectId())
                    .eq(DnProjectAsset::getAssetType, type).eq(DnProjectAsset::getAssetId, aid));
            if (bound == null || bound == 0) { soft.add(name + " 已不在项目资产中"); continue; }
            if ("SYNC_JOB".equals(type)) {
                if (syncJobMapper.selectById(aid) == null) { soft.add("同步任务 " + name + " 已删除"); continue; }
                com.datanote.domain.orchestration.model.DnTaskExecution e = taskExecutionMapper.selectOne(
                        new LambdaQueryWrapper<com.datanote.domain.orchestration.model.DnTaskExecution>()
                                .eq(com.datanote.domain.orchestration.model.DnTaskExecution::getSyncTaskId, aid)
                                .eq(com.datanote.domain.orchestration.model.DnTaskExecution::getTaskType, "DbSync")
                                .in(com.datanote.domain.orchestration.model.DnTaskExecution::getStatus, "SUCCESS", "FAILED")
                                .orderByDesc(com.datanote.domain.orchestration.model.DnTaskExecution::getId).last("LIMIT 1"));
                if (e != null && "FAILED".equals(e.getStatus())) soft.add("同步任务 " + name + " 最近一次运行失败");
            } else if ("SCRIPT".equals(type)) {
                if (scriptMapper.selectById(aid) == null) soft.add("脚本 " + name + " 已删除");
            } else if ("QUALITY_RULE".equals(type)) {
                com.datanote.domain.governance.model.DnQualityRule rule = qualityRuleMapper.selectById(aid);
                if (rule == null) { soft.add("质量规则 " + name + " 已删除"); continue; }
                com.datanote.domain.governance.model.DnQualityRun run = qualityRunMapper.selectOne(
                        new LambdaQueryWrapper<com.datanote.domain.governance.model.DnQualityRun>()
                                .eq(com.datanote.domain.governance.model.DnQualityRun::getRuleId, aid)
                                .orderByDesc(com.datanote.domain.governance.model.DnQualityRun::getId).last("LIMIT 1"));
                if (run != null && "failed".equalsIgnoreCase(run.getRunStatus())) {
                    if (rule.getBlockDownstream() != null && rule.getBlockDownstream() == 1)
                        hard.add("质量规则 " + name + " 检查失败(强规则)");
                    else soft.add("质量规则 " + name + " 检查失败");
                }
            } else if ("METRIC".equals(type)) {
                // 指标无 blockDownstream 概念, 一律 soft 警示项
                com.datanote.domain.governance.model.DnMetric metric = metricMapper.selectById(aid);
                if (metric == null) { soft.add("指标 " + name + " 已删除"); continue; }
                if (metric.getStatus() == null || metric.getStatus() != 1) soft.add("指标 " + name + " 已停用");
                else if (metric.getCalcFormula() == null || metric.getCalcFormula().trim().isEmpty()) soft.add("指标 " + name + " 无计算公式");
                else {
                    com.datanote.domain.consumption.model.DnMetricValue mv = metricValueMapper.selectOne(
                            new LambdaQueryWrapper<com.datanote.domain.consumption.model.DnMetricValue>()
                                    .eq(com.datanote.domain.consumption.model.DnMetricValue::getMetricId, aid)
                                    .orderByDesc(com.datanote.domain.consumption.model.DnMetricValue::getId).last("LIMIT 1"));
                    if (mv != null && "error".equalsIgnoreCase(mv.getRunStatus())) soft.add("指标 " + name + " 最近取值失败");
                }
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("hard", hard);
        out.put("soft", soft);
        return out;
    }

    /** N6: 审批链路留痕进全局审计(项目活动流自动可见) */
    private void audit(DnProjectRelease r, String action, String detail) {
        try {
            auditService.record(ProjectService.currentUser(), "project-release", "POST",
                    "/api/project/" + r.getProjectId() + "/releases/" + r.getId() + "/" + action,
                    "local", 200, detail);
        } catch (Exception ignore) {}
    }

    private DnProjectRelease get(Long id) {
        DnProjectRelease r = releaseMapper.selectById(id);
        if (r == null) throw new IllegalArgumentException("发布版本不存在: " + id);
        return r;
    }

    private int nextVersion(Long projectId) {
        List<DnProjectRelease> rs = releaseMapper.selectList(new LambdaQueryWrapper<DnProjectRelease>()
                .eq(DnProjectRelease::getProjectId, projectId)
                .orderByDesc(DnProjectRelease::getVersionNo).last("LIMIT 1"));
        return rs == null || rs.isEmpty() || rs.get(0).getVersionNo() == null ? 1 : rs.get(0).getVersionNo() + 1;
    }
}
