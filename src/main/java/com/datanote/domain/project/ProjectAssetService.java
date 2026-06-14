package com.datanote.domain.project;

import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.governance.mapper.DnQualityRuleMapper;
import com.datanote.domain.integration.mapper.DnSyncJobMapper;
import com.datanote.domain.project.mapper.DnProjectAssetMapper;
import com.datanote.domain.project.mapper.DnProjectMapper;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.governance.model.DnQualityRule;
import com.datanote.domain.integration.model.DnSyncJob;
import com.datanote.domain.project.model.DnProjectAsset;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final com.datanote.domain.governance.mapper.DnMetricMapper metricMapper;

    public static final List<String> TYPES = Collections.unmodifiableList(
            Arrays.asList("SYNC_JOB", "SCRIPT", "DATASOURCE", "QUALITY_RULE", "METRIC"));
    private static final Map<String, String> TYPE_LABEL = new LinkedHashMap<>();
    static {
        TYPE_LABEL.put("SYNC_JOB", "同步任务");
        TYPE_LABEL.put("SCRIPT", "脚本");
        TYPE_LABEL.put("DATASOURCE", "数据源");
        TYPE_LABEL.put("QUALITY_RULE", "质量规则");
        TYPE_LABEL.put("METRIC", "指标");
    }

    public List<DnProjectAsset> list(Long projectId) {
        projectService.getById(projectId);
        return assetMapper.selectList(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getProjectId, projectId)
                .orderByAsc(DnProjectAsset::getAssetType).orderByAsc(DnProjectAsset::getId));
    }

    public DnProjectAsset bind(Long projectId, String type, Long assetId, String assetName) {
        com.datanote.domain.project.model.DnProject proj = projectService.getById(projectId);
        if ("ARCHIVED".equals(proj.getStatus())) throw new IllegalArgumentException("项目已归档, 仅可查看, 不能绑定资产");
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
        com.datanote.domain.project.model.DnProjectAsset a = assetMapper.selectById(rowId);
        if (a == null || projectId == null || !projectId.equals(a.getProjectId())) {
            throw new IllegalArgumentException("资产不存在或不属于该项目");
        }
        assetMapper.deleteById(rowId);
    }

    /** 候选：该类型全部资产 {id,name,bound}，标注是否已绑（前端过滤展示）。 */
    public List<Map<String, Object>> candidates(Long projectId, String type) {
        projectService.getById(projectId);
        if (!TYPES.contains(type)) throw new IllegalArgumentException("非法资产类型: " + type);
        Set<Long> bound = new HashSet<>();
        for (DnProjectAsset a : nz(assetMapper.selectList(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getProjectId, projectId).eq(DnProjectAsset::getAssetType, type)))) {
            if (a != null) bound.add(a.getAssetId());
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
        if (bindings == null || bindings.isEmpty()) return out;
        // 收集项目 id 去重剔空,批量查项目,消 N+1(原逐绑定 selectById)
        Set<Long> pids = new LinkedHashSet<>();
        for (DnProjectAsset b : bindings) {
            if (b != null && b.getProjectId() != null) pids.add(b.getProjectId());
        }
        if (pids.isEmpty()) return out;
        Map<Long, com.datanote.domain.project.model.DnProject> projById = new HashMap<>();
        for (com.datanote.domain.project.model.DnProject p : nz(projectMapper.selectBatchIds(pids))) {
            if (p != null) projById.put(p.getId(), p);
        }
        // 保持原绑定顺序输出(同项目多次绑定按原语义重复出现)
        for (DnProjectAsset b : bindings) {
            if (b == null || b.getProjectId() == null) continue;
            com.datanote.domain.project.model.DnProject p = projById.get(b.getProjectId());
            if (p == null || "DELETED".equals(p.getStatus())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bindingId", b.getId());
            m.put("projectId", p.getId());
            m.put("projectName", p.getProjectName());
            m.put("projectCode", p.getProjectCode());
            out.add(m);
        }
        return out;
    }

    /** 批量反查：同类型一批资产各自被哪些项目绑定（列表页归属徽标，一次查询防 N+1）。 */
    public Map<Long, List<Map<String, Object>>> projectsOfAssetsBatch(String type, List<Long> assetIds) {
        Map<Long, List<Map<String, Object>>> out = new LinkedHashMap<>();
        if (!TYPES.contains(type) || assetIds == null || assetIds.isEmpty()) return out;
        List<DnProjectAsset> bindings = assetMapper.selectList(new LambdaQueryWrapper<DnProjectAsset>()
                .eq(DnProjectAsset::getAssetType, type).in(DnProjectAsset::getAssetId, assetIds));
        if (bindings == null || bindings.isEmpty()) return out;
        Set<Long> pids = new LinkedHashSet<>();
        for (DnProjectAsset b : bindings) {
            if (b != null && b.getProjectId() != null) pids.add(b.getProjectId());
        }
        if (pids.isEmpty()) return out;
        Map<Long, com.datanote.domain.project.model.DnProject> projById = new HashMap<>();
        for (com.datanote.domain.project.model.DnProject p : nz(projectMapper.selectBatchIds(pids))) {
            if (p != null && !"DELETED".equals(p.getStatus())) projById.put(p.getId(), p);
        }
        for (DnProjectAsset b : bindings) {
            if (b == null || b.getProjectId() == null || b.getAssetId() == null) continue;
            com.datanote.domain.project.model.DnProject p = projById.get(b.getProjectId());
            if (p == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectId", p.getId());
            m.put("projectName", p.getProjectName());
            m.put("projectCode", p.getProjectCode());
            out.computeIfAbsent(b.getAssetId(), k -> new ArrayList<>()).add(m);
        }
        return out;
    }

    /** null 安全：selectList 理论可返回 null,统一兜底空列表,for-each 不再 NPE。 */
    private static <T> List<T> nz(List<T> l) {
        return l == null ? Collections.emptyList() : l;
    }

    private List<Object[]> allOfType(String type) {
        List<Object[]> list = new ArrayList<>();
        switch (type) {
            case "SYNC_JOB":
                for (DnSyncJob j : nz(syncJobMapper.selectList(null))) list.add(new Object[]{j.getId(), j.getJobName()});
                break;
            case "SCRIPT":
                for (DnScript s : nz(scriptMapper.selectList(null))) list.add(new Object[]{s.getId(), s.getScriptName()});
                break;
            case "DATASOURCE":
                for (DnDatasource d : nz(datasourceMapper.selectList(null))) list.add(new Object[]{d.getId(), d.getName()});
                break;
            case "QUALITY_RULE":
                for (DnQualityRule q : nz(qualityRuleMapper.selectList(null))) list.add(new Object[]{q.getId(), q.getRuleName()});
                break;
            case "METRIC":
                for (com.datanote.domain.governance.model.DnMetric m : nz(metricMapper.selectList(null))) list.add(new Object[]{m.getId(), m.getMetricName()});
                break;
            default:
                break;
        }
        return list;
    }

    private String resolveName(String type, Long id) {
        // 按类型+id 精确查单条,不再全表加载线性查找(消内存大开销);
        // found 区分"无此行"(走兜底)与"有行但名字为null"(同原语义返回"null")
        boolean found = false;
        Object name = null;
        switch (type) {
            case "SYNC_JOB":
                DnSyncJob j = syncJobMapper.selectById(id);
                if (j != null) { found = true; name = j.getJobName(); }
                break;
            case "SCRIPT":
                DnScript s = scriptMapper.selectById(id);
                if (s != null) { found = true; name = s.getScriptName(); }
                break;
            case "DATASOURCE":
                DnDatasource d = datasourceMapper.selectById(id);
                if (d != null) { found = true; name = d.getName(); }
                break;
            case "QUALITY_RULE":
                DnQualityRule q = qualityRuleMapper.selectById(id);
                if (q != null) { found = true; name = q.getRuleName(); }
                break;
            case "METRIC":
                com.datanote.domain.governance.model.DnMetric m = metricMapper.selectById(id);
                if (m != null) { found = true; name = m.getMetricName(); }
                break;
            default:
                break;
        }
        if (found) return String.valueOf(name);
        return type + "#" + id;
    }
}
