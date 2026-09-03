package com.levango7.dataenginebdp.streambatch.batchpipeline;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * batch-pipeline 批处理服务（data-quality 实体）接入配置。
 *
 * <p>batch-pipeline 为独立部署的 FastAPI 服务（platform/batch-pipeline，
 * 五阶段流水线 ingest→validate→clean→compute→output 的提交/查询壳），
 * 由 {@link BatchPipelineClient} 经 REST 提交批次并轮询状态。
 *
 * <p>鉴权约定（与平台 jwt_auth 对齐）：调度器按请求签发 HS256 JWT，
 * {@code tenantId} 写入 token claim（role=admin），服务端据此分区
 * {@code run/<tenant>/<batch>/}。
 */
@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "shuqing.stream-batch.batch-pipeline")
public class BatchPipelineConfig {

    /** batch-pipeline API 基地址（含 API 前缀）。 */
    private String baseUrl = "http://localhost:8080/api/v1";

    /** HS256 JWT 密钥（与服务端 JWT_SECRET 一致；realSubmitEnabled=true 时必填）。 */
    private String jwtSecret = "";

    /** 提交批次使用的租户 id（写入 JWT tenantId claim；节点 extraConfig.tenant 可覆盖）。 */
    private String tenantId = "default";

    /**
     * 真实提交开关：true 经 REST 真实提交并轮询；false 本地模拟
     * （本地无 batch-pipeline 服务时默认，与 Spark realSubmitEnabled 约定一致）。
     */
    private boolean realSubmitEnabled = false;

    /** HTTP 连接超时（毫秒）。 */
    private int connectTimeoutMs = 5000;

    /** HTTP 读超时（毫秒）。 */
    private int readTimeoutMs = 30000;

    /** 批次状态轮询间隔（毫秒）。 */
    private long pollIntervalMs = 2000;

    /** 批次状态轮询超时（秒），超时判 FAILED。 */
    private long pollTimeoutSeconds = 3600;
}
