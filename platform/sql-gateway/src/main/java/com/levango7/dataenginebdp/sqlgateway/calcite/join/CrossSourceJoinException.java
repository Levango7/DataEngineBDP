package com.levango7.dataenginebdp.sqlgateway.calcite.join;

/**
 * 跨源 Join 执行异常。
 */
public class CrossSourceJoinException extends RuntimeException {
    public CrossSourceJoinException(String message) {
        super(message);
    }

    public CrossSourceJoinException(String message, Throwable cause) {
        super(message, cause);
    }
}
