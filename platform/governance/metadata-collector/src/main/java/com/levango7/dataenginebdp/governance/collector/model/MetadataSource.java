package com.levango7.dataenginebdp.governance.collector.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 元数据采集源配置。
 *
 * <p>描述一个待采集的数据源（Hive/Doris/Kafka/FileSystem）的连接信息与调度策略。
 * 通过 Spring Data JPA 持久化到 H2/PostgreSQL，由 CollectorController 维护 CRUD。</p>
 *
 * <p>不同 {@link #type} 对应不同的连接参数语义，统一以 {@link #connectionProps}
 * JSON 字符串承载，由各 Collector 实现自行解析。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "metadata_sources")
public class MetadataSource {

    /** 数据源类型常量：Hive Metastore */
    public static final String TYPE_HIVE = "HIVE";
    /** 数据源类型常量：Doris FE */
    public static final String TYPE_DORIS = "DORIS";
    /** 数据源类型常量：Kafka 集群 */
    public static final String TYPE_KAFKA = "KAFKA";
    /** 数据源类型常量：HDFS/对象存储文件系统 */
    public static final String TYPE_FILESYSTEM = "FILESYSTEM";

    /** 数据源自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 数据源名称（逻辑唯一），创建时必填 */
    @NotBlank(message = "name must not be blank")
    private String name;

    /**
     * 数据源类型：取值范围
     * {@link #TYPE_HIVE}/{@link #TYPE_DORIS}/{@link #TYPE_KAFKA}/{@link #TYPE_FILESYSTEM}
     */
    @NotBlank(message = "type must not be blank")
    private String type;

    /** JDBC/Bootstrap/HDFS 连接 URL */
    private String url;

    /** 连接用户名（Kafka 可空，使用 SASL 配置） */
    private String username;

    /** 连接密码（密文存储由上层负责，本字段以明文承载） */
    private String password;

    /**
     * 连接附加参数 JSON，例如：
     * <pre>{"kerberos":true,"principal":"hive/_HOST@REALM","sasl.mechanism":"GSSAPI"}</pre>
     */
    @Lob
    private String connectionProps;

    /** 定时采集 cron 表达式，为空表示仅手动触发 */
    private String cron;

    /** 数据源状态：ACTIVE/INACTIVE/ERROR */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}