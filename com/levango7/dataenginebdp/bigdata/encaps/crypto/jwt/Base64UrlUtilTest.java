package com.shuqing.bigdata.encaps.crypto.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Base64UrlUtil} 单元测试。
 *
 * <p>覆盖 RFC 4648 §5 Base64URL 编解码往返、边界条件、非法输入等。</p>
 */
class Base64UrlUtilTest {

    // ===== 编码 =====

    @Test
    @DisplayName("encode — 空字节数组返回空串")
    void encode_emptyBytes_returnsEmptyString() {
        assertThat(Base64UrlUtil.encode(new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("encode — null 返回 null")
    void encode_null_returnsNull() {
        assertThat(Base64UrlUtil.encode(null)).isNull();
    }

    @Test
    @DisplayName("encode — 单字节 'f' 应为 'Zg'")
    void encode_singleByte_returnsCorrectString() {
        byte[] data = "f".getBytes(StandardCharsets.UTF_8);
        assertThat(Base64UrlUtil.encode(data)).isEqualTo("Zg");
    }

    @Test
    @DisplayName("encode — 'fo' 应为 'Zm8'（无填充）")
    void encode_twoBytes_returnsNoPadding() {
        byte[] data = "fo".getBytes(StandardCharsets.UTF_8);
        assertThat(Base64UrlUtil.encode(data)).isEqualTo("Zm8");
    }

    @Test
    @DisplayName("encode — 'foo' 应为 'Zm9v'（无填充）")
    void encode_threeBytes_returnsNoPadding() {
        byte[] data = "foo".getBytes(StandardCharsets.UTF_8);
        assertThat(Base64UrlUtil.encode(data)).isEqualTo("Zm9v");
    }

    @Test
    @DisplayName("encode — 含 + / 的字节应替换为 - _ ")
    void encode_specialChars_replacedWithUrlSafe() {
        // 0xFB 0xFF 0xFE 在标准 Base64 中会生成 + /
        byte[] data = {(byte) 0xFB, (byte) 0xFF, (byte) 0xFE};
        String encoded = Base64UrlUtil.encode(data);
        assertThat(encoded).doesNotContain("+").doesNotContain("/");
    }

    @Test
    @DisplayName("encodeString — UTF-8 字符串编码")
    void encodeString_utf8_returnsEncoded() {
        String encoded = Base64UrlUtil.encodeString("hello");
        assertThat(encoded).isEqualTo("aGVsbG8");
    }

    // ===== 解码 =====

    @Test
    @DisplayName("decode — null 返回 null")
    void decode_null_returnsNull() {
        assertThat(Base64UrlUtil.decode(null)).isNull();
    }

    @Test
    @DisplayName("decode — 空串返回空字节数组")
    void decode_emptyString_returnsEmptyBytes() {
        assertThat(Base64UrlUtil.decode("")).isEqualTo(new byte[0]);
    }

    @Test
    @DisplayName("decode — 'Zg' 解码为 'f'")
    void decode_validString_returnsCorrectBytes() {
        byte[] decoded = Base64UrlUtil.decode("Zg");
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo("f");
    }

    @Test
    @DisplayName("decode — 容忍末尾填充 '='")
    void decode_withPadding_stillWorks() {
        byte[] decoded = Base64UrlUtil.decode("Zm9v");
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo("foo");
    }

    @Test
    @DisplayName("decode — 含 - _ 的 URL 安全字符")
    void decode_urlSafeChars_decodedCorrectly() {
        byte[] data = {(byte) 0xFB, (byte) 0xFF, (byte) 0xFE};
        String encoded = Base64UrlUtil.encode(data);
        byte[] decoded = Base64UrlUtil.decode(encoded);
        assertThat(decoded).isEqualTo(data);
    }

    @Test
    @DisplayName("decode — 非法字符抛 IllegalArgumentException")
    void decode_invalidChar_throwsException() {
        assertThatThrownBy(() -> Base64UrlUtil.decode("Zm9@"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decode — 长度不合法（单字符）抛异常")
    void decode_invalidLength_throwsException() {
        assertThatThrownBy(() -> Base64UrlUtil.decode("Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 往返测试 =====

    @Test
    @DisplayName("encode → decode 往返 — 任意字节")
    void roundTrip_randomBytes_preservesData() {
        byte[] data = "shuqing-bigdata-jwt-test-2024".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64UrlUtil.encode(data);
        byte[] decoded = Base64UrlUtil.decode(encoded);
        assertThat(decoded).isEqualTo(data);
    }

    @Test
    @DisplayName("encode → decode 往返 — 多种长度")
    void roundTrip_variousLengths_preservesData() {
        for (int len = 0; len <= 256; len++) {
            byte[] data = new byte[len];
            Arrays.fill(data, (byte) 'x');
            String encoded = Base64UrlUtil.encode(data);
            byte[] decoded = Base64UrlUtil.decode(encoded);
            assertThat(decoded).as("length=%d", len).isEqualTo(data);
        }
    }

    @Test
    @DisplayName("encodeString → decodeString 往返")
    void roundTripString_preservesData() {
        String original = "你好世界-Hello-World-1234567890";
        String encoded = Base64UrlUtil.encodeString(original);
        String decoded = Base64UrlUtil.decodeString(encoded);
        assertThat(decoded).isEqualTo(original);
    }

    // ===== 区间编码 =====

    @Test
    @DisplayName("encode(offset, length) — 区间编码")
    void encodeWithOffset_subRange_correct() {
        byte[] data = "abcdefg".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64UrlUtil.encode(data, 1, 3);  // "bcd"
        byte[] decoded = Base64UrlUtil.decode(encoded);
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo("bcd");
    }

    @Test
    @DisplayName("encode(offset, length) — 非法区间抛异常")
    void encodeWithOffset_invalidRange_throwsException() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> Base64UrlUtil.encode(data, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base64UrlUtil.encode(data, -1, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}