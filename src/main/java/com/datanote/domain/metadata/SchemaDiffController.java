package com.datanote.domain.metadata;

import com.datanote.common.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 表结构对比 Controller — 比较两张表的字段差异(ODS↔DWD/跨环境/迁移核对)。
 */
@Slf4j
@RestController
@RequestMapping("/api/metadata")
@RequiredArgsConstructor
@Tag(name = "表结构对比", description = "两张表字段差异对比")
public class SchemaDiffController {

    private final SchemaDiffService schemaDiffService;

    @Operation(summary = "表结构对比")
    @GetMapping("/schema-diff")
    public R<Map<String, Object>> schemaDiff(@RequestParam String db1, @RequestParam String table1,
                                             @RequestParam String db2, @RequestParam String table2) {
        try {
            return R.ok(schemaDiffService.diff(db1, table1, db2, table2));
        } catch (com.datanote.common.exception.BusinessException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("表结构对比失败 {}.{} vs {}.{}", db1, table1, db2, table2, e);
            return R.fail("结构对比失败: " + e.getMessage());
        }
    }
}
