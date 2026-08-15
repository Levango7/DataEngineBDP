package com.levango7.dataenginebdp.ruleengine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行配置（任务 F）。
 *
 * <p>规则批量执行线程池：core=4 / max=8 / queue=100。
 * 拒绝策略 CallerRuns（队列满时由调用线程执行，保证不丢任务）。</p>
 */
@Configuration
@EnableAsync
public class RuleAsyncConfig {

    @Bean(name = "ruleExecutorPool")
    public Executor ruleExecutorPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("rule-executor-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
