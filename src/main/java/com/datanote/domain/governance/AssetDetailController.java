package com.datanote.domain.governance;

import com.datanote.domain.governance.model.DnGlossaryTerm;
import com.datanote.common.model.R;
import com.datanote.domain.governance.AssetDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 资产详情 Controller — 字段级元数据 / Profiler 探查 / 业务术语表
 */
@Slf4j
@RestController
@RequestMapping("/api/gov/asset")
@RequiredArgsConstructor
@Tag(name = "资产详情", description = "字段级元数据、Profiler 探查、业务术语表")
public class AssetDetailController {

    private final AssetDetailService assetDetailService;

    @Operation(summary = "资产详情(表+字段级元数据)")
    @GetMapping("/detail")
    public R<Map<String, Object>> detail(@RequestParam String db, @RequestParam String table) {
        return R.ok(assetDetailService.assetDetail(db, table));
    }

    @Operation(summary = "字段 Profiler 探查(下推数仓采样)")
    @GetMapping("/profile")
    public R<Map<String, Object>> profile(@RequestParam String db, @RequestParam String table) {
        try {
            return R.ok(assetDetailService.profile(db, table));
        } catch (Exception e) {
            log.warn("Profiler 探查失败: {}.{}, {}", db, table, e.getMessage());
            return R.fail("探查失败: " + e.getMessage());
        }
    }

    // ===== 业务术语表 =====

    @Operation(summary = "术语列表")
    @GetMapping("/glossary")
    public R<List<DnGlossaryTerm>> glossary() {
        return R.ok(assetDetailService.listTerms());
    }

    @Operation(summary = "新增/更新术语")
    @PostMapping("/glossary")
    public R<DnGlossaryTerm> saveTerm(@RequestBody DnGlossaryTerm term) {
        if (term.getTerm() == null || term.getTerm().trim().isEmpty()) {
            return R.fail("术语名不能为空");
        }
        return R.ok(assetDetailService.saveTerm(term));
    }

    @Operation(summary = "删除术语")
    @DeleteMapping("/glossary/{id}")
    public R<String> deleteTerm(@PathVariable Long id) {
        assetDetailService.deleteTerm(id);
        return R.ok("删除成功");
    }
}
