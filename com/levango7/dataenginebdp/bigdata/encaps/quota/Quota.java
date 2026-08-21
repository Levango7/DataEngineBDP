package com.shuqing.bigdata.encaps.quota;

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
 * Quota 模型 JPA Entity。
 *
 * <p>Quota 是数擎大数据平台中对 Workspace 的资源配额抽象，由封装层翻译为
 * K8s ResourceQuota + LimitRange 一组底层资源原语。客户无需感知 K8s 配额细节。</p>
 *
 * <p>核心字段分两组：</p>
 * <ul>
 *   <li>ResourceQuota 组（Workspace 级总量限制）：cpuLimit/memoryLimit/storageLimit/podLimit/pvcLimit/serviceLimit</li>
 *   <li>LimitRange 组（per-Pod/Container 限制）：maxCpuPerPod/maxMemoryPerPod/minCpuPerPod/minMemoryPerPod</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "quotas")
public class Quota {

    /** Quota 唯一标识，由数据库自增生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属 Workspace ID，必填 */
    @NotNull(message = "workspaceId must not be null")
    private Long workspaceId;

    /** 所属租户 ID，必填（用于跨 Workspace 聚合查询） */
    @NotNull(message = "tenantId must not be null")
    private Long tenantId;

    /* ------------------------------ ResourceQuota 组 ------------------------------ */

    /** CPU 核数限制，如 {@code 10} */
    @NotBlank(message = "cpuLimit must not be blank")
    private String cpuLimit;

    /** 内存限制，如 {@code 20Gi} */
    @NotBlank(message = "memoryLimit must not be blank")
    private String memoryLimit;

    /** 存储限制，如 {@code 100Gi} */
    @NotBlank(message = "storageLimit must not be blank")
    private String storageLimit;

    /** Pod 数量限制，如 {@code 100} */
    @NotBlank(message = "podLimit must not be blank")
    private String podLimit;

    /** PVC 数量限制，如 {@code 50} */
    @NotBlank(message = "pvcLimit must not be blank")
    private String pvcLimit;

    /** Service 数量限制，如 {@code 20} */
    @NotBlank(message = "serviceLimit must not be blank")
    private String serviceLimit;

    /* ------------------------------ LimitRange 组（per-Pod 限制） ------------------------------ */

    /** 单 Pod 最大 CPU，如 {@code 4} */
    private String maxCpuPerPod;

    /** 单 Pod 最大内存，如 {@code 8Gi} */
    private String maxMemoryPerPod;

    /** 单 Pod 最小 CPU，如 {@code 100m} */
    private String minCpuPerPod;

    /** 单 Pod 最小内存，如 {@code 256Mi} */
    private String minMemoryPerPod;

    /* ------------------------------ 元数据 ------------------------------ */

    /** Quota 生命周期状态 */
    @Enumerated(EnumType.STRING)
    private QuotaStatus status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;

    /**
     * Quota 生命周期状态枚举。
     *
     * <ul>
     *   <li>{@link #SETTING} — 设置中：K8s 资源正在翻译与下发</li>
     *   <li>{@link #ACTIVE} — 活跃：K8s ResourceQuota + LimitRange 已就绪</li>
     *   <li>{@link #UPDATING} — 更新中：K8s 资源正在更新</li>
     *   <li>{@link #DELETING} — 删除中：K8s 资源正在删除</li>
     *   <li>{@link #DELETED} — 已删除：DB 记录保留，K8s 资源已清除</li>
     *   <li>{@link #FAILED} — 失败：K8s 翻译失败</li>
     * </ul>
     */
    public enum QuotaStatus {
        /** 设置中 */
        SETTING,
        /** 活跃 */
        ACTIVE,
        /** 更新中 */
        UPDATING,
        /** 删除中 */
        DELETING,
        /** 已删除 */
        DELETED,
        /** 失败 */
        FAILED
    }
}