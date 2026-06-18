package com.datanote.domain.metadata;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.metadata.mapper.DnTableCommentMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnTableComment;
import com.datanote.platform.iam.DataAclService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 评论删除·行级归属单测 —— 仅作者(或 admin)可删, 防 IDOR 按自增ID枚举删他人评论。
 * 单测无 Security 上下文, CurrentUserUtil.currentUser() 恒为 "anonymous"。
 */
@ExtendWith(MockitoExtension.class)
class DataMapCommentTest {

    @Mock private DnTableCommentMapper tableCommentMapper;
    @Mock private DnTableMetaMapper tableMetaMapper;
    @Mock private DataAclService dataAclService;

    private DataMapService svc() {
        return new DataMapService(null, tableCommentMapper, null, null, tableMetaMapper, null, null, dataAclService,
                org.mockito.Mockito.mock(com.datanote.platform.ai.vector.SemanticSearchService.class));
    }

    private DnTableComment comment(long id, String by) {
        DnTableComment c = new DnTableComment();
        c.setId(id);
        c.setCreatedBy(by);
        return c;
    }

    @Test
    void deleteComment_notOwner_throwsNoDelete() {
        when(tableCommentMapper.selectById(5L)).thenReturn(comment(5L, "alice"));
        assertThrows(BusinessException.class, () -> svc().deleteComment(5L));
        verify(tableCommentMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void deleteComment_owner_deletes() {
        when(tableCommentMapper.selectById(5L)).thenReturn(comment(5L, "anonymous"));
        svc().deleteComment(5L);
        verify(tableCommentMapper).deleteById((java.io.Serializable) Long.valueOf(5L));
    }

    @Test
    void deleteComment_missing_noop() {
        when(tableCommentMapper.selectById(5L)).thenReturn(null);
        svc().deleteComment(5L);
        verify(tableCommentMapper, never()).deleteById(any(java.io.Serializable.class));
    }
}
