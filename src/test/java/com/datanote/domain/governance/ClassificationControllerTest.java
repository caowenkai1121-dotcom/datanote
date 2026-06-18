package com.datanote.domain.governance;

import com.datanote.common.exception.BusinessException;
import com.datanote.common.model.R;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClassificationControllerTest {

    @Test
    void scan_hidesInternalExceptionMessage() throws Exception {
        ClassificationService service = mock(ClassificationService.class);
        when(service.scanTable("ods", "orders"))
                .thenThrow(new SQLException("jdbc password=secret"));
        ClassificationController controller = new ClassificationController(service);

        R<List<Map<String, Object>>> response = controller.scan("ods", "orders");

        assertEquals(R.CODE_FAIL, response.getCode());
        assertEquals("采样识别失败", response.getMsg());
        assertFalse(response.getMsg().contains("secret"));
    }

    @Test
    void scan_rethrowsBusinessException() throws Exception {
        ClassificationService service = mock(ClassificationService.class);
        when(service.scanTable("ods", "orders"))
                .thenThrow(new BusinessException("无权访问该表"));
        ClassificationController controller = new ClassificationController(service);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.scan("ods", "orders"));
        assertEquals("无权访问该表", ex.getMessage());
    }
}
