package com.datanote.domain.orchestration;

import com.datanote.common.model.R;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.orchestration.model.DnLineageEdge;
import com.datanote.domain.orchestration.model.DnTaskDependency;
import com.alibaba.fastjson.JSONObject;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.domain.develop.ScriptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 血缘关系 Controller — 参数校验 + 调用 Service
 */
@Slf4j
@RestController
@RequestMapping("/api/lineage")
@RequiredArgsConstructor
@Tag(name = "血缘关系", description = "血缘关系查询、下游依赖树、依赖刷新")
public class LineageController {

    private final DolphinService dolphinService;
    private final ScriptService scriptService;
    private final TaskDependencyService taskDependencyService;
    private final LineageEdgeService lineageEdgeService;
    private final SqlLineageService sqlLineageService;
    private final LineageQueryService lineageQueryService;   // 血缘查询图优先+MySQL兜底

    @GetMapping("/{scriptId}")
    @Operation(summary = "查询脚本血缘关系")
    public R<JSONObject> getLineage(@PathVariable Long scriptId) {
        try {
            DnScript script = scriptService.getById(scriptId);
            if (script == null) throw new ResourceNotFoundException("脚本");
            if (script.getDsWorkflowCode() == null || script.getDsWorkflowCode() == 0) {
                return R.ok(new JSONObject());
            }
            return R.ok(dolphinService.getWorkflowLineage(script.getDsWorkflowCode()));
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询血缘失败: scriptId={}", scriptId, e);
            return R.fail("查询血缘失败");
        }
    }

    @GetMapping("/all")
    @Operation(summary = "查询项目全部血缘关系")
    public R<JSONObject> getAllLineage() {
        try {
            return R.ok(dolphinService.getAllLineage());
        } catch (Exception e) {
            log.error("查询全部血缘失败", e);
            return R.fail("查询血缘失败");
        }
    }

    @GetMapping("/downstream-tree")
    @Operation(summary = "获取下游依赖树")
    public R<List<Map<String, Object>>> getDownstreamTree(
            @RequestParam Long taskId, @RequestParam String taskType) {
        return R.ok(taskDependencyService.getDownstreamTree(taskId, taskType));
    }

    @PostMapping("/refresh-deps")
    @Operation(summary = "刷新所有依赖关系")
    public R<Map<String, Object>> refreshDeps() {
        int count = taskDependencyService.refreshAllDependencies();
        Map<String, Object> result = new HashMap<>();
        result.put("dependencyCount", count);
        return R.ok(result);
    }

    @GetMapping("/search-tasks")
    @Operation(summary = "搜索在线任务（用于手动添加依赖）")
    public R<List<Map<String, Object>>> searchTasks(@RequestParam String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return R.fail("关键字不能为空");
        if (keyword.length() > 100) return R.fail("关键字过长");
        return R.ok(taskDependencyService.searchOnlineTasks(keyword.trim()));
    }

    @PostMapping("/add-dep")
    @Operation(summary = "手动添加依赖")
    public R<String> addDependency(@RequestBody Map<String, Object> body) {
        if (body == null || body.get("taskId") == null || body.get("upstreamTaskId") == null) return R.fail("taskId/upstreamTaskId 不能为空");
        Long taskId, upstreamTaskId;
        try {
            taskId = Long.valueOf(body.get("taskId").toString());
            upstreamTaskId = Long.valueOf(body.get("upstreamTaskId").toString());
        } catch (NumberFormatException e) {
            return R.fail("taskId/upstreamTaskId 必须为数字");
        }
        String taskType = (String) body.get("taskType");
        String upstreamTaskType = (String) body.get("upstreamTaskType");
        String depTable = body.get("depTable") != null ? body.get("depTable").toString() : null;

        boolean added = taskDependencyService.addManualDependency(taskId, taskType, upstreamTaskId, upstreamTaskType, depTable);
        return added ? R.ok("添加成功") : R.fail("该依赖关系已存在");
    }

    @DeleteMapping("/dep/{id}")
    @Operation(summary = "删除依赖")
    public R<String> deleteDependency(@PathVariable Long id) {
        taskDependencyService.deleteDependency(id);
        return R.ok("删除成功");
    }

    @GetMapping("/deps")
    @Operation(summary = "查询任务依赖列表")
    public R<List<DnTaskDependency>> listDeps(@RequestParam Long taskId, @RequestParam String taskType) {
        return R.ok(taskDependencyService.listDependencies(taskId, taskType));
    }

    @PostMapping("/rebuild-edges")
    @Operation(summary = "从同步任务重建字段级血缘边")
    public R<Map<String, Object>> rebuildEdges() {
        int count = lineageEdgeService.rebuildFromSyncJobs();
        Map<String, Object> result = new HashMap<>();
        result.put("edgeCount", count);
        return R.ok(result);
    }

    @GetMapping("/table-edges")
    @Operation(summary = "查询表级上下游血缘")
    public R<Map<String, List<DnLineageEdge>>> tableEdges(@RequestParam String db, @RequestParam String table) {
        return R.ok(lineageEdgeService.tableNeighbors(db, table));
    }

    @GetMapping("/column-edges")
    @Operation(summary = "查询字段级入边(目标列来源)")
    public R<List<DnLineageEdge>> columnEdges(@RequestParam String db, @RequestParam String table) {
        return R.ok(lineageEdgeService.columnEdgesInto(db, table));
    }

    @GetMapping("/column-impact")
    @Operation(summary = "字段下游影响(多跳BFS)")
    public R<List<Map<String, Object>>> columnImpact(@RequestParam String db, @RequestParam String table,
                                                     @RequestParam String column) {
        return R.ok(lineageEdgeService.columnImpact(db, table, column));
    }

    @GetMapping("/column-trace")
    @Operation(summary = "字段上游溯源(多跳BFS)")
    public R<List<Map<String, Object>>> columnTrace(@RequestParam String db, @RequestParam String table,
                                                    @RequestParam String column) {
        return R.ok(lineageEdgeService.columnTrace(db, table, column));
    }

    @GetMapping("/column-graph")
    @Operation(summary = "以某字段为中心的N跳字段级血缘子图(双向BFS)")
    public R<Map<String, Object>> columnGraph(@RequestParam String db, @RequestParam String table,
                                              @RequestParam String column, @RequestParam(defaultValue = "2") int depth) {
        int d = depth < 1 ? 1 : (depth > 6 ? 6 : depth);
        return R.ok(lineageEdgeService.columnGraph(db, table, column, d));
    }

    @PostMapping("/parse-scripts")
    @Operation(summary = "解析脚本SQL重建SQL血缘边")
    public R<Map<String, Object>> parseScripts() {
        int count = sqlLineageService.rebuildFromScripts();
        Map<String, Object> result = new HashMap<>();
        result.put("edgeCount", count);
        return R.ok(result);
    }

    @GetMapping("/impact")
    @Operation(summary = "下游影响清单(图优先,MySQL兜底)")
    public R<List<Map<String, Object>>> impact(@RequestParam String db, @RequestParam String table) {
        return R.ok(lineageQueryService.impact(db, table));
    }

    @GetMapping("/trace")
    @Operation(summary = "上游溯源清单(图优先,MySQL兜底)")
    public R<List<Map<String, Object>>> trace(@RequestParam String db, @RequestParam String table) {
        return R.ok(lineageQueryService.trace(db, table));
    }

    @GetMapping("/cycles")
    @Operation(summary = "血缘环检测(循环依赖, 图库独有能力)")
    public R<Map<String, Object>> cycles() {
        List<String> c = lineageQueryService.detectCycles();
        Map<String, Object> m = new HashMap<>();
        m.put("graphAvailable", lineageQueryService.graphAvailable());
        m.put("hasCycle", !c.isEmpty());
        m.put("tables", c);
        return R.ok(m);
    }

    @GetMapping("/blast-radius")
    @Operation(summary = "爆炸半径: 下游受影响表数(图优先)")
    public R<Map<String, Object>> blastRadius(@RequestParam String db, @RequestParam String table) {
        List<Map<String, Object>> down = lineageQueryService.impact(db, table);
        Map<String, Object> m = new HashMap<>();
        m.put("count", down.size());
        m.put("downstream", down);
        return R.ok(m);
    }

    @GetMapping("/graph")
    @Operation(summary = "以某表为中心的N跳血缘子图(双向BFS)")
    public R<Map<String, Object>> graph(@RequestParam String db, @RequestParam String table,
                                        @RequestParam(defaultValue = "2") int depth) {
        int d = depth < 1 ? 1 : (depth > 6 ? 6 : depth); // 后端兜底限深,防直连API传超大depth致昂贵BFS
        return R.ok(lineageEdgeService.graph(db, table, d));
    }
}
