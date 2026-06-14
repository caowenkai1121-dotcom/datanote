package com.datanote.platform.ai.agent;

import com.datanote.platform.ai.agent.engine.Validation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Validation 纯函数单测：AI 工具入参的 required/type 粗校验。 */
class ValidationTest {

    private final ObjectMapper om = new ObjectMapper();

    private String validate(String argsJson, String schemaJson) throws Exception {
        JsonNode args = argsJson == null ? null : om.readTree(argsJson);
        return Validation.validate(args, schemaJson, om);
    }

    @Test
    void emptySchema_passes() throws Exception {
        assertNull(validate("{}", null));
        assertNull(validate("{}", ""));
        assertNull(validate("{}", "{}"));
    }

    @Test
    void required_missingOrNull_fails() throws Exception {
        String schema = "{\"id\":{\"required\":true,\"type\":\"number\"}}";
        assertNotNull(validate("{}", schema));
        assertNotNull(validate("{\"id\":null}", schema));
        assertNull(validate("{\"id\":5}", schema));
    }

    @Test
    void numberType_acceptsNumberAndNumericString_rejectsOther() throws Exception {
        String schema = "{\"n\":{\"type\":\"number\"}}";
        assertNull(validate("{\"n\":12}", schema));
        assertNull(validate("{\"n\":-3.5}", schema));
        assertNull(validate("{\"n\":\"123\"}", schema));
        assertNull(validate("{\"n\":\"-12.50\"}", schema));
        assertNotNull(validate("{\"n\":\"123abc\"}", schema));
        assertNotNull(validate("{\"n\":\"abc\"}", schema));
    }

    @Test
    void stringType_acceptsTextOrNumber_rejectsBoolean() throws Exception {
        String schema = "{\"s\":{\"type\":\"string\"}}";
        assertNull(validate("{\"s\":\"hi\"}", schema));
        assertNull(validate("{\"s\":7}", schema));
        assertNotNull(validate("{\"s\":true}", schema));
    }

    @Test
    void booleanType_acceptsBoolOnly() throws Exception {
        String schema = "{\"b\":{\"type\":\"boolean\"}}";
        assertNull(validate("{\"b\":true}", schema));
        assertNotNull(validate("{\"b\":\"true\"}", schema));
        assertNotNull(validate("{\"b\":1}", schema));
    }

    @Test
    void optionalAbsent_passes_andBadSchemaIsLenient() throws Exception {
        // 非必填且缺省 → 通过
        assertNull(validate("{}", "{\"x\":{\"type\":\"number\"}}"));
        // schema 非法 JSON → 不阻断(返回 null)
        assertNull(validate("{\"x\":1}", "not-a-json"));
    }
}
