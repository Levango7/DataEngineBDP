package com.shuqing.bigdata.encaps.crypto.jwt.transport;

import com.shuqing.bigdata.encaps.crypto.CryptoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DigitalEnvelope} 单元测试。
 *
 * <p>覆盖数字信封序列化/反序列化往返、格式校验、异常处理等。</p>
 */
class DigitalEnvelopeTest {

    private byte[] iv;
    private byte[] encKey;
    private byte[] ciphertext;

    @BeforeEach
    void setUp() {
        SecureRandom random = new SecureRandom();
        iv = new byte[16];
        encKey = new byte[64];
        ciphertext = new byte[128];
        random.nextBytes(iv);
        random.nextBytes(encKey);
        random.nextBytes(ciphertext);
    }

    // ===== 序列化往返 =====

    @Test
    @DisplayName("toBytes → fromBytes 往返")
    void toBytesFromBytes_roundTrip() {
        DigitalEnvelope envelope = new DigitalEnvelope(iv, encKey, ciphertext);
        byte[] bytes = envelope.toBytes();
        DigitalEnvelope restored = DigitalEnvelope.fromBytes(bytes);

        assertThat(restored.getIv()).isEqualTo(iv);
        assertThat(restored.getEncryptedSessionKey()).isEqualTo(encKey);
        assertThat(restored.getCiphertext()).isEqualTo(ciphertext);
    }

    @Test
    @DisplayName("toBase64 → fromBase64 往返")
    void toBase64FromBase64_roundTrip() {
        DigitalEnvelope envelope = new DigitalEnvelope(iv, encKey, ciphertext);
        String base64 = envelope.toBase64();
        DigitalEnvelope restored = DigitalEnvelope.fromBase64(base64);

        assertThat(restored).isEqualTo(envelope);
    }

    @Test
    @DisplayName("toBytes — 包含正确魔数")
    void toBytes_containsMagic() {
        DigitalEnvelope envelope = new DigitalEnvelope(iv, encKey, ciphertext);
        byte[] bytes = envelope.toBytes();
        assertThat(bytes[0]).isEqualTo((byte) 'S');
        assertThat(bytes[1]).isEqualTo((byte) 'Q');
        assertThat(bytes[2]).isEqualTo((byte) 'D');
        assertThat(bytes[3]).isEqualTo((byte) 'E');
    }

    @Test
    @DisplayName("toBytes — 包含正确版本号")
    void toBytes_containsVersion() {
        DigitalEnvelope envelope = new DigitalEnvelope(iv, encKey, ciphertext);
        byte[] bytes = envelope.toBytes();
        assertThat(bytes[4]).isEqualTo(DigitalEnvelope.CURRENT_VERSION);
    }

    @Test
    @DisplayName("toBytes — 包含正确算法 id")
    void toBytes_containsAlgId() {
        DigitalEnvelope envelope = new DigitalEnvelope(iv, encKey, ciphertext);
        byte[] bytes = envelope.toBytes();
        assertThat(bytes[5]).isEqualTo(DigitalEnvelope.ALG_SM2_SM4_CBC);
    }

    // ===== 异常处理 =====

    @Test
    @DisplayName("构造 — null 参数抛异常")
    void constructor_nullArgs_throwsException() {
        assertThatThrownBy(() -> new DigitalEnvelope(null, encKey, ciphertext))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> new DigitalEnvelope(iv, null, ciphertext))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> new DigitalEnvelope(iv, encKey, null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("fromBytes — null 抛异常")
    void fromBytes_null_throwsException() {
        assertThatThrownBy(() -> DigitalEnvelope.fromBytes(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("fromBytes — 过短抛异常")
    void fromBytes_tooShort_throwsException() {
        assertThatThrownBy(() -> DigitalEnvelope.fromBytes(new byte[5]))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("fromBytes — 错误魔数抛异常")
    void fromBytes_wrongMagic_throwsException() {
        byte[] bytes = new DigitalEnvelope(iv, encKey, ciphertext).toBytes();
        bytes[0] = 'X';  // 篡改魔数
        assertThatThrownBy(() -> DigitalEnvelope.fromBytes(bytes))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("magic");
    }

    @Test
    @DisplayName("fromBytes — 不支持的版本抛异常")
    void fromBytes_unsupportedVersion_throwsException() {
        byte[] bytes = new DigitalEnvelope(iv, encKey, ciphertext).toBytes();
        bytes[4] = 99;  // 篡改版本
        assertThatThrownBy(() -> DigitalEnvelope.fromBytes(bytes))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("fromBytes — 不支持的算法 id 抛异常")
    void fromBytes_unsupportedAlgId_throwsException() {
        byte[] bytes = new DigitalEnvelope(iv, encKey, ciphertext).toBytes();
        bytes[5] = 99;  // 篡改算法 id
        assertThatThrownBy(() -> DigitalEnvelope.fromBytes(bytes))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("algorithm id");
    }

    @Test
    @DisplayName("fromBase64 — null 抛异常")
    void fromBase64_null_throwsException() {
        assertThatThrownBy(() -> DigitalEnvelope.fromBase64(null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("fromBase64 — 非法 Base64 抛异常")
    void fromBase64_invalidBase64_throwsException() {
        assertThatThrownBy(() -> DigitalEnvelope.fromBase64("@@@invalid@@@"))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 空密文 =====

    @Test
    @DisplayName("空密文 — 序列化往返")
    void emptyCiphertext_roundTrip() {
        DigitalEnvelope envelope = new DigitalEnvelope(iv, encKey, new byte[0]);
        byte[] bytes = envelope.toBytes();
        DigitalEnvelope restored = DigitalEnvelope.fromBytes(bytes);
        assertThat(restored.getCiphertext()).isEqualTo(new byte[0]);
    }

    // ===== equals/hashCode =====

    @Test
    @DisplayName("equals — 相同内容相等")
    void equals_sameContent_equal() {
        DigitalEnvelope e1 = new DigitalEnvelope(iv, encKey, ciphertext);
        DigitalEnvelope e2 = new DigitalEnvelope(iv, encKey, ciphertext);
        assertThat(e1).isEqualTo(e2);
        assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
    }

    @Test
    @DisplayName("equals — 不同内容不相等")
    void equals_differentContent_notEqual() {
        DigitalEnvelope e1 = new DigitalEnvelope(iv, encKey, ciphertext);
        byte[] differentCipher = new byte[128];
        new SecureRandom().nextBytes(differentCipher);
        DigitalEnvelope e2 = new DigitalEnvelope(iv, encKey, differentCipher);
        assertThat(e1).isNotEqualTo(e2);
    }

    @Test
    @DisplayName("toString — 包含长度信息")
    void toString_containsLengthInfo() {
        DigitalEnvelope envelope = new DigitalEnvelope(iv, encKey, ciphertext);
        String str = envelope.toString();
        assertThat(str).contains("ivLen=16").contains("encKeyLen=64").contains("cipherLen=128");
    }

    // ===== getter 防御性拷贝 =====

    @Test
    @DisplayName("getter — 返回副本，修改不影响原对象")
    void getter_returnsDefensiveCopy() {
        DigitalEnvelope envelope = new DigitalEnvelope(iv, encKey, ciphertext);
        byte[] ivCopy = envelope.getIv();
        ivCopy[0] ^= 0xFF;
        assertThat(envelope.getIv()).isEqualTo(iv);
    }
}