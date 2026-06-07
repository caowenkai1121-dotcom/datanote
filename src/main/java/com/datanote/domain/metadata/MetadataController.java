package com.datanote.domain.metadata;

import com.datanote.common.model.R;
import com.datanote.domain.metadata.model.ColumnInfo;
import com.datanote.domain.metadata.model.DnTableComment;
import com.datanote.domain.metadata.model.DnTableFavorite;
import com.datanote.platform.portal.model.DnSearchHistory;
import com.datanote.domain.metadata.DataMapService;
import com.datanote.domain.datasource.DatasourceExploreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 数据地图 Controller — 参数校验 + 调用 Service
 */
@Slf4j
@RestController
@RequestMapping("/api/metadata")
@RequiredArgsConstructor
@Tag(name = "数据地图", description = "元数据查询、AI搜索、收藏、评论等")
public class MetadataController {

    private final DataMapService dataMapService;          // 离线目录：搜索/收藏/评论/历史/表详情
    private final DatasourceExploreService exploreService; // 在线探查：库/表/列/预览/探查/DDL/分区

    private static final String NAME_PATTERN = "[a-zA-Z0-9_]+";

    /**
     * 数据扫描类查询失败时，识别 Doris BE(计算/存储后端)不可用的情况，给出清晰可操作提示，
     * 否则返回原有兜底文案。（BE 宕机会导致 SELECT/探查/分区等扫描查询失败，而元数据查询仍正常）
     */
    private static String dorisMsg(Exception e, String fallback) {
        String m = (e == null || e.getMessage() == null) ? "" : e.getMessage();
        if (m.contains("No backend available") || m.contains("not alive")
                || m.contains("no queryable replicas") || m.contains("does not exist or not alive")) {
            return "数据仓库后端(Doris BE)当前不可用，无法查询表数据，请联系管理员检查 Doris BE 服务状态";
        }
        return fallback;
    }

    // ========== 基础元数据查询（Hive） ==========

    @Operation(summary = "获取Hive数据库列表")
    @GetMapping("/databases")
    public R<List<String>> databases() {
        try {
            return R.ok(exploreService.getHiveDatabases());
        } catch (Exception e) {
            log.error("获取数据库列表失败", e);
            return R.fail("获取数据库列表失败");
        }
    }

    @Operation(summary = "获取Hive表列表")
    @GetMapping("/tables")
    public R<List<String>> tables(@RequestParam String db) {
        if (db == null || !db.matches(NAME_PATTERN)) {
            return R.fail("非法的库名");
        }
        try {
            return R.ok(exploreService.getHiveTables(db));
        } catch (Exception e) {
            log.error("获取表列表失败", e);
            return R.fail("获取表列表失败");
        }
    }

    @Operation(summary = "获取Hive字段列表")
    @GetMapping("/columns")
    public R<List<ColumnInfo>> columns(@RequestParam String db, @RequestParam String table) {
        if (db == null || !db.matches(NAME_PATTERN) || table == null || !table.matches(NAME_PATTERN)) {
            return R.fail("非法的库名或表名");
        }
        try {
            return R.ok(exploreService.getHiveColumns(db, table));
        } catch (Exception e) {
            log.error("获取字段列表失败", e);
            return R.fail("获取字段列表失败");
        }
    }

    // ========== 搜索 ==========

    @Operation(summary = "搜索表")
    @GetMapping("/search")
    public R<List<Map<String, Object>>> search(@RequestParam String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return R.ok(Collections.<Map<String, Object>>emptyList());
        }
        try {
            return R.ok(dataMapService.searchTables(keyword.trim()));
        } catch (Exception e) {
            log.error("搜索失败", e);
            return R.fail("搜索失败");
        }
    }

    @Operation(summary = "AI智能搜索表")
    @PostMapping("/ai-search")
    public R<Map<String, Object>> aiSearch(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        if (query == null || query.trim().isEmpty()) {
            return R.fail("查询内容不能为空");
        }
        try {
            return R.ok(dataMapService.aiSearch(query.trim()));
        } catch (Exception e) {
            log.error("AI搜索失败", e);
            return R.fail("AI搜索失败");
        }
    }

    @Operation(summary = "获取所有表列表（带元数据）")
    @GetMapping("/all-tables")
    public R<List<Map<String, Object>>> allTables(@RequestParam(required = false) String db) {
        try {
            List<Map<String, Object>> tables = exploreService.getAllTablesSummary();
            if (db != null && !db.isEmpty()) {
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> t : tables) {
                    if (db.equals(t.get("TABLE_SCHEMA"))) {
                        filtered.add(t);
                    }
                }
                return R.ok(filtered);
            }
            return R.ok(tables);
        } catch (Exception e) {
            log.error("获取表列表失败", e);
            return R.fail("获取表列表失败");
        }
    }

    // ========== 搜索历史 ==========

    @Operation(summary = "获取最近搜索")
    @GetMapping("/search-history")
    public R<List<DnSearchHistory>> searchHistory() {
        return R.ok(dataMapService.getSearchHistory());
    }

    @Operation(summary = "记录搜索历史")
    @PostMapping("/search-history")
    public R<String> addSearchHistory(@RequestBody DnSearchHistory history) {
        dataMapService.addSearchHistory(history);
        return R.ok("ok");
    }

    @Operation(summary = "清空搜索历史")
    @DeleteMapping("/search-history")
    public R<String> clearSearchHistory() {
        dataMapService.clearSearchHistory("default");
        return R.ok("已清空");
    }

    // ========== 收藏 ==========

    @Operation(summary = "获取收藏列表")
    @GetMapping("/favorites")
    public R<List<DnTableFavorite>> favorites() {
        return R.ok(dataMapService.getFavorites());
    }

    @Operation(summary = "收藏/取消收藏")
    @PostMapping("/favorite/toggle")
    public R<Map<String, Object>> toggleFavorite(@RequestBody Map<String, String> body) {
        String db = body.get("databaseName");
        String table = body.get("tableName");
        if (db == null || db.isEmpty() || table == null || table.isEmpty()) {
            return R.fail("参数不完整");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("favorited", dataMapService.toggleFavorite(db, table));
        return R.ok(result);
    }

    @Operation(summary = "检查是否已收藏")
    @GetMapping("/favorite/check")
    public R<Map<String, Object>> checkFavorite(@RequestParam String db, @RequestParam String table) {
        Map<String, Object> result = new HashMap<>();
        result.put("favorited", dataMapService.isFavorited(db, table));
        return R.ok(result);
    }

    // ========== 热门表 ==========

    @Operation(summary = "获取热门表")
    @GetMapping("/popular")
    public R<List<Map<String, Object>>> popular() {
        try {
            return R.ok(dataMapService.getPopularTables());
        } catch (Exception e) {
            log.error("获取热门表失败", e);
            return R.fail("获取热门表失败");
        }
    }

    // ========== 评论 ==========

    @Operation(summary = "获取表评论列表")
    @GetMapping("/comments")
    public R<List<DnTableComment>> getComments(@RequestParam String db, @RequestParam String table) {
        return R.ok(dataMapService.getComments(db, table));
    }

    @Operation(summary = "新增评论")
    @PostMapping("/comments")
    public R<DnTableComment> addComment(@RequestBody Map<String, String> body) {
        String db = body.get("db");
        String table = body.get("table");
        String content = body.get("content");
        if (db == null || db.isEmpty() || table == null || table.isEmpty()) {
            return R.fail("参数不完整");
        }
        if (content == null || content.trim().isEmpty()) {
            return R.fail("评论内容不能为空");
        }
        return R.ok(dataMapService.addComment(db, table, content));
    }

    @Operation(summary = "删除评论")
    @DeleteMapping("/comments/{id}")
    public R<String> deleteComment(@PathVariable Long id) {
        dataMapService.deleteComment(id);
        return R.ok("删除成功");
    }

    // ========== 数据预览 ==========

    @Operation(summary = "数据预览")
    @GetMapping("/preview")
    public R<Map<String, Object>> preview(@RequestParam String db, @RequestParam String table) {
        if (!db.matches(NAME_PATTERN) || !table.matches(NAME_PATTERN)) {
            return R.fail("非法的库名或表名");
        }
        try {
            return R.ok(exploreService.preview(db, table));
        } catch (Exception e) {
            log.error("数据预览失败: {}.{}", db, table, e);
            return R.fail(dorisMsg(e, "查询失败"));
        }
    }

    // ========== 数据探查 ==========

    @Operation(summary = "数据探查")
    @GetMapping("/profile")
    public R<Map<String, Object>> profile(@RequestParam String db, @RequestParam String table) {
        if (!db.matches(NAME_PATTERN) || !table.matches(NAME_PATTERN)) {
            return R.fail("非法的库名或表名");
        }
        try {
            return R.ok(exploreService.profile(db, table));
        } catch (Exception e) {
            log.error("数据探查失败: {}.{}", db, table, e);
            return R.fail(dorisMsg(e, "探查失败"));
        }
    }

    // ========== DDL / SQL ==========

    @Operation(summary = "生成建表DDL和查询SQL")
    @GetMapping("/ddl")
    public R<Map<String, String>> ddl(@RequestParam String db, @RequestParam String table) {
        if (!db.matches(NAME_PATTERN) || !table.matches(NAME_PATTERN)) {
            return R.fail("非法的库名或表名");
        }
        try {
            return R.ok(exploreService.generateDdlAndSelect(db, table));
        } catch (Exception e) {
            log.error("获取DDL失败: {}.{}", db, table, e);
            return R.fail("获取DDL失败");
        }
    }

    // ========== 表详情 ==========

    @Operation(summary = "获取表详情（基本信息+字段+元数据）")
    @GetMapping("/table-detail")
    public R<Map<String, Object>> tableDetail(@RequestParam String db, @RequestParam String table) {
        if (!db.matches(NAME_PATTERN) || !table.matches(NAME_PATTERN)) {
            return R.fail("非法的库名或表名");
        }
        try {
            return R.ok(dataMapService.getTableDetail(db, table));
        } catch (Exception e) {
            log.error("获取表详情失败: {}.{}", db, table, e);
            return R.fail("获取表详情失败");
        }
    }

    // ========== 分区信息 ==========

    @Operation(summary = "获取表分区列表")
    @GetMapping("/partitions")
    public R<List<Map<String, Object>>> partitions(@RequestParam String db, @RequestParam String table) {
        if (!db.matches(NAME_PATTERN) || !table.matches(NAME_PATTERN)) {
            return R.fail("非法的库名或表名");
        }
        try {
            return R.ok(exploreService.getPartitions(db, table));
        } catch (Exception e) {
            log.error("获取分区信息失败: {}.{}", db, table, e);
            return R.fail(dorisMsg(e, "获取分区信息失败"));
        }
    }
}
