package com.levango7.dataenginebdp.streambatch.plugin;

/**
 * 任务通道工厂接口（模拟 DolphinScheduler TaskChannelFactory SPI）。
 *
 * <p>每个任务类型对应一个工厂，由 ServiceLoader 发现并注册。
 * 工厂创建具体的 {@link TaskChannel} 实例，注入运行时依赖
 * （SparkLauncher、FlinkRestClient、Iceberg Catalog 等）。
 */
public interface TaskChannelFactory {

    /**
     * 获取工厂支持的通道类型标识。
     *
     * @return 通道类型字符串（与 {@link TaskChannel#getChannelType()} 一致）
     */
    String getChannelType();

    /**
     * 创建任务通道实例。
     *
     * @return 任务通道
     */
    TaskChannel createChannel();
}