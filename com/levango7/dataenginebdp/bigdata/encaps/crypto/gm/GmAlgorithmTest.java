package com.shuqing.bigdata.encaps.crypto.gm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GmAlgorithm} 单元测试。
 *
 * <p>验证国密算法常量定义的正确性，并覆盖私有构造函数的防御性异常。</p>
 */
class GmAlgorithmTest {

    @Test
    @DisplayName("SM2 常量 — 算法名/曲线/密钥长度")
    void sm2_constants_shouldMatchSpec() {
        assertThat(GmAlgorithm.SM2).isEqualTo("SM2");
        assertThat(GmAlgorithm.SM2_WITH_SM3).isEqualTo("SM3withSM2");
        assertThat(GmAlgorithm.SM2_CURVE).isEqualTo("sm2p256v1");
        assertThat(GmAlgorithm.SM2_PUBLIC_KEY_LEN).isEqualTo(65);
        assertThat(GmAlgorithm.SM2_PRIVATE_KEY_LEN).isEqualTo(32);
    }

    @Test
    @DisplayName("SM3 常量 — 算法名/摘要长度/分组长度")
    void sm3_constants_shouldMatchSpec() {
        assertThat(GmAlgorithm.SM3).isEqualTo("SM3");
        assertThat(GmAlgorithm.SM3_DIGEST_LEN).isEqualTo(32);
        assertThat(GmAlgorithm.SM3_BLOCK_LEN).isEqualTo(64);
    }

    @Test
    @DisplayName("SM4 常量 — 算法名/分组/密钥长度/模式/填充")
    void sm4_constants_shouldMatchSpec() {
        assertThat(GmAlgorithm.SM4).isEqualTo("SM4");
        assertThat(GmAlgorithm.SM4_BLOCK_LEN).isEqualTo(16);
        assertThat(GmAlgorithm.SM4_KEY_LEN).isEqualTo(16);
        assertThat(GmAlgorithm.SM4_MODE_ECB).isEqualTo("ECB");
        assertThat(GmAlgorithm.SM4_MODE_CBC).isEqualTo("CBC");
        assertThat(GmAlgorithm.PADDING_PKCS7).isEqualTo("PKCS7Padding");
        assertThat(GmAlgorithm.PADDING_NONE).isEqualTo("NoPadding");
    }

    @Test
    @DisplayName("私有构造函数 — 反射调用抛 UnsupportedOperationException（常量类不可实例化）")
    void privateConstructor_shouldThrowUnsupportedOperationException() throws Exception {
        java.lang.reflect.Constructor<GmAlgorithm> ctor = GmAlgorithm.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThatThrownBy(ctor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasRootCauseInstanceOf(UnsupportedOperationException.class);
    }
}