package com.datanote.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.Constants;
import com.datanote.mapper.DnSchedulerRunMapper;
import com.datanote.mapper.DnScriptMapper;
import com.datanote.mapper.DnSyncTaskMapper;
import com.datanote.model.DnSchedulerRun;
import com.datanote.model.DnScript;
import com.datanote.model.DnSyncTask;
import com.datanote.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
