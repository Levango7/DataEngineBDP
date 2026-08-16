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
 * API 密钥实体（ROADMAP 前后端接线：/gateway/keys）。
 *
 * <p>租户隔离：所有查询按 {@code tenantId} 过滤；secret 仅在创建时返回一次，
 * 之后以哈希值持久化，前端列表只看到掩码。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "gateway_api_key")
public class ApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Key 名称（用户可读）。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 路由模型标识。 */
    @Column(nullable = false, length = 128)
    private String routeModel;

    /** 限流（次/秒）。 */
    @Column(nullable = false)
    private Integer rateLimit;

    /** 状态：enabled / disabled / pending。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 实际 apiKey（明文，调用方持有）。 */
    @Column(nullable = false, length = 64, unique = true)
    private String apiKey;

    /** secret 哈希（SHA-256），创建时明文一次性返回，之后不再泄露。 */
    @Column(nullable = false, length = 128)
    private String secretHash;

    /** 权限范围（逗号分隔的模型或路由列表，可选）。 */
    @Column(length = 512)
    private String scope;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}