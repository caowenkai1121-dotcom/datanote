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

    /** AI 搜索提示词最多纳入的表数, 防大元数据库撑爆 LLM 上下文。 */
    private static final int AI_SEARCH_MAX_TABLES = 800;

    private final AiAssistService aiAssistService;
    private final DnTableCommentMapper tableCommentMapper;
    private final DnTableFavoriteMapper tableFavoriteMapper;
    private final DnSearchHistoryMapper searchHistoryMapper;
    private final DnTableMetaMapper tableMetaMapper;
    private final com.datanote.domain.metadata.mapper.DnColumnMetaMapper columnMetaMapper;   // 表详情合并离线业务属性
    private final DatasourceExploreService exploreService;
    private final com.datanote.platform.iam.DataAclService dataAclService;   // 数据权限: 过滤受限库表
    private final com.datanote.platform.ai.vector.SemanticSearchService semanticSearchService;   // 向量语义检索(降级关键字)

    // ========== 搜索（基于在线全表摘要） ==========

    private static final int SEARCH_LIMIT = 50;

    /**
     * 表搜索: 向量库可用时按语义相关度排序(中文同义召回, 如"支付"命中交易/订单/付款), 关键字 LIKE 兜底填充;
     * 向量不可用则纯 LIKE(行为与改造前一致)。数据权限过滤受限表。
     */
    public List<Map<String, Object>> searchTables(String keyword) throws SQLException {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new BusinessException("搜索关键词不能为空");
        }
        List<Map<String, Object>> allTables = exploreService.getAllTablesSummary();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (allTables == null || allTables.isEmpty()) {
            return result;
        }
        Set<String> denied = dataAclService.deniedIds("TABLE");   // 受限且当前用户未授权的 db.table
        // 建库.表(小写) → 表摘要 索引(已过滤受限表), 供向量命中回填完整记录
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> t : allTables) {
            if (t == null || denied.contains(tableKey(t))) continue;
            byKey.put(tableKey(t).toLowerCase(), t);
        }
        Set<String> added = new HashSet<String>();
        // 1) 向量语义排序在前(仅向量引擎命中才用, 关键字降级噪音大不掺入排序)
        try {
            Map<String, Object> rag = semanticSearchService.search(keyword.trim(), "table", SEARCH_LIMIT);
            if (rag != null && "vector".equals(rag.get("engine")) && rag.get("results") instanceof List) {
                for (Object o : (List<?>) rag.get("results")) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> hit = (Map<?, ?>) o;
                    Object db = hit.get("db"), name = hit.get("name");
                    if (db == null || name == null) continue;
                    String key = (db + "." + name).toLowerCase();
                    Map<String, Object> t = byKey.get(key);
                    if (t != null && added.add(key)) {
                        result.add(t);
                        if (result.size() >= SEARCH_LIMIT) return result;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("表语义检索失败, 降级关键字: {}", e.getMessage());
        }
        // 2) 关键字 LIKE 兜底填充(向量未命中或不可用; 不丢子串匹配)
        String kw = keyword.trim().toLowerCase();
        for (Map.Entry<String, Map<String, Object>> en : byKey.entrySet()) {
            if (result.size() >= SEARCH_LIMIT) break;
            if (added.contains(en.getKey())) continue;
            Map<String, Object> t = en.getValue();
            String tableName = t.get("TABLE_NAME") != null ? String.valueOf(t.get("TABLE_NAME")).toLowerCase() : "";
            String dbName = t.get("TABLE_SCHEMA") != null ? String.valueOf(t.get("TABLE_SCHEMA")).toLowerCase() : "";
            String comment = t.get("TABLE_COMMENT") != null ? String.valueOf(t.get("TABLE_COMMENT")).toLowerCase() : "";
            if (tableName.contains(kw) || dbName.contains(kw) || comment.contains(kw)) {
                result.add(t);
                added.add(en.getKey());
            }
        }
        return result;
    }

    /** 表唯一键 schema.name(与 dn_data_grant.resource_id 对齐, 大小写按源)。 */
    private static String tableKey(Map<String, Object> t) {
        Object db = t.get("TABLE_SCHEMA"), tb = t.get("TABLE_NAME");
        return (db == null ? "" : String.valueOf(db)) + "." + (tb == null ? "" : String.valueOf(tb));
    }

    public Map<String, Object> aiSearch(String query) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            throw new BusinessException("AI 搜索的查询内容不能为空");
        }
        List<Map<String, Object>> allTables = exploreService.getAllTablesSummary();
        java.util.Set<String> deniedAi = dataAclService.deniedIds("TABLE");
        StringBuilder tableList = new StringBuilder();
        int included = 0, total = allTables == null ? 0 : allTables.size();
        if (allTables != null) {
            for (Map<String, Object> t : allTables) {
                if (t == null) continue;
                if (deniedAi.contains(tableKey(t))) continue;   // 数据权限: 受限表不喂给 LLM/不返回
                // 限制送入提示词的表数, 防大元数据库撑爆 LLM 上下文
                if (included >= AI_SEARCH_MAX_TABLES) break;
                tableList.append(t.get("TABLE_SCHEMA")).append(".").append(t.get("TABLE_NAME"));
                Object comment = t.get("TABLE_COMMENT");
                if (comment != null && !comment.toString().isEmpty()) {
                    tableList.append(" (").append(comment).append(")");
                }
                tableList.append("\n");
                included++;
            }
        }
        if (total > included) {
            tableList.append("...(共 ").append(total).append(" 张表, 仅列出前 ").append(included)
                     .append(" 张; 如未命中请用更具体的关键词)\n");
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
        qw.eq("created_by", com.datanote.platform.iam.CurrentUserUtil.currentUser());   // 按登录用户隔离, 不共享他人历史
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
        String me = com.datanote.platform.iam.CurrentUserUtil.currentUser();
        QueryWrapper<DnSearchHistory> qw = new QueryWrapper<DnSearchHistory>();
        qw.eq("database_name", history.getDatabaseName()).eq("table_name", history.getTableName())
          .eq("created_by", me);   // 按用户去重, 否则会更新到他人同表记录
        DnSearchHistory existing = searchHistoryMapper.selectOne(qw);
        if (existing != null) {
            existing.setSearchedAt(LocalDateTime.now());
            searchHistoryMapper.updateById(existing);
        } else {
            history.setSearchedAt(LocalDateTime.now());
            history.setCreatedBy(me);
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
        qw.eq("created_by", com.datanote.platform.iam.CurrentUserUtil.currentUser());   // 仅看自己的收藏
        qw.orderByDesc("created_at").last("LIMIT 30");
        return tableFavoriteMapper.selectList(qw);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean toggleFavorite(String db, String table) {
        if (db == null || db.trim().isEmpty() || table == null || table.trim().isEmpty()) {
            throw new BusinessException("收藏操作的库名和表名不能为空");
        }
        QueryWrapper<DnTableFavorite> qw = new QueryWrapper<DnTableFavorite>();
        qw.eq("database_name", db).eq("table_name", table)
          .eq("created_by", com.datanote.platform.iam.CurrentUserUtil.currentUser());   // 仅切换自己的收藏, 不误删他人
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
        qw.eq("database_name", db).eq("table_name", table)
          .eq("created_by", com.datanote.platform.iam.CurrentUserUtil.currentUser());   // 收藏态按用户判定
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
        qw.eq("table_meta_id", tableMetaId).orderByDesc("created_at").last("LIMIT 50");   // 取最近50条, 防热门表评论无界累积
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
        // 行级归属: 仅评论作者(或 admin)可删, 防任意登录用户按自增ID枚举删他人评论(IDOR)
        DnTableComment comment = tableCommentMapper.selectById(id);
        if (comment == null) return;
        String me = com.datanote.platform.iam.CurrentUserUtil.currentUser();
        if (!"admin".equals(me) && me != null && !me.equals(comment.getCreatedBy())) {
            throw new BusinessException("只能删除自己发表的评论");
        }
        tableCommentMapper.deleteById(id);
    }

    // ========== 表详情（在线信息 + 离线收藏/评论） ==========

    public Map<String, Object> getTableDetail(String db, String table) throws Exception {
        if (db == null || db.trim().isEmpty() || table == null || table.trim().isEmpty()) {
            throw new BusinessException("获取表详情的库名和表名不能为空");
        }
        // 数据权限: 受限表非授权用户不可看详情
        if (!dataAclService.canAccess("TABLE", db.trim() + "." + table.trim())) {
            throw new BusinessException("无权访问该表(数据权限受限), 请联系管理员授权");
        }
        Map<String, Object> result = new HashMap<String, Object>();

        Map<String, Object> info = exploreService.getDorisTableInfo(db, table);

        result.put("tableInfo", info);
        result.put("columns", exploreService.getHiveColumns(db, table));
        result.put("favorited", isFavorited(db, table));
        DnTableMeta meta = tableMetaMapper.selectOne(
                new QueryWrapper<DnTableMeta>().eq("database_name", db).eq("table_name", table).last("LIMIT 1"));
        Long tableMetaId = meta != null ? meta.getId() : null;
        if (tableMetaId != null) {
            // 表级业务元数据(负责人/标签/重要性): 供数据地图表详情展示与编辑
            Map<String, Object> tm = new HashMap<String, Object>();
            tm.put("id", meta.getId());
            tm.put("owner", meta.getOwner());
            tm.put("tags", meta.getTags());
            tm.put("importance", meta.getImportance());
            result.put("tableMeta", tm);
            QueryWrapper<DnTableComment> cmtQw = new QueryWrapper<DnTableComment>();
            cmtQw.eq("table_meta_id", tableMetaId);
            Long cmtCount = tableCommentMapper.selectCount(cmtQw);
            result.put("commentCount", cmtCount != null ? cmtCount : 0L);
            // 合并离线业务属性(业务名/描述/安全级别/敏感类型): 实时列只有技术信息, 前端按列名叠加展示
            Map<String, Object> columnMeta = new HashMap<String, Object>();
            List<com.datanote.domain.metadata.model.DnColumnMeta> _cols = columnMetaMapper.selectList(
                    new QueryWrapper<com.datanote.domain.metadata.model.DnColumnMeta>().eq("table_meta_id", tableMetaId));
            if (_cols != null) for (com.datanote.domain.metadata.model.DnColumnMeta c : _cols) { // selectList 理论可返回 null
                if (c.getColumnName() == null) continue;
                Map<String, Object> m = new HashMap<String, Object>();
                m.put("businessName", c.getBusinessName());
                m.put("businessDesc", c.getBusinessDesc());
                m.put("securityLevel", c.getSecurityLevel());
                m.put("sensitiveType", c.getSensitiveType());
                columnMeta.put(c.getColumnName(), m);
            }
            result.put("columnMeta", columnMeta);
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
