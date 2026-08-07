package com.shuqing.bigdata.encaps.crypto;

/**
 * 加密相关异常。
 *
 * <p>封装 SPI 加载、Profile 切换、Provider 调用等场景下的运行时错误，
 * 统一为 unchecked 异常，避免业务层被迫处理大量 checked exception。</p>
 *
 * <p>典型场景：</p>
 * <ul>
 *   <li>ServiceLoader 未找到任何 Provider</li>
 *   <li>指定 Profile 下不存在匹配的 Provider</li>
 *   <li>切换到不存在的 Provider 名称</li>
 *   <li>Provider 内部签名/加密/解密失败</li>
 *   <li>密钥不匹配、算法不支持等</li>
 * </ul>
 */
public class CryptoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造带消息的异常。
     *
     * @param message 异常消息
     */
    public CryptoException(String message) {
        super(message);
    }

    /**
     * 构造带消息和原因的异常。
     *
     * @param message 异常消息
     * @param cause   根因
     */
    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 构造仅带原因的异常。
     *
     * @param cause 根因
     */
    public CryptoException(Throwable cause) {
        super(cause);
    }
}