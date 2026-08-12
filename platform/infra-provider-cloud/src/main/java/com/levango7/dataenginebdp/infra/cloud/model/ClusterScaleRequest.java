package com.levango7.dataenginebdp.infra.cloud.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 集群扩缩容请求。
 *
 * <p>支持手动调整集群节点数。扩容时按原 vmSpec 创建新节点并加入 K8s；
 * 缩容时先驱逐 Pod 再销毁 VM。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterScaleRequest {

    /** 目标节点数（含控制面） */
    @NotNull(message = "targetNodeCount 不能为空")
    private Integer targetNodeCount;

    /** 操作原因（审计日志用） */
    @NotBlank(message = "reason 不能为空")
    private String reason;
}