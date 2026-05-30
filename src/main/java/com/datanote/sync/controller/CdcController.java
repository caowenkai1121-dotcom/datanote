package com.datanote.sync.controller;

import com.datanote.model.R;
import com.datanote.sync.service.CdcEngineManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CDC 实时同步任务 Controller：启动 / 停止 / 状态查询。
 */
@Slf4j
@RestController
@RequestMapping("/api/cdc")
@Tag(name = "CDC 实时同步", description = "基于 Debezium 的 MySQL binlog 实时同步任务控制")
@RequiredArgsConstructor
public class CdcController {

    private final CdcEngineManager cdcEngineManager;

    @Operation(summary = "启动 CDC 任务")
    @PostMapping("/{jobId}/start")
    public R<String> start(@PathVariable Long jobId) {
        try {
            cdcEngineManager.start(jobId);
            return R.ok("CDC 任务已启动");
        } catch (Exception e) {
            log.error("启动 CDC 任务失败 jobId={}", jobId, e);
            return R.fail("启动失败: " + e.getMessage());
        }
    }

    @Operation(summary = "停止 CDC 任务")
    @PostMapping("/{jobId}/stop")
    public R<String> stop(@PathVariable Long jobId) {
        try {
            cdcEngineManager.stop(jobId);
            return R.ok("CDC 任务已停止");
        } catch (Exception e) {
            log.error("停止 CDC 任务失败 jobId={}", jobId, e);
            return R.fail("停止失败: " + e.getMessage());
        }
    }

    @Operation(summary = "查询 CDC 任务运行状态")
    @GetMapping("/{jobId}/status")
    public R<Boolean> status(@PathVariable Long jobId) {
        return R.ok(cdcEngineManager.status(jobId));
    }

    @Operation(summary = "查询 CDC 任务实时指标（计数/binlog 延迟）")
    @GetMapping("/{jobId}/metrics")
    public R<java.util.Map<String, Object>> metrics(@PathVariable Long jobId) {
        return R.ok(cdcEngineManager.metrics(jobId));
    }

    @Operation(summary = "重置 CDC(清位点+schema历史,可选重新全量快照)")
    @PostMapping("/{jobId}/reset")
    public R<String> reset(@PathVariable Long jobId,
                           @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean confirm,
                           @org.springframework.web.bind.annotation.RequestParam(defaultValue = "true") boolean restart) {
        if (!confirm) return R.fail("高危操作,需 confirm=true 确认");
        try {
            cdcEngineManager.resetAndRestart(jobId, restart);
            return R.ok(restart ? "已重置并重新全量快照" : "已重置(未重启)");
        } catch (Exception e) {
            log.error("重置 CDC 失败 jobId={}", jobId, e);
            return R.fail("重置失败: " + e.getMessage());
        }
    }
}
