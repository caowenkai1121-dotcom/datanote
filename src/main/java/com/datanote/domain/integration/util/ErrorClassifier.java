package com.datanote.domain.integration.util;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
/** 区分瞬时错误(可重试)与永久错误。扫异常链。 */
public final class ErrorClassifier {
    private static final Set<Integer> TRANSIENT_CODES = new HashSet<>(Arrays.asList(1205, 1213));
    /** 异常链遍历深度上限,防御循环/超长 cause 链。 */
    private static final int MAX_CAUSE_DEPTH = 20;
    private ErrorClassifier() {}
    public static boolean isTransient(Throwable t) {
        int guard = 0;
        while (t != null && guard++ < MAX_CAUSE_DEPTH) {
            if (t instanceof SQLException) {
                SQLException se = (SQLException) t;
                String state = se.getSQLState();
                if (state != null && (state.startsWith("08") || "40001".equals(state))) return true;
                if (TRANSIENT_CODES.contains(se.getErrorCode())) return true;
                String m = se.getMessage();
                if (m != null && m.toLowerCase().contains("communications link failure")) return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
