package com.levango7.dataenginebdp.encaps.model;

import com.levango7.dataenginebdp.encaps.security.Encrypt;
import com.levango7.dataenginebdp.encaps.security.EncryptType;
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
 * 数据源实体（ROADMAP 前后端接线：/datasources）。
 *
 * <p>连接元数据持久化（密码仅写入时使用，查询不返回）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "datasource")
public class DataSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 数据源名称。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 类型：mysql/postgresql/clickhouse/kafka/hive/oracle/sqlserver/doris/trino。 */
    @Column(nullable = false, length = 32)
    private String type;

    /** 主机地址。 */
    @Column(nullable = false, length = 255)
    private String host;

    /** 端口。 */
    @Column(nullable = false)
    private Integer port;

    /** 数据库名（Kafka 等可选）。 */
    @Column(length = 128)
    private String database;

    /** 用户名。 */
    @Column(nullable = false, length = 128)
    private String username;

    /** 密码（仅写入时使用，查询不返回；SM4 加密存储）。 */
    @Encrypt(EncryptType.SM4)
    @Column(length = 255)
    private String password;

    /** 连接状态：connected/disconnected/testing。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    /** 创建时间。 */
    @Column(nullable = false)
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
