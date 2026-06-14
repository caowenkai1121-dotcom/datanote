package com.datanote.platform.ai.agent.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;

/** Jackson 手写 required/type 粗校验（零依赖，不引 json-schema-validator）。 */
public final class Validation {

    private Validation() {}

    /** 据 schema 粗校验 args；通过返回 null，否则返回错误信息。schema 解析失败不阻断（返回 null）。 */
    public static String validate(JsonNode args, String schemaJson, ObjectMapper om) {
        try {
            if (schemaJson == null || schemaJson.trim().isEmpty() || schemaJson.trim().equals("{}")) {
                return null;
            }
            JsonNode schema = om.readTree(schemaJson);
            Iterator<String> fields = schema.fieldNames();
            while (fields.hasNext()) {
                String f = fields.next();
                JsonNode spec = schema.get(f);
                boolean required = spec.path("required").asBoolean(false);
                JsonNode v = (args == null) ? null : args.get(f);
                if (required && (v == null || v.isNull())) {
                    return "缺少必填参数: " + f;
                }
                if (v != null && !v.isNull()) {
                    String type = spec.path("type").asText("");
                    if ("number".equals(type)
                            && !v.isNumber()
                            && !(v.isTextual() && v.asText().matches("^-?\\d+(\\.\\d+)?$"))) {
                        return "参数 " + f + " 应为数字";
                    }
                    if ("string".equals(type) && !v.isTextual() && !v.isNumber()) {
                        return "参数 " + f + " 应为字符串";
                    }
                    if ("boolean".equals(type) && !v.isBoolean()) {
                        return "参数 " + f + " 应为布尔";
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
