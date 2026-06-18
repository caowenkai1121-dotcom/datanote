package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.project.mapper.DnProjectMilestoneMapper;
import com.datanote.domain.project.mapper.DnProjectTaskMapper;
import com.datanote.domain.project.model.DnProjectMilestone;
import com.datanote.domain.project.model.DnProjectTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/** 项目任务待办 + 里程碑 CRUD 与统计。 */
@Service
@RequiredArgsConstructor
public class ProjectTaskService {

    private static final Set<String> STATUS = new HashSet<>(Arrays.asList("TODO", "DOING", "DONE"));
    private static final Set<String> PRIORITY = new HashSet<>(Arrays.asList("HIGH", "MEDIUM", "LOW"));
    // 任务关联实体类型白名单(防脏数据): 非法类型一律清空
    private static final Set<String> REF_TYPES = new HashSet<>(Arrays.asList("SYNC_JOB", "SCRIPT", "QUALITY_RULE", "METRIC", "GOV_ISSUE"));

    private final DnProjectTaskMapper taskMapper;
    private final DnProjectMilestoneMapper milestoneMapper;
    private final ProjectService projectService;
    private final com.datanote.domain.project.mapper.DnProjectMemberMapper memberMapper;
    private final com.datanote.domain.project.mapper.DnProjectMapper projectMapper;
    private final com.datanote.domain.project.mapper.DnProjectAssetMapper assetMapper;
    private final com.datanote.domain.project.mapper.DnProjectTaskCommentMapper commentMapper;
    private final com.datanote.domain.governance.mapper.DnGovernanceIssueMapper issueMapper;
    // project→governance 单向依赖(治理不依赖项目, 无环): 任务完成回写工单状态
    private final com.datanote.domain.governance.IssueService issueService;
    private final com.datanote.platform.notify.NotificationService notificationService;   // IV-1 旁路通知(fail-safe)

    public List<DnProjectTask> listTasks(Long projectId) {
        projectService.getById(projectId);
        return taskMapper.selectList(new LambdaQueryWrapper<DnProjectTask>()
                .eq(DnProjectTask::getProjectId, projectId).orderByDesc(DnProjectTask::getId));
    }

    public DnProjectTask saveTask(Long projectId, DnProjectTask t) {
        com.datanote.domain.project.model.DnProject proj = projectService.requireProjectPermission(projectId, "task:manage");
        if ("ARCHIVED".equals(proj.getStatus())) throw new IllegalArgumentException("项目已归档, 仅可查看, 不能新建/修改任务");
        if (t == null) throw new IllegalArgumentException("任务内容不能为空");
        if (t.getTitle() == null || t.getTitle().trim().isEmpty()) throw new IllegalArgumentException("任务标题不能为空");
        t.setTitle(t.getTitle().trim());
        if (t.getStatus() == null || !STATUS.contains(t.getStatus())) t.setStatus("TODO");
        if (t.getPriority() == null || !PRIORITY.contains(t.getPriority())) t.setPriority("MEDIUM");
        // 关联实体白名单, 非法类型一律清空(防脏数据)
        if (t.getRefType() != null && !REF_TYPES.contains(t.getRefType())) {
            t.setRefType(null); t.setRefId(null);
        }
        if (t.getRefType() == null) t.setRefId(null);
        validateRef(projectId, t);
        validateAssignee(projectId, t);
        t.setProjectId(projectId);
        String prevAssignee = null;
        if (t.getId() == null) {
            t.setCreatedBy(ProjectService.currentUser());
            taskMapper.insert(t);
        } else {
            DnProjectTask old = taskMapper.selectById(t.getId());
            if (old == null || !projectId.equals(old.getProjectId())) throw new IllegalArgumentException("任务不存在");
            prevAssignee = old.getAssignee();
            t.setCreatedBy(old.getCreatedBy());
            t.setCreatedAt(old.getCreatedAt());
            // 显式 UpdateWrapper: 可空字段(截止日/里程碑/关联/指派/描述)清空才能落库, updateById 跳 null
            taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DnProjectTask>()
                    .eq(DnProjectTask::getId, t.getId())
                    .set(DnProjectTask::getTitle, t.getTitle())
                    .set(DnProjectTask::getDescription, t.getDescription())
                    .set(DnProjectTask::getAssignee, t.getAssignee())
                    .set(DnProjectTask::getPriority, t.getPriority())
                    .set(DnProjectTask::getStatus, t.getStatus())
                    .set(DnProjectTask::getDueDate, t.getDueDate())
                    .set(DnProjectTask::getMilestoneId, t.getMilestoneId())
                    .set(DnProjectTask::getRefType, t.getRefType())
                    .set(DnProjectTask::getRefId, t.getRefId()));
        }
        // IV-1 埋点①: 指派变更通知新指派人(自指派不扰)
        String op = ProjectService.currentUser();
        if (t.getAssignee() != null && !t.getAssignee().isEmpty()
                && !t.getAssignee().equals(prevAssignee) && !t.getAssignee().equals(op)) {
            notificationService.notify(t.getAssignee(), "TASK_ASSIGN",
                    "[任务指派] " + op + " 把任务「" + t.getTitle() + "」指派给你",
                    "project", projectId, "task");
        }
        return t;
    }

    /** B6: 关联引用必须真实存在; 资产类引用必须是本项目已绑定资产(防悬空/越界) */
    private void validateRef(Long projectId, DnProjectTask t) {
        if (t.getRefType() == null) return;
        if (t.getRefId() == null || t.getRefId() <= 0) throw new IllegalArgumentException("关联实体ID非法");
        if ("GOV_ISSUE".equals(t.getRefType())) {
            if (issueMapper.selectById(t.getRefId()) == null) throw new IllegalArgumentException("关联的治理工单不存在");
        } else {
            Long n = assetMapper.selectCount(new LambdaQueryWrapper<com.datanote.domain.project.model.DnProjectAsset>()
                    .eq(com.datanote.domain.project.model.DnProjectAsset::getProjectId, projectId)
                    .eq(com.datanote.domain.project.model.DnProjectAsset::getAssetType, t.getRefType())
                    .eq(com.datanote.domain.project.model.DnProjectAsset::getAssetId, t.getRefId()));
            if (n == null || n == 0) throw new IllegalArgumentException("关联对象不是本项目已绑定资产");
        }
    }

    /**
     * N5: 保存任务并回写治理工单 —— 任务首次置 DONE 且关联 GOV_ISSUE 时, 按状态机推进工单
     * (OPEN→FIXING→RESOLVED 两跳 / FIXING→RESOLVED 一跳); 复检拦截时任务照常保存, 返回 warning。
     * @return {task, warning?}
     */
    private void validateAssignee(Long projectId, DnProjectTask t) {
        if (t.getAssignee() == null || t.getAssignee().trim().isEmpty()) {
            t.setAssignee(null);
            return;
        }
        String assignee = t.getAssignee().trim();
        Long n = memberMapper.selectCount(new LambdaQueryWrapper<com.datanote.domain.project.model.DnProjectMember>()
                .eq(com.datanote.domain.project.model.DnProjectMember::getProjectId, projectId)
                .eq(com.datanote.domain.project.model.DnProjectMember::getUsername, assignee));
        if (n == null || n == 0) throw new IllegalArgumentException("任务指派人必须是项目成员: " + assignee);
        t.setAssignee(assignee);
    }

    public Map<String, Object> saveTaskAndSync(Long projectId, DnProjectTask t) {
        String oldStatus = null;
        if (t != null && t.getId() != null) {
            DnProjectTask old = taskMapper.selectById(t.getId());
            if (old != null) oldStatus = old.getStatus();
        }
        DnProjectTask saved = saveTask(projectId, t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", saved);
        if ("DONE".equals(saved.getStatus()) && !"DONE".equals(oldStatus)
                && "GOV_ISSUE".equals(saved.getRefType()) && saved.getRefId() != null) {
            String warn = advanceIssue(saved.getRefId());
            if (warn != null) out.put("warning", warn);
        }
        return out;
    }

    /** 把工单推进到 RESOLVED(已是 RESOLVED/VERIFIED/CLOSED 不动); 复检不过或异常返回 warning 文案 */
    private String advanceIssue(Long issueId) {
        try {
            com.datanote.domain.governance.model.DnGovernanceIssue issue = issueMapper.selectById(issueId);
            if (issue == null) return null;
            String st = issue.getStatus();
            String op = ProjectService.currentUser();
            if ("OPEN".equals(st)) { issueService.transition(issueId, "FIXING", op); st = "FIXING"; }
            if ("FIXING".equals(st)) issueService.transition(issueId, "RESOLVED", op);
            return null;
        } catch (IllegalStateException e) {
            return "任务已完成, 但关联工单未自动流转: " + e.getMessage();
        } catch (Exception e) {
            return "任务已完成, 但关联工单流转异常: " + e.getMessage();
        }
    }

    /** N5: 按关联实体批量反查任务(工单/资产侧"已转任务/任务×N"徽标, 一条 in 查询防 N+1) */
    public Map<Long, List<Map<String, Object>>> taskRefsBatch(String refType, List<Long> refIds, boolean excludeDone) {
        Map<Long, List<Map<String, Object>>> out = new LinkedHashMap<>();
        if (refType == null || refIds == null || refIds.isEmpty()) return out;
        LambdaQueryWrapper<DnProjectTask> qw = new LambdaQueryWrapper<DnProjectTask>()
                .eq(DnProjectTask::getRefType, refType).in(DnProjectTask::getRefId, refIds);
        if (excludeDone) qw.ne(DnProjectTask::getStatus, "DONE");
        List<DnProjectTask> tasks = taskMapper.selectList(qw);
        if (tasks == null || tasks.isEmpty()) return out;
        Set<Long> pids = new LinkedHashSet<>();
        for (DnProjectTask t : tasks) if (t.getProjectId() != null) pids.add(t.getProjectId());
        Map<Long, String> pname = new HashMap<>();
        if (!pids.isEmpty()) {
            List<com.datanote.domain.project.model.DnProject> _ps = projectMapper.selectBatchIds(pids);
            if (_ps != null) for (com.datanote.domain.project.model.DnProject p : _ps) { // selectList 理论可返回 null
                if (p != null) pname.put(p.getId(), p.getProjectName());
            }
        }
        for (DnProjectTask t : tasks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.getId());
            m.put("projectId", t.getProjectId());
            m.put("projectName", pname.get(t.getProjectId()));
            m.put("title", t.getTitle());
            m.put("status", t.getStatus());
            out.computeIfAbsent(t.getRefId(), k -> new ArrayList<>()).add(m);
        }
        return out;
    }

    public void deleteTask(Long projectId, Long taskId) {
        projectService.requireProjectPermission(projectId, "task:manage");
        projectService.getById(projectId);   // 数据级访问校验, 防跨项目越权
        DnProjectTask t = taskMapper.selectById(taskId);
        if (t == null) return;
        if (!projectId.equals(t.getProjectId())) throw new IllegalArgumentException("任务不属于该项目");
        taskMapper.deleteById(taskId);
        commentMapper.delete(new LambdaQueryWrapper<com.datanote.domain.project.model.DnProjectTaskComment>()
                .eq(com.datanote.domain.project.model.DnProjectTaskComment::getTaskId, taskId));
    }

    // ===== IV-1: 任务评论(协作触达) =====

    public List<com.datanote.domain.project.model.DnProjectTaskComment> listComments(Long projectId, Long taskId) {
        projectService.getById(projectId);   // 数据级访问校验, 防跨项目越权
        DnProjectTask t = taskMapper.selectById(taskId);
        if (t == null || !projectId.equals(t.getProjectId())) throw new IllegalArgumentException("任务不存在");
        return commentMapper.selectList(new LambdaQueryWrapper<com.datanote.domain.project.model.DnProjectTaskComment>()
                .eq(com.datanote.domain.project.model.DnProjectTaskComment::getTaskId, taskId)
                .orderByAsc(com.datanote.domain.project.model.DnProjectTaskComment::getId));
    }

    public com.datanote.domain.project.model.DnProjectTaskComment addComment(Long projectId, Long taskId, String content) {
        projectService.getById(projectId);   // 数据级访问校验, 防跨项目越权
        DnProjectTask t = taskMapper.selectById(taskId);
        if (t == null || !projectId.equals(t.getProjectId())) throw new IllegalArgumentException("任务不存在");
        if (content == null || content.trim().isEmpty()) throw new IllegalArgumentException("评论内容不能为空");
        com.datanote.domain.project.model.DnProjectTaskComment c = new com.datanote.domain.project.model.DnProjectTaskComment();
        c.setTaskId(taskId);
        c.setAuthor(ProjectService.currentUser());   // 服务端取身份, 不信前端
        c.setContent(content.trim().length() > 1000 ? content.trim().substring(0, 1000) : content.trim());
        c.setCreatedAt(java.time.LocalDateTime.now());
        commentMapper.insert(c);
        // IV-1 埋点④: 评论@某人 → 通知被@者(自@不扰); 匹配 @用户名(非空白串)
        java.util.regex.Matcher mat = java.util.regex.Pattern.compile("@([\\w.\\-]+)").matcher(c.getContent());
        java.util.Set<String> ats = new java.util.LinkedHashSet<>();
        while (mat.find()) ats.add(mat.group(1));
        for (String u : ats) {
            if (u.equals(c.getAuthor())) continue;
            notificationService.notify(u, "COMMENT_AT",
                    "[评论提及] " + c.getAuthor() + " 在任务「" + t.getTitle() + "」中@了你: "
                            + (c.getContent().length() > 80 ? c.getContent().substring(0, 80) + "…" : c.getContent()),
                    "project", projectId, "task");
        }
        return c;
    }

    public List<DnProjectMilestone> listMilestones(Long projectId) {
        projectService.getById(projectId);
        return milestoneMapper.selectList(new LambdaQueryWrapper<DnProjectMilestone>()
                .eq(DnProjectMilestone::getProjectId, projectId).orderByAsc(DnProjectMilestone::getId));
    }

    public DnProjectMilestone saveMilestone(Long projectId, DnProjectMilestone m) {
        projectService.requireProjectPermission(projectId, "project:edit");
        if (m == null) throw new IllegalArgumentException("里程碑内容不能为空");
        if (m.getName() == null || m.getName().trim().isEmpty()) throw new IllegalArgumentException("里程碑名称不能为空");
        m.setName(m.getName().trim());
        m.setProjectId(projectId);
        if (m.getId() == null) milestoneMapper.insert(m);
        else {
            DnProjectMilestone old = milestoneMapper.selectById(m.getId());
            if (old == null || !projectId.equals(old.getProjectId())) throw new IllegalArgumentException("里程碑不存在");
            m.setCreatedAt(old.getCreatedAt());
            milestoneMapper.updateById(m);
        }
        return m;
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void deleteMilestone(Long projectId, Long milestoneId) {
        projectService.requireProjectPermission(projectId, "project:edit");
        DnProjectMilestone m = milestoneMapper.selectById(milestoneId);
        if (m == null) return;
        if (!projectId.equals(m.getProjectId())) throw new IllegalArgumentException("里程碑不属于该项目");
        milestoneMapper.deleteById(milestoneId);
        // 解除任务的里程碑关联（set null 需 UpdateWrapper，实体 null 字段会被跳过）
        taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DnProjectTask>()
                .eq(DnProjectTask::getMilestoneId, milestoneId)
                .set(DnProjectTask::getMilestoneId, null));
    }
}
