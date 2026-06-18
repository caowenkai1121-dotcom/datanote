package com.datanote.common.util;

import java.util.regex.Pattern;

public final class SecretRedactor {

    private static final Pattern SECRET_KV = Pattern.compile(
            "(?i)(password|passwd|pwd|token|api[_-]?key|secret|access[_-]?key|private[_-]?key|credential|2fa)" +
                    "[\"']?\\s*[=:]\\s*[\"']?([^\\s\"',;}，]{1,})");
    private static final Pattern SECRET_URI = Pattern.compile("(://[^:/?#@\\s]+:)([^@/\\s]+)(@)");
    private static final Pattern SECRET_BEARER = Pattern.compile("(?i)(bearer\\s+)([A-Za-z0-9._\\-]{8,})");
    private static final Pattern SECRET_PEM = Pattern.compile("(?is)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");
    private static final Pattern SECRET_PREFIX = Pattern.compile("\\b(sk-[A-Za-z0-9_\\-]{8,}|AKIA[0-9A-Z]{12,}|ghp_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{8,})\\b");

    private SecretRedactor() {
    }

    public static String redact(String text) {
        if (text == null) return null;
        String t = text;
        t = SECRET_PEM.matcher(t).replaceAll("-----REDACTED PRIVATE KEY-----");
        t = SECRET_KV.matcher(t).replaceAll("$1=***REDACTED***");
        t = SECRET_URI.matcher(t).replaceAll("$1***REDACTED***$3");
        t = SECRET_BEARER.matcher(t).replaceAll("$1***REDACTED***");
        t = SECRET_PREFIX.matcher(t).replaceAll("***REDACTED***");
        return t;
    }
}
