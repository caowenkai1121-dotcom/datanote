package com.datanote.domain.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.Constants;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.orchestration.model.DnTaskExecution;
import com.datanote.common.model.R;
import com.datanote.platform.iam.CurrentUserUtil;
import com.datanote.platform.iam.RbacService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/task-execution")
@RequiredArgsConstructor
public class TaskExecutionController {

    private final DnTaskExecutionMapper taskExecutionMapper;
    private final DnScriptMapper scriptMapper;
    private final DnSyncTaskMapper syncTaskMapper;
    private final RbacService rbacService;

    @GetMapping("/list")
    public R<List<DnTaskExecution>> list(@RequestParam Long taskId,
                                          @RequestParam String taskType,
                                          @RequestParam(defaultValue = "20") int limit) {
        // 收敛 limit 上限，防止传负值/超大值导致 LIMIT 异常或全表扫描
        int safeLimit = Math.max(1, Math.min(limit, 200));
        QueryWrapper<DnTaskExecution> qw = new QueryWrapper<>();
        if (Constants.TASK_TYPE_SCRIPT.equals(taskType)) {
            requireTaskAccess(taskId, taskType);
            qw.eq("script_id", taskId);
        } else {
            requireTaskAccess(taskId, Constants.TASK_TYPE_SYNC_TASK);
            qw.eq("sync_task_id", taskId);
        }
        qw.orderByDesc("start_time").last("LIMIT " + safeLimit);
        return R.ok(taskExecutionMapper.selectList(qw));
    }

    @GetMapping("/{id}")
    public R<DnTaskExecution> detail(@PathVariable Long id) {
        DnTaskExecution exec = taskExecutionMapper.selectById(id);
        if (exec != null) requireExecutionAccess(exec);
        return R.ok(exec);
    }

    /**
     * 手动任务统计：按任务+日期范围+触发类型查询
     */
    @GetMapping("/manual")
    public R<List<DnTaskExecution>> manual(@RequestParam(required = false) Long taskId,
                                            @RequestParam(required = false) String taskType,
                                            @RequestParam(required = false) String startDate,
                                            @RequestParam(required = false) String endDate) {
        QueryWrapper<DnTaskExecution> qw = new QueryWrapper<>();
        qw.eq("trigger_type", "manual");
        if (taskId != null) {
            if (Constants.TASK_TYPE_SCRIPT.equals(taskType)) {
                requireTaskAccess(taskId, taskType);
                qw.eq("script_id", taskId);
            } else {
                requireTaskAccess(taskId, Constants.TASK_TYPE_SYNC_TASK);
                qw.eq("sync_task_id", taskId);
            }
        } else {
            requireSchedulePrivilege();
        }
        if (startDate != null) qw.ge("start_time", LocalDateTime.of(LocalDate.parse(startDate), LocalTime.MIN));
        if (endDate != null) qw.le("start_time", LocalDateTime.of(LocalDate.parse(endDate), LocalTime.MAX));
        qw.orderByDesc("start_time").last("LIMIT 100");
        return R.ok(taskExecutionMapper.selectList(qw));
    }

    /**
     * 数据集成统计：查同步任务某天的执行情况
     */
    @GetMapping("/integration")
    public R<List<DnTaskExecution>> integration(@RequestParam(required = false) String date) {
        requireSchedulePrivilege();
        QueryWrapper<DnTaskExecution> qw = new QueryWrapper<>();
        qw.eq("task_type", Constants.TASK_TYPE_SYNC_TASK);
        if (date != null) {
            LocalDate d = LocalDate.parse(date);
            qw.ge("start_time", LocalDateTime.of(d, LocalTime.MIN));
            qw.le("start_time", LocalDateTime.of(d, LocalTime.MAX));
        }
        qw.orderByDesc("start_time").last("LIMIT 200");
        return R.ok(taskExecutionMapper.selectList(qw));
    }

    private void requireExecutionAccess(DnTaskExecution exec) {
        if (exec.getScriptId() != null) {
            requireTaskAccess(exec.getScriptId(), Constants.TASK_TYPE_SCRIPT);
            return;
        }
        if (exec.getSyncTaskId() != null) {
            requireTaskAccess(exec.getSyncTaskId(), Constants.TASK_TYPE_SYNC_TASK);
            return;
        }
        requireSchedulePrivilege();
    }

    private void requireTaskAccess(Long taskId, String taskType) {
        if (taskId == null) throw new BusinessException("任务ID不能为空");
        if (hasSchedulePrivilege()) return;
        String user = CurrentUserUtil.currentUser();
        if (Constants.TASK_TYPE_SCRIPT.equals(taskType)) {
            DnScript script = scriptMapper.selectById(taskId);
            if (script != null && user.equals(script.getCreatedBy())) return;
        } else {
            DnSyncTask task = syncTaskMapper.selectById(taskId);
            if (task != null && user.equals(task.getCreatedBy())) return;
        }
        throw new BusinessException("无权访问该任务执行记录");
    }

    private void requireSchedulePrivilege() {
        if (!hasSchedulePrivilege()) throw new BusinessException("无权访问任务执行记录");
    }

    private boolean hasSchedulePrivilege() {
        String user = CurrentUserUtil.currentUser();
        if (user == null || "anonymous".equals(user)) return false;
        try {
            java.util.Set<String> perms = rbacService.getUserPermsByUsername(user);
            return perms != null && (perms.contains("*") || perms.contains("operations:schedule"));
        } catch (Exception e) {
            return false;
        }
    }
}
