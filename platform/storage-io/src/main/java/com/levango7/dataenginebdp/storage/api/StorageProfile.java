package com.levango7.dataenginebdp.storage.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对象存储连接配置（支持多租户隔离）。
 *
 * <p>生产环境按环境变量注入（如 STORAGE_TYPE、STORAGE_ENDPOINT 等），
 * 本地默认使用 MinIO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageProfile {

    /** 存储类型（s3 | minio）。 */
    private String storageType;

    /** 服务端点（如 http://minio:9000 或 https://oss-cn-hangzhou.aliyuncs.com）。 */
    private String endpoint;

    /** 存储桶。 */
    private String bucket;

    /** 访问密钥 ID（环境变量注入）。 */
    private String accessKey;

    /** 访问密钥 Secret（环境变量注入）。 */
    private String secretKey;

    /** 区域（如 us-east-1、cn-hangzhou；非 S3 必填）。 */
    private String region;

    /** 是否启用路径风格访问（MinIO/Ceph 默认 true，AWS S3 默认 false）。 */
    @Builder.Default
    private boolean pathStyleAccess = false;

    /** 是否允许公开访问（生产 false）。 */
    @Builder.Default
    private boolean publicAccess = false;
}
