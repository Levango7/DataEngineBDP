package com.levango7.dataenginebdp.streambatch.plugin;

import com.levango7.dataenginebdp.streambatch.spark.SparkBatchSubmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Spark 批任务通道工厂（DolphinScheduler TaskChannelFactory SPI 实现）。
 *
 * <p>通过 Spring {@code @Component} 注册；实际部署时可同时通过
 * {@code META-INF/services/org.apache.dolphinscheduler.plugin.task.api.TaskChannelFactory}
 * 注册到 DolphinScheduler ServiceLoader。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SparkBatchTaskChannelFactory implements TaskChannelFactory {

    private final SparkBatchSubmitter submitter;

    @Override
    public String getChannelType() {
        return com.levango7.dataenginebdp.streambatch.model.TaskType.SPARK_BATCH.getCode();
    }

    @Override
    public TaskChannel createChannel() {
        log.debug("创建 SparkBatchTaskChannel 实例");
        return new SparkBatchTaskChannel(submitter);
    }
}