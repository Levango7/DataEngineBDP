package com.levango7.dataenginebdp.infra.orchestrator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 跨环境统一集群运行态信息。
 *
 * <p>编排层将下游各 Provider 返回的异构集群信息归一化为本模型，
 * 对上暴露统一的集群查询/列表响应。</p>
 *
 * <p>对应 REST API：</p>
 * <ul>
 *   <li>{@code GET /api/v1/clusters/{environment}/{clusterId}}</li>
 *   <li>{@code GET /api/v1/clusters}</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterInfo {

    /**
     * 集群状态枚举 - 跨环境统一状态空间。
     *
     * <p>各 Provider 的本地状态枚举（如 xinchang 的 CREATING/RUNNING/...）
     * 在编排层归一化为此枚举。</p>
     */
    public enum Status {
        /** 创建中。 */
        CREATING,
        /** 运行中（对应下游 ACTIVE/RUNNING）。 */
        ACTIVE,
        /** 扩缩容中。 */
        SCALING,
        /** 销毁中。 */
        DESTROYING,
        /** 已销毁。 */
        DESTROYED,
        /** 失败。 */
        FAILED,
        /** 未知（下游返回了无法识别的状态）。 */
        UNKNOWN
    }

    /** 集群 ID（UUID）。 */
    private String clusterId;

    /** 集群名称。 */
    private String clusterName;

    /** 租户 ID。 */
    private String tenantId;

    /** 环境类型。 */
    private EnvironmentType environment;

    /** K8s 版本。 */
    private String k8sVersion;

    /** 集群当前状态。 */
    private Status status;

    /** control-plane 端点 VIP。 */
    private String controlPlaneEndpoint;

    /** 节点信息列表，每项为节点摘要 JSON 或 {@code hostname|role|status}。 */
    private List<String> nodes;

    /** 元数据：kubeconfig / 证书指纹 / 创建耗时 / 下游原始响应等。 */
    private Map<String, String> metadata;

    /** 创建时间。 */
    private Instant createdAt;

    /** 最近更新时间。 */
    private Instant updatedAt;

    /** 错误信息（status=FAILED 时填充）。 */
    private String errorMessage;

    /**
     * 将下游字符串状态归一化为 {@link Status}。
     *
     * @param raw 下游状态字符串
     * @return 统一状态；无法识别返回 {@link Status#UNKNOWN}
     */
    public static Status normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return Status.UNKNOWN;
        }
        String upper = raw.trim().toUpperCase();
        return switch (upper) {
            case "CREATING", "PENDING", "PROVISIONING" -> Status.CREATING;
            case "ACTIVE", "RUNNING", "READY" -> Status.ACTIVE;
            case "SCALING", "UPDATING", "RESIZING" -> Status.SCALING;
            case "DESTROYING", "DELETING", "TERMINATING" -> Status.DESTROYING;
            case "DESTROYED", "DELETED", "TERMINATED" -> Status.DESTROYED;
            case "FAILED", "ERROR" -> Status.FAILED;
            default -> Status.UNKNOWN;
        };
    }
}