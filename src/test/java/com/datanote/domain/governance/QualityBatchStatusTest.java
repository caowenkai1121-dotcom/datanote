package com.datanote.domain.governance;

import com.datanote.common.exception.BusinessException;
import com.datanote.common.model.R;
import com.datanote.domain.governance.mapper.DnQualityRuleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 质量规则批量启停单测 —— 入参校验 + 批量更新计数。
 */
@ExtendWith(MockitoExtension.class)
class QualityBatchStatusTest {

    @Mock private DnQualityRuleMapper ruleMapper;

    private QualityController ctrl() {
        return new QualityController(ruleMapper, null, null, null, null);
    }

    private Map<String, Object> body(Object ids, Object status) {
        Map<String, Object> m = new HashMap<>();
        m.put("ids", ids);
        m.put("status", status);
        return m;
    }

    @Test
    void batchStatus_emptyIds_throws() {
        assertThrows(BusinessException.class, () -> ctrl().batchStatus(body(java.util.Collections.emptyList(), 1)));
        verify(ruleMapper, never()).update(any(), any());
    }

    @Test
    void batchStatus_invalidStatus_throws() {
        assertThrows(BusinessException.class, () -> ctrl().batchStatus(body(Arrays.asList(1, 2), 2)));
        verify(ruleMapper, never()).update(any(), any());
    }

    @Test
    void batchStatus_valid_updatesAndReturnsCount() {
        when(ruleMapper.update(any(), any())).thenReturn(3);
        R<Map<String, Object>> r = ctrl().batchStatus(body(Arrays.asList(1, 2, 3), 0));
        assertEquals(0, r.getCode());
        assertEquals(3, r.getData().get("updated"));
        assertEquals(0, r.getData().get("status"));
        verify(ruleMapper).update(any(), any());
    }
}
