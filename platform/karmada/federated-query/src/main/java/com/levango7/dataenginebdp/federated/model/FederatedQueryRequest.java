package com.levango7.dataenginebdp.federated.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 跨集群查询请求。
 *
 * <p>校验约束：
 * <ul>
 *   <li>{@code sql} 非空，长度 ≤ 8192（防 OOM）</li>
 *   <li>{@code database} 长度 ≤ 128，仅允许字母数字下划线连字符点</li>
 *   <li>{@code timeoutSeconds} 1~600（10 分钟上限）</li>
 *   <li>{@code mergeStrategy} 仅允许 CONCAT / UNION / JOIN</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FederatedQueryRequest {

    /** SQL 查询语句。 */
    @NotBlank(message = "sql must not be blank")
    @Size(max = 8192, message = "sql 长度不能超过 8192")
    private String sql;

    /** 默认数据库（schema）。 */
    @Size(max = 128, message = "database 长度不能超过 128")
    @Pattern(regexp = "^[A-Za-z0-9_.-]*$", message = "database 仅允许字母数字下划线连字符点")
    private String database;

    /** 租户 ID（多租户隔离，由 Controller 从 JWT claims 覆盖）。 */
    private String tenantId;

    /** 是否允许降级到单集群查询。 */
    @Builder.Default
    private boolean allowDegrade = true;

    /** 是否同步执行（默认 false 异步）。 */
    @Builder.Default
    private boolean sync = false;

    /** 查询超时（秒），1~600（10 分钟上限），覆盖默认配置。 */
    @Min(value = 1, message = "timeoutSeconds 不能小于 1")
    @Max(value = 600, message = "timeoutSeconds 不能超过 600（10 分钟）")
    private Integer timeoutSeconds;

    /** 期望归并策略：CONCAT / UNION / JOIN（覆盖默认）。 */
    @Pattern(regexp = "^(CONCAT|UNION|JOIN)?$", message = "mergeStrategy 仅支持 CONCAT / UNION / JOIN")
    private String mergeStrategy;

    /** 附加选项。 */
    @Builder.Default
    private Map<String, Object> options = new HashMap<>();
}
