package com.datanote.domain.develop;

import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.develop.model.DnScriptFolder;
import com.datanote.domain.develop.model.DnScriptVersion;
import com.datanote.domain.integration.model.DnSyncTask;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.develop.mapper.DnScriptFolderMapper;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.develop.mapper.DnScriptVersionMapper;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 脚本管理 Service — 树构建、版本管理、脚本/文件夹/同步任务 CRUD
 */
@Service
@RequiredArgsConstructor
public class ScriptService {

    private final DnScriptFolderMapper folderMapper;
    private final DnScriptMapper scriptMapper;
    private final DnDatasourceMapper datasourceMapper;
    private final DnScriptVersionMapper scriptVersionMapper;
    private final DnSyncTaskMapper syncTaskMapper;
    private final com.datanote.domain.project.ProjectAssetCleaner projectAssetCleaner;   // N4 删除联动清理项目引用
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;  // 脚本保存→异步重建SQL血缘
    private final com.datanote.platform.collab.EditLockService editLockService;          // 并发编辑防护: 保存须持锁 + 版本校验

    // ========== 文件树 ==========

    public List<Map<String, Object>> getTree() {
        QueryWrapper<DnScriptFolder> qw = new QueryWrapper<>();
        qw.orderByAsc("sort_order", "id");
        List<DnScriptFolder> folders = folderMapper.selectList(qw);
        List<DnScript> scripts = scriptMapper.selectList(null);
        List<DnDatasource> datasources = datasourceMapper.selectList(null);
        List<DnSyncTask> syncTasks = syncTaskMapper.selectList(null);
        // selectList 理论可返回 null,统一兜底空列表,buildTree 内不再判空
        return buildTree(folders == null ? new ArrayList<>() : folders,
                scripts == null ? new ArrayList<>() : scripts,
                datasources == null ? new ArrayList<>() : datasources,
                syncTasks == null ? new ArrayList<>() : syncTasks, 0L);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildTree(List<DnScriptFolder> folders,
                                                 List<DnScript> scripts,
                                                 List<DnDatasource> datasources,
                                                 List<DnSyncTask> syncTasks,
                                                 Long parentId) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (DnScriptFolder f : folders) {
            if (f == null) continue;
            if (!Objects.equals(f.getParentId(), parentId)) continue;

            Map<String, Object> node = new HashMap<>();
            node.put("id", f.getId());
            node.put("name", f.getFolderName());
            node.put("type", "folder");
            node.put("layer", f.getLayer());
            List<Map<String, Object>> children = buildTree(folders, scripts, datasources, syncTasks, f.getId());

            if ("数据源".equals(f.getLayer())) {
                for (DnDatasource ds : datasources) {
                    if (ds == null) continue;
                    Map<String, Object> dsNode = new HashMap<>();
                    dsNode.put("id", ds.getId());
                    dsNode.put("name", ds.getName());
                    dsNode.put("type", "datasource");
                    dsNode.put("host", ds.getHost());
                    dsNode.put("port", ds.getPort());
                    dsNode.put("dbType", ds.getType());
                    children.add(dsNode);
                }
            } else if ("ODS".equals(f.getLayer())) {
                for (DnSyncTask t : syncTasks) {
                    if (t == null) continue;
                    Map<String, Object> tNode = new HashMap<>();
                    tNode.put("id", t.getId());
                    tNode.put("name", t.getTaskName());
                    tNode.put("type", "syncTask");
                    tNode.put("sourceDb", t.getSourceDb());
                    tNode.put("sourceTable", t.getSourceTable());
                    tNode.put("syncMode", t.getSyncMode());
                    tNode.put("sourceDsId", t.getSourceDsId());
                    tNode.put("scheduleStatus", t.getScheduleStatus());
                    children.add(tNode);
                }
            } else {
                for (DnScript s : scripts) {
                    if (s == null) continue;
                    if (Objects.equals(s.getFolderId(), f.getId())) {
                        Map<String, Object> sNode = new HashMap<>();
                        sNode.put("id", s.getId());
                        sNode.put("name", s.getScriptName());
                        sNode.put("type", "script");
                        sNode.put("scriptType", s.getScriptType());
                        sNode.put("scheduleStatus", s.getScheduleStatus());
                        children.add(sNode);
                    }
                }
            }
            node.put("children", children);
            nodes.add(node);
        }
        return nodes;
    }

    // ========== 脚本 CRUD ==========

    public DnScript getById(Long id) {
        if (id == null) return null;
        return scriptMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public DnScript save(DnScript script) {
        if (script == null) {
            throw new BusinessException("脚本不能为空");
        }
        if (script.getId() != null) {
            DnScript existing = scriptMapper.selectById(script.getId());
            if (existing == null) {
                throw new ResourceNotFoundException("脚本");
            }
            // 并发编辑防护: ①他人持编辑锁则拒(服务端兜底, 防绕过前端) ②乐观版本校验(自上次加载后被他人改过则拒)
            editLockService.assertHeld("SCRIPT", String.valueOf(script.getId()));
            if (script.getBaseUpdatedAt() != null && existing.getUpdatedAt() != null
                    && !script.getBaseUpdatedAt().equals(existing.getUpdatedAt())) {
                throw new BusinessException("该脚本已被他人修改(" + (existing.getUpdatedBy() == null ? "" : existing.getUpdatedBy()) + "), 请刷新后重试以免覆盖对方改动");
            }
            createScriptVersion(existing);
            script.setCreatedAt(existing.getCreatedAt());
            script.setUpdatedAt(LocalDateTime.now());
            script.setUpdatedBy(com.datanote.platform.iam.CurrentUserUtil.currentUser());   // 记修改人(冲突提示显示是谁)
            scriptMapper.updateById(script);
        } else {
            script.setCreatedAt(LocalDateTime.now());
            script.setUpdatedAt(LocalDateTime.now());
            // 多用户: 记录创建人(调度失败通知 SCHED_FAILED 据此找接收人)
            if (script.getCreatedBy() == null || script.getCreatedBy().trim().isEmpty()) {
                script.setCreatedBy(com.datanote.platform.iam.CurrentUserUtil.currentUser());
            }
            // 新建脚本默认：每天凌晨2点执行，失败告警，重试1次
            if (script.getScheduleCron() == null) script.setScheduleCron("0 0 2 * * ?");
            if (script.getWarningType() == null) script.setWarningType("FAILURE");
            if (script.getRetryTimes() == null) script.setRetryTimes(1);
            if (script.getRetryInterval() == null) script.setRetryInterval(60);
            if (script.getTimeoutSeconds() == null) script.setTimeoutSeconds(3600);
            scriptMapper.insert(script);
        }
        // 脚本(SQL)变更→异步重建血缘/依赖, 开发改完即时反馈影响面, 不必等夜间兜底
        eventPublisher.publishEvent(new com.datanote.domain.orchestration.ScriptSavedEvent(script.getId()));
        // 返回库内真实行: updatedAt 为 DB 截断后的秒精度, 供前端刷新版本基线(否则毫秒≠秒致连续保存误判"被他人修改")
        return scriptMapper.selectById(script.getId());
    }

    public void updateBasicInfo(Long id, Map<String, String> body) {
        if (id == null) {
            throw new BusinessException("脚本 ID 不能为空");
        }
        if (body == null || body.isEmpty()) return;   // 无字段可更新,直接返回
        DnScript script = new DnScript();
        script.setId(id);
        if (body.containsKey("taskType")) script.setTaskType(body.get("taskType"));
        if (body.containsKey("modelDesc")) script.setModelDesc(body.get("modelDesc"));
        if (body.containsKey("subject")) script.setSubject(body.get("subject"));
        script.setUpdatedAt(LocalDateTime.now());
        scriptMapper.updateById(script);
    }

    public void updateDatabaseName(Long id, String databaseName) {
        if (id == null) throw new BusinessException("脚本 ID 不能为空");
        if (scriptMapper.selectById(id) == null) throw new ResourceNotFoundException("脚本");   // 防对不存在脚本静默空更新
        DnScript script = new DnScript();
        script.setId(id);
        script.setDatabaseName(databaseName);
        script.setUpdatedAt(LocalDateTime.now());
        scriptMapper.updateById(script);
    }

    /** 全站#4: 重命名落库——重名拒, 已上线脚本提示下游影响(不拦, 名称非依赖键) */
    public void rename(Long id, String name) {
        if (name == null || name.trim().isEmpty()) throw new com.datanote.common.exception.BusinessException("脚本名不能为空");
        name = name.trim();
        DnScript script = scriptMapper.selectById(id);
        if (script == null) throw new ResourceNotFoundException("脚本");
        if (name.equals(script.getScriptName())) return;
        Long dup = scriptMapper.selectCount(new QueryWrapper<DnScript>().eq("script_name", name));
        if (dup != null && dup > 0) throw new com.datanote.common.exception.BusinessException("已存在同名脚本: " + name);
        DnScript update = new DnScript();
        update.setId(id);
        update.setScriptName(name);
        update.setUpdatedAt(LocalDateTime.now());
        scriptMapper.updateById(update);
    }

    public void delete(Long id) {
        // 全站#2 删除门禁: 已上线脚本(调度运行中)禁删, 先下线再删——防误删生产任务
        DnScript script = scriptMapper.selectById(id);
        if (script != null && "online".equalsIgnoreCase(script.getScheduleStatus())) {
            throw new com.datanote.common.exception.BusinessException("脚本「" + script.getScriptName() + "」已上线调度, 请先下线再删除");
        }
        scriptMapper.deleteById(id);
        projectAssetCleaner.onAssetDeleted("SCRIPT", id);
        // 删除即清理: 触发 SQL 血缘重建(全删重建, 已删脚本自然排除), 否则残留边留到夜间兜底
        eventPublisher.publishEvent(new com.datanote.domain.orchestration.ScriptSavedEvent(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void moveScript(Long id, Long targetFolderId) {
        DnScript script = scriptMapper.selectById(id);
        if (script == null) throw new ResourceNotFoundException("脚本");
        DnScriptFolder targetFolder = folderMapper.selectById(targetFolderId);
        if (targetFolder == null) throw new ResourceNotFoundException("目标文件夹");
        DnScript update = new DnScript();
        update.setId(id);
        update.setFolderId(targetFolderId);
        update.setUpdatedAt(LocalDateTime.now());
        scriptMapper.updateById(update);
    }

    public List<Map<String, Object>> allWithContent() {
        List<DnScript> scripts = scriptMapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>();
        if (scripts != null) for (DnScript s : scripts) {
            if (s == null) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getScriptName());
            m.put("content", s.getContent());
            m.put("scheduleStatus", s.getScheduleStatus());
            m.put("type", "script");
            result.add(m);
        }
        // 同步任务也加入，让前端依赖分析能匹配到 ODS 任务
        List<DnSyncTask> syncTasks = syncTaskMapper.selectList(null);
        if (syncTasks != null) for (DnSyncTask t : syncTasks) {
            if (t == null) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getTargetTable());
            m.put("content", null);
            m.put("scheduleStatus", t.getScheduleStatus());
            m.put("type", "syncTask");
            result.add(m);
        }
        return result;
    }

    // ========== 版本管理 ==========

    public List<DnScriptVersion> listVersions(Long scriptId) {
        if (scriptId == null) return new ArrayList<>();
        // 最近版本(保存+上线混排)取前10
        QueryWrapper<DnScriptVersion> recentQ = new QueryWrapper<>();
        recentQ.eq("script_id", scriptId).orderByDesc("committed_at", "id").last("LIMIT 10");
        List<DnScriptVersion> recent = scriptVersionMapper.selectList(recentQ);
        // 上线版本永久保留, 须始终可见(可能被较新的保存挤出前10而丢失关键回滚点)
        QueryWrapper<DnScriptVersion> onlineQ = new QueryWrapper<>();
        onlineQ.eq("script_id", scriptId).eq("version_type", "online").orderByDesc("committed_at", "id");
        List<DnScriptVersion> online = scriptVersionMapper.selectList(onlineQ);
        java.util.LinkedHashMap<Long, DnScriptVersion> merged = new java.util.LinkedHashMap<>();
        if (recent != null) for (DnScriptVersion v : recent) if (v != null) merged.put(v.getId(), v);
        if (online != null) for (DnScriptVersion v : online) if (v != null) merged.putIfAbsent(v.getId(), v);
        List<DnScriptVersion> all = new ArrayList<>(merged.values());
        all.sort((a, b) -> {
            int c = compareTimeDesc(a.getCommittedAt(), b.getCommittedAt());
            return c != 0 ? c : Long.compare(b.getId() == null ? 0 : b.getId(), a.getId() == null ? 0 : a.getId());
        });
        return all;
    }

    /** committed_at 降序比较, null 排末尾。 */
    private static int compareTimeDesc(LocalDateTime a, LocalDateTime b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return b.compareTo(a);
    }

    /** 回滚到指定历史版本: 先把当前内容存为历史版本(防误操作丢失现状), 再用目标版本内容覆盖。 */
    @Transactional(rollbackFor = Exception.class)
    public DnScript rollbackToVersion(Long scriptId, Long versionId) {
        if (scriptId == null || versionId == null) throw new BusinessException("脚本ID与版本ID不能为空");
        DnScript script = scriptMapper.selectById(scriptId);
        if (script == null) throw new ResourceNotFoundException("脚本");
        DnScriptVersion ver = scriptVersionMapper.selectById(versionId);
        if (ver == null || !scriptId.equals(ver.getScriptId())) throw new BusinessException("版本不存在或不属于该脚本");
        createScriptVersion(script);   // 当前内容先存为历史版本, 保留回滚前现状
        script.setContent(ver.getContent());
        script.setUpdatedAt(LocalDateTime.now());
        scriptMapper.updateById(script);
        return scriptMapper.selectById(scriptId);
    }

    private void createScriptVersion(DnScript script) {
        String content = script.getContent() == null ? "" : script.getContent();
        if (content.trim().isEmpty()) return;

        QueryWrapper<DnScriptVersion> latestQuery = new QueryWrapper<>();
        latestQuery.eq("script_id", script.getId())
                .orderByDesc("version", "id")
                .last("LIMIT 1");
        DnScriptVersion latestVersion = scriptVersionMapper.selectOne(latestQuery);
        // 全站#3: 同内容不重复建版——防连续 Ctrl+S 刷掉最近10个回滚点
        if (latestVersion != null && content.equals(latestVersion.getContent())) {
            return;
        }

        DnScriptVersion version = new DnScriptVersion();
        version.setScriptId(script.getId());
        version.setVersion(latestVersion == null ? 1 : latestVersion.getVersion() + 1);
        version.setContent(content);
        version.setCommitMsg("自动保存历史版本");
        version.setCommittedBy("system");
        version.setCommittedAt(LocalDateTime.now());
        version.setVersionType("save");
        scriptVersionMapper.insert(version);

        // 清理旧版本，只保留最近 10 个 save 版本（online 版本永久保留）
        QueryWrapper<DnScriptVersion> cleanupQuery = new QueryWrapper<>();
        cleanupQuery.eq("script_id", script.getId())
                .eq("version_type", "save")
                .orderByDesc("committed_at", "id")
                .last("LIMIT 10, 1000000");
        List<DnScriptVersion> expired = scriptVersionMapper.selectList(cleanupQuery);
        if (expired != null) for (DnScriptVersion v : expired) {
            if (v != null) scriptVersionMapper.deleteById(v.getId());
        }
    }

    // ========== 文件夹 CRUD ==========

    public DnScriptFolder createFolder(DnScriptFolder folder) {
        folder.setCreatedAt(LocalDateTime.now());
        folderMapper.insert(folder);
        return folder;
    }

    public void renameFolder(Long id, String name) {
        DnScriptFolder folder = folderMapper.selectById(id);
        if (folder == null) throw new ResourceNotFoundException("文件夹");
        folder.setFolderName(name);
        folderMapper.updateById(folder);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteFolder(Long id) {
        deleteFolderRecursively(id);
    }

    private void deleteFolderRecursively(Long folderId) {
        QueryWrapper<DnScriptFolder> childQuery = new QueryWrapper<>();
        childQuery.eq("parent_id", folderId);
        List<DnScriptFolder> children = folderMapper.selectList(childQuery);
        if (children != null) for (DnScriptFolder child : children) {
            if (child != null) deleteFolderRecursively(child.getId());
        }
        // 全站#2: 逐脚本走 delete(id) 统一门禁+项目资产清理(原批量 delete 绕过两者)
        QueryWrapper<DnScript> scriptQuery = new QueryWrapper<>();
        scriptQuery.eq("folder_id", folderId);
        List<DnScript> scripts = scriptMapper.selectList(scriptQuery);
        if (scripts != null) for (DnScript s : scripts) {
            if (s != null) delete(s.getId());
        }
        folderMapper.deleteById(folderId);
    }

    // ========== 同步任务 CRUD ==========

    public DnSyncTask saveSyncTask(DnSyncTask task) {
        if (task.getId() != null) {
            task.setUpdatedAt(LocalDateTime.now());
            syncTaskMapper.updateById(task);
        } else {
            task.setCreatedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            syncTaskMapper.insert(task);
        }
        return task;
    }

    public void deleteSyncTask(Long id) {
        syncTaskMapper.deleteById(id);
    }
}
