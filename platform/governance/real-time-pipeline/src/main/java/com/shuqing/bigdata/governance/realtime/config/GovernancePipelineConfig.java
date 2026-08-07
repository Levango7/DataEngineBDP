package com.shuqing.bigdata.governance.realtime.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 实时治理管道配置。
 *
 * <p>集中管理三类资源端点与异步线程池：
 * <ul>
 *   <li>Iceberg REST Catalog 端点（监听 commit 事件）</li>
 *   <li>Flink SQL Gateway 端点（提交 CDC 血缘解析作业）</li>
 *   <li>NebulaGraph 端点（血缘图存储）</li>
 *   <li>异步治理线程池（事件触发元数据采集，避免阻塞 Catalog commit）</li>
 * </ul>
 *
 * <p>所有端点可通过 {@code application.yml} 或环境变量覆盖，便于 Docker/K8s 部署。
 */
@Configuration
public class GovernancePipelineConfig {

    @Value("${governance.iceberg.rest-catalog-url:http://localhost:8181}")
    private String icebergRestCatalogUrl;

    @Value("${governance.flink.sql-gateway-url:http://localhost:8083}")
    private String flinkSqlGatewayUrl;

    @Value("${governance.nebula.graphd-host:localhost}")
    private String nebulaGraphdHost;

    @Value("${governance.nebula.graphd-port:9669}")
    private int nebulaGraphdPort;

    @Value("${governance.nebula.space:lineage}")
    private String nebulaSpace;

    @Value("${governance.pipeline.metadata-collect-timeout-ms:5000}")
    private long metadataCollectTimeoutMs;

    @Value("${governance.pipeline.alert-latency-target-ms:5000}")
    private long alertLatencyTargetMs;

    @Value("${governance.pipeline闭环-p95-target-ms:10000}")
    private long pipelineP95TargetMs;

    /**
     * 异步治理事件线程池。
     *
     * <p>用于 {@code @Async} 标注的元数据采集、血缘更新、质量评估任务，
     * 避免阻塞 Iceberg REST Catalog commit 主线程。核心线程数 8，
     * 队列容量 256，拒绝策略由调用方自行降级处理。
     *
     * @return 配置好的异步执行器
     */
    @Bean(name = "governanceAsyncExecutor")
    public Executor governanceAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("gov-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    public String getIcebergRestCatalogUrl() {
        return icebergRestCatalogUrl;
    }

    public String getFlinkSqlGatewayUrl() {
        return flinkSqlGatewayUrl;
    }

    public String getNebulaGraphdHost() {
        return nebulaGraphdHost;
    }

    public int getNebulaGraphdPort() {
        return nebulaGraphdPort;
    }

    public String getNebulaSpace() {
        return nebulaSpace;
    }

    public long getMetadataCollectTimeoutMs() {
        return metadataCollectTimeoutMs;
    }

    public long getAlertLatencyTargetMs() {
        return alertLatencyTargetMs;
    }

    public long getPipelineP95TargetMs() {
        return pipelineP95TargetMs;
    }
}