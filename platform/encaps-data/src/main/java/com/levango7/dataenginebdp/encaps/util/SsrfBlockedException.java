package com.levango7.dataenginebdp.encaps.util;

/**
 * SSRF 防护拦截异常。
 *
 * <p>当目标地址落入禁止访问的网段（内网/环回/链路本地等）时抛出。
 * Controller 层捕获后应返回 400 Bad Request。</p>
 */
public class SsrfBlockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 被拦截的目标主机 */
    private final String host;

    /**
     * 构造 SSRF 拦截异常。
     *
     * @param host    被拦截的目标主机
     * @param message 拦截原因
     */
    public SsrfBlockedException(String host, String message) {
        super(message);
        this.host = host;
    }

    /**
     * 获取被拦截的目标主机。
     *
     * @return 主机名或 IP
     */
    public String getHost() {
        return host;
    }
}