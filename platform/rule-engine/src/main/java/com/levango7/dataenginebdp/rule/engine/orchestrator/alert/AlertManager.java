package com.levango7.dataenginebdp.rule.engine.orchestrator.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 告警管理器。
 *
 * <p>统一调度多个 {@link AlertChannel}，将告警事件分发到所有启用的通道。
 * 单通道失败不影响其他通道，最终返回是否至少一个通道成功。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>通过构造函数注入通道列表，Spring 自动收集所有 AlertChannel Bean；</li>
 *   <li>使用同步分发，MVP 阶段告警量小；后续可改为异步队列；</li>
 *   <li>暴露 channels 列表便于测试断言。</li>
 * </ul>
 * </p>
 */
@Component
public class AlertManager {

    private static final Logger log = LoggerFactory.getLogger(AlertManager.class);

    private final List<AlertChannel> channels;

    public AlertManager(List<AlertChannel> channels) {
        this.channels = channels;
    }

    /**
     * 分发告警事件到所有通道。
     *
     * @param event 告警事件
     * @return 至少一个通道发送成功返回 true；无通道或全部失败返回 false
     */
    public boolean dispatch(AlertEvent event) {
        if (event == null) {
            return false;
        }
        if (channels.isEmpty()) {
            log.warn("no alert channel configured, event={} dropped", event.getId());
            return false;
        }
        boolean anySuccess = false;
        for (AlertChannel channel : channels) {
            try {
                boolean ok = channel.send(event);
                if (ok) {
                    anySuccess = true;
                }
            } catch (Exception e) {
                // 单通道异常不影响其他通道
                log.warn("alert channel {} threw exception: {}", channel.name(), e.getMessage());
            }
        }
        return anySuccess;
    }

    /**
     * 返回已注册通道列表（只读）。
     *
     * @return 通道列表
     */
    public List<AlertChannel> getChannels() {
        return List.copyOf(channels);
    }
}