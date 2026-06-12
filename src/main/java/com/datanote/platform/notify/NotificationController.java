package com.datanote.platform.notify;

import com.datanote.common.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 站内通知端点（IV-1）。receiver 一律服务端取当前用户, 不信前端。 */
@RestController
@RequestMapping("/api/notify")
@Tag(name = "站内通知", description = "未读数/最近列表/已读")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    private static String currentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
                return auth.getName();
            }
        } catch (Exception ignore) {}
        return "admin";
    }

    @Operation(summary = "未读数(30s轮询专用, 仅count)")
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(notificationService.unreadCount(currentUser()));
    }

    @Operation(summary = "最近通知(未读优先)")
    @GetMapping("/recent")
    public R<List<DnNotification>> recent(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(notificationService.recent(currentUser(), limit));
    }

    @Operation(summary = "全部已读")
    @PostMapping("/read-all")
    public R<String> readAll() {
        notificationService.markAllRead(currentUser());
        return R.ok("ok");
    }

    @Operation(summary = "单条已读")
    @PostMapping("/{id}/read")
    public R<String> readOne(@PathVariable Long id) {
        notificationService.markRead(currentUser(), id);
        return R.ok("ok");
    }
}
