package com.datanote.platform.portal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.Constants;
import com.datanote.mapper.DnSchedulerRunMapper;
import com.datanote.mapper.DnScriptMapper;
import com.datanote.mapper.DnSyncTaskMapper;
import com.datanote.domain.orchestration.model.DnSchedulerRun;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.common.model.R;
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

    @Value("${spring.datasource.url:jdbc:mysql://127.0.0.1:3306/datanote}")
    private String dataSourceUrl;

    @Operation(summary = "Dashboard statistics")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(required = false) Boolean myTask) {
        Map<String, Object> data = new HashMap<>();
        String currentUser = "default";

        QueryWrapper<DnScript> scriptQw = new QueryWrapper<>();
        if (Boolean.TRUE.equals(myTask)) {
            scriptQw.eq("created_by", currentUser);
        }
        long scriptCount = scriptMapper.selectCount(scriptQw);

        long syncTaskCount = syncTaskMapper.selectCount(new QueryWrapper<DnSyncTask>());
        data.put("scriptCount", scriptCount);
        data.put("syncTaskCount", syncTaskCount);

        QueryWrapper<DnScript> onlineScriptQw = new QueryWrapper<>();
        onlineScriptQw.eq("schedule_status", Constants.SCHEDULE_ONLINE);
        if (Boolean.TRUE.equals(myTask)) {
            onlineScriptQw.eq("created_by", currentUser);
        }
        long onlineScriptCount = scriptMapper.selectCount(onlineScriptQw);

        QueryWrapper<DnSyncTask> onlineSyncQw = new QueryWrapper<>();
        onlineSyncQw.eq("schedule_status", Constants.SCHEDULE_ONLINE);
        long onlineSyncCount = syncTaskMapper.selectCount(onlineSyncQw);
        data.put("onlineCount", onlineScriptCount + onlineSyncCount);

        LocalDate today = LocalDate.now().minusDays(1);
        QueryWrapper<DnSchedulerRun> todayRunQw = new QueryWrapper<>();
        todayRunQw.eq("run_date", today).eq("run_type", Constants.RUN_TYPE_DAILY);
        data.put("todayExec", schedulerRunMapper.selectCount(todayRunQw));

        QueryWrapper<DnSchedulerRun> todaySuccessQw = new QueryWrapper<>();
        todaySuccessQw.eq("run_date", today).eq("run_type", Constants.RUN_TYPE_DAILY)
                .eq("status", DnSchedulerRun.STATUS_SUCCESS);
        data.put("todaySuccess", schedulerRunMapper.selectCount(todaySuccessQw));

        QueryWrapper<DnSchedulerRun> todayFailQw = new QueryWrapper<>();
        todayFailQw.eq("run_date", today).eq("run_type", Constants.RUN_TYPE_DAILY)
                .eq("status", DnSchedulerRun.STATUS_FAILED);
        data.put("todayFailed", schedulerRunMapper.selectCount(todayFailQw));

        return R.ok(data);
    }

    @Operation(summary = "Service status")
    @GetMapping("/services")
    public R<List<Map<String, Object>>> services() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(checkService("Doris FE", "38.76.183.50", 9030, "Doris query service"));
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
            dbOk = conn.isValid(2);
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
            socket.connect(new InetSocketAddress(host, port), 1000);
            alive = true;
        } catch (Exception ignored) {
        }
        svc.put("alive", alive);
        return svc;
    }
}
