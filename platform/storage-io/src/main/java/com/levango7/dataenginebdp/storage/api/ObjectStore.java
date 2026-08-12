package com.levango7.dataenginebdp.storage.api;

import java.io.InputStream;
import java.util.List;

/**
 * 对象存储统一操作接口。
 *
 * <p>所有实现必须在操作前执行租户路径映射：
 * 任何对象键 key 都会被转换为 {tenantId}/{key} 进行租户隔离。
 *
 * <p>实现类必须保证：
 * <ul>
 *   <li>不存在租户的读操作返回 null 或抛 NoSuchKeyException</li>
 *   <li>删除不存在的键不抛异常（幂等）</li>
 *   <li>所有写操作必须在 bucket 存在时执行，不存在则创建（或按配置略过）</li>
 * </ul>
 */
public interface ObjectStore {

    /**
     * 上传对象（同时创建租户前缀）。
     *
     * @param key          相对对象键（不含 tenantId）
     * @param inputStream  数据流
     * @param contentLength 数据长度（字节）
     * @param contentType  MIME 类型（可空）
     */
    void putObject(String key, InputStream inputStream, long contentLength, String contentType);

    /**
     * 获取对象（返回输入流）。
     *
     * @param key 相对对象键
     * @return 输入流；若键不存在返回 null
     */
    InputStream getObject(String key);

    /**
     * 获取对象的字节内容。
     *
     * @param key 相对对象键
     * @return 字节数组；若键不存在返回 null
     */
    byte[] getObjectAsBytes(String key);

    /**
     * 删除对象。
     *
     * @param key 相对对象键（幂等，键不存在时不抛异常）
     */
    void deleteObject(String key);

    /**
     * 列出某前缀下的所有对象。
     *
     * @param prefix 相对对象键前缀（不含 tenantId）
     * @return 对象键列表（返回相对键，已剥离 tenantId 前缀）
     */
    List<String> listObjects(String prefix);

    /**
     * 判断键是否存在。
     *
     * @param key 相对对象键
     */
    boolean existsObject(String key);

    /**
     * 创建 bucket（幂等）。
     *
     * @param bucket 桶名（若为空则使用 profile 中的 bucket）
     */
    void createBucketIfNotExists(String bucket);

    /**
     * 关闭连接释放资源。
     */
    void close();
}
