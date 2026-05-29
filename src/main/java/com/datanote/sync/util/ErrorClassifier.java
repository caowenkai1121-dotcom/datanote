package com.datanote.sync.util;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
/** 区分瞬时错误(可重试)与永久错误。扫异常链。 */
public final class ErrorClassifier {
    private static final Set<Integer> TRANSIENT_CODES = new HashSet<>(Arrays.asList(1205, 1213));
    private ErrorClassifier() {}
    public static boolean isTransient(Throwable t) {
        int guard = 0;
        while (t != null && guard++ < 20) {
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
