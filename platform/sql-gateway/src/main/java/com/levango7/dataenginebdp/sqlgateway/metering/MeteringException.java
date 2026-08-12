package com.levango7.dataenginebdp.sqlgateway.metering;

/**
 * 查询计量收集中出现的异常（不计入查询主链路）。
 */
public class MeteringException extends RuntimeException {

    public MeteringException(String message, Throwable cause) {
        super(message, cause);
    }

    public MeteringException(String message) {
        super(message);
    }
}