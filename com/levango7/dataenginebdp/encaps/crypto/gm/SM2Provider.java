package com.shuqing.bigdata.encaps.crypto.gm;

import com.shuqing.bigdata.encaps.crypto.CryptoException;
import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.engines.SM2Engine;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;

/**
 * SM2 椭圆曲线公钥密码算法 Provider（GB/T 32918）。
 *
 * <p>基于 Bouncy Castle 实现，覆盖：</p>
 * <ul>
 *   <li>数字签名/验签 — GB/T 32918.2，默认使用 SM3 作为摘要（SM3withSM2）</li>
 *   <li>非对称加密/解密 — GB/T 32918.4，默认使用 SM3 作为 KDF 摘要</li>
 *   <li>密钥对生成 — GB/T 32918.5 推荐曲线 sm2p256v1（256 bit）</li>
 * </ul>
 *
 * <h3>密钥格式</h3>
 * <ul>
 *   <li>私钥：32 字节大数（D 值）</li>
 *   <li>公钥：65 字节未压缩点编码（04||X||Y）</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>无共享可变状态，线程安全。</p>
 */
public class SM2Provider {

    /** SM2 推荐曲线参数（sm2p256v1） */
    private static final X9ECParameters CURVE_PARAMS = GMNamedCurves.getByName("sm2p256v1");
    private static final ECDomainParameters DOMAIN_PARAMS = new ECDomainParameters(
            CURVE_PARAMS.getCurve(), CURVE_PARAMS.getG(), CURVE_PARAMS.getN(), CURVE_PARAMS.getH());

    static {
        // 确保 BC JCA Provider 已注册（供 generateJcaKeyPair 使用）
        BcProviderHolder.ensureRegistered();
    }

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * SM2 数字签名（SM3withSM2）。
     *
     * @param data       待签名数据
     * @param privateKeyD 私钥 D 值（32 字节大数）
     * @return 签名值（DER 编码）
     * @throws CryptoException 参数非法或签名失败
     */
    public byte[] sign(byte[] data, byte[] privateKeyD) {
        if (data == null) {
            throw new CryptoException("data must not be null");
        }
        ECPrivateKeyParameters priv = toPrivateKey(privateKeyD);
        try {
            SM2Signer signer = new SM2Signer();
            signer.init(true, new ParametersWithRandom(priv, secureRandom));
            signer.update(data, 0, data.length);
            return signer.generateSignature();
        } catch (Exception e) {
            throw new CryptoException("SM2 sign failed", e);
        }
    }

    /**
     * SM2 验签（SM3withSM2）。
     *
     * @param data        原始数据
     * @param signature   签名值（DER 编码）
     * @param publicKeyQ  公钥点编码（未压缩 65 字节或压缩 33 字节）
     * @return 验签通过返回 true
     * @throws CryptoException 参数非法
     */
    public boolean verify(byte[] data, byte[] signature, byte[] publicKeyQ) {
        if (data == null || signature == null) {
            throw new CryptoException("data and signature must not be null");
        }
        ECPublicKeyParameters pub = toPublicKey(publicKeyQ);
        try {
            SM2Signer signer = new SM2Signer();
            signer.init(false, pub);
            signer.update(data, 0, data.length);
            return signer.verifySignature(signature);
        } catch (Exception e) {
            throw new CryptoException("SM2 verify failed", e);
        }
    }

    /**
     * SM2 加密（C1||C3||C2 格式，SM3 作为 KDF 摘要）。
     *
     * @param plaintext 明文
     * @param publicKeyQ 公钥点编码
     * @return 密文（C1||C3||C2）
     * @throws CryptoException 参数非法或加密失败
     */
    public byte[] encrypt(byte[] plaintext, byte[] publicKeyQ) {
        if (plaintext == null) {
            throw new CryptoException("plaintext must not be null");
        }
        // SM2Engine 对空明文处理不稳定，空明文直接返回空密文（解密侧对称处理）
        if (plaintext.length == 0) {
            return new byte[0];
        }
        ECPublicKeyParameters pub = toPublicKey(publicKeyQ);
        try {
            SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
            engine.init(true, new ParametersWithRandom(pub, secureRandom));
            return engine.processBlock(plaintext, 0, plaintext.length);
        } catch (Exception e) {
            throw new CryptoException("SM2 encrypt failed", e);
        }
    }

    /**
     * SM2 解密（C1||C3||C2 格式）。
     *
     * @param ciphertext 密文
     * @param privateKeyD 私钥 D 值
     * @return 明文
     * @throws CryptoException 参数非法或解密失败
     */
    public byte[] decrypt(byte[] ciphertext, byte[] privateKeyD) {
        if (ciphertext == null) {
            throw new CryptoException("ciphertext must not be null");
        }
        // 空密文对应空明文（与 encrypt 空明文处理对称）
        if (ciphertext.length == 0) {
            return new byte[0];
        }
        ECPrivateKeyParameters priv = toPrivateKey(privateKeyD);
        try {
            SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
            engine.init(false, priv);
            byte[] output = engine.processBlock(ciphertext, 0, ciphertext.length);
            return output;
        } catch (Exception e) {
            throw new CryptoException("SM2 decrypt failed", e);
        }
    }

    /**
     * 生成 SM2 密钥对。
     *
     * @return 密钥对：privateKeyD（32 字节）、publicKeyQ（65 字节未压缩）
     */
    public Sm2KeyPair generateKeyPair() {
        try {
            ECKeyPairGenerator generator = new ECKeyPairGenerator();
            ECKeyGenerationParameters genParams = new ECKeyGenerationParameters(DOMAIN_PARAMS, secureRandom);
            generator.init(genParams);
            AsymmetricCipherKeyPair pair = generator.generateKeyPair();

            ECPrivateKeyParameters priv = (ECPrivateKeyParameters) pair.getPrivate();
            ECPublicKeyParameters pub = (ECPublicKeyParameters) pair.getPublic();

            byte[] d = priv.getD().toByteArray();
            byte[] privateKeyD = normalizeToLength(d, GmAlgorithm.SM2_PRIVATE_KEY_LEN);
            byte[] publicKeyQ = pub.getQ().getEncoded(false);
            return new Sm2KeyPair(privateKeyD, publicKeyQ);
        } catch (Exception e) {
            throw new CryptoException("SM2 generate keypair failed", e);
        }
    }

    /**
     * 生成 java.security.KeyPair（用于 SPI 接口兼容）。
     *
     * @return KeyPair
     */
    public KeyPair generateJcaKeyPair() {
        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("EC", "BC");
            ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("sm2p256v1");
            kpg.initialize(spec, secureRandom);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new CryptoException("SM2 generate JCA keypair failed", e);
        }
    }

    /**
     * 从 java.security.PrivateKey 提取 SM2 私钥 D 值。
     *
     * @param privateKey JCA 私钥
     * @return 32 字节 D 值
     */
    public byte[] extractD(PrivateKey privateKey) {
        if (privateKey == null) {
            throw new CryptoException("privateKey must not be null");
        }
        try {
            java.security.interfaces.ECPrivateKey ecKey = (java.security.interfaces.ECPrivateKey) privateKey;
            byte[] d = ecKey.getS().toByteArray();
            return normalizeToLength(d, GmAlgorithm.SM2_PRIVATE_KEY_LEN);
        } catch (Exception e) {
            throw new CryptoException("extract SM2 D failed", e);
        }
    }

    /**
     * 从 java.security.PublicKey 提取 SM2 公钥点编码。
     *
     * @param publicKey JCA 公钥
     * @return 65 字节未压缩点编码
     */
    public byte[] extractQ(PublicKey publicKey) {
        if (publicKey == null) {
            throw new CryptoException("publicKey must not be null");
        }
        try {
            // 优先使用 BC 的 BCECPublicKey API 直接获取 Q 点
            if (publicKey instanceof org.bouncycastle.jce.interfaces.ECPublicKey) {
                org.bouncycastle.jce.interfaces.ECPublicKey bcKey =
                        (org.bouncycastle.jce.interfaces.ECPublicKey) publicKey;
                return bcKey.getQ().getEncoded(false);
            }
            // 回退：JCA ECPublicKey.getEncoded() 通常返回未压缩格式（04||X||Y）
            java.security.interfaces.ECPublicKey ecKey = (java.security.interfaces.ECPublicKey) publicKey;
            byte[] encoded = ecKey.getEncoded();
            if (encoded != null && encoded.length > 0 && encoded[0] == 0x04) {
                return encoded;
            }
            // 压缩格式，用 BC 曲线解码后输出未压缩格式
            ECPoint q = DOMAIN_PARAMS.getCurve().decodePoint(encoded);
            return q.getEncoded(false);
        } catch (Exception e) {
            throw new CryptoException("extract SM2 Q failed", e);
        }
    }

    // ===== 内部辅助 =====

    /**
     * 字节数组 → ECPrivateKeyParameters。
     *
     * <p>私钥 D 值按无符号大数解析（new BigInteger(1, d)），避免前导零被误判为负号。</p>
     */
    private ECPrivateKeyParameters toPrivateKey(byte[] d) {
        if (d == null) {
            throw new CryptoException("privateKey must not be null");
        }
        try {
            java.math.BigInteger bigD = new java.math.BigInteger(1, d);
            if (bigD.signum() <= 0) {
                throw new CryptoException("SM2 private key D must be positive");
            }
            return new ECPrivateKeyParameters(bigD, DOMAIN_PARAMS);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("invalid SM2 private key", e);
        }
    }

    /**
     * 字节数组 → ECPublicKeyParameters。
     */
    private ECPublicKeyParameters toPublicKey(byte[] q) {
        if (q == null) {
            throw new CryptoException("publicKey must not be null");
        }
        try {
            ECPoint point = DOMAIN_PARAMS.getCurve().decodePoint(q);
            return new ECPublicKeyParameters(point, DOMAIN_PARAMS);
        } catch (Exception e) {
            throw new CryptoException("invalid SM2 public key", e);
        }
    }

    /**
     * 将大数 byte[] 规范化为定长（左侧补零或截断）。
     */
    private static byte[] normalizeToLength(byte[] input, int targetLen) {
        if (input.length == targetLen) {
            return input;
        }
        byte[] result = new byte[targetLen];
        if (input.length > targetLen) {
            // 去除前导零
            int srcPos = input.length - targetLen;
            System.arraycopy(input, srcPos, result, 0, targetLen);
        } else {
            // 左补零
            int destPos = targetLen - input.length;
            System.arraycopy(input, 0, result, destPos, input.length);
        }
        return result;
    }

    /**
     * SM2 密钥对（裸 byte[] 形式）。
     */
    public static final class Sm2KeyPair {
        /** 私钥 D 值（32 字节） */
        private final byte[] privateKeyD;
        /** 公钥点编码（65 字节未压缩） */
        private final byte[] publicKeyQ;

        Sm2KeyPair(byte[] privateKeyD, byte[] publicKeyQ) {
            this.privateKeyD = privateKeyD;
            this.publicKeyQ = publicKeyQ;
        }

        public byte[] getPrivateKeyD() {
            return privateKeyD;
        }

        public byte[] getPublicKeyQ() {
            return publicKeyQ;
        }
    }
}