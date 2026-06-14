package com.datanote.domain.governance;

import com.datanote.common.model.R;
import com.datanote.domain.governance.mapper.DnMetricMapper;
import com.datanote.domain.governance.model.DnMetric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 指标保存·防重复编码单测 —— metric_code 是预警/取值/关联的引用键, 必须唯一。
 */
@ExtendWith(MockitoExtension.class)
class MetricSaveUniqueTest {

    @Mock private DnMetricMapper metricMapper;

    private MetricController ctrl() {
        return new MetricController(metricMapper, null, null, null, null, null, null, null);
    }

    private DnMetric metric(String code, String name) {
        DnMetric m = new DnMetric();
        m.setMetricCode(code);
        m.setMetricName(name);
        return m;
    }

    @Test
    void save_duplicateCode_returnsFailNoInsert() {
        when(metricMapper.selectCount(any())).thenReturn(1L);
        R<DnMetric> r = ctrl().save(metric("REV", "营收"));
        assertNotEquals(0, r.getCode());
        verify(metricMapper, never()).insert(any());
    }

    @Test
    void save_uniqueNew_inserts() {
        when(metricMapper.selectCount(any())).thenReturn(0L);
        DnMetric m = metric("REV", "营收");
        R<DnMetric> r = ctrl().save(m);
        assertEquals(0, r.getCode());
        verify(metricMapper).insert(m);
    }

    @Test
    void save_statusToggleNoCode_skipsUniqueCheck() {
        DnMetric m = new DnMetric();
        m.setId(5L);
        m.setStatus(0);   // {id,status} 启停, 不带 code
        R<DnMetric> r = ctrl().save(m);
        assertEquals(0, r.getCode());
        verify(metricMapper).updateById(m);
        verify(metricMapper, never()).selectCount(any());
    }
}
