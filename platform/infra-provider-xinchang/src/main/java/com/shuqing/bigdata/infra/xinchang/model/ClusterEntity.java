package com.shuqing.bigdata.infra.xinchang.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 集群元数据 JPA 实体。
 *
 * <p>持久化到 H2（开发）/ PostgreSQL（生产），用于跨重启恢复集群供应状态。
 * JSON 字段（nodes/metadata）以 TEXT 列存储，避免跨数据库 JSONB 差异。</p>
 */
@Entity
@Table(name = "xinchang_cluster")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterEntity {

    /** 集群 ID（UUID），主键 */
    @Id
    @Column(name = "cluster_id", length = 64)
    private String clusterId;

    /** 集群名称 */
    @Column(name = "cluster_name", nullable = false, length = 63)
    private String clusterName;

    /** 租户 ID */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** K8s 版本 */
    @Column(name = "k8s_version", length = 32)
    private String k8sVersion;

    /** 集群状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ClusterInfo.Status status;

    /** control-plane 端点 VIP */
    @Column(name = "control_plane_endpoint", length = 64)
    private String controlPlaneEndpoint;

    /** 节点清单 JSON */
    @Lob
    @Column(name = "nodes_json")
    private String nodesJson;

    /** 元数据 JSON */
    @Lob
    @Column(name = "metadata_json")
    private String metadataJson;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 最近更新时间 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 错误信息 */
    @Column(name = "error_message", length = 4096)
    private String errorMessage;
}