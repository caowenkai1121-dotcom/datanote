package com.datanote.domain.metadata;

import com.datanote.domain.metadata.model.DnTableComment;
import com.datanote.domain.metadata.model.DnTableFavorite;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.platform.portal.model.DnSearchHistory;
import com.datanote.domain.datasource.DatasourceExploreService;
import com.datanote.platform.ai.AiAssistService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.portal.mapper.DnSearchHistoryMapper;
import com.datanote.domain.metadata.mapper.DnTableCommentMapper;
import com.datanote.domain.metadata.mapper.DnTableFavoriteMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new BusinessException("搜索关键词不能为空");
        }
        List<Map<String, Object>> allTables = exploreService.getAllTablesSummary();
        List<Map<String, Object>> matched = new ArrayList<Map<String, Object>>();
        if (allTables == null || allTables.isEmpty()) {
            return matched;
        }
        String kw = keyword.trim().toLowerCase();
        for (Map<String, Object> t : allTables) {
            if (t == null) continue;
            String tableName = t.get("TABLE_NAME") != null ? String.valueOf(t.get("TABLE_NAME")).toLowerCase() : "";
            String dbName = t.get("TABLE_SCHEMA") != null ? String.valueOf(t.get("TABLE_SCHEMA")).toLowerCase() : "";
            String comment = t.get("TABLE_COMMENT") != null ? String.valueOf(t.get("TABLE_COMMENT")).toLowerCase() : "";
            if (tableName.contains(kw) || dbName.contains(kw) || comment.contains(kw)) {
                matched.add(t);
                if (matched.size() >= 50) break;
            }
        }
        return matched;
    }

    public Map<String, Object> aiSearch(String query) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            throw new BusinessException("AI 搜索的查询内容不能为空");
        }
        List<Map<String, Object>> allTables = exploreService.getAllTablesSummary();
        StringBuilder tableList = new StringBuilder();
        if (allTables != null) {
            for (Map<String, Object> t : allTables) {
                if (t == null) continue;
                tableList.append(t.get("TABLE_SCHEMA")).append(".").append(t.get("TABLE_NAME"));
                Object comment = t.get("TABLE_COMMENT");
                if (comment != null && !comment.toString().isEmpty()) {
                    tableList.append(" (").append(comment).append(")");
                }
                tableList.append("\n");
            }
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

    @Transactional(rollbackFor = Exception.class)
    public void addSearchHistory(DnSearchHistory history) {
        if (history == null) {
            throw new BusinessException("搜索历史记录不能为空");
        }
        if (history.getDatabaseName() == null || history.getDatabaseName().trim().isEmpty()
                || history.getTableName() == null || history.getTableName().trim().isEmpty()) {
            throw new BusinessException("搜索历史的库名和表名不能为空");
        }
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
        if (createdBy == null || createdBy.trim().isEmpty()) {
            throw new BusinessException("清空搜索历史时操作人不能为空");
        }
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

    @Transactional(rollbackFor = Exception.class)
    public boolean toggleFavorite(String db, String table) {
        if (db == null || db.trim().isEmpty() || table == null || table.trim().isEmpty()) {
            throw new BusinessException("收藏操作的库名和表名不能为空");
        }
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
            fav.setCreatedBy(com.datanote.platform.iam.CurrentUserUtil.currentUser());
            fav.setCreatedAt(LocalDateTime.now());
            tableFavoriteMapper.insert(fav);
            return true;
        }
    }

    public boolean isFavorited(String db, String table) {
        if (db == null || db.trim().isEmpty() || table == null || table.trim().isEmpty()) {
            throw new BusinessException("查询收藏状态的库名和表名不能为空");
        }
        QueryWrapper<DnTableFavorite> qw = new QueryWrapper<DnTableFavorite>();
        qw.eq("database_name", db).eq("table_name", table);
        Long cnt = tableFavoriteMapper.selectCount(qw);
        return cnt != null && cnt > 0;
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
        if (historyList != null) {
            for (Map<String, Object> h : historyList) {
                if (h == null) continue;
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("TABLE_SCHEMA", h.get("database_name"));
                row.put("TABLE_NAME", h.get("table_name"));
                row.put("search_count", h.get("cnt"));
                row.put("TABLE_COMMENT", "");
                row.put("TABLE_ROWS", null);
                row.put("col_count", null);
                result.add(row);
            }
        }

        // 不足 10 个则用全部表填充
        if (result.size() < 10) {
            List<Map<String, Object>> allTables = exploreService.getAllTablesSummary();
            if (allTables != null) {
                Set<String> existing = new HashSet<String>();
                for (Map<String, Object> t : result) {
                    existing.add(t.get("TABLE_SCHEMA") + "." + t.get("TABLE_NAME"));
                }
                for (Map<String, Object> t : allTables) {
                    if (result.size() >= 10) break;
                    if (t == null) continue;
                    String key = t.get("TABLE_SCHEMA") + "." + t.get("TABLE_NAME");
                    if (existing.add(key)) {   // add 返回 false 表示已存在, 同时把新键纳入去重集, 防止 allTables 自身重复表被重复填充
                        result.add(t);
                    }
                }
            }
        }
        return result;
    }

    // ========== 评论 ==========

    @Transactional(rollbackFor = Exception.class)
    public List<DnTableComment> getComments(String db, String table) {
        if (db == null || db.trim().isEmpty() || table == null || table.trim().isEmpty()) {
            throw new BusinessException("查询评论的库名和表名不能为空");
        }
        Long tableMetaId = getOrCreateTableMetaId(db, table);
        QueryWrapper<DnTableComment> qw = new QueryWrapper<DnTableComment>();
        qw.eq("table_meta_id", tableMetaId).orderByDesc("created_at");
        List<DnTableComment> list = tableCommentMapper.selectList(qw);
        return list != null ? list : new ArrayList<DnTableComment>();
    }

    @Transactional(rollbackFor = Exception.class)
    public DnTableComment addComment(String db, String table, String content) {
        if (db == null || db.trim().isEmpty() || table == null || table.trim().isEmpty()) {
            throw new BusinessException("新增评论的库名和表名不能为空");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException("评论内容不能为空");
        }
        Long tableMetaId = getOrCreateTableMetaId(db, table);
        DnTableComment comment = new DnTableComment();
        comment.setTableMetaId(tableMetaId);
        comment.setContent(content.trim());
        comment.setCreatedBy(com.datanote.platform.iam.CurrentUserUtil.currentUser());   // 多用户: 评论显示真实作者
        comment.setCreatedAt(LocalDateTime.now());
        tableCommentMapper.insert(comment);
        return comment;
    }

    public void deleteComment(Long id) {
        if (id == null) {
            throw new BusinessException("删除评论时评论 ID 不能为空");
        }
        tableCommentMapper.deleteById(id);
    }

    // ========== 表详情（在线信息 + 离线收藏/评论） ==========

    public Map<String, Object> getTableDetail(String db, String table) throws Exception {
        if (db == null || db.trim().isEmpty() || table == null || table.trim().isEmpty()) {
            throw new BusinessException("获取表详情的库名和表名不能为空");
        }
        Map<String, Object> result = new HashMap<String, Object>();

        Map<String, Object> info = exploreService.getDorisTableInfo(db, table);

        result.put("tableInfo", info);
        result.put("columns", exploreService.getHiveColumns(db, table));
        result.put("favorited", isFavorited(db, table));
        Long tableMetaId = findTableMetaId(db, table);
        if (tableMetaId != null) {
            QueryWrapper<DnTableComment> cmtQw = new QueryWrapper<DnTableComment>();
            cmtQw.eq("table_meta_id", tableMetaId);
            Long cmtCount = tableCommentMapper.selectCount(cmtQw);
            result.put("commentCount", cmtCount != null ? cmtCount : 0L);
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
            // 并发下可能已被其他线程插入(唯一约束冲突)，重查命中即视为成功；否则上抛原异常
            Long retryId = findTableMetaId(db, table);
            if (retryId != null) {
                log.warn("插入表元数据冲突后重查命中, db={}, table={}, 原因={}", db, table, e.getMessage());
                return retryId;
            }
            log.error("获取或创建表元数据失败, db={}, table={}", db, table, e);
            throw e;
        }
    }

    private String extractJsonArray(String text) {
        if (text == null) return null;
        int start = text.indexOf('[');
        if (start == -1) return null;
        int end = text.lastIndexOf(']');
        if (end == -1 || end <= start) return null;
        return text.substring(start, end + 1);
    }
}
