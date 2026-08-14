package com.levango7.dataenginebdp.encaps.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 主数据标准实体（ROADMAP 前后端接线：/standards）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "standard")
public class StandardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 标准项名（如 user_id）。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 类型：primary_key/enum/dict/amount/date/string。 */
    @Column(nullable = false, length = 32)
    private String type;

    /** 码值/规则描述。 */
    @Column(length = 512)
    private String rule;

    /** 描述。 */
    @Column(length = 512)
    private String description;

    /** 状态。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
