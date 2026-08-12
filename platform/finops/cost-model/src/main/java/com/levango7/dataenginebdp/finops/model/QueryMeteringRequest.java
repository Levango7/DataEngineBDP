package com.levango7.dataenginebdp.finops.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询计量上报请求（sql-gateway → cost-model）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryMeteringRequest {

    /** 租户 ID。 */
    @NotBlank
    private String tenantId;

    /** 命名空间（可空）。 */
    private String namespace;

    /** 执行引擎（trino / doris）。 */
    @NotBlank
    private String engine;

    /** SQL 指纹（可选）。 */
    private String sqlHash;

    /** 扫描字节数。 */
    @NotNull
    @PositiveOrZero
    private Long bytesScanned;

    /** 是否为估算值。 */
    private boolean estimated;

    /** 查询耗时（毫秒，可空）。 */
    private Long durationMs;

    /** 客户端请求 ID（幂等键）。 */
    @NotBlank
    private String clientRequestId;
}