package com.datanote.controller;

import com.datanote.exception.BusinessException;
import com.datanote.model.R;
import com.datanote.service.ScheduleLifecycleService;
import com.datanote.service.ScheduleTargetType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 调度生命周期门面 —— 脚本/同步任务 × 本地/远程 上下线（8 端点）。
 * 仅做委托，业务编排统一在 ScheduleLifecycleService（消除脚本/同步逐字重复）。
 * 路由前缀沿用 /api/scheduler，路径与重构前一字不变，前端零改动。
 */
@Slf4j
@RestController
@RequestMapping("/api/scheduler")
@RequiredArgsConstructor
@Tag(name = "调度上下线", description = "脚本/同步任务的本地与 DS 远程上下线")
public class ScheduleLifecycleController {

    private final ScheduleLifecycleService lifecycleService;

    @PostMapping("/online/{scriptId}")
    @Operation(summary = "脚本上线（DS远程调度）")
    public R<Map<String, Object>> online(@PathVariable Long scriptId) {
        try {
            return R.ok(lifecycleService.onlineRemote(scriptId, ScheduleTargetType.SCRIPT));
        } catch (Exception e) {
            log.error("脚本上线失败, scriptId={}", scriptId, e);
            return R.fail("脚本上线失败");
        }
    }

    @PostMapping("/offline/{scriptId}")
    @Operation(summary = "脚本下线（DS远程调度）")
    public R<Void> offline(@PathVariable Long scriptId) {
        try {
            lifecycleService.offlineRemote(scriptId, ScheduleTargetType.SCRIPT);
            return R.ok();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("脚本下线失败, scriptId={}", scriptId, e);
            return R.fail("脚本下线失败");
        }
    }

    @PostMapping("/local-online/{scriptId}")
    @Operation(summary = "脚本上线（本地调度）")
    public R<Void> localOnline(@PathVariable Long scriptId) {
        lifecycleService.onlineLocal(scriptId, ScheduleTargetType.SCRIPT);
        return R.ok();
    }

    @PostMapping("/local-offline/{scriptId}")
    @Operation(summary = "脚本下线（本地调度）")
    public R<Void> localOffline(@PathVariable Long scriptId) {
        lifecycleService.offlineLocal(scriptId, ScheduleTargetType.SCRIPT);
        return R.ok();
    }

    @PostMapping("/sync-online/{taskId}")
    @Operation(summary = "同步任务上线（DS远程调度）")
    public R<Map<String, Object>> syncOnline(@PathVariable Long taskId) {
        try {
            return R.ok(lifecycleService.onlineRemote(taskId, ScheduleTargetType.SYNC));
        } catch (Exception e) {
            log.error("同步任务上线失败, taskId={}", taskId, e);
            return R.fail("同步任务上线失败");
        }
    }

    @PostMapping("/sync-offline/{taskId}")
    @Operation(summary = "同步任务下线（DS远程调度）")
    public R<Void> syncOffline(@PathVariable Long taskId) {
        try {
            lifecycleService.offlineRemote(taskId, ScheduleTargetType.SYNC);
            return R.ok();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("同步任务下线失败, taskId={}", taskId, e);
            return R.fail("同步任务下线失败");
        }
    }

    @PostMapping("/sync-local-online/{taskId}")
    @Operation(summary = "同步任务上线（本地调度）")
    public R<Void> syncLocalOnline(@PathVariable Long taskId) {
        lifecycleService.onlineLocal(taskId, ScheduleTargetType.SYNC);
        return R.ok();
    }

    @PostMapping("/sync-local-offline/{taskId}")
    @Operation(summary = "同步任务下线（本地调度）")
    public R<Void> syncLocalOffline(@PathVariable Long taskId) {
        lifecycleService.offlineLocal(taskId, ScheduleTargetType.SYNC);
        return R.ok();
    }
}
