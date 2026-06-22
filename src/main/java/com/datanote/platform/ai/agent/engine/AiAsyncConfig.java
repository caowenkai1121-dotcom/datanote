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

    /**
     * 子代理并行执行器(借鉴 hermes 委派并行): delegate_task 批量子任务并发跑。
     * core2/max4 限并发, 调用方 CompletableFuture 提交; 满则调用线程自跑(CallerRuns)保不丢任务。
     */
    @Bean("aiChildExecutor")
    public ThreadPoolTaskExecutor aiChildExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(8);
        ex.setThreadNamePrefix("ai-child-");
        // AbortPolicy(非 CallerRuns): 满则抛, 由 runBatch 显式降级标记; 避免把整段子循环压到父 servlet 线程并绕过超时保护
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }

    /**
     * cron 任务执行器: 占行后异步跑 agent, 使 @Scheduled tick 线程快速返回, 不阻塞全局调度器(CDC/同步/对账等)。
     */
    @Bean("aiCronExecutor")
    public ThreadPoolTaskExecutor aiCronExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(20);
        ex.setThreadNamePrefix("ai-cron-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy()); // 满则丢, 下次 tick 再领(next_run 已推进, 不重复)
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }

    /**
     * 无人值守自主执行器: 后台驱动器持续推进自主会话(每会话占一线程循环跑数小时直到计划完成/预算耗尽)。
     * core2/max2 → 至多 2 个自主会话并发; 满则丢(下次 tick 凭心跳过期重新领取, 不重复)。
     */
    @Bean("aiAutonomousExecutor")
    public ThreadPoolTaskExecutor aiAutonomousExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(8);
        ex.setThreadNamePrefix("ai-auto-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy()); // 满则抛, 调度器回退心跳下次 tick 再领(不丢)
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }

    /**
     * 重型索引执行器(单线程): 列级向量重建等大批量嵌入作业不阻塞 HTTP 请求线程。
     * 单线程串行 + 浅队列(满则丢弃), 同一时刻至多一个重建在跑。
     */
    @Bean("aiIndexExecutor")
    public ThreadPoolTaskExecutor aiIndexExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(1);
        ex.setQueueCapacity(2);
        ex.setThreadNamePrefix("ai-index-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }
}
