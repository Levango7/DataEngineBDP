package com.levango7.dataenginebdp.infra.privatecloud.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 私有云 K8s 集群信息（JPA Entity）。
 *
 * <p>由 {@code PrivateCloudProvider} 创建后写入关系型数据库（开发环境 H2，
 * 生产环境 PostgreSQL），供后续查询、扩缩容、销毁使用。</p>
 *
 * <p>VM 列表以 JSON 字符串形式存于 {@code vmJson} 字段（{@link Lob}），
 * 避免引入额外的关联表；读取时由 service 层反序列化为 {@link VMInfo} 列表。</p>
 *
 * @author shuqing-bigdata
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "private_clusters")
public class PrivateClusterInfo {

    /** 集群唯一标识，由数据库自增生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 集群名称 */
    private String clusterName;

    /** 云平台类型：vsphere / openstack */
    private String provider;

    /** 租户 ID（来自 JWT） */
    private String tenantId;

    /** 集群状态：CREATING / RUNNING / SCALING / DELETING / FAILED / DELETED */
    private String status;

    /** K8s 版本 */
    private String k8sVersion;

    /** Pod CIDR */
    private String podCidr;

    /** Service CIDR */
    private String serviceCidr;

    /** 控制面节点数 */
    private Integer controlPlaneCount;

    /** 工作节点数 */
    private Integer workerCount;

    /** VM 列表 JSON（VMInfo 数组序列化） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String vmJson;

    /** 错误信息（FAILED 状态时填充） */
    private String errorMessage;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /**
     * VM 实时信息（非持久化字段，由 service 层从 {@link #vmJson} 反序列化填充）。
     */
    @Transient
    private List<VMInfo> vms;

    /**
     * VM 信息（非持久化 POJO，作为 {@link PrivateClusterInfo#getVmJson()} 的反序列化目标）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VMInfo {
        /** VM ID（vSphere {@code vm-xxx} / OpenStack server UUID） */
        private String vmId;
        /** VM 名称 */
        private String name;
        /** 角色：control-plane / worker */
        private String role;
        /** 电源状态：POWERED_ON / POWERED_OFF / SUSPENDED */
        private String powerState;
        /** IP 地址（vSphere guestIpAddress / OpenStack accessIPv4） */
        private String ipAddress;
        /** 浮动 IP（OpenStack 专用） */
        private String floatingIp;
    }
}