package com.levango7.dataenginebdp.encaps.crypto.intl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IntlAlgorithm} 单元测试。
 *
 * <p>验证国际算法枚举的 JCE 名称、分类判断、字符串解析等。</p>
 */
class IntlAlgorithmTest {

    // ===== JCE 名称 =====

    @Test
    @DisplayName("getJceName — 返回正确的JCE算法名")
    void getJceName_shouldReturnCorrectName() {
        assertThat(IntlAlgorithm.SHA256_WITH_RSA.getJceName()).isEqualTo("SHA256withRSA");
        assertThat(IntlAlgorithm.SHA384_WITH_RSA.getJceName()).isEqualTo("SHA384withRSA");
        assertThat(IntlAlgorithm.SHA512_WITH_RSA.getJceName()).isEqualTo("SHA512withRSA");
        assertThat(IntlAlgorithm.SHA_256.getJceName()).isEqualTo("SHA-256");
        assertThat(IntlAlgorithm.SHA_384.getJceName()).isEqualTo("SHA-384");
        assertThat(IntlAlgorithm.SHA_512.getJceName()).isEqualTo("SHA-512");
        assertThat(IntlAlgorithm.AES_GCM.getJceName()).isEqualTo("AES/GCM/NoPadding");
        assertThat(IntlAlgorithm.AES_CBC.getJceName()).isEqualTo("AES/CBC/PKCS5Padding");
    }

    // ===== 输出长度 =====

    @Test
    @DisplayName("getOutputLength — 返回正确长度")
    void getOutputLength_shouldReturnCorrectLength() {
        assertThat(IntlAlgorithm.SHA_256.getOutputLength()).isEqualTo(32);
        assertThat(IntlAlgorithm.SHA_384.getOutputLength()).isEqualTo(48);
        assertThat(IntlAlgorithm.SHA_512.getOutputLength()).isEqualTo(64);
        assertThat(IntlAlgorithm.AES_GCM.getOutputLength()).isEqualTo(12);
        assertThat(IntlAlgorithm.AES_CBC.getOutputLength()).isEqualTo(16);
    }

    // ===== 分类判断 =====

    @Test
    @DisplayName("isDigest — 正确判断SHA摘要算法")
    void isDigest_shouldWork() {
        assertThat(IntlAlgorithm.SHA_256.isDigest()).isTrue();
        assertThat(IntlAlgorithm.SHA_384.isDigest()).isTrue();
        assertThat(IntlAlgorithm.SHA_512.isDigest()).isTrue();
        assertThat(IntlAlgorithm.SHA256_WITH_RSA.isDigest()).isFalse();
        assertThat(IntlAlgorithm.AES_GCM.isDigest()).isFalse();
    }

    @Test
    @DisplayName("isRsaSign — 正确判断RSA签名算法")
    void isRsaSign_shouldWork() {
        assertThat(IntlAlgorithm.SHA256_WITH_RSA.isRsaSign()).isTrue();
        assertThat(IntlAlgorithm.SHA384_WITH_RSA.isRsaSign()).isTrue();
        assertThat(IntlAlgorithm.SHA512_WITH_RSA.isRsaSign()).isTrue();
        assertThat(IntlAlgorithm.SHA_256.isRsaSign()).isFalse();
        assertThat(IntlAlgorithm.AES_GCM.isRsaSign()).isFalse();
    }

    @Test
    @DisplayName("isAesMode — 正确判断AES模式")
    void isAesMode_shouldWork() {
        assertThat(IntlAlgorithm.AES_GCM.isAesMode()).isTrue();
        assertThat(IntlAlgorithm.AES_CBC.isAesMode()).isTrue();
        assertThat(IntlAlgorithm.SHA_256.isAesMode()).isFalse();
        assertThat(IntlAlgorithm.SHA256_WITH_RSA.isAesMode()).isFalse();
    }

    // ===== fromString =====

    @Test
    @DisplayName("fromString — 正确解析已知算法名")
    void fromString_shouldParseKnownAlgorithms() {
        assertThat(IntlAlgorithm.fromString("SHA-256")).isEqualTo(IntlAlgorithm.SHA_256);
        assertThat(IntlAlgorithm.fromString("SHA-384")).isEqualTo(IntlAlgorithm.SHA_384);
        assertThat(IntlAlgorithm.fromString("SHA-512")).isEqualTo(IntlAlgorithm.SHA_512);
        assertThat(IntlAlgorithm.fromString("SHA256withRSA")).isEqualTo(IntlAlgorithm.SHA256_WITH_RSA);
        assertThat(IntlAlgorithm.fromString("AES/GCM/NoPadding")).isEqualTo(IntlAlgorithm.AES_GCM);
        assertThat(IntlAlgorithm.fromString("AES/CBC/PKCS5Padding")).isEqualTo(IntlAlgorithm.AES_CBC);
    }

    @Test
    @DisplayName("fromString — 大小写不敏感")
    void fromString_caseInsensitive_shouldWork() {
        assertThat(IntlAlgorithm.fromString("sha-256")).isEqualTo(IntlAlgorithm.SHA_256);
        assertThat(IntlAlgorithm.fromString("SHA-256")).isEqualTo(IntlAlgorithm.SHA_256);
        assertThat(IntlAlgorithm.fromString("aes/gcm/nopadding")).isEqualTo(IntlAlgorithm.AES_GCM);
    }

    @Test
    @DisplayName("fromString — 未知算法返回null")
    void fromString_unknown_shouldReturnNull() {
        assertThat(IntlAlgorithm.fromString("MD5")).isNull();
        assertThat(IntlAlgorithm.fromString("unknown")).isNull();
    }

    @Test
    @DisplayName("fromString — null/空白返回null")
    void fromString_nullOrBlank_shouldReturnNull() {
        assertThat(IntlAlgorithm.fromString(null)).isNull();
        assertThat(IntlAlgorithm.fromString("")).isNull();
        assertThat(IntlAlgorithm.fromString("  ")).isNull();
    }

    // ===== 描述 =====

    @Test
    @DisplayName("getDescription — 返回非空描述")
    void getDescription_shouldReturnNonEmpty() {
        for (IntlAlgorithm algo : IntlAlgorithm.values()) {
            assertThat(algo.getDescription()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("枚举完整性 — 包含8个算法")
    void values_shouldContain8Algorithms() {
        assertThat(IntlAlgorithm.values()).hasSize(8);
    }
}