package com.levango7.dataenginebdp.federated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 降级告警事件。
 *
 * <p>当网络中断检测发现某集群不可达时，触发降级并生成此告警。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DegradationAlert {

    /** 告警 ID。 */
    private String alertId;

    /** 告警级别：WARN / ERROR / CRITICAL。 */
    private String severity;

    /** 告警类型：NETWORK_TIMEOUT / CONNECTION_FAILURE / DEGRADE_TRIGGERED / DEGRADE_RECOVERED。 */
    private String type;

    /** 受影响的集群。 */
    private String cluster;

    /** 告警消息。 */
    private String message;

    /** 触发时间。 */
    private Instant timestamp;

    /** 是否已恢复。 */
    private boolean recovered;

    /** 降级到的集群（type=DEGRADE_TRIGGERED 时）。 */
    private String degradedTo;
}