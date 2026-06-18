package com.datanote.domain.metadata;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.metadata.mapper.DnMetaCollectLogMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnColumnMeta;
import com.datanote.domain.metadata.model.DnMetaCollectLog;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.model.R;
import com.datanote.domain.datasource.MetadataCrawlerService;
import com.datanote.platform.iam.DataAclService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 元数据中心 Controller — 表/字段元数据管理
 */
@RestController
@RequestMapping("/api/metadata-center")
@Tag(name = "元数据中心", description = "表和字段级元数据管理、搜索、标签")
@RequiredArgsConstructor
public class MetadataCenterController {

    private final DnTableMetaMapper tableMetaMapper;
    private final DnColumnMetaMapper columnMetaMapper;
    private final MetadataCrawlerService crawlerService;
    private final DnMetaCollectLogMapper collectLogMapper;
    private final DataAclService dataAclService;

    /** 表搜索默认/最大分页与采集日志条数限制 */
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 5000;
    private static final int COLLECT_LOG_LIMIT = 50;

    /**
     * 搜索表元数据
     */
    @Operation(summary = "搜索表元数据")
    @GetMapping("/tables")
    public R<Map<String, Object>> searchTables(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        QueryWrapper<DnTableMeta> qw = new QueryWrapper<>();
        if (datasourceId != null) {
            qw.eq("datasource_id", datasourceId);
        }
        if (subjectId != null) {   // R39 按主题域筛选资产(完成主题域→资产链服务端)
            qw.eq("subject_id", subjectId);
        }
        if (keyword != null && !keyword.isEmpty()) {
            qw.and(w -> w.like("table_name", keyword)
                    .or().like("table_comment", keyword)
                    .or().like("database_name", keyword));
        }
        if (tag != null && !tag.isEmpty()) {
            qw.like("tags", tag);
        }
        // 同条件全量计数(须在 orderBy/limit 之前), 供前端展示真实总数, 不再静默截断
        long total = tableMetaMapper.selectCount(qw);
        qw.orderByDesc("updated_at");
        int lim = (limit == null || limit <= 0) ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);   // 无参默认 100 行为不变
        int off = (offset == null || offset < 0) ? 0 : offset;
        qw.last("LIMIT " + lim + " OFFSET " + off);
        List<DnTableMeta> rows = filterTables(tableMetaMapper.selectList(qw));
        Map<String, Object> data = new HashMap<>();
        data.put("rows", rows);
        data.put("total", rows.size());
        data.put("limit", lim);
        data.put("offset", off);
        return R.ok(data);
    }

    /**
     * 获取表元数据详情
     */
    @Operation(summary = "表元数据详情")
    @GetMapping("/table/{id}")
    public R<Map<String, Object>> getTableDetail(@PathVariable Long id) {
        DnTableMeta meta = tableMetaMapper.selectById(id);
        if (meta == null) {
            return R.fail("表元数据不存在");
        }
        requireTableAccess(meta);
        QueryWrapper<DnColumnMeta> colQw = new QueryWrapper<>();
        colQw.eq("table_meta_id", id).orderByAsc("id");
        List<DnColumnMeta> columns = columnMetaMapper.selectList(colQw);

        Map<String, Object> result = new HashMap<>();
        result.put("table", meta);
        result.put("columns", columns);
        return R.ok(result);
    }

    /**
     * 保存表元数据
     */
    @Operation(summary = "保存表元数据")
    @PostMapping("/table/save")
    public R<DnTableMeta> saveTable(@RequestBody DnTableMeta meta) {
        if (meta.getId() != null) {
            DnTableMeta old = tableMetaMapper.selectById(meta.getId());
            if (old != null) requireTableAccess(old);
            requireTableAccess(meta);
            meta.setUpdatedAt(LocalDateTime.now());
            tableMetaMapper.updateById(meta);
        } else {
            requireTableAccess(meta);
            meta.setCreatedAt(LocalDateTime.now());
            meta.setUpdatedAt(LocalDateTime.now());
            tableMetaMapper.insert(meta);
        }
        return R.ok(meta);
    }

    /**
     * 删除表元数据
     */
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "删除表元数据")
    @DeleteMapping("/table/{id}")
    public R<String> deleteTable(@PathVariable Long id) {
        DnTableMeta meta = tableMetaMapper.selectById(id);
        if (meta != null) requireTableAccess(meta);
        tableMetaMapper.deleteById(id);
        // 同时删除关联的字段元数据
        QueryWrapper<DnColumnMeta> colQw = new QueryWrapper<>();
        colQw.eq("table_meta_id", id);
        columnMetaMapper.delete(colQw);
        return R.ok("删除成功");
    }

    /**
     * 保存字段元数据
     */
    @Operation(summary = "保存字段元数据")
    @PostMapping("/column/save")
    public R<DnColumnMeta> saveColumn(@RequestBody DnColumnMeta meta) {
        requireTableMetaIdAccess(meta.getTableMetaId());
        if (meta.getId() != null) {
            meta.setUpdatedAt(LocalDateTime.now());
            columnMetaMapper.updateById(meta);
        } else {
            meta.setCreatedAt(LocalDateTime.now());
            meta.setUpdatedAt(LocalDateTime.now());
            columnMetaMapper.insert(meta);
        }
        return R.ok(meta);
    }

    /**
     * 批量保存字段元数据
     */
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "批量保存字段元数据")
    @PostMapping("/columns/batch-save")
    public R<String> batchSaveColumns(@RequestBody List<DnColumnMeta> columns) {
        for (DnColumnMeta col : columns) {
            requireTableMetaIdAccess(col.getTableMetaId());
            if (col.getId() != null) {
                col.setUpdatedAt(LocalDateTime.now());
                columnMetaMapper.updateById(col);
            } else {
                col.setCreatedAt(LocalDateTime.now());
                col.setUpdatedAt(LocalDateTime.now());
                columnMetaMapper.insert(col);
            }
        }
        return R.ok("保存成功");
    }

    /**
     * 元数据统计概览
     */
    @Operation(summary = "元数据统计")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        Map<String, Object> data = new HashMap<>();
        data.put("tableCount", tableMetaMapper.selectCount(null));
        data.put("columnCount", columnMetaMapper.selectCount(null));

        QueryWrapper<DnTableMeta> coreQw = new QueryWrapper<>();
        coreQw.eq("importance", "core");
        data.put("coreTableCount", tableMetaMapper.selectCount(coreQw));

        return R.ok(data);
    }

    /**
     * 手动触发采集：指定源数据源
     */
    @Operation(summary = "采集指定数据源元数据")
    @PostMapping("/crawl/datasource/{id}")
    public R<DnMetaCollectLog> crawlDatasource(@PathVariable Long id) {
        requireDatasourceAccess(id);
        return R.ok(crawlerService.crawlDatasource(id));
    }

    /**
     * 手动触发采集：Doris 数仓
     */
    @Operation(summary = "采集Doris数仓元数据")
    @PostMapping("/crawl/warehouse")
    public R<DnMetaCollectLog> crawlWarehouse() {
        return R.ok(crawlerService.crawlWarehouse());
    }

    /**
     * 手动触发采集：全部（源库 + 数仓，异步执行避免请求超时）
     */
    @Operation(summary = "采集全部元数据")
    @PostMapping("/crawl/all")
    public R<String> crawlAll() {
        new Thread(crawlerService::crawlAll, "meta-crawl-all").start();
        return R.ok("采集已启动，完成后可在采集日志查看");
    }

    /**
     * 采集日志列表（最近 50 条）
     */
    @Operation(summary = "采集日志")
    @GetMapping("/collect-logs")
    public R<List<DnMetaCollectLog>> collectLogs() {
        QueryWrapper<DnMetaCollectLog> qw = new QueryWrapper<>();
        qw.orderByDesc("started_at").last("LIMIT " + COLLECT_LOG_LIMIT);
        return R.ok(collectLogMapper.selectList(qw));
    }

    private List<DnTableMeta> filterTables(List<DnTableMeta> rows) {
        if (rows == null || rows.isEmpty()) return new java.util.ArrayList<>();
        List<DnTableMeta> visible = new java.util.ArrayList<>(rows);
        Set<String> denied = dataAclService.deniedIds("TABLE");
        if (denied != null && !denied.isEmpty()) {
            visible.removeIf(meta -> meta == null || denied.contains(tableResourceId(meta)));
        }
        return visible;
    }

    private void requireTableMetaIdAccess(Long tableMetaId) {
        if (tableMetaId == null) return;
        DnTableMeta meta = tableMetaMapper.selectById(tableMetaId);
        if (meta != null) requireTableAccess(meta);
    }

    private void requireTableAccess(DnTableMeta meta) {
        if (meta == null) return;
        if (!dataAclService.canAccess("TABLE", tableResourceId(meta))) {
            throw new BusinessException("无权访问该表");
        }
    }

    private String tableResourceId(DnTableMeta meta) {
        String db = meta.getDatabaseName() == null ? "" : meta.getDatabaseName().trim();
        String table = meta.getTableName() == null ? "" : meta.getTableName().trim();
        return db + "." + table;
    }

    private void requireDatasourceAccess(Long id) {
        if (id != null && !dataAclService.canAccess("DATASOURCE", String.valueOf(id))) {
            throw new BusinessException("无权访问该数据源");
        }
    }
}
