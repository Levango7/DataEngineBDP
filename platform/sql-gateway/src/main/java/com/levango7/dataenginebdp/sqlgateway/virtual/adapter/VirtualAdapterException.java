package com.levango7.dataenginebdp.sqlgateway.virtual.adapter;

/**
 * 虚拟表适配器异常。
 *
 * <p>封装适配器访问外部数据源时发生的各类错误，
 * 携带错误码与原始异常，便于上层统一处理与可观测。</p>
 *
 * @author shuqing-bigdata
 */
public class VirtualAdapterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    /**
     * 构造异常。
     *
     * @param errorCode 错误码（如 {@code MYSQL_CONNECT_FAILED}）
     * @param message   错误消息
     */
    public VirtualAdapterException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 构造异常（含原因）。
     *
     * @param errorCode 错误码
     * @param message   错误消息
     * @param cause     原始异常
     */
    public VirtualAdapterException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public String getErrorCode() {
        return errorCode;
    }
}