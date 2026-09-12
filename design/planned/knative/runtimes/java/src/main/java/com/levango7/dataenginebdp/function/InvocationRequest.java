package com.levango7.dataenginebdp.function;

import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

/**
 * 函数调用请求 DTO.
 *
 * <p>封装 Knative Service 收到的 invocation 请求信息。</p>
 */
public class InvocationRequest {

    /** 租户 ID（用于计量隔离）. */
    private final String tenantId;

    /** 函数名. */
    private final String functionName;

    /** 调用事件（任意 JSON）. */
    private final Map<String, Object> event;

    /**
     * 构造函数.
     *
     * @param tenantId    租户 ID
     * @param functionName 函数名
     * @param event       调用事件
     */
    public InvocationRequest(final String tenantId,
                             final String functionName,
                             final Map<String, Object> event) {
        this.tenantId = tenantId;
        this.functionName = functionName;
        this.event = event != null ? event : Collections.emptyMap();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getFunctionName() {
        return functionName;
    }

    public Map<String, Object> getEvent() {
        return event;
    }
}