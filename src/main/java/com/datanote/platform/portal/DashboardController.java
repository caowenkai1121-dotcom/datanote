package com.datanote.platform.portal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.Constants;
import com.datanote.domain.orchestration.mapper.DnSchedulerRunMapper;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.orchestration.model.DnSchedulerRun;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.common.model.R;
import com.datanote.platform.iam.CurrentUserUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.datanote.platform.portal.util.SysMetricsUtil;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Statistics and service checks")
public class DashboardController {

    private final DnSyncTaskMapper syncTaskMapper;
    private final DnScriptMapper scriptMapper;
    private final DnSchedulerRunMapper schedulerRunMapper;
    private final DataSource dataSource;
    private final com.datanote.platform.cache.CacheService cacheService;   // 热读缓存(fail-open)

    /** 服务探活 TCP 连接超时(毫秒) */
    private static final int SERVICE_CONNECT_TIMEOUT_MS = 1000;
    /** 元数据库连接有效性校验超时(秒) */
    private static final int DB_VALIDATE_TIMEOUT_SEC = 2;

    @Value("${spring.datasource.url:jdbc:mysql://127.0.0.1:3306/datanote}")
    private String dataSourceUrl;

    @Value("${doris.host:}")
    private String dorisHost;

    @Value("${doris.query-port:9030}")
    private int dorisQueryPort;

    @Operation(summary = "Dashboard statistics")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(required = false) Boolean myTask) {
        boolean mine = Boolean.TRUE.equals(myTask);
        String currentUser = CurrentUserUtil.currentUser();
        // 缓存看板聚合计数(60s; 全局/本人分键); Redis 不可用自动直查(fail-open)
        String cacheKey = "dash:stats:" + (mine ? "u:" + currentUser : "all");
        Map<String, Object> data = cacheService.getOrLoad(cacheKey, 60,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {},
                () -> computeStats(mine, currentUser));
        return R.ok(data);
    }

    private Map<String, Object> computeStats(boolean mine, String currentUser) {
        Map<String, Object> data = new HashMap<>();

        QueryWrapper<DnScript> scriptQw = new QueryWrapper<>();
        if (mine) {
            scriptQw.eq("created_by", currentUser);
        }
        long scriptCount = scriptMapper.selectCount(scriptQw);

        long syncTaskCount = syncTaskMapper.selectCount(new QueryWrapper<DnSyncTask>());
        data.put("scriptCount", scriptCount);
        data.put("syncTaskCount", syncTaskCount);

        QueryWrapper<DnScript> onlineScriptQw = new QueryWrapper<>();
        onlineScriptQw.eq("schedule_status", Constants.SCHEDULE_ONLINE);
        if (mine) {
            onlineScriptQw.eq("created_by", currentUser);
        }
        long onlineScriptCount = scriptMapper.selectCount(onlineScriptQw);

        QueryWrapper<DnSyncTask> onlineSyncQw = new QueryWrapper<>();
        onlineSyncQw.eq("schedule_status", Constants.SCHEDULE_ONLINE);
        long onlineSyncCount = syncTaskMapper.selectCount(onlineSyncQw);
        data.put("onlineCount", onlineScriptCount + onlineSyncCount);

        // 今日执行/成功/失败 3 计数合并为 1 条 GROUP BY status(原 3 次 selectCount → 1 次, 命中 idx_status_date_type)
        LocalDate today = LocalDate.now().minusDays(1);
        QueryWrapper<DnSchedulerRun> todayQw = new QueryWrapper<>();
        todayQw.select("status", "COUNT(*) AS cnt").eq("run_date", today).eq("run_type", Constants.RUN_TYPE_DAILY).groupBy("status");
        long todayExec = 0, todaySuccess = 0, todayFailed = 0;
        for (Map<String, Object> row : schedulerRunMapper.selectMaps(todayQw)) {
            Object co = row.get("cnt"); long c = co instanceof Number ? ((Number) co).longValue() : 0L;
            todayExec += c;
            Object so = row.get("status"); int st = so instanceof Number ? ((Number) so).intValue() : Integer.MIN_VALUE;
            if (st == DnSchedulerRun.STATUS_SUCCESS) todaySuccess = c;
            else if (st == DnSchedulerRun.STATUS_FAILED) todayFailed = c;
        }
        data.put("todayExec", todayExec);
        data.put("todaySuccess", todaySuccess);
        data.put("todayFailed", todayFailed);

        return data;
    }

    @Operation(summary = "Service status")
    @GetMapping("/services")
    public R<List<Map<String, Object>>> services() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(hasText(dorisHost)
                ? checkService("Doris FE", dorisHost, dorisQueryPort, "Doris query service")
                : serviceUnavailable("Doris FE", dorisQueryPort, "Doris query service (not configured)"));
        list.add(checkService("DolphinScheduler", "127.0.0.1", 12345, "Task scheduler"));
        list.add(checkService("MySQL", getMysqlHost(), getMysqlPort(), "DataNote metadata database"));
        return R.ok(list);
    }

    @Operation(summary = "系统运行指标(JVM/系统/DB)")
    @GetMapping("/metrics")
    public R<Map<String, Object>> metrics() {
        Map<String, Object> data = new LinkedHashMap<>();

        // JVM 内存/线程/启动时长/GC
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();
        long heapMax = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        int heapPct = SysMetricsUtil.usagePct(heap.getUsed(), heapMax);
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        RuntimeMXBean rtBean = ManagementFactory.getRuntimeMXBean();
        long gcCount = 0, gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() > 0) gcTime += gc.getCollectionTime();
        }
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heapUsed", heap.getUsed());
        jvm.put("heapMax", heapMax);
        jvm.put("heapPct", heapPct);
        jvm.put("heapLevel", SysMetricsUtil.usageLevel(heapPct));
        jvm.put("nonHeapUsed", nonHeap.getUsed());
        jvm.put("threadCount", threadBean.getThreadCount());
        jvm.put("peakThreadCount", threadBean.getPeakThreadCount());
        jvm.put("uptimeMs", rtBean.getUptime());
        jvm.put("uptimeText", SysMetricsUtil.humanDuration(rtBean.getUptime()));
        jvm.put("gcCount", gcCount);
        jvm.put("gcTimeMs", gcTime);
        jvm.put("jvmVersion", System.getProperty("java.version"));
        data.put("jvm", jvm);

        // 系统 CPU/磁盘
        Map<String, Object> sys = new LinkedHashMap<>();
        java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        sys.put("processors", osBean.getAvailableProcessors());
        sys.put("osName", osBean.getName() + " " + osBean.getArch());
        sys.put("loadAverage", osBean.getSystemLoadAverage());
        double cpuLoad = -1;
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                cpuLoad = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
            }
        } catch (Throwable ignored) {
        }
        int cpuPct = cpuLoad >= 0 ? (int) Math.round(cpuLoad * 100) : -1;
        sys.put("cpuPct", cpuPct);
        sys.put("cpuLevel", cpuPct >= 0 ? SysMetricsUtil.usageLevel(cpuPct) : "ok");
        File root = new File("/").getTotalSpace() > 0 ? new File("/") : new File(".");
        long diskTotal = root.getTotalSpace();
        long diskFree = root.getUsableSpace();
        int diskPct = SysMetricsUtil.usagePct(diskTotal - diskFree, diskTotal);
        sys.put("diskTotal", diskTotal);
        sys.put("diskFree", diskFree);
        sys.put("diskUsedPct", diskPct);
        sys.put("diskLevel", SysMetricsUtil.usageLevel(diskPct));
        data.put("system", sys);

        // 元数据库 ping 延迟
        Map<String, Object> db = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        boolean dbOk = false;
        try (Connection conn = dataSource.getConnection()) {
            dbOk = conn.isValid(DB_VALIDATE_TIMEOUT_SEC);
        } catch (Exception ignored) {
        }
        db.put("ok", dbOk);
        db.put("pingMs", System.currentTimeMillis() - t0);
        data.put("db", db);

        data.put("serverTime", System.currentTimeMillis());
        return R.ok(data);
    }

    private String getMysqlHost() {
        Matcher matcher = mysqlUrlMatcher();
        return matcher != null ? matcher.group(1) : "127.0.0.1";
    }

    private int getMysqlPort() {
        Matcher matcher = mysqlUrlMatcher();
        if (matcher == null || matcher.group(2) == null) {
            return 3306;
        }
        return Integer.parseInt(matcher.group(2));
    }

    private Matcher mysqlUrlMatcher() {
        if (dataSourceUrl == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("^jdbc:mysql://([^:/?]+)(?::(\\d+))?[/?:].*").matcher(dataSourceUrl);
        return matcher.matches() ? matcher : null;
    }

    private Map<String, Object> checkService(String name, String host, int port, String desc) {
        Map<String, Object> svc = new HashMap<>();
        svc.put("name", name);
        svc.put("port", port);
        svc.put("desc", desc);
        boolean alive = false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), SERVICE_CONNECT_TIMEOUT_MS);
            alive = true;
        } catch (Exception ignored) {
        }
        svc.put("alive", alive);
        return svc;
    }

    private Map<String, Object> serviceUnavailable(String name, int port, String desc) {
        Map<String, Object> svc = new HashMap<>();
        svc.put("name", name);
        svc.put("port", port);
        svc.put("desc", desc);
        svc.put("alive", false);
        return svc;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
