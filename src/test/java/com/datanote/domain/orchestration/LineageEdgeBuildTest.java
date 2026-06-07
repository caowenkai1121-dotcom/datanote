package com.datanote.domain.orchestration;

import com.datanote.domain.orchestration.LineageEdgeService;
import com.datanote.domain.orchestration.model.DnLineageEdge;
import com.datanote.domain.integration.model.DnSyncJob;
import com.datanote.domain.integration.dto.FieldMapping;
import com.datanote.domain.integration.dto.TableSyncConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineageEdgeBuildTest {

    private FieldMapping fm(String s, String t, Boolean sync, String transform, String mask) {
        FieldMapping f = new FieldMapping();
        f.setSource(s); f.setTarget(t); f.setSync(sync);
        f.setTransformExpression(transform); f.setMaskingType(mask);
        return f;
    }

    private DnSyncJob job() {
        DnSyncJob j = new DnSyncJob();
        j.setId(7L); j.setSourceDb("mall"); j.setTargetDb("ods");
        return j;
    }

    private TableSyncConfig table(String src, String dst, List<FieldMapping> fields) {
        TableSyncConfig tc = new TableSyncConfig();
        tc.setSourceTable(src); tc.setTargetTable(dst); tc.setFields(fields);
        return tc;
    }

    @Test
    void buildsTableEdgeAndColumnEdges() {
        List<FieldMapping> fields = new ArrayList<>();
        fields.add(fm("id", "order_id", true, null, null));
        fields.add(fm("phone", "phone", true, null, "PHONE"));
        fields.add(fm("name", "cust_name", true, "{\"type\":\"substring\"}", null));
        fields.add(fm("age", "age", false, null, null)); // 不同步，应排除
        List<TableSyncConfig> tables = new ArrayList<>();
        tables.add(table("orders", "ods_orders", fields));

        List<DnLineageEdge> edges = LineageEdgeService.buildEdgesForJob(job(), tables);

        long tableEdges = edges.stream().filter(e -> "TABLE".equals(e.getLevelType())).count();
        long colEdges = edges.stream().filter(e -> "COLUMN".equals(e.getLevelType())).count();
        assertEquals(1, tableEdges, "应有 1 条表级边");
        assertEquals(3, colEdges, "应有 3 条字段级边(age 不同步被排除)");

        DnLineageEdge te = edges.stream().filter(e -> "TABLE".equals(e.getLevelType())).findFirst().get();
        assertEquals("mall", te.getSrcDb());
        assertEquals("orders", te.getSrcTable());
        assertEquals("", te.getSrcColumn());
        assertEquals("ods", te.getDstDb());
        assertEquals("ods_orders", te.getDstTable());
        assertEquals("MAPPING", te.getSource());
        assertEquals(100, te.getConfidence());
        assertEquals(7L, te.getJobId());
    }

    @Test
    void transformTypeReflectsMaskAndTransform() {
        List<FieldMapping> fields = new ArrayList<>();
        fields.add(fm("id", "id", true, null, null));
        fields.add(fm("phone", "phone", true, null, "PHONE"));
        fields.add(fm("name", "name", true, "{\"type\":\"x\"}", null));
        List<DnLineageEdge> edges = LineageEdgeService.buildEdgesForJob(job(),
                java.util.Collections.singletonList(table("t", "ods_t", fields)));

        assertEquals("DIRECT", colEdge(edges, "id").getTransformType());
        assertEquals("MASK", colEdge(edges, "phone").getTransformType());
        assertEquals("TRANSFORM", colEdge(edges, "name").getTransformType());
    }

    @Test
    void nullFieldsYieldsOnlyTableEdge() {
        List<DnLineageEdge> edges = LineageEdgeService.buildEdgesForJob(job(),
                java.util.Collections.singletonList(table("t", "ods_t", null)));
        assertEquals(1, edges.size());
        assertTrue("TABLE".equals(edges.get(0).getLevelType()));
    }

    private DnLineageEdge colEdge(List<DnLineageEdge> edges, String srcCol) {
        return edges.stream().filter(e -> "COLUMN".equals(e.getLevelType()) && srcCol.equals(e.getSrcColumn()))
                .findFirst().orElseThrow(() -> new AssertionError("缺字段边: " + srcCol));
    }
}
