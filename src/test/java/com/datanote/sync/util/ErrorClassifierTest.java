package com.datanote.domain.integration.util;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
public class ErrorClassifierTest {
    @Test void connectionStateTransient() { assertTrue(ErrorClassifier.isTransient(new SQLException("x","08S01"))); }
    @Test void deadlockTransient() { assertTrue(ErrorClassifier.isTransient(new SQLException("deadlock","40001",1213))); }
    @Test void lockWaitTransient() { assertTrue(ErrorClassifier.isTransient(new SQLException("lock","HY000",1205))); }
    @Test void syntaxErrorPermanent() { assertFalse(ErrorClassifier.isTransient(new SQLException("bad sql","42000",1064))); }
    @Test void nestedCauseScanned() { assertTrue(ErrorClassifier.isTransient(new RuntimeException(new SQLException("x","08S01")))); }
    @Test void nullFalse() { assertFalse(ErrorClassifier.isTransient(null)); }
}
