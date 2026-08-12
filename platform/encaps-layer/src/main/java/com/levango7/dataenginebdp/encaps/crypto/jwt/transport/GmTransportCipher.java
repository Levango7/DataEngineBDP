package com.levango7.dataenginebdp.encaps.crypto.jwt.transport;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.gm.GmAlgorithm;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM2Provider;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM4Provider;

import java.nio.charset.StandardCharsets;

/**
 * 国密传输加密（SM2+SM4 数字信封）。
 *
 * <p>基于 T022 双栈的 {@link SM2Provider} 与 {@link SM4Provider} 实现数字信封：</p>
 * <ol>
 *   <li>加密：生成随机 SM4 会话密钥与 IV → SM4-CBC 加密明文 → SM2 加密会话密钥 → 组装信封</li>
 *   <li>解密：拆解信封 → SM2 解密会话密钥 → SM4-CBC 解密密文 → 返回明文</li>
 * </ol>
 *
 * <h3>密钥约定</h3>
 * <ul>
 *   <li>发送方加密：仅需接收方 SM2 公钥（{@code publicKeyQ}）</li>
 *   <li>接收方解密：仅需自身 SM2 私钥（{@code privateKeyD}）</li>
 * </ul>
 *
 * <p>本类同时持有公钥与私钥，支持双向加解密（典型用于服务间双向通信）。
 * 若仅需单向加密，可只注入公钥（私钥传 null）；反之亦然。</p>
 *
 * <h3>线程安全</h3>
 * <p>线程安全（{@link SM2Provider}/{@link SM4Provider} 线程安全，密钥只读）。</p>
 */
public class GmTransportCipher implements TransportCipher {

    /** 算法标识 */
    public static final String ALGORITHM = "SM2+SM4-CBC";

    private final SM2Provider sm2;
    private final SM4Provider sm4;
    private final byte[] publicKeyQ;
    private final byte[] privateKeyD;

    /**
     * 双向构造（同时持有公钥与私钥）。
     *
     * @param publicKeyQ SM2 公钥点编码（65 字节）；可为 null（仅解密场景）
     * @param privateKeyD SM2 私钥 D 值（32 字节）；可为 null（仅加密场景）
     * @throws CryptoException 两者都为 null
     */
    public GmTransportCipher(byte[] publicKeyQ, byte[] privateKeyD) {
        this(new SM2Provider(), new SM4Provider(), publicKeyQ, privateKeyD);
    }

    /**
     * 仅加密构造（仅持有公钥）。
     *
     * @param publicKeyQ SM2 公钥
     */
    public GmTransportCipher(byte[] publicKeyQ) {
        this(publicKeyQ, null);
    }

    /**
     * 注入构造（便于测试与自定义）。
     *
     * @param sm2        SM2 算法实现
     * @param sm4        SM4 算法实现
     * @param publicKeyQ SM2 公钥；可为 null
     * @param privateKeyD SM2 私钥；可为 null
     */
    public GmTransportCipher(SM2Provider sm2, SM4Provider sm4, byte[] publicKeyQ, byte[] privateKeyD) {
        if (sm2 == null || sm4 == null) {
            throw new CryptoException("sm2 and sm4 must not be null");
        }
        if (publicKeyQ == null && privateKeyD == null) {
            throw new CryptoException("at least one of publicKeyQ/privateKeyD must be non-null");
        }
        this.sm2 = sm2;
        this.sm4 = sm4;
        this.publicKeyQ = publicKeyQ == null ? null : publicKeyQ.clone();
        this.privateKeyD = privateKeyD == null ? null : privateKeyD.clone();
    }

    @Override
    public DigitalEnvelope encrypt(byte[] plaintext) {
        if (plaintext == null) {
            throw new CryptoException("plaintext must not be null");
        }
        if (publicKeyQ == null) {
            throw new CryptoException("publicKeyQ not configured, cannot encrypt");
        }
        // 1. 生成一次性 SM4 会话密钥与 IV
        byte[] sessionKey = sm4.generateKey();
        byte[] iv = sm4.generateIv();
        // 2. SM4-CBC 加密明文
        byte[] ciphertext = sm4.encrypt(plaintext, sessionKey, GmAlgorithm.SM4_MODE_CBC, iv);
        // 3. SM2 加密会话密钥
        byte[] encryptedSessionKey = sm2.encrypt(sessionKey, publicKeyQ);
        // 4. 组装信封
        return new DigitalEnvelope(iv, encryptedSessionKey, ciphertext);
    }

    @Override
    public byte[] decrypt(DigitalEnvelope envelope) {
        if (envelope == null) {
            throw new CryptoException("envelope must not be null");
        }
        if (privateKeyD == null) {
            throw new CryptoException("privateKeyD not configured, cannot decrypt");
        }
        // 1. SM2 解密会话密钥
        byte[] sessionKey = sm2.decrypt(envelope.getEncryptedSessionKey(), privateKeyD);
        if (sessionKey.length != GmAlgorithm.SM4_KEY_LEN) {
            throw new CryptoException("invalid session key length: " + sessionKey.length);
        }
        // 2. SM4-CBC 解密密文
        return sm4.decrypt(envelope.getCiphertext(), sessionKey, GmAlgorithm.SM4_MODE_CBC, envelope.getIv());
    }

    @Override
    public String encryptString(String plaintext) {
        if (plaintext == null) {
            throw new CryptoException("plaintext must not be null");
        }
        return encrypt(plaintext.getBytes(StandardCharsets.UTF_8)).toBase64();
    }

    @Override
    public String decryptString(String envelopeBase64) {
        if (envelopeBase64 == null) {
            throw new CryptoException("envelopeBase64 must not be null");
        }
        DigitalEnvelope envelope = DigitalEnvelope.fromBase64(envelopeBase64);
        byte[] plain = decrypt(envelope);
        return new String(plain, StandardCharsets.UTF_8);
    }

    @Override
    public String getAlgorithm() {
        return ALGORITHM;
    }

    @Override
    public boolean isGm() {
        return true;
    }

    /**
     * 获取内部 SM2 Provider。
     *
     * @return SM2Provider 实例
     */
    public SM2Provider getSm2() {
        return sm2;
    }

    /**
     * 获取内部 SM4 Provider。
     *
     * @return SM4Provider 实例
     */
    public SM4Provider getSm4() {
        return sm4;
    }
}