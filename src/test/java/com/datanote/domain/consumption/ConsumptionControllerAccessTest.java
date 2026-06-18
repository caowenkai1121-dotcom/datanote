package com.datanote.domain.consumption;

import com.datanote.domain.consumption.mapper.DnMetricAlertRuleMapper;
import com.datanote.domain.consumption.model.DnMetricValue;
import com.datanote.domain.governance.mapper.DnMetricMapper;
import com.datanote.domain.governance.model.DnMetric;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConsumptionControllerAccessTest {

    @Mock private MetricValueService valueService;
    @Mock private DnMetricMapper metricMapper;
    @Mock private DnMetricAlertRuleMapper alertRuleMapper;
    @Mock private MetricDetailService metricDetailService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void value_usesCurrentUserInsteadOfConsumerParam() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", "n/a", Collections.emptyList()));
        ConsumptionController controller = new ConsumptionController(valueService, metricMapper, alertRuleMapper, metricDetailService);
        when(metricMapper.selectById(7L)).thenReturn(metric(7L));
        DnMetricValue latest = new DnMetricValue();
        latest.setMetricCode("REV");
        when(valueService.latest(7L)).thenReturn(latest);

        controller.value(7L, "alice");

        verify(valueService).logConsumption(eq("bob"), eq("METRIC_VALUE"), eq("REV"), eq("QUERY"),
                eq(1L), isNull(), eq(true), eq("ok"));
        verify(valueService, never()).logConsumption(eq("alice"), anyString(), any(), anyString(),
                any(), any(), anyBoolean(), any());
    }

    private static DnMetric metric(Long id) {
        DnMetric metric = new DnMetric();
        metric.setId(id);
        metric.setMetricCode("REV");
        metric.setStatus(1);
        return metric;
    }
}
