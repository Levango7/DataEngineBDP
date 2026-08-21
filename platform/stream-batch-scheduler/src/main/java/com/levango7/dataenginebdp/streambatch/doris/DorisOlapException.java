package com.levango7.dataenginebdp.streambatch.doris;

/**
 * Doris OLAP 查询异常。
 *
 * <p>封装 Doris FE HTTP API 调用过程中的所有异常情况：
 * HTTP 非 200、JSON 解析失败、网络异常、Doris 返回错误码等。
 */
public class DorisOlapException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * 构造异常。
     *
     * @param message 异常信息
     */
    public DorisOlapException(String message) {
        super(message);
    }

    /**
     * 构造异常（含原因）。
     *
     * @param message 异常信息
     * @param cause   原因异常
     */
    public DorisOlapException(String message, Throwable cause) {
        super(message, cause);
    }
}