package com.datanote.platform.ai.dto;

import lombok.Data;

/**
 * AI 对话请求
 */
@Data
public class AiChatRequest {
    private String message;
    private String context;
}
