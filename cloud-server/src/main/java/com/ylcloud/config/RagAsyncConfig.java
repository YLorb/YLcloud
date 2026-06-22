package com.ylcloud.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * RAG indexing tasks run outside request transactions.
 */
@Configuration
@EnableAsync
public class RagAsyncConfig {

    @Bean("ragTaskExecutor")
    public Executor ragTaskExecutor(RagProperties ragProperties) {
        int concurrency = Math.max(1, ragProperties.getIndex().getConcurrency() == null ? 5 : ragProperties.getIndex().getConcurrency());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(Math.max(100, concurrency * 20));
        executor.setThreadNamePrefix("rag-task-");
        executor.initialize();
        return executor;
    }
}
