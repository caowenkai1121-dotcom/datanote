package com.datanote.domain.governance;

import com.datanote.domain.governance.QualityService;
import com.datanote.model.DnQualityRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityTargetTest {

    private DnQualityRule rule(Long dsId) {
        DnQualityRule r = new DnQualityRule();
        r.setDatasourceId(dsId);
        return r;
    }

    @Test
    void nullOrZeroDatasourceMeansWarehouse() {
        assertTrue(QualityService.isWarehouseTarget(rule(null)), "datasourceId=null 应判为数仓");
        assertTrue(QualityService.isWarehouseTarget(rule(0L)), "datasourceId=0 应判为数仓");
    }

    @Test
    void positiveDatasourceMeansSource() {
        assertFalse(QualityService.isWarehouseTarget(rule(5L)), "datasourceId>0 应判为源库");
    }

    @Test
    void buildsSourceJdbcUrl() {
        String url = QualityService.buildSourceJdbcUrl("10.0.0.9", 3306, "mall");
        assertTrue(url.startsWith("jdbc:mysql://10.0.0.9:3306/mall"), "URL 应含 host/port/db: " + url);
        assertTrue(url.contains("useSSL=false"), "URL 应禁用 SSL");
        assertTrue(url.contains("allowPublicKeyRetrieval=true"), "URL 应允许公钥获取");
    }

    @Test
    void buildSourceUrlEquivalenceAcrossArgs() {
        assertEquals(QualityService.buildSourceJdbcUrl("h", 1, "d"),
                QualityService.buildSourceJdbcUrl("h", 1, "d"));
    }
}
