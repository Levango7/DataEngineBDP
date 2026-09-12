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
 * 主数据模型定义 JPA 实体。
 *
 * <p>定义企业核心主数据的结构 schema，如组织、人员、物料、客户等。
 * fieldsSchema 以 JSON 数组描述字段列表（字段名、类型、是否必填等）。
 * 支持多租户隔离（tenantId）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "master_data_models")
public class MasterDataModel {

    /** 模型唯一标识，由数据库自增生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模型编码（唯一标识，如 md-organization / md-material） */
    @Column(nullable = false)
    private String code;

    /** 模型名称 */
    @Column(nullable = false)
    private String name;

    /** 字段 schema（JSON 数组，描述字段名、类型、是否必填等） */
    @Column(length = 8000)
    private String fieldsSchema;

    /** 模型描述 */
    @Column(length = 2000)
    private String description;

    /**
     * 租户 ID（多租户隔离）。
     *
     * <p>创建时写入，查询时按此字段过滤，避免跨租户数据泄漏。</p>
     */
    private String tenantId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}