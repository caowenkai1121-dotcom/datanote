package com.datanote.domain.project;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnProjectMemberMapper;
import com.datanote.mapper.DnProjectTemplateMapper;
import com.datanote.model.DnProject;
import com.datanote.model.DnProjectMember;
import com.datanote.model.DnProjectTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 项目模板：存为模板(快照类型/env/标签/成员角色) + 从模板初始化新项目。 */
@Service
@RequiredArgsConstructor
public class ProjectTemplateService {

    private final DnProjectTemplateMapper templateMapper;
    private final DnProjectMemberMapper memberMapper;
    private final ProjectService projectService;

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
        for (DnProjectMember m : memberMapper.selectList(new LambdaQueryWrapper<DnProjectMember>().eq(DnProjectMember::getProjectId, projectId))) {
            if ("OWNER".equals(m.getProjectRole())) continue; // owner 由新建者承担
            JSONObject mm = new JSONObject();
            mm.put("username", m.getUsername());
            mm.put("role", m.getProjectRole());
            members.add(mm);
        }
        cfg.put("members", members);

        DnProjectTemplate t = new DnProjectTemplate();
        t.setTemplateName(name);
        t.setTemplateType(p.getProjectType());
        t.setDescription(description);
        t.setConfigJson(cfg.toJSONString());
        t.setCreatedBy(ProjectService.currentUser());
        templateMapper.insert(t);
        return t;
    }

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
        return p;
    }

    public void delete(Long templateId) {
        templateMapper.deleteById(templateId);
    }
}
