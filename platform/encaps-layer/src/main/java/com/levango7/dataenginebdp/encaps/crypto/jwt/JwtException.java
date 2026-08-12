package com.levango7.dataenginebdp.encaps.crypto.jwt;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;

/**
 * 国密 JWT 相关异常。
 *
 * <p>封装 JWT 签名/验签/解析过程中的所有错误，统一为 unchecked 异常。</p>
 *
 * <h3>典型场景</h3>
 * <ul>
 *   <li>JWT 格式非法（非三段式、Base64 解码失败、JSON 解析失败）</li>
 *   <li>签名验签失败（签名值不匹配、密钥不正确）</li>
 *   <li>声明校验失败（过期、未生效、issuer 不匹配、audience 不匹配）</li>
 *   <li>算法不支持（非 SM3withSM2 / RS256 / HS256）</li>
 * </ul>
 */
public class JwtException extends CryptoException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造带消息的异常。
     *
     * @param message 异常消息
     */
    public JwtException(String message) {
        super(message);
    }

    /**
     * 构造带消息和原因的异常。
     *
     * @param message 异常消息
     * @param cause   根因
     */
    public JwtException(String message, Throwable cause) {
        super(message, cause);
    }
}