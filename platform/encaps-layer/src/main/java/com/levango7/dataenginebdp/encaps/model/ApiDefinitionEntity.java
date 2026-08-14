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
 * API 定义实体（ROADMAP 前后端接线：/apis）。
 *
 * <p>核心字段独立存储，完整契约（params/responses/upstream 等）以 JSON 透传。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "api_definition")
public class ApiDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** API 名。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 版本。 */
    @Column(nullable = false, length = 32)
    private String version;

    /** 分类。 */
    @Column(length = 64)
    private String category;

    /** HTTP 方法：GET/POST/PUT/DELETE。 */
    @Column(nullable = false, length = 8)
    private String method;

    /** 路径。 */
    @Column(nullable = false, length = 255)
    private String path;

    /** 状态：published/draft/offline。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 完整契约 JSON。 */
    @Column(length = 32768)
    private String fullJson;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
