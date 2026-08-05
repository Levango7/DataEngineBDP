package com.shuqing.bigdata.encaps.workspace;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Workspace 模型 JPA Entity。
 *
 * <p>Workspace 是数擎大数据平台中租户下的工作空间（隔离边界），由封装层翻译为
 * K8s Namespace + NetworkPolicy + RBAC + ResourceQuota 一组底层资源原语。
 * 客户无需感知 K8s/容器编排细节。</p>
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code namespace} — K8s Namespace 名称，由翻译器生成或外部指定</li>
 *   <li>{@code resourceQuota} — CPU/内存/存储配额，格式如 {@code cpu=4,memory=8Gi,storage=100Gi}</li>
 *   <li>{@code networkPolicy} — 网络隔离策略标识，如 {@code tenant-isolated}（租户内互通，跨租户隔离）</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "workspaces")
public class Workspace {

    /** Workspace 唯一标识，由数据库自增生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Workspace 名称（同一租户下逻辑唯一），创建时必填 */
    @NotBlank(message = "name must not be blank")
    private String name;

    /** 所属租户 ID，必填 */
    @NotNull(message = "tenantId must not be null")
    private Long tenantId;

    /** Workspace 描述，便于人读 */
    private String description;

    /** Workspace 生命周期状态 */
    @Enumerated(EnumType.STRING)
    private WorkspaceStatus status;

    /** 对应 K8s Namespace 名称，由翻译器生成 */
    private String namespace;

    /** 资源配额字符串，格式 {@code cpu=4,memory=8Gi,storage=100Gi} */
    private String resourceQuota;

    /** 网络隔离策略标识，如 {@code tenant-isolated} / {@code deny-all} */
    private String networkPolicy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;

    /**
     * Workspace 生命周期状态枚举。
     *
     * <ul>
     *   <li>{@link #CREATING} — 创建中：K8s 资源正在翻译与下发</li>
     *   <li>{@link #ACTIVE} — 活跃：K8s Namespace 已就绪</li>
     *   <li>{@link #DELETING} — 删除中：K8s Namespace 正在级联删除</li>
     *   <li>{@link #DELETED} — 已删除：DB 记录保留，K8s 资源已清除</li>
     * </ul>
     */
    public enum WorkspaceStatus {
        /** 创建中 */
        CREATING,
        /** 活跃 */
        ACTIVE,
        /** 删除中 */
        DELETING,
        /** 已删除 */
        DELETED
    }
}