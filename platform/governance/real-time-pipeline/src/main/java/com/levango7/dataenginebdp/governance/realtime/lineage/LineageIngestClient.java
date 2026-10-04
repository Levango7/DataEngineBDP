package com.levango7.dataenginebdp.governance.realtime.lineage;

import com.levango7.dataenginebdp.governance.realtime.model.FieldLineage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.levango7.dataenginebdp.common.security.ServiceTokenMinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 血缘写入客户端——pipeline 侧唯一的血缘外发通路（裁决 Q2：统一写入者）。
 *
 * <p>背景：pipeline 原先自持 Nebula 写入（{@code NebulaLineageGraphClient.writeLineage}），
 * 与 lineage-analyzer 的 {@code LineageGraphWriter} 形成**两个写入者**，导致
 * 血缘页看不到 pipeline 产生的血缘（docs/治理闭环修复草案.md 断点 1）。
 * 现改为一律发给 lineage-analyzer 的 OpenLineage ingest（{@code POST /api/v1/lineage/events}），
 * 由它做唯一写入；本类不含任何图写入逻辑。
 *
 * <p>载荷形状：标准 OpenLineage RunEvent（该端点按 inputs × outputs 映射为表级血缘边）。
 * 注意**粒度**：OpenLineage 通道是表级；pipeline 的字段级映射（fieldMappings）不经此通道外发，
 * 仍保留在本地解析结果中（B1 裁决：查询面统一到 analyzer，字段级不再对外提供）。
 *
 * <p>失败策略：外发失败只记警告并返回 false，**不阻塞**采集/解析主流程
 * （草案阶段 2 的约定）；调用方可据此计数并暴露指标。
 */
@Component
public class LineageIngestClient {

    private static final Logger log = LoggerFactory.getLogger(LineageIngestClient.class);

    /** OpenLineage 事件命名空间前缀：区分 pipeline 生产的血缘与其它生产端。 */
    private static final String JOB_NAMESPACE = "dataenginebdp.realtime-pipeline";

    /** 服务间凭据密钥（裁决 A）：与用户令牌同源的 JWT_SECRET。 */
    @Value("${app.security.jwt.secret:}")
    private String jwtSecret;

    @Value("${governance.service.issuer:dataenginebdp-pipeline}")
    private String issuer;

    private final RestClient restClient;
    private final String ingestUrl;

    public LineageIngestClient(
            @Value("${governance.lineage-analyzer.url:http://lineage-analyzer:8086}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.ingestUrl = "/api/v1/lineage/events";
    }

    /**
     * 把一次 SQL 解析得到的血缘以 OpenLineage RunEvent 外发给 lineage-analyzer。
     *
     * @param lineage 解析结果（含源表/目标表/租户）
     * @param jobId   作业标识（作为 runId 与 job 名）
     * @return true=已成功投递；false=失败已降级（仅记日志，不抛）
     */
    public boolean emitRunEvent(FieldLineage lineage, String jobId) {
        if (lineage == null || lineage.getSourceTable() == null || lineage.getTargetTable() == null) {
            log.warn("跳过血缘外发：lineage/jobId 不完整 (jobId={})", jobId);
            return false;
        }
        try {
            // 服务端只认 JWT 声明租户（探针实测：仅 X-Tenant-Id 会 403），故随调用签发短 TTL 服务令牌
            String bearer = ServiceTokenMinter.mint(
                    jwtSecret, issuer, lineage.getTenantId(), "real-time-pipeline");
            restClient.post()
                    .uri(ingestUrl)
                    .header("Authorization", "Bearer " + bearer)
                    .header("X-Tenant-Id", lineage.getTenantId() == null ? "" : lineage.getTenantId())
                    .body(buildRunEvent(lineage, jobId))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("血缘已外发 lineage-analyzer: jobId={}, {} -> {}",
                    jobId, lineage.getSourceTable(), lineage.getTargetTable());
            return true;
        } catch (Exception e) {
            // 不阻塞主流程：血缘链路断开不应导致采集/解析失败
            log.warn("血缘外发失败（已降级，不影响主流程）: jobId={}, reason={}", jobId, e.getMessage());
            return false;
        }
    }

    private Map<String, Object> buildRunEvent(FieldLineage lineage, String jobId) {
        String namespace = "dataenginebdp";
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", "COMPLETE");
        event.put("eventTime", Instant.now().toString());
        event.put("producer", "real-time-pipeline");
        event.put("run", Map.of("runId", jobId == null ? "" : jobId));
        event.put("job", Map.of("namespace", JOB_NAMESPACE, "name", jobId == null ? "" : jobId));
        event.put("inputs", java.util.List.of(Map.of("namespace", namespace, "name", lineage.getSourceTable())));
        event.put("outputs", java.util.List.of(Map.of("namespace", namespace, "name", lineage.getTargetTable())));
        return event;
    }
}
