package com.levango7.dataenginebdp.masterdata.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 主数据记录 JPA 实体。
 *
 * <p>基于主数据模型定义创建的具体数据记录，如某个组织、某个物料等。
 * 支持多租户隔离（tenantId）与版本管理。
 * attributes 字段以 JSON 存储动态属性，由所属模型的 fieldsSchema 约束。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "master_data")
public class MasterData {

    /** 记录唯一标识，由数据库自增生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属主数据模型编码（关联 MasterDataModel.code） */
    @Column(nullable = false)
    private String modelCode;

    /** 数据键（业务唯一标识，如 ORG-001） */
    @Column(nullable = false)
    private String dataKey;

    /** 数据值（显示名称，如"总部"） */
    @Column(nullable = false)
    private String dataValue;

    /** 动态属性（JSON 格式，由模型 fieldsSchema 约束） */
    @Column(length = 8000)
    private String attributes;

    /** 版本号（如 1.0.0） */
    private String version;

    /** 状态：ACTIVE / INACTIVE / DEPRECATED */
    private String status;

    /**
     * 租户 ID（多租户隔离）。
     *
     * <p>创建时写入，查询 / 更新 / 删除时按此字段过滤，避免跨租户数据泄漏。</p>
     */
    private String tenantId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}