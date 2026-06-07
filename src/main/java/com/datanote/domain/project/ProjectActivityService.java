package com.datanote.domain.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnAuditLogMapper;
import com.datanote.platform.audit.model.DnAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 项目活动审计：复用全局 dn_audit_log，按 /api/project/{id} 路径过滤写操作。 */
@Service
@RequiredArgsConstructor
public class ProjectActivityService {

    private final DnAuditLogMapper auditMapper;
    private final ProjectService projectService;

    public List<DnAuditLog> list(Long projectId, int limit) {
        projectService.getById(projectId);
        String base = "/api/project/" + projectId;
        int n = Math.min(Math.max(limit, 1), 200);
        return auditMapper.selectList(new LambdaQueryWrapper<DnAuditLog>()
                .and(w -> w.eq(DnAuditLog::getPath, base).or().likeRight(DnAuditLog::getPath, base + "/"))
                .orderByDesc(DnAuditLog::getId)
                .last("LIMIT " + n));
    }
}
