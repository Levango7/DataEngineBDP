package com.levango7.dataenginebdp.storage.impl;

import com.levango7.dataenginebdp.storage.TenantPathMapper;
import com.levango7.dataenginebdp.storage.api.ObjectStore;
import com.levango7.dataenginebdp.storage.api.StorageProfile;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * S3 兼容存储实现（覆盖 AWS S3、阿里云 OSS、华为 OBS、MinIO、Ceph）。
 */
@Slf4j
public class S3ObjectStore implements ObjectStore {

    private final S3Client s3Client;
    private final StorageProfile profile;
    private final TenantPathMapper tenantPathMapper;

    public S3ObjectStore(StorageProfile profile, TenantPathMapper tenantPathMapper) {
        this.profile = profile;
        this.tenantPathMapper = tenantPathMapper;

        S3Configuration config = S3Configuration.builder()
                .pathStyleAccessEnabled(profile.isPathStyleAccess())
                .build();

        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(profile.getEndpoint()))
                .region(profile.getRegion() != null && !profile.getRegion().isEmpty()
                        ? Region.of(profile.getRegion()) : Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(profile.getAccessKey(), profile.getSecretKey())))
                .serviceConfiguration(config)
                .build();

        createBucketIfNotExists(profile.getBucket());
    }

    private String toFullKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        return tenantPathMapper.toStorageKey(key);
    }

    @Override
    public void putObject(String key, InputStream inputStream, long contentLength, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(profile.getBucket())
                .key(toFullKey(key))
                .contentLength(contentLength)
                .contentType(contentType)
                .build();
        s3Client.putObject(request, RequestBody.fromInputStream(inputStream, contentLength));
    }

    @Override
    public InputStream getObject(String key) {
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(profile.getBucket())
                    .key(toFullKey(key))
                    .build());
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    @Override
    public byte[] getObjectAsBytes(String key) {
        InputStream in = getObject(key);
        if (in == null) {
            return null;
        }
        try {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("读取对象失败: " + key, e);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Override
    public void deleteObject(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(profile.getBucket())
                .key(toFullKey(key))
                .build());
    }

    @Override
    public List<String> listObjects(String prefix) {
        String fullPrefix = tenantPathMapper.toStoragePrefix(prefix);
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(profile.getBucket())
                .prefix(fullPrefix)
                .build();

        List<String> keys = new ArrayList<>();
        try {
            s3Client.listObjectsV2Paginator(request).stream().forEach(page ->
                page.contents().forEach(obj -> {
                    String fullKey = obj.key();
                    String relKey = tenantPathMapper.stripTenantPrefix(fullKey);
                    if (relKey != null) {
                        keys.add(relKey);
                    }
                })
            );
        } catch (S3Exception e) {
            log.warn("列表对象失败: prefix={}", fullPrefix, e);
        }
        return keys;
    }

    @Override
    public boolean existsObject(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(profile.getBucket())
                    .key(toFullKey(key))
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void createBucketIfNotExists(String bucket) {
        try {
            s3Client.headBucket(b -> b.bucket(bucket));
            log.info("bucket 已存在: {}", bucket);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                try {
                    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                    log.info("bucket 已创建: {}", bucket);
                } catch (S3Exception ce) {
                    log.error("创建 bucket 失败: {}", bucket, ce);
                    throw new IllegalStateException("无法创建 bucket: " + bucket, ce);
                }
            } else {
                log.error("检查 bucket 失败: {}", bucket, e);
                throw new IllegalStateException("检查 bucket 失败: " + bucket, e);
            }
        }
    }

    @Override
    public void close() {
        s3Client.close();
    }
}
