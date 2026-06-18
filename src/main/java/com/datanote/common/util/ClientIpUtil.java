package com.datanote.common.util;

import javax.servlet.http.HttpServletRequest;

public final class ClientIpUtil {

    private ClientIpUtil() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String remote = request.getRemoteAddr();
        if (isTrustedProxy(remote)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.trim().isEmpty()) {
                int comma = xff.indexOf(',');
                String first = comma > 0 ? xff.substring(0, comma) : xff;
                if (!first.trim().isEmpty()) {
                    return first.trim();
                }
            }
        }
        return remote == null ? "" : remote;
    }

    private static boolean isTrustedProxy(String remote) {
        if (remote == null || remote.trim().isEmpty()) {
            return false;
        }
        String ip = remote.trim();
        if ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return true;
        }
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }
}
