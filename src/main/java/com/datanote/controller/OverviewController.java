package com.datanote.controller;

import com.datanote.model.R;
import com.datanote.service.OverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 治理总览 Controller —— 一屏看板聚合数据。
 */
@RestController
@RequestMapping("/api/gov")
@Tag(name = "治理总览", description = "健康分/资产/质量/工单/敏感分布一屏聚合")
@RequiredArgsConstructor
public class OverviewController {

    private final OverviewService overviewService;

    @Operation(summary = "治理总览聚合")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        return R.ok(overviewService.overview());
    }
}
