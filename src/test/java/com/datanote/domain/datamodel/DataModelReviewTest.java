package com.datanote.domain.datamodel;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datamodel.mapper.DnModelChangeMapper;
import com.datanote.domain.datamodel.model.DnModelChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型变更审批·驳回须填原因单测 —— 与前端必填一致, 服务端兜底防空驳回。
 */
@ExtendWith(MockitoExtension.class)
class DataModelReviewTest {

    @Mock private DnModelChangeMapper changeMapper;

    private DataModelService svc() {
        return new DataModelService(null, null, null, null, changeMapper,
                null, null, null, null, null, null, null);
    }

    @Test
    void review_rejectWithoutReason_throwsAndDoesNotUpdate() {
        DnModelChange c = new DnModelChange();
        c.setId(5L);
        c.setStatus("pending");
        c.setRequestedBy("alice");   // 审批人(anonymous)非本人 → 越过禁自批, 命中驳回原因校验
        when(changeMapper.selectById(5L)).thenReturn(c);

        assertThrows(BusinessException.class, () -> svc().review(5L, "rejected", "   "));
        verify(changeMapper, never()).update(any(), any());
    }
}
