package com.datanote.domain.metadata;

import com.datanote.domain.metadata.model.DnTableComment;
import com.datanote.domain.metadata.model.DnTableFavorite;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.platform.portal.model.DnSearchHistory;
import com.datanote.domain.datasource.DatasourceExploreService;
import com.datanote.platform.ai.AiAssistService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnSearchHistoryMapper;
import com.datanote.mapper.DnTableCommentMapper;
import com.datanote.mapper.DnTableFavoriteMapper;
import com.datanote.mapper.DnTableMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 数据资产目录 Service —— 离线目录侧职责：搜索、AI 搜索、搜索历史、收藏、热门、评论、表详情。
 *
 * 重构(R5)：原 648 行 god-service 把"连库探查"与"读离线目录"混在一处。本服务现只负责
 * 离线目录/社交(读 DnTableMeta/收藏/评论/搜索历史)，凡需实时库数据均委托
 * {@link DatasourceExploreService}（唯一直连库出口），不再自行持有 DB 连接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataMapService {

    private final AiAssistService aiAssistService;
    private final DnTableCommentMapper tableCommentMapper;
    private final DnTableFavoriteMapper tableFavoriteMapper;
    private final DnSearchHistoryMapper searchHistoryMapper;
    private final DnTableMetaMapper tableMetaMapper;
    private final DatasourceExploreService exploreService;

    // ========== 搜索（基于在线全表摘要） ==========

    public List<Map<String, Object>> searchTables(String keyword) throws SQLException {
        List<Map<String, Object>> allTables = exploreService.getAllTablesSummary();
        List<Map<String, Object>> matched = new ArrayList<Map<String, Object>>();
        String kw = keyword.toLowerCase();
        for (Map<String, Object> t : allTables) {
            String tableName = String.valueOf(t.get("TABLE_NAME")).toLowerCase();
            String dbName = String.valueOf(t.get("TABLE_SCHEMA")).toLowerCase();
            String comment = t.get("TABLE_COMMENT") != null ? String.valueOf(t.get("TABLE_COMMENT")).toLowerCase() : "";
            if (tableName.contains(kw) || dbName.contains(kw) || comment.contains(kw)) {
                matched.add(t);
                if (matched.size() >= 50) break;
            }
        }
        return matched;
    }

    public Map<String, Object> aiSearch(String query) throws Exception {
        List<Map<String, Object>> allTables = exploreService.getAllTablesSummary();
        StringBuilder tableList = new StringBuilder();
        for (Map<String, Object> t : allTables) {
            tableList.append(t.get("TABLE_SCHEMA")).append(".").append(t.get("TABLE_NAME"));
            Object comment = t.get("TABLE_COMMENT");
            if (comment != null && !comment.toString().isEmpty()) {
                tableList.append(" (").append(comment).append(")");
            }
            tableList.append("\n");
        }

        String prompt = "你是一个数据资产搜索引擎。用户想找数据表，请根据用户描述匹配最相关的表。\n\n"
                + "可用的数据表列表：\n" + tableList.toString() + "\n"
                + "用户描述：" + query + "\n\n"
                + "请返回最匹配的表（最多5个），格式如下（严格JSON数组，不要其他文字）：\n"
                + "[{\"db\":\"库名\",\"table\":\"表名\",\"reason\":\"匹配原因\"}]";

        String aiReply = aiAssistService.chat(prompt, null);

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("interpretation", "AI 正在为您分析：" + query);
        result.put("raw", aiReply);
        String jsonStr = extractJsonArray(aiReply);
        if (jsonStr != null) {
            result.put("tables", jsonStr);
        }
        return result;
    }

    // ========== 搜索历史 ==========

    public List<DnSearchHistory> getSearchHistory() {
        QueryWrapper<DnSearchHistory> qw = new QueryWrapper<DnSearchHistory>();
        qw.orderByDesc("searched_at").last("LIMIT 10");
        return searchHistoryMapper.selectList(qw);
    }

    public void addSearchHistory(DnSearchHistory history) {
        QueryWrapper<DnSearchHistory> qw = new QueryWrapper<DnSearchHistory>();
        qw.eq("database_name", history.getDatabaseName()).eq("table_name", history.getTableName());
        DnSearchHistory existing = searchHistoryMapper.selectOne(qw);
        if (existing != null) {
            existing.setSearchedAt(LocalDateTime.now());
            searchHistoryMapper.updateById(existing);
        } else {
            history.setSearchedAt(LocalDateTime.now());
            searchHistoryMapper.insert(history);
        }
    }

    public void clearSearchHistory(String createdBy) {
        QueryWrapper<DnSearchHistory> qw = new QueryWrapper<DnSearchHistory>();
        qw.eq("created_by", createdBy);
        searchHistoryMapper.delete(qw);
    }

    // ========== 收藏 ==========

    public List<DnTableFavorite> getFavorites() {
        QueryWrapper<DnTableFavorite> qw = new QueryWrapper<DnTableFavorite>();
        qw.orderByDesc("created_at").last("LIMIT 30");
        return tableFavoriteMapper.selectList(qw);
    }

    public boolean toggleFavorite(String db, String table) {
        QueryWrapper<DnTableFavorite> qw = new QueryWrapper<DnTableFavorite>();
        qw.eq("database_name", db).eq("table_name", table);
        DnTableFavorite existing = tableFavoriteMapper.selectOne(qw);
        if (existing != null) {
            tableFavoriteMapper.deleteById(existing.getId());
            return false;
        } else {
            DnTableFavorite fav = new DnTableFavorite();
            fav.setDatabaseName(db);
            fav.setTableName(table);
            fav.setCreatedBy("default");
            fav.setCreatedAt(LocalDateTime.now());
            tableFavoriteMapper.insert(fav);
            return true;
        }
    }

    public boolean isFavorited(String db, String table) {
        QueryWrapper<DnTableFavorite> qw = new QueryWrapper<DnTableFavorite>();
        qw.eq("database_name", db).eq("table_name", table);
        return tableFavoriteMapper.selectCount(qw) > 0;
    }

    // ========== 热门表 ==========

    public List<Map<String, Object>> getPopularTables() throws SQLException {
        // 先从搜索历史中获取热门表
        QueryWrapper<DnSearchHistory> qw = new QueryWrapper<DnSearchHistory>();
        qw.groupBy("database_name", "table_name")
          .orderByDesc("count(*)").last("LIMIT 10")
          .select("database_name", "table_name", "count(*) as cnt");
        List<Map<String, Object>> historyList = searchHistoryMapper.selectMaps(qw);

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> h : historyList) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("TABLE_SCHEMA", h.get("database_name"));
            row.put("TABLE_NAME", h.get("table_name"));
            row.put("search_count", h.get("cnt"));
            row.put("TABLE_COMMENT", "");
            row.put("TABLE_ROWS", null);
            row.put("col_count", null);
            result.add(row);
        }

        // 不足 10 个则用全部表填充
        if (result.size() < 10) {
            List<Map<String, Object>> allTables = exploreService.getAllTablesSummary();
            Set<String> existing = new HashSet<String>();
            for (Map<String, Object> t : result) {
                existing.add(t.get("TABLE_SCHEMA") + "." + t.get("TABLE_NAME"));
            }
            for (Map<String, Object> t : allTables) {
                if (result.size() >= 10) break;
                String key = t.get("TABLE_SCHEMA") + "." + t.get("TABLE_NAME");
                if (!existing.contains(key)) {
                    result.add(t);
                }
            }
        }
        return result;
    }

    // ========== 评论 ==========

    public List<DnTableComment> getComments(String db, String table) {
        Long tableMetaId = getOrCreateTableMetaId(db, table);
        QueryWrapper<DnTableComment> qw = new QueryWrapper<DnTableComment>();
        qw.eq("table_meta_id", tableMetaId).orderByDesc("created_at");
        return tableCommentMapper.selectList(qw);
    }

    public DnTableComment addComment(String db, String table, String content) {
        Long tableMetaId = getOrCreateTableMetaId(db, table);
        DnTableComment comment = new DnTableComment();
        comment.setTableMetaId(tableMetaId);
        comment.setContent(content.trim());
        comment.setCreatedBy("default");
        comment.setCreatedAt(LocalDateTime.now());
        tableCommentMapper.insert(comment);
        return comment;
    }

    public void deleteComment(Long id) {
        tableCommentMapper.deleteById(id);
    }

    // ========== 表详情（在线信息 + 离线收藏/评论） ==========

    public Map<String, Object> getTableDetail(String db, String table) throws Exception {
        Map<String, Object> result = new HashMap<String, Object>();

        Map<String, Object> info = exploreService.getDorisTableInfo(db, table);

        result.put("tableInfo", info);
        result.put("columns", exploreService.getHiveColumns(db, table));
        result.put("favorited", isFavorited(db, table));
        Long tableMetaId = findTableMetaId(db, table);
        if (tableMetaId != null) {
            QueryWrapper<DnTableComment> cmtQw = new QueryWrapper<DnTableComment>();
            cmtQw.eq("table_meta_id", tableMetaId);
            result.put("commentCount", tableCommentMapper.selectCount(cmtQw));
        } else {
            result.put("commentCount", 0);
        }
        return result;
    }

    // ========== 内部方法 ==========

    private Long findTableMetaId(String db, String table) {
        QueryWrapper<DnTableMeta> qw = new QueryWrapper<DnTableMeta>();
        qw.eq("database_name", db).eq("table_name", table).last("LIMIT 1");
        DnTableMeta meta = tableMetaMapper.selectOne(qw);
        return meta != null ? meta.getId() : null;
    }

    private Long getOrCreateTableMetaId(String db, String table) {
        Long id = findTableMetaId(db, table);
        if (id != null) return id;
        try {
            DnTableMeta meta = new DnTableMeta();
            meta.setDatasourceId(0L);
            meta.setDatabaseName(db);
            meta.setTableName(table);
            meta.setCreatedAt(LocalDateTime.now());
            meta.setUpdatedAt(LocalDateTime.now());
            tableMetaMapper.insert(meta);
            return meta.getId();
        } catch (Exception e) {
            Long retryId = findTableMetaId(db, table);
            if (retryId != null) return retryId;
            throw e;
        }
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        if (start == -1) return null;
        int end = text.lastIndexOf(']');
        if (end == -1 || end <= start) return null;
        return text.substring(start, end + 1);
    }
}
