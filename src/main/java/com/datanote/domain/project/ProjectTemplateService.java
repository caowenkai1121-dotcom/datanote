package com.datanote.domain.project;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.project.mapper.DnProjectMemberMapper;
import com.datanote.domain.project.mapper.DnProjectTemplateMapper;
import com.datanote.domain.project.model.DnProject;
import com.datanote.domain.project.model.DnProjectMember;
import com.datanote.domain.project.model.DnProjectTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 项目模板：存为模板(快照类型/env/标签/成员角色) + 从模板初始化新项目。 */
@Service
@RequiredArgsConstructor
public class ProjectTemplateService {

    private final DnProjectTemplateMapper templateMapper;
    private final DnProjectMemberMapper memberMapper;
    private final ProjectService projectService;
    private final com.datanote.domain.project.mapper.DnProjectTaskMapper taskMapper;   // II-5 任务骨架快照/重建

    public List<DnProjectTemplate> list() {
        return templateMapper.selectList(new LambdaQueryWrapper<DnProjectTemplate>().orderByDesc(DnProjectTemplate::getId));
    }

    public DnProjectTemplate saveAsTemplate(Long projectId, String name, String description) {
        DnProject p = projectService.getById(projectId);
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("模板名不能为空");
        name = name.trim();
        Long dup = templateMapper.selectCount(new LambdaQueryWrapper<DnProjectTemplate>().eq(DnProjectTemplate::getTemplateName, name));
        if (dup != null && dup > 0) throw new IllegalArgumentException("模板已存在: " + name);

        // 模板仅快照项目基础属性与成员，刻意不含 env-params（设备/库密钥等敏感值），新建后需重新配置。
        JSONObject cfg = new JSONObject();
        cfg.put("projectType", p.getProjectType());
        cfg.put("env", p.getEnv());
        cfg.put("tags", p.getTags());
        cfg.put("sensitivity", p.getSensitivity());
        com.alibaba.fastjson.JSONArray members = new com.alibaba.fastjson.JSONArray();
        List<DnProjectMember> memberList = memberMapper.selectList(new LambdaQueryWrapper<DnProjectMember>().eq(DnProjectMember::getProjectId, projectId));
        if (memberList != null) for (DnProjectMember m : memberList) {
            if (m == null) continue;
            if ("OWNER".equals(m.getProjectRole())) continue; // owner 由新建者承担
            JSONObject mm = new JSONObject();
            mm.put("username", m.getUsername());
            mm.put("role", m.getProjectRole());
            members.add(mm);
        }
        cfg.put("members", members);
        // II-5 模板做厚: 快照任务骨架(SOP)——title/priority/相对截止天数, 不带指派人
        com.alibaba.fastjson.JSONArray tasks = new com.alibaba.fastjson.JSONArray();
        java.time.LocalDate today = java.time.LocalDate.now();
        List<com.datanote.domain.project.model.DnProjectTask> taskList = taskMapper.selectList(
                new LambdaQueryWrapper<com.datanote.domain.project.model.DnProjectTask>()
                        .eq(com.datanote.domain.project.model.DnProjectTask::getProjectId, projectId)
                        .ne(com.datanote.domain.project.model.DnProjectTask::getStatus, "DONE")
                        .last("LIMIT 50"));
        if (taskList != null) for (com.datanote.domain.project.model.DnProjectTask tk : taskList) {
            if (tk == null || tk.getTitle() == null) continue;
            JSONObject tt = new JSONObject();
            tt.put("title", tk.getTitle());
            tt.put("priority", tk.getPriority());
            if (tk.getDueDate() != null) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(today, tk.getDueDate());
                if (days > 0) tt.put("dueDays", days);
            }
            tasks.add(tt);
        }
        cfg.put("tasks", tasks);

        DnProjectTemplate t = new DnProjectTemplate();
        t.setTemplateName(name);
        t.setTemplateType(p.getProjectType());
        t.setDescription(description);
        t.setConfigJson(cfg.toJSONString());
        t.setCreatedBy(ProjectService.currentUser());
        templateMapper.insert(t);
        return t;
    }

    @Transactional(rollbackFor = Exception.class)
    public DnProject createFromTemplate(Long templateId, String projectName) {
        DnProjectTemplate t = templateMapper.selectById(templateId);
        if (t == null) throw new IllegalArgumentException("模板不存在");
        if (projectName == null || projectName.trim().isEmpty()) throw new IllegalArgumentException("项目名不能为空");
        JSONObject cfg = t.getConfigJson() == null ? new JSONObject() : JSON.parseObject(t.getConfigJson());

        DnProject p = new DnProject();
        p.setProjectName(projectName.trim());
        p.setProjectType(cfg.getString("projectType"));
        p.setEnv(cfg.getString("env"));
        p.setTags(cfg.getString("tags"));
        p.setSensitivity(cfg.getString("sensitivity"));
        projectService.save(p); // 创建 + owner(当前用户) 自动入成员

        com.alibaba.fastjson.JSONArray members = cfg.getJSONArray("members");
        if (members != null) {
            for (int i = 0; i < members.size(); i++) {
                JSONObject mm = members.getJSONObject(i);
                String username = mm.getString("username");
                String role = mm.getString("role");
                if (username == null || username.isEmpty() || !ProjectRoles.isValid(role)) continue;
                Long dup = memberMapper.selectCount(new LambdaQueryWrapper<DnProjectMember>()
                        .eq(DnProjectMember::getProjectId, p.getId()).eq(DnProjectMember::getUsername, username));
                if (dup != null && dup > 0) continue;
                DnProjectMember m = new DnProjectMember();
                m.setProjectId(p.getId());
                m.setUsername(username);
                m.setProjectRole(role);
                m.setAddedBy(ProjectService.currentUser());
                memberMapper.insert(m);
            }
        }
        // II-5: 模板任务骨架批量建 TODO(旧模板无 tasks 空数组兜底; 相对截止天数换算为绝对日期)
        com.alibaba.fastjson.JSONArray tasks = cfg.getJSONArray("tasks");
        if (tasks != null) {
            java.time.LocalDate today = java.time.LocalDate.now();
            for (int i = 0; i < tasks.size(); i++) {
                JSONObject tt = tasks.getJSONObject(i);
                String title = tt.getString("title");
                if (title == null || title.trim().isEmpty()) continue;
                com.datanote.domain.project.model.DnProjectTask tk = new com.datanote.domain.project.model.DnProjectTask();
                tk.setProjectId(p.getId());
                tk.setTitle(title.trim());
                tk.setStatus("TODO");
                String prio = tt.getString("priority");
                tk.setPriority(prio != null && java.util.Arrays.asList("HIGH", "MEDIUM", "LOW").contains(prio) ? prio : "MEDIUM");
                Long dueDays = tt.getLong("dueDays");
                if (dueDays != null && dueDays > 0) tk.setDueDate(today.plusDays(dueDays));
                tk.setCreatedBy(ProjectService.currentUser());
                taskMapper.insert(tk);
            }
        }
        return p;
    }

    public void delete(Long templateId) {
        templateMapper.deleteById(templateId);
    }
}
