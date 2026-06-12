package com.datanote.platform.portal;

import com.datanote.platform.notify.NotificationService;
import com.datanote.platform.portal.util.SysMetricsUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统资源告警巡检(全站#23): 每 5 分钟检查 JVM 堆/磁盘/元数据库连通,
 * danger 级问题写入通知中心(receiver=admin, type=SYS_ALERT), 同类告警 1 小时内只发一次。
 * 与 /api/dashboard/metrics 共用 SysMetricsUtil 阈值口径(>=90% 为 danger)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SysAlertScheduler {

    private final NotificationService notificationService;
    private final DataSource dataSource;

    /** 同类告警去重: key -> 上次发送毫秒时刻(内存即可, 重启重置无害) */
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private static final long DEDUP_MS = 60 * 60 * 1000L;

    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void check() {
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long heapMax = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
            int heapPct = SysMetricsUtil.usagePct(heap.getUsed(), heapMax);
            if ("danger".equals(SysMetricsUtil.usageLevel(heapPct))) {
                alert("heap", "系统告警: JVM 堆内存占用 " + heapPct + "%, 请关注服务稳定性");
            }
            File root = new File("/").getTotalSpace() > 0 ? new File("/") : new File(".");
            int diskPct = SysMetricsUtil.usagePct(root.getTotalSpace() - root.getUsableSpace(), root.getTotalSpace());
            if ("danger".equals(SysMetricsUtil.usageLevel(diskPct))) {
                alert("disk", "系统告警: 磁盘已用 " + diskPct + "%, 请尽快清理");
            }
            boolean dbOk = false;
            try (Connection conn = dataSource.getConnection()) {
                dbOk = conn.isValid(2);
            } catch (Exception ignored) {
            }
            if (!dbOk) {
                alert("db", "系统告警: 元数据库连接异常, 请检查数据库服务");
            }
        } catch (Exception e) {
            log.warn("系统资源巡检失败", e);
        }
    }

    private void alert(String key, String title) {
        long now = System.currentTimeMillis();
        Long last = lastSent.get(key);
        if (last != null && now - last < DEDUP_MS) {
            return;
        }
        lastSent.put(key, now);
        notificationService.notify("admin", "SYS_ALERT", title, "settings", null, null);
        log.warn("已发送系统告警通知: {}", title);
    }
}
