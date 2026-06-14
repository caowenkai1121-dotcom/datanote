package com.datanote.domain.governance;

import com.datanote.common.exception.BusinessException;
import com.datanote.platform.config.mapper.DnCodeDictMapper;
import com.datanote.domain.governance.mapper.DnWordRootMapper;
import com.datanote.platform.config.model.DnCodeDict;
import com.datanote.domain.governance.model.DnWordRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 词根/码表保存·防重复编码单测 —— word_en / dict_code 是引用键, 必须唯一。
 */
@ExtendWith(MockitoExtension.class)
class StandardCodeUniqueTest {

    @Mock private DnWordRootMapper wordRootMapper;
    @Mock private DnCodeDictMapper codeDictMapper;

    private StandardController ctrl() {
        return new StandardController(null, wordRootMapper, codeDictMapper, null, null, null);
    }

    @Test
    void saveRoot_duplicateWordEn_throwsAndNoInsert() {
        DnWordRoot r = new DnWordRoot();
        r.setWordCn("用户"); r.setWordEn("user");
        when(wordRootMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> ctrl().saveRoot(r));
        verify(wordRootMapper, never()).insert(any());
    }

    @Test
    void saveRoot_unique_inserts() {
        DnWordRoot r = new DnWordRoot();
        r.setWordCn("用户"); r.setWordEn("user");
        when(wordRootMapper.selectCount(any())).thenReturn(0L);
        ctrl().saveRoot(r);
        verify(wordRootMapper).insert(r);
    }

    @Test
    void saveDict_duplicateCode_throwsAndNoInsert() {
        DnCodeDict d = new DnCodeDict();
        d.setDictCode("GENDER"); d.setDictName("性别");
        when(codeDictMapper.selectCount(any())).thenReturn(2L);
        assertThrows(BusinessException.class, () -> ctrl().saveDict(d));
        verify(codeDictMapper, never()).insert(any());
    }

    @Test
    void saveDict_unique_inserts() {
        DnCodeDict d = new DnCodeDict();
        d.setDictCode("GENDER"); d.setDictName("性别");
        when(codeDictMapper.selectCount(any())).thenReturn(0L);
        ctrl().saveDict(d);
        verify(codeDictMapper).insert(d);
    }
}
