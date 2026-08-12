package com.levango7.dataenginebdp.finops.dashboard.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 分账配置。
 *
 * <p>定义将某父工作空间（或 namespace）的成本按比例分配到子工作空间的规则。
 * 分账比例可配置，合计需 = 1.0。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllocationConfig {

    /** 配置 ID */
    @NotBlank
    private String id;

    /** 父工作空间名 */
    @NotBlank
    private String parentWorkspace;

    /** 分账维度（namespace / workspace_label） */
    @NotBlank
    private String dimension;

    /** 子工作空间分账比例：subWorkspace → ratio（合计需 = 1.0） */
    private Map<String, Double> ratios;

    /** 是否启用 */
    private boolean enabled;

    /** 备注 */
    private String remark;
}