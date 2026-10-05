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
     * <p><b>隐式契约</b>：实现<b>必须</b>在返回前完整消费 {@code inputStream}。
     * {@code ObjectReplicator} 以 {@code DigestInputStream} 包装源流后交给本方法，
     * 依赖“传完即读完”在传输过程中流式计算源对象 sha256；若实现不读满流，
     * 校验哈希将退化为空内容哈希，导致复制被误判为 FAILED。
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
     * 读取对象元数据快照（不下载对象内容）。
     *
     * <p>用于跨域复制的冲突判定与流式复制前的长度获取，避免整对象读入内存。
     *
     * @param key 相对对象键
     * @return 元数据；键不存在返回 null
     */
    ObjectMetadata statObject(String key);

    /**
     * 当前存储实例的访问端点（源 / 目标端点写入复制报告，便于产物溯源）。
     *
     * @return 端点字符串（如 http://localhost:9100）
     */
    String endpoint();

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