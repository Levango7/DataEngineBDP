package com.shuqing.bigdata.governance.collector.collector;

import com.shuqing.bigdata.governance.collector.model.CollectionResult;
import com.shuqing.bigdata.governance.collector.model.MetadataSource;

/**
 * 元数据采集器 SPI 接口。
 *
 * <p>所有具体采集器（Hive/Doris/Kafka/FileSystem）实现本接口，
 * 由 {@code CollectionSchedulerService} 按 {@link #getType()} 路由到对应实现。</p>
 *
 * <p>实现类应注册为 Spring Bean，构造时通过 {@code @Service} 暴露。
 * 单元测试可通过 Mock 本接口模拟采集行为。</p>
 *
 * <p>实现需保证线程安全：{@link #collect} 与 {@link #testConnection} 可能被并发调用。</p>
 */
public interface MetadataCollector {

    /**
     * 返回采集器支持的数据源类型。
     *
     * <p>取值范围：{@link MetadataSource#TYPE_HIVE}/{@link MetadataSource#TYPE_DORIS}/
     * {@link MetadataSource#TYPE_KAFKA}/{@link MetadataSource#TYPE_FILESYSTEM}。</p>
     *
     * @return 数据源类型字符串
     */
    String getType();

    /**
     * 执行元数据采集。
     *
     * <p>从数据源拉取数据库/表/列/分区/统计信息，组装为 {@link CollectionResult} 返回。
     * 实现应捕获所有异常并返回 {@code success=false} 的结果，避免抛出异常中断调度。</p>
     *
     * @param source 数据源配置
     * @return 采集结果
     */
    CollectionResult collect(MetadataSource source);

    /**
     * 测试数据源连接是否可用。
     *
     * <p>用于 {@code POST /api/v1/metadata/collect/test/{sourceId}} 端点，
     * 在添加数据源时校验连接。实现应快速返回，不进行完整采集。</p>
     *
     * @param source 数据源配置
     * @return 连接可用返回 {@code true}，否则 {@code false}
     */
    boolean testConnection(MetadataSource source);
}