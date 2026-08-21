package com.shuqing.bigdata.federated.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 跨集群查询请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FederatedQueryRequest {

    /** SQL 查询语句。 */
    @NotBlank(message = "sql must not be blank")
    private String sql;

    /** 默认数据库（schema）。 */
    private String database;

    /** 租户 ID（多租户隔离）。 */
    private String tenantId;

    /** 是否允许降级到单集群查询。 */
    @Builder.Default
    private boolean allowDegrade = true;

    /** 是否同步执行（默认 false 异步）。 */
    @Builder.Default
    private boolean sync = false;

    /** 查询超时（秒），覆盖默认配置。 */
    private Integer timeoutSeconds;

    /** 期望归并策略：CONCAT / UNION / JOIN（覆盖默认）。 */
    private String mergeStrategy;

    /** 附加选项。 */
    @Builder.Default
    private Map<String, Object> options = new HashMap<>();
}