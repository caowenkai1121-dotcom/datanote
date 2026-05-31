package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnProjectMilestoneMapper;
import com.datanote.mapper.DnProjectTaskMapper;
import com.datanote.model.DnProjectMilestone;
import com.datanote.model.DnProjectTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/** 项目任务待办 + 里程碑 CRUD 与统计。 */
@Service
@RequiredArgsConstructor
public class ProjectTaskService {

    private static final Set<String> STATUS = new HashSet<>(Arrays.asList("TODO", "DOING", "DONE"));
    private static final Set<String> PRIORITY = new HashSet<>(Arrays.asList("HIGH", "MEDIUM", "LOW"));

    private final DnProjectTaskMapper taskMapper;
    private final DnProjectMilestoneMapper milestoneMapper;
    private final ProjectService projectService;

    public List<DnProjectTask> listTasks(Long projectId) {
        projectService.getById(projectId);
        return taskMapper.selectList(new LambdaQueryWrapper<DnProjectTask>()
                .eq(DnProjectTask::getProjectId, projectId).orderByDesc(DnProjectTask::getId));
    }

    public DnProjectTask saveTask(Long projectId, DnProjectTask t) {
        projectService.getById(projectId);
        if (t.getTitle() == null || t.getTitle().trim().isEmpty()) throw new IllegalArgumentException("任务标题不能为空");
        t.setTitle(t.getTitle().trim());
        if (t.getStatus() == null || !STATUS.contains(t.getStatus())) t.setStatus("TODO");
        if (t.getPriority() == null || !PRIORITY.contains(t.getPriority())) t.setPriority("MEDIUM");
        t.setProjectId(projectId);
        if (t.getId() == null) {
            t.setCreatedBy(ProjectService.currentUser());
            taskMapper.insert(t);
        } else {
            DnProjectTask old = taskMapper.selectById(t.getId());
            if (old == null || !projectId.equals(old.getProjectId())) throw new IllegalArgumentException("任务不存在");
            t.setCreatedBy(old.getCreatedBy());
            t.setCreatedAt(old.getCreatedAt());
            taskMapper.updateById(t);
        }
        return t;
    }

    public void deleteTask(Long projectId, Long taskId) {
        DnProjectTask t = taskMapper.selectById(taskId);
        if (t == null) return;
        if (!projectId.equals(t.getProjectId())) throw new IllegalArgumentException("任务不属于该项目");
        taskMapper.deleteById(taskId);
    }

    public List<DnProjectMilestone> listMilestones(Long projectId) {
        projectService.getById(projectId);
        return milestoneMapper.selectList(new LambdaQueryWrapper<DnProjectMilestone>()
                .eq(DnProjectMilestone::getProjectId, projectId).orderByAsc(DnProjectMilestone::getId));
    }

    public DnProjectMilestone saveMilestone(Long projectId, DnProjectMilestone m) {
        projectService.getById(projectId);
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

    public void deleteMilestone(Long projectId, Long milestoneId) {
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
