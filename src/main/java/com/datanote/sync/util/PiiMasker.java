package com.datanote.sync.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * PII 脱敏：掩码/确定性哈希/整列遮蔽。
 * null 值或 null 类型原样返回。
 */
public final class PiiMasker {

    private PiiMasker() {}

    public static Object mask(Object value, String type, String salt) {
        if (value == null || type == null || type.trim().isEmpty()) return value;
        String s = String.valueOf(value);
        switch (type.toUpperCase()) {
            case "PHONE":
                return s.length() < 7 ? "****" : s.substring(0, 3) + "****" + s.substring(s.length() - 4);
            case "EMAIL": {
                int at = s.indexOf('@');
                if (at < 1) return "***";
                return s.charAt(0) + "***" + s.substring(at);
            }
            case "IDCARD":
                return s.length() < 8 ? "***" : s.substring(0, 3) + repeat("*", s.length() - 7) + s.substring(s.length() - 4);
            case "REDACT":
                return "***";
            case "HASH_SHA256":
                return sha256((salt == null ? "" : salt) + s);
            default:
                return value;
        }
    }

    private static String repeat(String c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    private static String sha256(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(in.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
