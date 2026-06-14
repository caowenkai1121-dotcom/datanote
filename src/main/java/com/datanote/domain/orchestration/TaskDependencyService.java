package com.datanote.domain.orchestration;

import com.datanote.domain.develop.mapper.DnScriptFolderMapper;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.orchestration.mapper.DnSchedulerRunMapper;
import com.datanote.domain.orchestration.mapper.DnTaskDependencyMapper;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.develop.model.DnScriptFolder;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.domain.orchestration.model.DnSchedulerRun;
import com.datanote.domain.orchestration.model.DnTaskDependency;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.Constants;
import com.datanote.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 依赖解析和管理服务 — SQL 解析、依赖刷新、环检测、下游收集
 */
@Service
@RequiredArgsConstructor
public class TaskDependencyService {

    private static final Logger log = LoggerFactory.getLogger(TaskDependencyService.class);

    private final DnScriptMapper scriptMapper;
    private final DnSyncTaskMapper syncTaskMapper;
    private final DnScriptFolderMapper folderMapper;
    private final DnTaskDependencyMapper depMapper;
    private final DnSchedulerRunMapper runMapper;

    // 引用全局常量
    private static final int MAX_DOWNSTREAM_DEPTH = Constants.MAX_DOWNSTREAM_DEPTH;

    // SQL 中提取表名的正则
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+(?:`?\\w+`?\\.)?`?([a-zA-Z_]\\w*)`?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> SQL_KEYWORDS = new HashSet<>(Arrays.asList(
            "select", "where", "group", "order", "having", "limit", "union",
            "lateral", "table", "dual", "if", "exists", "not", "set", "values"
    ));

    // ======================== 依赖解析 ========================

    /**
     * 刷新所有依赖关系，并检测循环依赖
     */
    @Transactional(rollbackFor = Exception.class)
    public synchronized int refreshAllDependencies() {
        depMapper.delete(null);

        List<DnScript> scripts = scriptMapper.selectList(null);
        List<DnSyncTask> syncTasks = syncTaskMapper.selectList(null);
        if (scripts == null) scripts = Collections.emptyList();
        if (syncTasks == null) syncTasks = Collections.emptyList();

        // 构建表名 → 任务映射
        Map<String, TaskRef> tableToTask = new HashMap<>();
        for (DnSyncTask st : syncTasks) {
            if (st != null && st.getTargetTable() != null) {
                tableToTask.put(st.getTargetTable().toLowerCase(), new TaskRef(st.getId(), Constants.TASK_TYPE_SYNC_TASK));
            }
        }
        for (DnScript s : scripts) {
            if (s != null && s.getScriptName() != null) {
                tableToTask.put(s.getScriptName().toLowerCase(), new TaskRef(s.getId(), Constants.TASK_TYPE_SCRIPT));
            }
        }

        // 构建依赖关系 + 邻接表（用于环检测）
        Map<String, Set<String>> graph = new HashMap<>();
        List<DnTaskDependency> allDeps = new ArrayList<>();

        for (DnScript script : scripts) {
            if (script == null || script.getId() == null) continue;
            if (script.getContent() == null || script.getContent().trim().isEmpty()) continue;
            String nodeKey = "script:" + script.getId();
            graph.putIfAbsent(nodeKey, new HashSet<>());

            Set<String> upstreamTables = parseSQLTables(script.getContent());
            for (String table : upstreamTables) {
                TaskRef upstream = tableToTask.get(table.toLowerCase());
                if (upstream == null) continue;
                if (Constants.TASK_TYPE_SCRIPT.equals(upstream.taskType) && upstream.taskId.equals(script.getId())) continue;

                String upstreamKey = upstream.taskType + ":" + upstream.taskId;
                graph.get(nodeKey).add(upstreamKey);

                DnTaskDependency dep = new DnTaskDependency();
                dep.setTaskId(script.getId());
                dep.setTaskType(Constants.TASK_TYPE_SCRIPT);
                dep.setUpstreamTaskId(upstream.taskId);
                dep.setUpstreamTaskType(upstream.taskType);
                dep.setDepTable(table);
                allDeps.add(dep);
            }
        }

        // 循环依赖检测（DFS 染色法）
        if (hasCycle(graph)) {
            log.warn("检测到循环依赖！依赖关系未保存。请检查脚本之间的引用关系。");
            throw new RuntimeException("检测到循环依赖，请检查脚本之间的表引用关系，确保不存在 A→B→C→A 的环形依赖");
        }

        // 无循环，保存依赖
        for (DnTaskDependency dep : allDeps) {
            depMapper.insert(dep);
        }

        log.info("依赖关系刷新完成，共 {} 条（无循环依赖）", allDeps.size());
        return allDeps.size();
    }

    // ======================== 上游检查 ========================

    /**
     * 检查指定任务的所有上游依赖是否已完成
     *
     * @param taskId   任务 ID
     * @param taskType 任务类型
     * @param runDate  运行日期
     * @param runType  运行类型
     * @return 所有上游是否已完成
     */
    public boolean allUpstreamsCompleted(Long taskId, String taskType, LocalDate runDate, String runType) {
        if (taskId == null) throw new BusinessException("任务 ID 不能为空");
        if (taskType == null || taskType.trim().isEmpty()) throw new BusinessException("任务类型不能为空");
        if (runDate == null) throw new BusinessException("运行日期不能为空");
        if (runType == null || runType.trim().isEmpty()) throw new BusinessException("运行类型不能为空");

        QueryWrapper<DnTaskDependency> depQw = new QueryWrapper<>();
        depQw.eq("task_id", taskId).eq("task_type", taskType);
        List<DnTaskDependency> deps = depMapper.selectList(depQw);
        if (deps == null || deps.isEmpty()) return true;

        // 批量取全部上游 run 状态: 一条 IN 查询替代逐上游 selectOne(消除 N+1, 15s tick 热路径)
        List<Long> upIds = new ArrayList<>();
        for (DnTaskDependency dep : deps) if (dep != null && dep.getUpstreamTaskId() != null) upIds.add(dep.getUpstreamTaskId());
        if (upIds.isEmpty()) return true;
        List<DnSchedulerRun> upRuns = runMapper.selectList(new QueryWrapper<DnSchedulerRun>()
                .in("task_id", upIds).eq("run_date", runDate).eq("run_type", runType));
        if (upRuns == null) upRuns = Collections.emptyList();   // selectList 理论可返回 null, 统一兜底
        Map<String, DnSchedulerRun> runMap = new HashMap<>();
        for (DnSchedulerRun r : upRuns) runMap.put(r.getTaskType() + ":" + r.getTaskId(), r);
        for (DnTaskDependency dep : deps) {
            if (dep == null || dep.getUpstreamTaskId() == null) continue;
            DnSchedulerRun upstreamRun = runMap.get(dep.getUpstreamTaskType() + ":" + dep.getUpstreamTaskId());
            if (upstreamRun == null || upstreamRun.getStatus() != DnSchedulerRun.STATUS_SUCCESS) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查补数据批次中指定任务的上游依赖是否已完成
     *
     * @param run     运行记录
     * @param batchId 补数据批次 ID
     * @return 上游是否已完成
     */
    public boolean allUpstreamsCompletedInBatch(DnSchedulerRun run, String batchId) {
        if (run == null) throw new BusinessException("运行记录不能为空");
        if (run.getTaskId() == null) throw new BusinessException("运行记录的任务 ID 不能为空");
        if (batchId == null || batchId.trim().isEmpty()) throw new BusinessException("补数据批次 ID 不能为空");

        QueryWrapper<DnTaskDependency> depQw = new QueryWrapper<>();
        depQw.eq("task_id", run.getTaskId()).eq("task_type", run.getTaskType());
        List<DnTaskDependency> deps = depMapper.selectList(depQw);
        if (deps == null || deps.isEmpty()) return true;

        // 批量取本批次全部上游 run: 一条 IN 查询替代逐上游 selectOne(消除 N+1)
        List<Long> upIds = new ArrayList<>();
        for (DnTaskDependency dep : deps) if (dep != null && dep.getUpstreamTaskId() != null) upIds.add(dep.getUpstreamTaskId());
        if (upIds.isEmpty()) return true;
        List<DnSchedulerRun> upRuns = runMapper.selectList(new QueryWrapper<DnSchedulerRun>()
                .in("task_id", upIds).eq("run_date", run.getRunDate())
                .eq("run_type", Constants.RUN_TYPE_BACKFILL).eq("batch_id", batchId));
        if (upRuns == null) upRuns = Collections.emptyList();   // selectList 理论可返回 null, 统一兜底
        Map<String, DnSchedulerRun> runMap = new HashMap<>();
        for (DnSchedulerRun r : upRuns) runMap.put(r.getTaskType() + ":" + r.getTaskId(), r);
        for (DnTaskDependency dep : deps) {
            if (dep == null || dep.getUpstreamTaskId() == null) continue;
            DnSchedulerRun upstreamRun = runMap.get(dep.getUpstreamTaskType() + ":" + dep.getUpstreamTaskId());
            // 区分两种 null：上游本就不在本批次 → 跳过（不阻塞，避免补数据死等未选中的上游）；
            // 上游在本批次但 run 记录尚未就绪 → 阻塞（与非批次版本一致，保证批内依赖顺序）
            if (upstreamRun == null) {
                if (isUpstreamInBatch(dep.getUpstreamTaskId(), dep.getUpstreamTaskType(), batchId)) {
                    return false;
                }
                continue;
            }
            if (upstreamRun.getStatus() != DnSchedulerRun.STATUS_SUCCESS) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断上游任务是否被选入指定补数据批次（按 batch_id 是否存在该上游的 run 记录）
     */
    private boolean isUpstreamInBatch(Long upstreamTaskId, String upstreamTaskType, String batchId) {
        QueryWrapper<DnSchedulerRun> qw = new QueryWrapper<>();
        qw.eq("task_id", upstreamTaskId)
          .eq("task_type", upstreamTaskType)
          .eq("run_type", Constants.RUN_TYPE_BACKFILL)
          .eq("batch_id", batchId);
        Long cnt = runMapper.selectCount(qw);
        return cnt != null && cnt > 0;
    }

    // ======================== 下游收集 ========================

    /**
     * 递归收集指定任务的所有下游任务
     *
     * @param taskId   任务 ID
     * @param taskType 任务类型
     * @param keys     下游任务标识集合（输出参数）
     */
    public void collectDownstream(Long taskId, String taskType, Set<String> keys) {
        collectDownstream(taskId, taskType, keys, 0);
    }

    private void collectDownstream(Long taskId, String taskType, Set<String> keys, int depth) {
        if (depth > MAX_DOWNSTREAM_DEPTH) {
            log.warn("下游依赖链超过最大深度 {}，可能存在数据异常", MAX_DOWNSTREAM_DEPTH);
            return;
        }
        QueryWrapper<DnTaskDependency> qw = new QueryWrapper<>();
        qw.eq("upstream_task_id", taskId).eq("upstream_task_type", taskType);
        List<DnTaskDependency> downs = depMapper.selectList(qw);
        if (downs == null) return;   // selectList 理论可返回 null, 统一兜底
        for (DnTaskDependency d : downs) {
            String key = d.getTaskType() + ":" + d.getTaskId();
            if (keys.add(key)) {
                collectDownstream(d.getTaskId(), d.getTaskType(), keys, depth + 1);
            }
        }
    }

    /**
     * 获取下游依赖树（含层级信息）
     *
     * @param taskId   任务 ID
     * @param taskType 任务类型
     * @return 下游依赖树列表
     */
    public List<Map<String, Object>> getDownstreamTree(Long taskId, String taskType) {
        List<Map<String, Object>> result = new ArrayList<>();
        // 一次性载入全部依赖边在内存 BFS, 消除逐节点 selectList(下游)与逐节点 getTaskName 的 N+1
        // (补数弹窗在大型 DAG 下原先每层每节点各一条 SQL, 加载极慢甚至超时)
        List<DnTaskDependency> all = depMapper.selectList(null);
        if (all == null) all = Collections.emptyList();   // selectList 理论可返回 null, 统一兜底
        Map<String, List<DnTaskDependency>> byUpstream = new HashMap<>();
        for (DnTaskDependency d : all) {
            if (d == null || d.getUpstreamTaskId() == null) continue;
            byUpstream.computeIfAbsent(d.getUpstreamTaskType() + ":" + d.getUpstreamTaskId(), k -> new ArrayList<>()).add(d);
        }
        collectDownstreamTree(taskType + ":" + taskId, byUpstream, result, new HashSet<>(), 1);
        fillTaskNames(result);   // 批量解析任务名(脚本/同步任务各一次 selectBatchIds)
        return result;
    }

    private void collectDownstreamTree(String upKey, Map<String, List<DnTaskDependency>> byUpstream,
                                        List<Map<String, Object>> result, Set<String> visited, int level) {
        List<DnTaskDependency> downs = byUpstream.get(upKey);
        if (downs == null) return;
        for (DnTaskDependency d : downs) {
            String key = d.getTaskType() + ":" + d.getTaskId();
            if (visited.contains(key)) continue;
            visited.add(key);

            Map<String, Object> node = new HashMap<>();
            node.put("taskId", d.getTaskId());
            node.put("taskType", d.getTaskType());
            node.put("level", level);
            node.put("depTable", d.getDepTable());
            result.add(node);
            collectDownstreamTree(key, byUpstream, result, visited, level + 1);
        }
    }

    /** 批量回填节点的 taskName(脚本/同步任务各一次 selectBatchIds), 替代逐节点 selectById。 */
    private void fillTaskNames(List<Map<String, Object>> nodes) {
        List<Long> scriptIds = new ArrayList<>(), syncIds = new ArrayList<>();
        for (Map<String, Object> n : nodes) {
            Long id = (Long) n.get("taskId");
            if (id == null) continue;
            if (Constants.TASK_TYPE_SCRIPT.equals(n.get("taskType"))) scriptIds.add(id); else syncIds.add(id);
        }
        Map<Long, String> scriptNames = new HashMap<>(), syncNames = new HashMap<>();
        if (!scriptIds.isEmpty()) {
            List<DnScript> ss = scriptMapper.selectBatchIds(scriptIds);
            if (ss != null) for (DnScript s : ss) if (s != null && s.getId() != null) scriptNames.put(s.getId(), s.getScriptName());
        }
        if (!syncIds.isEmpty()) {
            List<DnSyncTask> ts = syncTaskMapper.selectBatchIds(syncIds);
            if (ts != null) for (DnSyncTask t : ts) if (t != null && t.getId() != null) syncNames.put(t.getId(), t.getTaskName());
        }
        for (Map<String, Object> n : nodes) {
            Long id = (Long) n.get("taskId");
            boolean isScript = Constants.TASK_TYPE_SCRIPT.equals(n.get("taskType"));
            String nm = isScript ? scriptNames.get(id) : syncNames.get(id);
            n.put("taskName", nm != null ? nm : (isScript ? "未知脚本" : "未知任务"));
        }
    }

    // ======================== SQL 解析 ========================

    /**
     * 解析 SQL 中引用的表名
     *
     * @param sql SQL 语句
     * @return 表名集合
     */
    public Set<String> parseSQLTables(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        if (sql == null) return tables;
        String cleaned = sql.replaceAll("--.*", "").replaceAll("/\\*[\\s\\S]*?\\*/", "");
        Matcher m = TABLE_PATTERN.matcher(cleaned);
        while (m.find()) {
            String tableName = m.group(1);
            if (!SQL_KEYWORDS.contains(tableName.toLowerCase())) {
                tables.add(tableName);
            }
        }
        return tables;
    }

    // ======================== 暂停/恢复下游 ========================

    /**
     * 暂停指定任务的所有下游任务
     *
     * @param taskId   任务 ID
     * @param taskType 任务类型
     * @param runDate  运行日期
     * @param runType  运行类型
     * @return 暂停的任务数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int pauseDownstream(Long taskId, String taskType, LocalDate runDate, String runType) {
        Set<String> downstreamKeys = new HashSet<>();
        collectDownstream(taskId, taskType, downstreamKeys);

        int paused = 0;
        for (String key : downstreamKeys) {
            String[] parts = key.split(":");
            if (parts.length < 2) continue;   // 防御非法 key 格式, 避免 parts[1] 越界
            String dTaskType = parts[0];
            Long dTaskId = Long.valueOf(parts[1]);

            QueryWrapper<DnSchedulerRun> qw = new QueryWrapper<>();
            qw.eq("task_id", dTaskId).eq("task_type", dTaskType)
              .eq("run_date", runDate).eq("run_type", runType)
              .in("status", DnSchedulerRun.STATUS_WAITING, DnSchedulerRun.STATUS_FAILED);

            List<DnSchedulerRun> runs = runMapper.selectList(qw);
            if (runs == null) continue;   // selectList 理论可返回 null, 统一兜底
            for (DnSchedulerRun run : runs) {
                run.setStatus(DnSchedulerRun.STATUS_PAUSED);
                runMapper.updateById(run);
                paused++;
            }
        }
        log.info("已暂停 {} 个下游任务", paused);
        return paused;
    }

    /**
     * 上游任务成功后恢复其下游暂停的任务为等待状态
     *
     * @param taskId   任务 ID
     * @param taskType 任务类型
     * @param runDate  运行日期
     * @param runType  运行类型
     */
    public void resumeDownstreamAfterSuccess(Long taskId, String taskType, LocalDate runDate, String runType) {
        Set<String> downstreamKeys = new HashSet<>();
        collectDownstream(taskId, taskType, downstreamKeys);

        int resumed = 0;
        for (String key : downstreamKeys) {
            String[] parts = key.split(":");
            if (parts.length < 2) continue;   // 防御非法 key 格式, 避免 parts[1] 越界
            String dTaskType = parts[0];
            Long dTaskId = Long.valueOf(parts[1]);

            QueryWrapper<DnSchedulerRun> qw = new QueryWrapper<>();
            qw.eq("task_id", dTaskId).eq("task_type", dTaskType)
              .eq("run_date", runDate).eq("run_type", runType)
              .eq("status", DnSchedulerRun.STATUS_PAUSED);

            List<DnSchedulerRun> runs = runMapper.selectList(qw);
            if (runs == null) continue;   // selectList 理论可返回 null, 统一兜底
            for (DnSchedulerRun r : runs) {
                r.setStatus(DnSchedulerRun.STATUS_WAITING);
                runMapper.updateById(r);
                resumed++;
            }
        }
        if (resumed > 0) {
            log.info("任务 {}:{} 成功后，已恢复 {} 个下游暂停任务为等待状态", taskType, taskId, resumed);
        }
    }

    // ======================== 工具方法 ========================

    /**
     * 获取任务名称
     *
     * @param taskId   任务 ID
     * @param taskType 任务类型
     * @return 任务名称
     */
    public String getTaskName(Long taskId, String taskType) {
        if (Constants.TASK_TYPE_SCRIPT.equals(taskType)) {
            DnScript s = scriptMapper.selectById(taskId);
            return s != null ? s.getScriptName() : "未知脚本";
        } else {
            DnSyncTask t = syncTaskMapper.selectById(taskId);
            return t != null ? t.getTaskName() : "未知任务";
        }
    }

    /**
     * 获取脚本所属层级名称
     *
     * @param folderId 文件夹 ID
     * @return 层级名称
     */
    public String getScriptLayer(Long folderId) {
        if (folderId == null) return "其他";
        DnScriptFolder folder = folderMapper.selectById(folderId);
        if (folder == null) return "其他";
        String layer = folder.getLayer();
        return (layer != null && !layer.isEmpty()) ? layer : folder.getFolderName();
    }

    // ======================== 循环依赖检测 ========================

    /**
     * 循环依赖检测（DFS 染色法）
     * WHITE=未访问, GRAY=递归栈中, BLACK=已完成
     */
    /**
     * 从当前所有依赖记录构建邻接表（节点 = taskType:taskId，边 = task → upstream），供环检测使用
     */
    private Map<String, Set<String>> buildDependencyGraph() {
        Map<String, Set<String>> graph = new HashMap<>();
        List<DnTaskDependency> all = depMapper.selectList(null);
        if (all == null) return graph;
        for (DnTaskDependency d : all) {
            if (d == null || d.getTaskId() == null || d.getUpstreamTaskId() == null) continue;
            String nodeKey = d.getTaskType() + ":" + d.getTaskId();
            String upstreamKey = d.getUpstreamTaskType() + ":" + d.getUpstreamTaskId();
            graph.computeIfAbsent(nodeKey, k -> new HashSet<>()).add(upstreamKey);
        }
        return graph;
    }

    private boolean hasCycle(Map<String, Set<String>> graph) {
        Map<String, Integer> color = new HashMap<>(); // 0=WHITE, 1=GRAY, 2=BLACK
        for (String node : graph.keySet()) {
            color.put(node, 0);
        }
        // 确保所有被引用的节点也在 color 中
        for (Set<String> deps : graph.values()) {
            for (String dep : deps) {
                color.putIfAbsent(dep, 0);
            }
        }

        for (String node : color.keySet()) {
            if (color.get(node) == 0) {
                if (dfsCycleDetect(node, graph, color)) return true;
            }
        }
        return false;
    }

    private boolean dfsCycleDetect(String node, Map<String, Set<String>> graph, Map<String, Integer> color) {
        color.put(node, 1); // GRAY - 进入递归栈
        Set<String> neighbors = graph.getOrDefault(node, Collections.emptySet());
        for (String neighbor : neighbors) {
            if (color.getOrDefault(neighbor, 0) == 1) return true; // 发现环
            if (color.getOrDefault(neighbor, 0) == 0 && dfsCycleDetect(neighbor, graph, color)) return true;
        }
        color.put(node, 2); // BLACK - 退出递归栈
        return false;
    }

    private static class TaskRef {
        Long taskId;
        String taskType;
        TaskRef(Long taskId, String taskType) {
            this.taskId = taskId;
            this.taskType = taskType;
        }
    }

    // ========== 手动依赖管理 ==========

    public List<Map<String, Object>> searchOnlineTasks(String keyword) {
        List<Map<String, Object>> results = new ArrayList<>();
        QueryWrapper<DnScript> sq = new QueryWrapper<>();
        sq.like("script_name", keyword).eq("schedule_status", "online").last("LIMIT 20");
        List<DnScript> onlineScripts = scriptMapper.selectList(sq);
        if (onlineScripts != null) for (DnScript s : onlineScripts) {
            Map<String, Object> m = new HashMap<>();
            m.put("taskId", s.getId());
            m.put("taskType", "script");
            m.put("taskName", s.getScriptName());
            results.add(m);
        }
        QueryWrapper<DnSyncTask> tq = new QueryWrapper<>();
        tq.like("task_name", keyword).eq("schedule_status", "online").last("LIMIT 20");
        List<DnSyncTask> onlineTasks = syncTaskMapper.selectList(tq);
        if (onlineTasks != null) for (DnSyncTask t : onlineTasks) {
            Map<String, Object> m = new HashMap<>();
            m.put("taskId", t.getId());
            m.put("taskType", "syncTask");
            m.put("taskName", t.getTaskName());
            results.add(m);
        }
        return results;
    }

    public boolean addManualDependency(Long taskId, String taskType, Long upstreamTaskId, String upstreamTaskType, String depTable) {
        QueryWrapper<DnTaskDependency> qw = new QueryWrapper<>();
        qw.eq("task_id", taskId).eq("task_type", taskType)
                .eq("upstream_task_id", upstreamTaskId).eq("upstream_task_type", upstreamTaskType);
        if (depMapper.selectCount(qw) > 0) {
            return false;
        }
        // 加边前把新边并入现有依赖图跑环检测，禁止手动构造循环依赖（复用 refreshAllDependencies 的 hasCycle）
        Map<String, Set<String>> graph = buildDependencyGraph();
        String nodeKey = taskType + ":" + taskId;
        String upstreamKey = upstreamTaskType + ":" + upstreamTaskId;
        graph.computeIfAbsent(nodeKey, k -> new HashSet<>()).add(upstreamKey);
        if (hasCycle(graph)) {
            throw new BusinessException("该依赖会形成循环依赖，已拒绝添加");
        }
        DnTaskDependency dep = new DnTaskDependency();
        dep.setTaskId(taskId);
        dep.setTaskType(taskType);
        dep.setUpstreamTaskId(upstreamTaskId);
        dep.setUpstreamTaskType(upstreamTaskType);
        dep.setDepTable(depTable);
        depMapper.insert(dep);
        return true;
    }

    public void deleteDependency(Long id) {
        depMapper.deleteById(id);
    }

    public List<DnTaskDependency> listDependencies(Long taskId, String taskType) {
        QueryWrapper<DnTaskDependency> qw = new QueryWrapper<>();
        qw.eq("task_id", taskId).eq("task_type", taskType);
        return depMapper.selectList(qw);
    }
}
