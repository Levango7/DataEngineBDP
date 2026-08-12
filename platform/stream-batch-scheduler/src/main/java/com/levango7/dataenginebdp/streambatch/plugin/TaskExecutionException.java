package com.levango7.dataenginebdp.streambatch.plugin;

/**
 * 任务执行异常。
 */
public class TaskExecutionException extends Exception {

    private static final long serialVersionUID = 1L;

    public TaskExecutionException(String message) {
        super(message);
    }

    public TaskExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}