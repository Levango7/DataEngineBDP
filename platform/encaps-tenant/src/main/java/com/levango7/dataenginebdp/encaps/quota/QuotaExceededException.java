package com.levango7.dataenginebdp.encaps.quota;

/**
 * 配额超限异常。
 *
 * <p>由 {@link QuotaValidator} 在资源创建前校验时抛出，表示当前用量 + 新请求超过 Workspace 配额。
 * REST 层将其映射为 HTTP 422 Unprocessable Entity。</p>
 */
public class QuotaExceededException extends RuntimeException {

    /** 超限资源键名，如 {@code requests.cpu} */
    private final String resourceKey;
    /** 当前已用量 */
    private final String used;
    /** 配额硬上限 */
    private final String hard;
    /** 新请求量 */
    private final String requested;

    public QuotaExceededException(String resourceKey, String used, String hard, String requested) {
        super(String.format("Quota exceeded for %s: used=%s, hard=%s, requested=%s",
                resourceKey, used, hard, requested));
        this.resourceKey = resourceKey;
        this.used = used;
        this.hard = hard;
        this.requested = requested;
    }

    public QuotaExceededException(String message) {
        super(message);
        this.resourceKey = null;
        this.used = null;
        this.hard = null;
        this.requested = null;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public String getUsed() {
        return used;
    }

    public String getHard() {
        return hard;
    }

    public String getRequested() {
        return requested;
    }
}