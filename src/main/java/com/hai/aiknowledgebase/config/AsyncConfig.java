package com.hai.aiknowledgebase.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    /**
     * 流式问答专用线程池
     * - 核心线程 3：应对日常并发问答
     * - 最大线程 5：应对突发峰值
     * - 队列 50：排队等待，超过则拒绝（快速失败优于无限等待）
     * - 拒绝策略：CallerRunsPolicy（降级到调用线程执行，保证不丢任务）
     */
    @Bean(name = "chatTaskExecutor")
    public ThreadPoolTaskExecutor chatTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("Chat-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 查询改写专用线程池
     * - 核心线程 2：改写耗时短（秒级），不需要太多线程
     * - 最大线程 3：应对少量并发
     * - 队列 30
     */
    @Bean(name = "rewriteTaskExecutor")
    public ThreadPoolTaskExecutor rewriteTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(30);
        executor.setThreadNamePrefix("Rewrite-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    /**
     * 文档向量化专用线程池
     * - 核心线程 1：向量化是重 IO/CPU 任务，串行执行避免资源争抢
     * - 最大线程 2：允许两个文档并行处理
     * - 队列 20：文档上传不会太频繁
     */
    @Bean(name = "vectorizationTaskExecutor")
    public ThreadPoolTaskExecutor vectorizationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("Vector-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 文档解析专用线程池
     * - 核心线程 1：解析是重 IO 任务（Python 子进程），串行执行避免资源争抢
     * - 最大线程 2：允许两个文档并行解析
     * - 队列 10
     */
    @Bean(name = "parseTaskExecutor")
    public ThreadPoolTaskExecutor parseTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("Parse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}