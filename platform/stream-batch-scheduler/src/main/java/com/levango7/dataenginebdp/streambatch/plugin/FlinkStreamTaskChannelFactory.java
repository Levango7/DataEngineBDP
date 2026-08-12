package com.levango7.dataenginebdp.streambatch.plugin;

import com.levango7.dataenginebdp.streambatch.flink.FlinkStreamSubmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Flink 流任务通道工厂（DolphinScheduler TaskChannelFactory SPI 实现）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlinkStreamTaskChannelFactory implements TaskChannelFactory {

    private final FlinkStreamSubmitter submitter;

    @Override
    public String getChannelType() {
        return com.levango7.dataenginebdp.streambatch.model.TaskType.FLINK_STREAM.getCode();
    }

    @Override
    public TaskChannel createChannel() {
        log.debug("创建 FlinkStreamTaskChannel 实例");
        return new FlinkStreamTaskChannel(submitter);
    }
}