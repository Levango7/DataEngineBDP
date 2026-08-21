package com.levango7.dataenginebdp.federated.transaction;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 开启跨集群事务请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BeginTransactionRequest {

    /** 参与集群（集群名 → endpoint URL）。 */
    @NotEmpty(message = "participants must not be empty")
    private Map<String, String> participants;

    /** 参与的 Iceberg 表标识列表（用于创建 snapshot）。 */
    @Builder.Default
    private List<String> tableIds = List.of();

    /** 附加选项。 */
    @Builder.Default
    private Map<String, Object> options = new HashMap<>();
}