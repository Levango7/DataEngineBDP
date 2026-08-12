package com.levango7.dataenginebdp.rule.engine.orchestrator.alert;

/**
 * 告警通道接口。
 *
 * <p>每种通道（邮件、Webhook、IM 等）实现该接口，由 {@link AlertManager} 统一调度。
 * 通道实现应自行捕获异常并返回布尔结果，避免单通道失败影响其他通道分发。</p>
 */
public interface AlertChannel {

    /**
     * 发送告警事件。
     *
     * @param event 告警事件
     * @return 发送成功返回 true，失败返回 false
     */
    boolean send(AlertEvent event);

    /**
     * 通道名称，用于日志与配置开关识别。
     *
     * @return 通道名
     */
    String name();
}