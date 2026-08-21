package com.shuqing.bigdata.encaps.crypto.gm;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

/**
 * Bouncy Castle JCA Provider 注册工具。
 *
 * <p>确保 Bouncy Castle 作为 JCA Provider 注册到 {@link Security}，
 * 供 SM2/SM3/SM4 的 JCE API（{@link javax.crypto.Cipher} 等）使用。</p>
 *
 * <p>幂等：多次调用安全；通过静态初始化块自动注册。</p>
 */
final class BcProviderHolder {

    static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(PROVIDER);
        }
    }

    private BcProviderHolder() {
    }

    /**
     * 确保 BC Provider 已注册（触发静态初始化）。
     */
    static void ensureRegistered() {
        // 触发静态块
    }
}