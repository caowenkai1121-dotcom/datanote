package com.datanote.domain.governance;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.governance.mapper.DnDataElementMapper;
import com.datanote.domain.governance.model.DnDataElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 数据元保存·防重复编码单测 —— element_code 是落标稽核类型映射的键, 必须唯一。
 */
@ExtendWith(MockitoExtension.class)
class StandardElementSaveTest {

    @Mock private DnDataElementMapper elementMapper;

    private StandardController ctrl() {
        return new StandardController(elementMapper, null, null, null, null, null);
    }

    private DnDataElement el(String code, String name) {
        DnDataElement e = new DnDataElement();
        e.setElementCode(code);
        e.setNameCn(name);
        return e;
    }

    @Test
    void saveElement_duplicateCode_throwsAndDoesNotInsert() {
        when(elementMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> ctrl().saveElement(el("USER_NAME", "用户名")));
        verify(elementMapper, never()).insert(any());
    }

    @Test
    void saveElement_uniqueCode_inserts() {
        when(elementMapper.selectCount(any())).thenReturn(0L);
        DnDataElement e = el("NEW_CODE", "新元素");
        ctrl().saveElement(e);
        verify(elementMapper).insert(e);
    }

    @Test
    void saveElement_blankName_throwsBeforeDupCheck() {
        assertThrows(BusinessException.class, () -> ctrl().saveElement(el("X", "  ")));
        verify(elementMapper, never()).selectCount(any());
    }
}
