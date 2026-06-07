package com.datanote.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataMapPartitionMapperTest {

    @Test
    void mapsDorisShowPartitionsRowToPartitionInfo() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("PartitionName", "p20260530");
        raw.put("PartitionKey", "dt");
        raw.put("Range", "[types: [DATE]; keys: [2026-05-30]; ]");
        raw.put("Buckets", 10);
        raw.put("State", "NORMAL");
        raw.put("DataSize", "1.234 GB");
        raw.put("VisibleVersionTime", "2026-05-30 11:22:33");

        Map<String, Object> info = DatasourceExploreService.mapDorisPartitionRow(raw);

        assertEquals("p20260530", info.get("partition"));
        assertEquals("dt", info.get("partitionKey"));
        assertTrue(info.get("range").toString().contains("2026-05-30"));
        assertEquals("NORMAL", info.get("state"));
        assertEquals("1.234 GB", info.get("totalSizeDisplay"));
        assertEquals("2026-05-30 11:22:33", info.get("lastModified"));
    }

    @Test
    void toleratesMissingOptionalColumns() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("PartitionName", "p1");

        Map<String, Object> info = DatasourceExploreService.mapDorisPartitionRow(raw);

        assertEquals("p1", info.get("partition"));
        assertEquals("", info.get("partitionKey"));
        assertEquals("", info.get("totalSizeDisplay"));
    }
}
