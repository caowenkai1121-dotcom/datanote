package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.*;
import com.datanote.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/** 项目资产纳管：关联表 dn_project_asset 绑定/解绑 + 候选(服务端查并排除已绑) + 分类计数。 */
@Service
@RequiredArgsConstructor
public class ProjectAssetService {

    private final DnProjectAssetMapper assetMapper;
    private final DnProjectMapper projectMapper;
    private final ProjectService projectService;
    private final DnSyncJobMapper syncJobMapper;
    private final DnScriptMapper scriptMapper;
    private final DnDatasourceMapper datasourceMapper;
    private final DnQualityRuleMapper qualityRuleMapper;

    public static final List<String> TYPES = Collections.unmodifiableList(
            Arrays.asList("SYNC_JOB", "SCRIPT", "DATASOURCE", "QUALITY_RULE"));
    private static final Map<String, String> TYPE_LABEL = new LinkedHashMap<>();
    static {
        TYPE_LABEL.put("SYNC_JOB", "同步任务");
        TYPE_LABEL.put("SCRIPT", "脚本");
        TYPE_LABEL.put("DATASOURCE", "数据源");
        TYPE_LABEL.put("QUALITY_RULE", "质量规则");
    }

    public List<DnProjectAsset> list(Long projectId) {
        projectService.getById(projectId);
        return assetMapper.selectList(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getProjectId, projectId)
                .orderByAsc(DnProjectAsset::getAssetType).orderByAsc(DnProjectAsset::getId));
    }

    public DnProjectAsset bind(Long projectId, String type, Long assetId, String assetName) {
        projectService.getById(projectId);
        if (!TYPES.contains(type)) throw new IllegalArgumentException("非法资产类型: " + type);
        if (assetId == null) throw new IllegalArgumentException("资产ID不能为空");
        Long dup = assetMapper.selectCount(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getProjectId, projectId)
                .eq(DnProjectAsset::getAssetType, type)
                .eq(DnProjectAsset::getAssetId, assetId));
        if (dup != null && dup > 0) throw new IllegalArgumentException("该资产已绑定到本项目");
        DnProjectAsset a = new DnProjectAsset();
        a.setProjectId(projectId);
        a.setAssetType(type);
        a.setAssetId(assetId);
        a.setAssetName((assetName == null || assetName.trim().isEmpty()) ? resolveName(type, assetId) : assetName.trim());
        a.setCreatedBy(ProjectService.currentUser());
        assetMapper.insert(a);
        return a;
    }

    public void unbind(Long projectId, Long rowId) {
        com.datanote.model.DnProjectAsset a = assetMapper.selectById(rowId);
        if (a == null || (projectId != null && !projectId.equals(a.getProjectId()))) {
            throw new IllegalArgumentException("资产不存在或不属于该项目");
        }
        assetMapper.deleteById(rowId);
    }

    /** 候选：该类型全部资产 {id,name,bound}，标注是否已绑（前端过滤展示）。 */
    public List<Map<String, Object>> candidates(Long projectId, String type) {
        projectService.getById(projectId);
        if (!TYPES.contains(type)) throw new IllegalArgumentException("非法资产类型: " + type);
        Set<Long> bound = new HashSet<>();
        for (DnProjectAsset a : assetMapper.selectList(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getProjectId, projectId).eq(DnProjectAsset::getAssetType, type))) {
            bound.add(a.getAssetId());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] idName : allOfType(type)) {
            Long id = (Long) idName[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", idName[1]);
            m.put("bound", bound.contains(id));
            out.add(m);
        }
        return out;
    }

    /** 各类型已绑数量。 */
    public Map<String, Long> countsByType(Long projectId) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : TYPES) {
            Long c = assetMapper.selectCount(new LambdaQueryWrapper<DnProjectAsset>()
                    .eq(DnProjectAsset::getProjectId, projectId).eq(DnProjectAsset::getAssetType, t));
            m.put(t, c == null ? 0L : c);
        }
        return m;
    }

    public static String typeLabel(String type) {
        return TYPE_LABEL.getOrDefault(type, type);
    }

    /** 资产反查：该资产被哪些项目绑定（返回 {projectId, projectName, projectCode}）。 */
    public List<Map<String, Object>> projectsOfAsset(String type, Long assetId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!TYPES.contains(type) || assetId == null) return out;
        List<DnProjectAsset> bindings = assetMapper.selectList(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getAssetType, type).eq(DnProjectAsset::getAssetId, assetId));
        for (DnProjectAsset b : bindings) {
            com.datanote.model.DnProject p = projectMapper.selectById(b.getProjectId());
            if (p == null || "DELETED".equals(p.getStatus())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectId", p.getId());
            m.put("projectName", p.getProjectName());
            m.put("projectCode", p.getProjectCode());
            out.add(m);
        }
        return out;
    }

    private List<Object[]> allOfType(String type) {
        List<Object[]> list = new ArrayList<>();
        switch (type) {
            case "SYNC_JOB":
                for (DnSyncJob j : syncJobMapper.selectList(null)) list.add(new Object[]{j.getId(), j.getJobName()});
                break;
            case "SCRIPT":
                for (DnScript s : scriptMapper.selectList(null)) list.add(new Object[]{s.getId(), s.getScriptName()});
                break;
            case "DATASOURCE":
                for (DnDatasource d : datasourceMapper.selectList(null)) list.add(new Object[]{d.getId(), d.getName()});
                break;
            case "QUALITY_RULE":
                for (DnQualityRule q : qualityRuleMapper.selectList(null)) list.add(new Object[]{q.getId(), q.getRuleName()});
                break;
            default:
                break;
        }
        return list;
    }

    private String resolveName(String type, Long id) {
        for (Object[] idName : allOfType(type)) {
            if (id.equals(idName[0])) return String.valueOf(idName[1]);
        }
        return type + "#" + id;
    }
}
