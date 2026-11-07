package com.levango7.dataenginebdp.infra.orchestrator.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PVC 创建请求。
 *
 * <p>对应 REST API：{@code POST /api/v1/clusters/{environment}/{clusterId}/storage/pvcs}。</p>
 *
 * <p>字段契约对齐前端 {@code frontend/src/api/infra.ts} 的 {@code createPvc}：
 * InfraStore.vue 创建表单提交 {@code name} / {@code namespace} /
 * {@code storageClassName} / {@code capacity}（容量字符串如 {@code 100Gi}）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PvcCreateRequest {

    /**
     * PVC 名称（K8s RFC1123 名称）。
     */
    @NotBlank
    private String name;

    /**
     * 目标命名空间，缺省由前端传入 "default"。
     */
    @NotBlank
    private String namespace;

    /**
     * StorageClass 名称（空串表示使用集群默认 StorageClass）。
     */
    private String storageClassName;

    /**
     * 容量，K8s Quantity 字符串，如 {@code 100Gi} / {@code 500Mi}。
     */
    @NotBlank
    private String capacity;
}
