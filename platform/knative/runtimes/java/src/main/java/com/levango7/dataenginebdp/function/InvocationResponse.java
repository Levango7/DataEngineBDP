package com.levango7.dataenginebdp.function;

import java.util.Map;
import java.util.HashMap;

/**
 * 函数调用响应 DTO.
 */
public class InvocationResponse {

    /** 响应状态（success/error）. */
    private final String status;

    /** HTTP 状态码. */
    private final int statusCode;

    /** 响应体. */
    private final Map<String, Object> body;

    /**
     * 构造函数.
     *
     * @param status     响应状态
     * @param statusCode HTTP 状态码
     * @param body       响应体
     */
    public InvocationResponse(final String status,
                              final int statusCode,
                              final Map<String, Object> body) {
        this.status = status;
        this.statusCode = statusCode;
        this.body = body != null ? body : new HashMap<>();
    }

    /** 成功响应工厂方法. */
    public static InvocationResponse success(final Map<String, Object> body) {
        return new InvocationResponse("success", 200, body);
    }

    /** 错误响应工厂方法. */
    public static InvocationResponse error(final String message, final int statusCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        return new InvocationResponse("error", statusCode, body);
    }

    public String getStatus() {
        return status;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Map<String, Object> getBody() {
        return body;
    }
}