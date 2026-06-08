package com.datanote.platform.ai.agent.engine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 自学习异步执行器: 有界队列 + 满则丢弃, 防 learn 蒸馏(每次一发 LLM 调用)在高频会话下
 * 落到默认无界队列(applicationTaskExecutor, queueCapacity=MAX_VALUE)堆积致内存增长/延迟膨胀。
 * 经验沉淀属"尽力而为", 丢弃个别不影响主系统。
 */
@Configuration
public class AiAsyncConfig {

    @Bean("aiLearnExecutor")
    public ThreadPoolTaskExecutor aiLearnExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("ai-learn-");
        // 队列满直接丢弃最旧任务, 不阻塞主请求线程, 不抛异常
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }
}
