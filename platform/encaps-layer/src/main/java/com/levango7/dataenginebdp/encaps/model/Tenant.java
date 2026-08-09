package com.levango7.dataenginebdp.encaps.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户模型 JPA Entity。
 *
 * <p>描述数据引擎大数据平台中一个租户的核心元数据，包括命名空间、配额档位与状态等。
 * 通过 Spring Data JPA 持久化到关系型数据库（开发环境 H2，生产环境 PostgreSQL）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tenants")
public class Tenant {

    /** 租户唯一标识，由数据库自增生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 租户名称（逻辑唯一），创建时必填 */
    @NotBlank(message = "name must not be blank")
    private String name;

    /** 租户展示名称，便于人读 */
    private String displayName;

    /** 租户对应的 K8s namespace */
    private String namespace;

    /** 配额档位标识，例如 small/medium/large */
    private String quotaProfile;

    /** 租户状态：ACTIVE/INACTIVE/CREATING/DELETING 等 */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}
