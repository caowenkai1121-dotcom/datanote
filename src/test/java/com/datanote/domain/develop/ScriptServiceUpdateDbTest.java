package com.datanote.domain.develop;

import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.develop.model.DnScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * updateDatabaseName 存在性守卫单测 —— 防对不存在脚本静默空更新却谎报成功。
 */
@ExtendWith(MockitoExtension.class)
class ScriptServiceUpdateDbTest {

    @Mock private DnScriptMapper scriptMapper;

    private ScriptService svc() {
        return new ScriptService(null, scriptMapper, null, null, null, null, null);
    }

    @Test
    void updateDatabaseName_nullId_throws() {
        assertThrows(BusinessException.class, () -> svc().updateDatabaseName(null, "db"));
    }

    @Test
    void updateDatabaseName_missingScript_throwsNoUpdate() {
        when(scriptMapper.selectById(9L)).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> svc().updateDatabaseName(9L, "db"));
        verify(scriptMapper, never()).updateById(any());
    }

    @Test
    void updateDatabaseName_exists_updates() {
        when(scriptMapper.selectById(1L)).thenReturn(new DnScript());
        svc().updateDatabaseName(1L, "ods");
        verify(scriptMapper).updateById(any(DnScript.class));
    }
}
