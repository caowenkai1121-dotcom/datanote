package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnProjectEnvParamMapper;
import com.datanote.mapper.DnProjectQuotaMapper;
import com.datanote.model.DnProjectEnvParam;
import com.datanote.model.DnProjectQuota;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 项目设置增强：资源配额(1:1 upsert) + 环境参数映射(CRUD)。配置存档，供发布/运维参考。 */
@Service
@RequiredArgsConstructor
public class ProjectSettingService {

    private final DnProjectQuotaMapper quotaMapper;
    private final DnProjectEnvParamMapper envParamMapper;
    private final ProjectService projectService;

    public DnProjectQuota getQuota(Long projectId) {
        projectService.getById(projectId);
        DnProjectQuota q = quotaMapper.selectById(projectId);
        if (q == null) {
            q = new DnProjectQuota();
            q.setProjectId(projectId);
            q.setConcurrentLimit(4);
            q.setTimeoutDefault(0);
            q.setRetryDefault(0);
            q.setStorageQuotaGb(0);
        }
        return q;
    }

    public DnProjectQuota saveQuota(Long projectId, DnProjectQuota q) {
        projectService.getById(projectId);
        q.setProjectId(projectId);
        boolean exists = quotaMapper.selectById(projectId) != null;
        if (exists) quotaMapper.updateById(q);
        else quotaMapper.insert(q);
        return q;
    }

    public List<DnProjectEnvParam> listEnvParams(Long projectId) {
        projectService.getById(projectId);
        return envParamMapper.selectList(new LambdaQueryWrapper<DnProjectEnvParam>()
                .eq(DnProjectEnvParam::getProjectId, projectId).orderByAsc(DnProjectEnvParam::getId));
    }

    public DnProjectEnvParam saveEnvParam(Long projectId, DnProjectEnvParam p) {
        projectService.getById(projectId);
        if (p.getParamKey() == null || p.getParamKey().trim().isEmpty()) {
            throw new IllegalArgumentException("参数键不能为空");
        }
        p.setProjectId(projectId);
        p.setParamKey(p.getParamKey().trim());
        DnProjectEnvParam old = envParamMapper.selectOne(new LambdaQueryWrapper<DnProjectEnvParam>()
                .eq(DnProjectEnvParam::getProjectId, projectId).eq(DnProjectEnvParam::getParamKey, p.getParamKey()));
        if (old != null) {
            p.setId(old.getId());
            envParamMapper.updateById(p);
        } else {
            p.setId(null);
            envParamMapper.insert(p);
        }
        return p;
    }

    public void deleteEnvParam(Long paramId) {
        envParamMapper.deleteById(paramId);
    }
}
