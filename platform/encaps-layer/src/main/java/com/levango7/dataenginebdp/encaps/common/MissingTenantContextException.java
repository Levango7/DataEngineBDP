package com.levango7.dataenginebdp.encaps.common;

/**
 * 缺少租户上下文异常。
 *
 * <p>当请求缺少租户上下文（TenantContext 为空）时抛出，
 * 由 {@link GlobalExceptionHandler} 映射为 HTTP 403 Forbidden。
 *
 * <p>此前使用 {@link IllegalStateException} 表示此场景，被误映射为 409 Conflict；
 * 独立异常类型保证语义精确——缺少租户上下文是鉴权问题（403）而非资源冲突（409）。</p>
 */
public class MissingTenantContextException extends RuntimeException {

    public MissingTenantContextException() {
        super("缺少租户上下文");
    }

    public MissingTenantContextException(String message) {
        super(message);
    }
}