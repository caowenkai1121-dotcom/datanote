package com.datanote.domain.develop;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.develop.mapper.DnSqlSnippetMapper;
import com.datanote.domain.develop.model.DnSqlSnippet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SQL 片段库服务单测 —— 校验/同名查重/行级归属删除。
 * 单测无 Security 上下文, CurrentUserUtil.currentUser() 恒为 "anonymous"。
 */
@ExtendWith(MockitoExtension.class)
class SqlSnippetServiceTest {

    @Mock private DnSqlSnippetMapper snippetMapper;

    private SqlSnippetService svc() {
        return new SqlSnippetService(snippetMapper);
    }

    private DnSqlSnippet snip(String name, String content) {
        DnSqlSnippet s = new DnSqlSnippet();
        s.setName(name);
        s.setContent(content);
        return s;
    }

    @Test
    void save_blankName_throws() {
        assertThrows(BusinessException.class, () -> svc().save(snip("  ", "select 1")));
    }

    @Test
    void save_blankContent_throws() {
        assertThrows(BusinessException.class, () -> svc().save(snip("x", "  ")));
    }

    @Test
    void save_duplicateName_throwsNoInsert() {
        when(snippetMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> svc().save(snip("dup", "select 1")));
        verify(snippetMapper, never()).insert(any());
    }

    @Test
    void save_newUnique_insertsWithOwnerAndZeroUse() {
        when(snippetMapper.selectCount(any())).thenReturn(0L);
        DnSqlSnippet s = snip("取昨日分区", "where dt='${bizdate}'");
        svc().save(s);
        assertEquals("anonymous", s.getCreatedBy());
        assertEquals(0, s.getUseCount());
        verify(snippetMapper).insert(s);
    }

    @Test
    void delete_notOwner_throws() {
        DnSqlSnippet s = snip("a", "select 1");
        s.setId(5L);
        s.setCreatedBy("alice");
        when(snippetMapper.selectById(5L)).thenReturn(s);
        assertThrows(BusinessException.class, () -> svc().delete(5L));
        verify(snippetMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void delete_owner_deletes() {
        DnSqlSnippet s = snip("a", "select 1");
        s.setId(5L);
        s.setCreatedBy("anonymous");
        when(snippetMapper.selectById(5L)).thenReturn(s);
        svc().delete(5L);
        verify(snippetMapper).deleteById((java.io.Serializable) Long.valueOf(5L));
    }

    @Test
    void delete_missing_noop() {
        when(snippetMapper.selectById(9L)).thenReturn(null);
        svc().delete(9L);
        verify(snippetMapper, never()).deleteById(any(java.io.Serializable.class));
    }
}
