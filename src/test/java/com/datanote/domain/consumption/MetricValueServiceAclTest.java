package com.datanote.domain.consumption;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.consumption.mapper.DnConsumptionLogMapper;
import com.datanote.domain.consumption.mapper.DnMetricValueMapper;
import com.datanote.domain.governance.mapper.DnMetricMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MetricValueServiceAclTest {

    @Mock private DnMetricMapper metricMapper;
    @Mock private DnMetricValueMapper valueMapper;
    @Mock private DnConsumptionLogMapper logMapper;
    @Mock private com.datanote.domain.integration.HiveService hiveService;
    @Mock private MetricAlertService metricAlertService;
    @Mock private com.datanote.domain.governance.mapper.DnMetricRefMapper metricRefMapper;
    @Mock private com.datanote.domain.governance.mapper.DnQualityRuleMapper qualityRuleMapper;
    @Mock private com.datanote.domain.governance.mapper.DnQualityRunMapper qualityRunMapper;
    @Mock private com.datanote.platform.iam.DataAclService dataAclService;

    @Test
    void latest_deniedMetric_doesNotReadMetricValue() {
        MetricValueService service = new MetricValueService(metricMapper, valueMapper, logMapper, hiveService,
                metricAlertService, metricRefMapper, qualityRuleMapper, qualityRunMapper, dataAclService);
        org.mockito.Mockito.when(dataAclService.canAccess("METRIC", "7")).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.latest(7L));
        verify(valueMapper, never()).selectOne(any());
    }
}
