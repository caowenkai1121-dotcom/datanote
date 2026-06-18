package com.datanote.common.exception;

import com.datanote.common.model.R;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleBusiness_redactsSecretsInMessage() {
        R<?> response = handler.handleBusiness(new BusinessException("连接失败 password=plain-secret"));

        assertFalse(response.getMsg().contains("plain-secret"));
        assertTrue(response.getMsg().contains("***REDACTED***"));
    }

    @Test
    void handleNotFound_redactsSecretsInMessage() {
        R<?> response = handler.handleNotFound(new ResourceNotFoundException("token=abcdef123456"));

        assertFalse(response.getMsg().contains("abcdef123456"));
        assertTrue(response.getMsg().contains("***REDACTED***"));
    }
}
