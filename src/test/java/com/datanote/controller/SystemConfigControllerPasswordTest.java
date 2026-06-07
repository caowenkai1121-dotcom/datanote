package com.datanote.controller;

import com.datanote.platform.config.SystemConfigController;
import com.datanote.mapper.DnSystemConfigMapper;
import com.datanote.model.DnSystemConfig;
import com.datanote.util.CryptoUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemConfigControllerPasswordTest {

    @Test
    void resolveDorisPasswordUsesSavedPasswordWhenUiSendsBlankOrMask() {
        DnSystemConfigMapper mapper = mock(DnSystemConfigMapper.class);
        when(mapper.selectById("doris.password")).thenReturn(config("doris.password",
                CryptoUtil.encrypt("123456", "DataNote_AES_Key")));

        SystemConfigController controller = new SystemConfigController(mapper, null);
        ReflectionTestUtils.setField(controller, "cryptoKey", "DataNote_AES_Key");
        ReflectionTestUtils.setField(controller, "envDorisPassword", "fallback");

        assertEquals("123456", ReflectionTestUtils.invokeMethod(controller, "resolveDorisPassword", ""));
        assertEquals("123456", ReflectionTestUtils.invokeMethod(controller, "resolveDorisPassword", "******"));
    }

    private DnSystemConfig config(String key, String value) {
        DnSystemConfig config = new DnSystemConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }
}
