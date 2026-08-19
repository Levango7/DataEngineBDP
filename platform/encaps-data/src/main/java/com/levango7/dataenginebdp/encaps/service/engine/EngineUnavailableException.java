package com.levango7.dataenginebdp.encaps.service.engine;

/**
 * 引擎不可用异常。
 *
 * <p>当外部引擎（Flink/Doris/Kafka/IoTDB）连接失败、驱动缺失或返回错误时抛出。
 * Controller 捕获后转换为 HTTP 503 Service Unavailable，向前端返回友好提示。</p>
 */
public class EngineUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造引擎不可用异常。
     *
     * @param message 错误描述
     */
    public EngineUnavailableException(String message) {
        super(message);
    }

    /**
     * 构造引擎不可用异常（带原因）。
     *
     * @param message 错误描述
     * @param cause   根因异常
     */
    public EngineUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}