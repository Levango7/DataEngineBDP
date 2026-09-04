package com.levango7.dataenginebdp.encaps.common;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一 API 响应封装。
 *
 * <p>所有 Controller 返回的裸对象经 {@link ApiResponseAdvice} 自动包装为本格式，
 * 与前端 {@code client.ts} 的 {@code ApiResponse<T>} 拆包契约对齐：
 * <pre>
 * {
 *   "code": 0,            // 业务状态码，0 表示成功
 *   "message": "OK",      // 提示消息
 *   "messageKey": null,   // i18n 消息键（A2，失败时携带，前端优先翻译）
 *   "data": T,            // 业务数据
 *   "traceId": "xxx",     // 链路追踪 ID（可空）
 *   "timestamp": 1700000000000  // 服务器时间戳（毫秒）
 * }
 * </pre>
 *
 * @param code       业务状态码，0 表示成功（见 {@link ErrorCode}）
 * @param message    提示消息
 * @param messageKey i18n 消息键（可空；失败响应携带，前端 vue-i18n 翻译用）
 * @param data       业务数据，失败时为 null
 * @param traceId    链路追踪 ID（可空，便于排障关联）
 * @param timestamp  服务器时间戳（毫秒）
 * @param <T>        业务数据类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        int code,
        String message,
        String messageKey,
        T data,
        String traceId,
        long timestamp
) {

    /** 成功业务码 */
    public static final int SUCCESS_CODE = 0;

    /**
     * 成功响应（默认 message=OK，无 traceId）。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "OK", null, data, null, System.currentTimeMillis());
    }

    /**
     * 成功响应（自定义 message）。
     *
     * @param data 业务数据
     * @param msg  自定义提示消息
     * @param <T>  业务数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data, String msg) {
        return new ApiResponse<>(SUCCESS_CODE, msg, null, data, null, System.currentTimeMillis());
    }

    /**
     * 失败响应（无 data）。
     *
     * @param code    错误码（非 0）
     * @param message 错误消息
     * @param <T>     业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, null, null, System.currentTimeMillis());
    }

    /**
     * 失败响应（带 traceId，便于排障关联）。
     *
     * @param code    错误码（非 0）
     * @param message 错误消息
     * @param traceId 链路追踪 ID
     * @param <T>     业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(int code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, null, traceId, System.currentTimeMillis());
    }

    /**
     * 由 {@link ErrorCode} 构造失败响应（A2：自动携带 messageKey 供前端翻译）。
     *
     * @param errorCode 错误码枚举
     * @param <T>       业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(),
                errorCode.getMessageKey(), null, null, System.currentTimeMillis());
    }

    /**
     * 失败响应（错误码枚举 + 拼接细节，A2：messageKey 随枚举携带）。
     *
     * @param errorCode 错误码枚举
     * @param detail    细节信息（拼接到 message 末尾，便于排障）
     * @param <T>       业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String detail) {
        return new ApiResponse<>(errorCode.getCode(),
                errorCode.getMessage() + ": " + detail,
                errorCode.getMessageKey(), null, null, System.currentTimeMillis());
    }

    /**
     * 判断是否成功。
     *
     * <p>record 的访问器方法默认不会被 Jackson 序列化为 JSON 属性，
     * 此处通过 {@link JsonGetter} 显式暴露为 {@code success} 字段，
     * 与前端 {@code client.ts} 的 {@code ApiResponse<T>.success} 契约对齐。</p>
     *
     * @return true 表示业务成功
     */
    @JsonGetter("success")
    public boolean isSuccess() {
        return code == SUCCESS_CODE;
    }
}