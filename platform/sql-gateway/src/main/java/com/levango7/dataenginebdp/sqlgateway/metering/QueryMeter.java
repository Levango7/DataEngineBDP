package com.levango7.dataenginebdp.sqlgateway.metering;

/**
 * 单条查询计量（sql-gateway 内部表示，待批量上报）。
 */
public record QueryMeter(
        String tenantId,
        String namespace,
        String engine,
        String sqlHash,
        long bytesScanned,
        boolean estimated,
        Long durationMs,
        String clientRequestId) {

    /** 转换为 cost-model 上报请求体（JSON 字段对齐）。 */
    public java.util.Map<String, Object> toPayload() {
        return java.util.Map.of(
                "tenantId", tenantId,
                "namespace", namespace == null ? "" : namespace,
                "engine", engine,
                "sqlHash", sqlHash == null ? "" : sqlHash,
                "bytesScanned", bytesScanned,
                "estimated", estimated,
                "durationMs", durationMs == null ? 0L : durationMs,
                "clientRequestId", clientRequestId);
    }
}