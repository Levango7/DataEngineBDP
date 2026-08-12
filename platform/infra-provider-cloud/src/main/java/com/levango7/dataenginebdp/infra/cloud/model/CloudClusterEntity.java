package com.levango7.dataenginebdp.infra.cloud.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 云集群元数据 JPA 实体。
 *
 * <p>持久化集群 ID、provider、状态与节点 JSON 等元数据，
 * 用于跨请求追踪集群生命周期。开发环境使用 H2 文件库，生产环境切换 PostgreSQL。</p>
 */
@Entity
@Table(name = "cloud_cluster",
        indexes = {
                @Index(name = "idx_cloud_cluster_provider", columnList = "provider"),
                @Index(name = "idx_cloud_cluster_workspace", columnList = "workspace_id"),
                @Index(name = "idx_cloud_cluster_status", columnList = "status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudClusterEntity {

    /** 平台内部集群 ID（UUID 风格，由 service 层生成） */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;

    /** 集群名称 */
    @Column(nullable = false, length = 128)
    private String clusterName;

    /** 云 provider 标识：huawei / ali / tencent */
    @Column(nullable = false, length = 16)
    private String provider;

    /** 工作空间 ID */
    @Column(nullable = false, length = 64)
    private String workspaceId;

    /** 集群状态：CREATING / RUNNING / STOPPED / DELETING / DELETED / ERROR */
    @Column(nullable = false, length = 16)
    private String status;

    /** 节点数 */
    @Column(nullable = false)
    private Integer nodeCount;

    /** K8s 控制面 API Server 端点 */
    @Column(length = 256)
    private String k8sApiServerEndpoint;

    /** K8s 引导状态：PENDING / BOOTSTRAPPING / READY / FAILED */
    @Column(length = 16)
    private String k8sBootstrapStatus;

    /** 节点 JSON（CloudClusterInfo.nodes 序列化） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String nodesJson;

    /** 错误信息 */
    @Column(length = 2048)
    private String errorMessage;

    /** 创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    /** 更新时间 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}