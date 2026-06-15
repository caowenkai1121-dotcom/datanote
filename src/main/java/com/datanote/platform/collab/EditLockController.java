package com.datanote.platform.collab;

import com.datanote.common.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 编辑锁 — 前端编辑共享资源前 acquire, 编辑中 heartbeat, 离开/保存后 release。
 * 登录即可调用(锁本身就是协同闸门, 无需额外权限点)。
 */
@RestController
@RequestMapping("/api/edit-lock")
@RequiredArgsConstructor
@Tag(name = "编辑锁", description = "并发编辑防护: 资源独占编辑锁")
public class EditLockController {

    private final EditLockService lockService;

    @Operation(summary = "获取编辑锁")
    @PostMapping("/acquire")
    public R<Map<String, Object>> acquire(@RequestBody Map<String, String> b) {
        return R.ok(lockService.acquire(b.get("type"), b.get("id")));
    }

    @Operation(summary = "心跳续锁")
    @PostMapping("/heartbeat")
    public R<Boolean> heartbeat(@RequestBody Map<String, String> b) {
        return R.ok(lockService.heartbeat(b.get("type"), b.get("id")));
    }

    @Operation(summary = "释放编辑锁")
    @PostMapping("/release")
    public R<String> release(@RequestBody Map<String, String> b) {
        lockService.release(b.get("type"), b.get("id"));
        return R.ok("ok");
    }

    @Operation(summary = "查当前持有者(空闲返回 null)")
    @GetMapping("/holder")
    public R<String> holder(@RequestParam String type, @RequestParam String id) {
        return R.ok(lockService.currentHolder(type, id));
    }
}
