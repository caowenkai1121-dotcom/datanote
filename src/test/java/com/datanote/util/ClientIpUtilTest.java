package com.datanote.util;

import com.datanote.common.util.ClientIpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpUtilTest {

    @Test
    void untrustedRemoteIgnoresForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertEquals("8.8.8.8", ClientIpUtil.resolve(request));
    }

    @Test
    void trustedProxyUsesFirstForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.2");

        assertEquals("1.2.3.4", ClientIpUtil.resolve(request));
    }
}
