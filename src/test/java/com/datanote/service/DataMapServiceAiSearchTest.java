package com.datanote.service;

import com.datanote.domain.datasource.DatasourceExploreService;
import com.datanote.domain.metadata.DataMapService;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.metadata.mapper.DnTableCommentMapper;
import com.datanote.domain.metadata.mapper.DnTableFavoriteMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.platform.ai.AiAssistService;
import com.datanote.platform.portal.mapper.DnSearchHistoryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * aiSearch 提示词上限单测 —— 大元数据库时仅纳入前 N 张表 + 截断提示, 防撑爆 LLM 上下文。
 */
@ExtendWith(MockitoExtension.class)
class DataMapServiceAiSearchTest {

    @Mock private AiAssistService aiAssistService;
    @Mock private DnTableCommentMapper tableCommentMapper;
    @Mock private DnTableFavoriteMapper tableFavoriteMapper;
    @Mock private DnSearchHistoryMapper searchHistoryMapper;
    @Mock private DnTableMetaMapper tableMetaMapper;
    @Mock private DnColumnMetaMapper columnMetaMapper;
    @Mock private DatasourceExploreService exploreService;

    private DataMapService svc() {
        return new DataMapService(aiAssistService, tableCommentMapper, tableFavoriteMapper,
                searchHistoryMapper, tableMetaMapper, columnMetaMapper, exploreService);
    }

    @Test
    void aiSearch_largeMetastore_capsPromptTablesAndAppendsTruncationNote() throws Exception {
        List<Map<String, Object>> tables = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Map<String, Object> t = new HashMap<>();
            t.put("TABLE_SCHEMA", "ods");
            t.put("TABLE_NAME", "t_" + i);
            tables.add(t);
        }
        when(exploreService.getAllTablesSummary()).thenReturn(tables);
        when(aiAssistService.chat(any(), isNull())).thenReturn("[]");

        svc().aiSearch("找订单表");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(aiAssistService).chat(prompt.capture(), isNull());
        String p = prompt.getValue();
        assertTrue(p.contains("共 1000 张表"), "应含总数提示");
        assertTrue(p.contains("仅列出前 800"), "应含截断上限提示");
        assertTrue(p.contains("ods.t_799"), "应含第800张表");
        assertFalse(p.contains("ods.t_800\n"), "第801张表不应出现在列表");
    }
}
