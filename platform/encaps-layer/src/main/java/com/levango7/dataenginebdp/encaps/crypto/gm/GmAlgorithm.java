package com.levango7.dataenginebdp.encaps.crypto.gm;

/**
 * 国密算法常量定义。
 *
 * <p>集中管理 SM2/SM3/SM4 算法的名称、密钥长度、摘要长度、块大小等常量，
 * 供 {@link SM2Provider}/{@link SM3Provider}/{@link SM4Provider} 及测试引用。</p>
 *
 * <h3>国标对照</h3>
 * <ul>
 *   <li>SM2 — GB/T 32918《信息安全技术 SM2 椭圆曲线公钥密码算法》</li>
 *   <li>SM3 — GB/T 32905《信息安全技术 SM3 密码杂凑算法》</li>
 *   <li>SM4 — GB/T 32907《信息安全技术 SM4 分组密码算法》</li>
 * </ul>
 */
public final class GmAlgorithm {

    private GmAlgorithm() {
        throw new UnsupportedOperationException("Constants class, no instance");
    }

    // ===== SM2（GB/T 32918） =====

    /** SM2 算法名 */
    public static final String SM2 = "SM2";

    /** SM2withSM3 签名算法名（GB/T 32918.2 默认使用 SM3 作为摘要） */
    public static final String SM2_WITH_SM3 = "SM3withSM2";

    /** SM2 椭圆曲线名（GB/T 32918.5 推荐曲线 sm2p256v1） */
    public static final String SM2_CURVE = "sm2p256v1";

    /** SM2 公钥点编码长度（未压缩 04||X||Y，X/Y 各 32 字节，共 65 字节） */
    public static final int SM2_PUBLIC_KEY_LEN = 65;

    /** SM2 私钥大数编码长度（32 字节） */
    public static final int SM2_PRIVATE_KEY_LEN = 32;

    // ===== SM3（GB/T 32905） =====

    /** SM3 算法名 */
    public static final String SM3 = "SM3";

    /** SM3 摘要输出长度（256 bit = 32 byte） */
    public static final int SM3_DIGEST_LEN = 32;

    /** SM3 分组长度（512 bit = 64 byte） */
    public static final int SM3_BLOCK_LEN = 64;

    // ===== SM4（GB/T 32907） =====

    /** SM4 算法名 */
    public static final String SM4 = "SM4";

    /** SM4 分组长度（128 bit = 16 byte） */
    public static final int SM4_BLOCK_LEN = 16;

    /** SM4 密钥长度（128 bit = 16 byte） */
    public static final int SM4_KEY_LEN = 16;

    /** SM4 ECB 模式 */
    public static final String SM4_MODE_ECB = "ECB";

    /** SM4 CBC 模式 */
    public static final String SM4_MODE_CBC = "CBC";

    /** PKCS7 填充名 */
    public static final String PADDING_PKCS7 = "PKCS7Padding";

    /** 无填充 */
    public static final String PADDING_NONE = "NoPadding";
}