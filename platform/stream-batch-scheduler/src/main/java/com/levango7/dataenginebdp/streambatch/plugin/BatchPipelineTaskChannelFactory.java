package com.levango7.dataenginebdp.streambatch.plugin;

import com.levango7.dataenginebdp.streambatch.batchpipeline.BatchPipelineClient;
import com.levango7.dataenginebdp.streambatch.batchpipeline.BatchPipelineConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * batch-pipeline 任务通道工厂（DolphinScheduler TaskChannelFactory SPI 实现）。
 *
 * <p>通过 Spring {@code @Component} 注册；并经
 * {@code META-INF/services/...TaskChannelFactory} 注册到 ServiceLoader。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchPipelineTaskChannelFactory implements TaskChannelFactory {

    private final BatchPipelineClient client;
    private final BatchPipelineConfig config;

    @Override
    public String getChannelType() {
        return com.levango7.dataenginebdp.streambatch.model.TaskType.BATCH_PIPELINE.getCode();
    }

    @Override
    public TaskChannel createChannel() {
        log.debug("创建 BatchPipelineTaskChannel 实例");
        return new BatchPipelineTaskChannel(client, config);
    }
}
