package com.levango7.dataenginebdp.storage;

import com.levango7.dataenginebdp.storage.api.ObjectStore;
import com.levango7.dataenginebdp.storage.api.StorageProfile;
import lombok.extern.slf4j.Slf4j;

import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 对象存储工厂（按配置类型创建实例）。
 */
@Slf4j
public final class ObjectStoreFactory {

    private static final ConcurrentMap<String, ObjectStore> CACHED = new ConcurrentHashMap<>();

    private ObjectStoreFactory() {
        // 工具类
    }

    /**
     * 根据配置创建对象存储实例。
     *
     * @param profile 存储配置
     * @param mapper  租户路径映射
     * @return 对象存储实例（按 storageType 缓存）
     */
    public static ObjectStore create(StorageProfile profile, TenantPathMapper mapper) {
        String key = profile.getStorageType() + "|" + profile.getEndpoint() + "|" + profile.getBucket();
        return CACHED.computeIfAbsent(key, k -> {
            ObjectStore store = switch (profile.getStorageType().toLowerCase()) {
                // MinIO 完全兼容 S3 API，统一走 S3ObjectStore
                case "s3", "minio" -> new com.levango7.dataenginebdp.storage.impl.S3ObjectStore(profile, mapper);
                default -> throw new IllegalArgumentException("不支持的对象存储类型: " + profile.getStorageType());
            };
            log.info("创建对象存储: type={}, endpoint={}, bucket={}",
                    profile.getStorageType(), profile.getEndpoint(), profile.getBucket());
            return store;
        });
    }

    /**
     * 从环境变量加载配置（生产环境注入模式）。
     *
     * @return 存储配置
     */
    public static StorageProfile fromEnvironment() {
        return StorageProfile.builder()
                .storageType(System.getenv("STORAGE_TYPE") != null
                        ? System.getenv("STORAGE_TYPE") : "minio")
                .endpoint(System.getenv("STORAGE_ENDPOINT") != null
                        ? System.getenv("STORAGE_ENDPOINT") : "http://minio:9000")
                .bucket(System.getenv("STORAGE_BUCKET") != null
                        ? System.getenv("STORAGE_BUCKET") : "shuqing-warehouse")
                .accessKey(System.getenv("STORAGE_ACCESS_KEY"))
                .secretKey(System.getenv("STORAGE_SECRET_KEY"))
                .region(System.getenv("STORAGE_REGION") != null
                        ? System.getenv("STORAGE_REGION") : "us-east-1")
                .pathStyleAccess(Boolean.parseBoolean(
                        System.getenv("STORAGE_PATH_STYLE_ACCESS") != null
                                ? System.getenv("STORAGE_PATH_STYLE_ACCESS") : "true"))
                .publicAccess(Boolean.parseBoolean(
                        System.getenv("STORAGE_PUBLIC_ACCESS") != null
                                ? System.getenv("STORAGE_PUBLIC_ACCESS") : "false"))
                .build();
    }

    /** ServiceLoader 扩展点（未来支持 OCI 对象存储等新驱动）。 */
    public static ObjectStore createViaServiceLoader(StorageProfile profile, TenantPathMapper mapper) {
        return ServiceLoader.load(ObjectStore.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(store -> {
                    try {
                        store.getClass().getDeclaredConstructor(StorageProfile.class, TenantPathMapper.class);
                        return true;
                    } catch (NoSuchMethodException e) {
                        return false;
                    }
                })
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到 ObjectStore 服务配置实现"));
    }

    /** 清空缓存（测试用）。 */
    public static void clearCache() {
        CACHED.clear();
    }
}
