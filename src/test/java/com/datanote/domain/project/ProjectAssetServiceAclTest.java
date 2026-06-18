package com.datanote.domain.project;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.governance.mapper.DnQualityRuleMapper;
import com.datanote.domain.integration.mapper.DnSyncJobMapper;
import com.datanote.domain.project.mapper.DnProjectAssetMapper;
import com.datanote.domain.project.mapper.DnProjectMapper;
import com.datanote.domain.project.model.DnProject;
import com.datanote.platform.iam.DataAclService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectAssetServiceAclTest {

    @Mock private DnProjectAssetMapper assetMapper;
    @Mock private DnProjectMapper projectMapper;
    @Mock private ProjectService projectService;
    @Mock private DnSyncJobMapper syncJobMapper;
    @Mock private DnScriptMapper scriptMapper;
    @Mock private DnDatasourceMapper datasourceMapper;
    @Mock private DnQualityRuleMapper qualityRuleMapper;
    @Mock private com.datanote.domain.governance.mapper.DnMetricMapper metricMapper;
    @Mock private DataAclService dataAclService;

    private ProjectAssetService service() {
        return new ProjectAssetService(assetMapper, projectMapper, projectService, syncJobMapper,
                scriptMapper, datasourceMapper, qualityRuleMapper, metricMapper, dataAclService);
    }

    private DnProject activeProject() {
        DnProject project = new DnProject();
        project.setId(1L);
        project.setStatus("ACTIVE");
        return project;
    }

    private DnDatasource datasource(Long id, String name) {
        DnDatasource datasource = new DnDatasource();
        datasource.setId(id);
        datasource.setName(name);
        return datasource;
    }

    @Test
    void candidates_filtersDeniedDatasources() {
        when(projectService.getById(1L)).thenReturn(activeProject());
        when(assetMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(datasourceMapper.selectList(isNull())).thenReturn(Arrays.asList(
                datasource(1L, "public_ds"),
                datasource(2L, "secret_ds")));
        when(dataAclService.canAccess("DATASOURCE", "1")).thenReturn(true);
        when(dataAclService.canAccess("DATASOURCE", "2")).thenReturn(false);

        List<Map<String, Object>> candidates = service().candidates(1L, "DATASOURCE");

        assertEquals(1, candidates.size());
        assertEquals(1L, candidates.get(0).get("id"));
        assertEquals("public_ds", candidates.get(0).get("name"));
    }

    @Test
    void bind_deniedDatasource_throws() {
        when(projectService.requireProjectPermission(1L, "asset:manage")).thenReturn(activeProject());
        when(dataAclService.canAccess("DATASOURCE", "2")).thenReturn(false);

        assertThrows(BusinessException.class, () -> service().bind(1L, "DATASOURCE", 2L, "secret_ds"));
        verify(assetMapper, never()).insert(any());
    }
}
