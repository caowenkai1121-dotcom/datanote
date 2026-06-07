package com.datanote.config;

import com.datanote.mapper.DnSystemConfigMapper;
import com.datanote.platform.config.model.DnSystemConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HiveConfigPasswordTest {

    @Test
    void reloadFromDbAcceptsPlainPasswordWhenCryptoKeyIsConfigured() {
        DnSystemConfigMapper mapper = mock(DnSystemConfigMapper.class);
        when(mapper.selectById("doris.url")).thenReturn(config("doris.url",
                "jdbc:mysql://127.0.0.1:1/ods?useSSL=false"));
        when(mapper.selectById("doris.username")).thenReturn(config("doris.username", "root"));
        when(mapper.selectById("doris.password")).thenReturn(config("doris.password", "123456"));

        HiveConfig hiveConfig = new HiveConfig();
        ReflectionTestUtils.setField(hiveConfig, "systemConfigMapper", mapper);
        ReflectionTestUtils.setField(hiveConfig, "cryptoKey", "DataNote_AES_Key");

        assertDoesNotThrow(hiveConfig::reloadFromDb);
    }

    private DnSystemConfig config(String key, String value) {
        DnSystemConfig config = new DnSystemConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }
}
