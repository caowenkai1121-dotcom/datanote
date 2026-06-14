package com.datanote.domain.mdm;

import com.datanote.common.exception.BusinessException;
import com.datanote.common.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MDM 源表导入·整合管线入口。把源库表记录消费/匹配/合并为黄金记录, 并维护 XREF。
 */
@RestController
@Tag(name = "主数据-源表导入", description = "从源库表消费记录→匹配→合并为黄金记录(整合管线)")
@RequiredArgsConstructor
public class MdmIngestController {

    private final MdmIngestService ingestService;

    @Operation(summary = "读源表列名(供配置字段映射)")
    @GetMapping("/api/mdm/ingest/columns")
    public R<List<String>> columns(@RequestParam String db, @RequestParam String table) {
        if (db == null || db.trim().isEmpty()) throw new BusinessException("源库不能为空");
        if (table == null || table.trim().isEmpty()) throw new BusinessException("源表不能为空");
        try {
            return R.ok(ingestService.sourceColumns(db, table));
        } catch (Exception e) {
            throw new BusinessException("读取源表列失败: " + e.getMessage());
        }
    }

    @Operation(summary = "从源表导入黄金记录")
    @PostMapping("/api/mdm/ingest/from-table")
    @SuppressWarnings("unchecked")
    public R<Map<String, Object>> fromTable(@RequestBody Map<String, Object> body) {
        if (body == null) throw new BusinessException("请求体不能为空");
        Long entityId = asLong(body.get("entityId"));
        String db = str(body.get("db"));
        String table = str(body.get("table"));
        Object mapObj = body.get("mapping");
        Map<String, String> mapping = (mapObj instanceof Map) ? toStrMap((Map<Object, Object>) mapObj) : null;
        String sourceSystem = str(body.get("sourceSystem"));
        String sourceIdColumn = str(body.get("sourceIdColumn"));
        int limit = body.get("limit") == null ? 500 : asInt(body.get("limit"), 500);
        boolean activate = Boolean.TRUE.equals(body.get("activate")) || "true".equals(String.valueOf(body.get("activate")));
        String operator = str(body.get("operator"));
        try {
            return R.ok(ingestService.importFromTable(entityId, db, table, mapping, sourceSystem, sourceIdColumn, limit, activate, operator));
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException("导入失败: " + e.getMessage());
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }
    private static int asInt(Object o, int dft) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return dft; }
    }
    private static Map<String, String> toStrMap(Map<Object, Object> in) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : in.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String v = String.valueOf(e.getValue()).trim();
            if (!v.isEmpty()) out.put(String.valueOf(e.getKey()), v);
        }
        return out;
    }
}
