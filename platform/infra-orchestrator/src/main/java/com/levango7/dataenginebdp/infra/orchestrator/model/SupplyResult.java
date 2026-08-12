package com.levango7.dataenginebdp.infra.orchestrator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 供应结果 - 编排层对一次供应流程的完整回报。
 *
 * <p>封装 {@link SupplyOrchestrator#createCluster} 的输出，包含最终集群信息、
 * 供应流程耗时、各阶段事件、以及下游 Provider 的原始响应（用于调试）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplyResult {

    /**
     * 供应状态。
     */
    public enum Phase {
        /** 供应成功，集群已就绪。 */
        SUCCEEDED,
        /** 供应失败，集群不可用。 */
        FAILED,
        /** 供应进行中（异步轮询场景）。 */
        IN_PROGRESS,
        /** 集群已销毁。 */
        DESTROYED
    }

    /** 供应阶段。 */
    private Phase phase;

    /** 供应的集群信息（phase=SUCCEEDED/IN_PROGRESS 时填充）。 */
    private ClusterInfo clusterInfo;

    /** 供应流程开始时间。 */
    private Instant startedAt;

    /** 供应流程结束时间。 */
    private Instant finishedAt;

    /** 供应总耗时。 */
    private Duration duration;

    /** 供应流程事件列表（如 "provider-selected", "cluster-creating", "cluster-active"）。 */
    private List<String> events;

    /** 下游 Provider 名称。 */
    private String providerName;

    /** 下游 Provider 基础 URL。 */
    private String providerBaseUrl;

    /** 错误信息（phase=FAILED 时填充）。 */
    private String errorMessage;

    /** 下游 Provider 原始响应（调试用）。 */
    private Map<String, Object> rawProviderResponse;

    /**
     * 构造成功结果。
     *
     * @param clusterInfo 集群信息
     * @param startedAt   开始时间
     * @param events      事件列表
     * @param providerName Provider 名称
     * @param providerBaseUrl Provider URL
     * @return 成功供应结果
     */
    public static SupplyResult success(ClusterInfo clusterInfo, Instant startedAt,
                                       List<String> events, String providerName, String providerBaseUrl) {
        Instant finishedAt = Instant.now();
        return SupplyResult.builder()
                .phase(Phase.SUCCEEDED)
                .clusterInfo(clusterInfo)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .duration(Duration.between(startedAt, finishedAt))
                .events(events)
                .providerName(providerName)
                .providerBaseUrl(providerBaseUrl)
                .build();
    }

    /**
     * 构造失败结果。
     *
     * @param environment 环境
     * @param startedAt   开始时间
     * @param events      事件列表
     * @param providerName Provider 名称
     * @param providerBaseUrl Provider URL
     * @param errorMessage 错误信息
     * @return 失败供应结果
     */
    public static SupplyResult failure(EnvironmentType environment, Instant startedAt,
                                       List<String> events, String providerName,
                                       String providerBaseUrl, String errorMessage) {
        Instant finishedAt = Instant.now();
        return SupplyResult.builder()
                .phase(Phase.FAILED)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .duration(Duration.between(startedAt, finishedAt))
                .events(events)
                .providerName(providerName)
                .providerBaseUrl(providerBaseUrl)
                .errorMessage(errorMessage)
                .build();
    }
}