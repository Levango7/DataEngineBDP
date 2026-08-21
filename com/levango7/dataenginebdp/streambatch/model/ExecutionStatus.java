package com.shuqing.bigdata.streambatch.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 任务执行状态枚举。
 */
public enum ExecutionStatus {
    /** 待执行。 */
    PENDING("PENDING"),
    /** 运行中。 */
    RUNNING("RUNNING"),
    /** 成功。 */
    SUCCESS("SUCCESS"),
    /** 失败。 */
    FAILED("FAILED"),
    /** 跳过（上游失败）。 */
    SKIPPED("SKIPPED"),
    /** 取消。 */
    CANCELLED("CANCELLED");

    private final String code;

    ExecutionStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static ExecutionStatus fromCode(String code) {
        for (ExecutionStatus s : values()) {
            if (s.code.equalsIgnoreCase(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的执行状态: " + code);
    }
}