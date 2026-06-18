package com.datanote.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * WebSocket + STOMP 消息代理配置
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${datanote.cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 订阅前缀: /topic(全局广播) + /queue(配合 /user 的私有用户队列, P0 跨用户隔离)
        config.enableSimpleBroker("/topic", "/queue");
        // 客户端发送前缀：/app/xxx
        config.setApplicationDestinationPrefixes("/app");
        // 私有用户目的地前缀: convertAndSendToUser(user, "/queue/x") → 客户端订阅 "/user/queue/x", 按会话 Principal 隔离
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket 连接端点，支持 SockJS 降级
        StompWebSocketEndpointRegistration endpoint = registry.addEndpoint("/ws");
        List<String> origins = (allowedOrigins == null || allowedOrigins.trim().isEmpty())
                ? java.util.Collections.emptyList()
                : Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
        if (!origins.isEmpty()) {
            endpoint.setAllowedOriginPatterns(origins.toArray(new String[0]));
        }
        endpoint.withSockJS();
    }
}
