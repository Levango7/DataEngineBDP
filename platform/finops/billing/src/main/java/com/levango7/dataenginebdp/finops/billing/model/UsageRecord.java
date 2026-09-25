package com.levango7.dataenginebdp.finops.billing.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 计量汇入的用量明细项。
 *
 * <p>由上游计量组件（如 open-api-catalog 的 API 调用量）聚合后随
 * {@link BillingGenerateRequest#getUsageData()} 汇入，作为出账的输入数据。</p>
 *
 * <p>计价优先级：{@code amount} 存在时以其为权威金额（上游已计价，
 * 例如按 API 定价规则算出）；否则用 {@code usage × 单价}，单价取
 * {@code unitPrice}，未提供时查内置单价表。两者都无法确定金额时拒绝出账
 * （不允许静默按 0 元计费）。</p>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} 为有意设置：
 * 上游一旦改了字段名，反序列化立即失败并报 400，避免不认识的字段被
 * Jackson 静默丢弃导致计量数据凭空消失（历史缺陷：usageData 整体被丢弃）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageRecord {

    /** 资源类型（如 API_CALL、SCANNED_DATA，或 CPU/MEMORY/STORAGE/GPU/NETWORK） */
    @NotBlank
    private String resourceType;

    /** 用量（单位由资源类型决定，如 API 调用次数）；仅当 amount 已给出时可为空 */
    private Double usage;

    /** 单价（元，可选）；未提供且无 amount 时查内置单价表 */
    private BigDecimal unitPrice;

    /** 金额（元，可选）；提供时作为权威金额，不做二次计价 */
    private BigDecimal amount;

    /** 归属 namespace（可选，API 类计量通常为空） */
    private String namespace;

    /** GPU 型号（仅 resourceType=GPU 时有意义） */
    private String gpuModel;

    /** 来源引用（如 API ID），用于账单追溯 */
    private String sourceRef;
}
