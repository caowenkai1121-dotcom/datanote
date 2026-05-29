package com.datanote.sync.util;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class FilterExpressionBuilderTest {
    @Test public void blankGivesEmpty() {
        assertEquals("", FilterExpressionBuilder.build(null));
        assertEquals("", FilterExpressionBuilder.build(""));
    }
    @Test public void singleStringEq() {
        assertEquals("(`status` = 'active')",
            FilterExpressionBuilder.build("{\"conditions\":[{\"column\":\"status\",\"op\":\"=\",\"value\":\"active\"}]}"));
    }
    @Test public void numericGt() {
        assertEquals("(`age` > 18)",
            FilterExpressionBuilder.build("{\"conditions\":[{\"column\":\"age\",\"op\":\">\",\"value\":18}]}"));
    }
    @Test public void andCombine() {
        assertEquals("(`status` = 'active' AND `age` >= 18)",
            FilterExpressionBuilder.build("{\"logic\":\"AND\",\"conditions\":[{\"column\":\"status\",\"op\":\"=\",\"value\":\"active\"},{\"column\":\"age\",\"op\":\">=\",\"value\":18}]}"));
    }
    @Test public void illegalColumnRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            FilterExpressionBuilder.build("{\"conditions\":[{\"column\":\"a; DROP\",\"op\":\"=\",\"value\":\"1\"}]}"));
    }
    @Test public void illegalOpRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            FilterExpressionBuilder.build("{\"conditions\":[{\"column\":\"a\",\"op\":\"OR 1=1\",\"value\":\"1\"}]}"));
    }
    @Test public void stringValueEscaped() {
        assertEquals("(`name` = 'O''Brien')",
            FilterExpressionBuilder.build("{\"conditions\":[{\"column\":\"name\",\"op\":\"=\",\"value\":\"O'Brien\"}]}"));
    }
}
