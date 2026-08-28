package com.levango7.dataenginebdp.sqlgateway.crosssource;

/**
 * 租户身份不一致异常。
 *
 * <p>请求显式携带的租户（body/header）与 JWT claim 中的 {@code tenantId} 不一致时抛出，
 * 由 Controller 统一转换为 HTTP 403。语义见 CONVENTIONS §9.5：</p>
 * <ul>
 *   <li>租户上下文一律以 JWT claim 为准；</li>
 *   <li>请求显式携带租户与 claim 不一致时必须返回 403。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public class TenantMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** JWT claim 中的租户 ID（用户真实所属） */
    private final String jwtTenantId;
    /** 请求显式携带的租户 ID（疑似越权目标） */
    private final String requestedTenantId;

    public TenantMismatchException(String jwtTenantId, String requestedTenantId) {
        super(String.format("请求携带租户 %s 与 JWT claim 租户 %s 不一致", requestedTenantId, jwtTenantId));
        this.jwtTenantId = jwtTenantId;
        this.requestedTenantId = requestedTenantId;
    }

    public String getJwtTenantId() {
        return jwtTenantId;
    }

    public String getRequestedTenantId() {
        return requestedTenantId;
    }
}
