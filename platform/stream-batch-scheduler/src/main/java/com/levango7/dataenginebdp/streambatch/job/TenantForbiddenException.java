package com.levango7.dataenginebdp.streambatch.job;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 租户越权访问异常（R8 安全修复）。
 *
 * <p>当 {@link JobService} 从 {@link com.levango7.dataenginebdp.common.security.TenantContext}
 * 取不到租户 ID，或目标资源不属于当前租户时抛出，映射为 HTTP 403。</p>
 *
 * <p>注意：消息体使用通用文案，不回显内部资源标识，避免越权信息泄露。</p>
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class TenantForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TenantForbiddenException(String message) {
        super(message);
    }

    public TenantForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}