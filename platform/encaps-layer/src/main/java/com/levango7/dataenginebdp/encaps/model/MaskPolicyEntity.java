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
 * 数据脱敏策略实体（ROADMAP 前后端接线：/sec）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sec_mask_policy")
public class MaskPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 字段名。 */
    @Column(nullable = false, length = 128)
    private String fieldName;

    /** 所属资产。 */
    @Column(nullable = false, length = 255)
    private String assetName;

    /** 策略：mask/hash/authorized_only/plain。 */
    @Column(nullable = false, length = 32)
    private String strategy;

    /** 算法：SM3/SHA256/AES/MASK_PHONE。 */
    @Column(nullable = false, length = 32)
    private String algorithm;

    /** 状态：active/pending/disabled。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
