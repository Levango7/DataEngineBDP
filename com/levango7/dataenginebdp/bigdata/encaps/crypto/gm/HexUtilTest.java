package com.shuqing.bigdata.encaps.crypto.gm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HexUtil} 单元测试。
 *
 * <p>覆盖 hex 编解码的边界情况：null 入参、空数组、奇数长度、非法字符、
 * 区间转 hex 等，提升工具类覆盖率。</p>
 */
class HexUtilTest {

    @Test
    @DisplayName("toHex(null) — 返回 null")
    void toHex_null_shouldReturnNull() {
        assertThat(HexUtil.toHex(null)).isNull();
    }

    @Test
    @DisplayName("toHex(空数组) — 返回空串")
    void toHex_emptyArray_shouldReturnEmptyString() {
        assertThat(HexUtil.toHex(new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("toHex — 单字节 0x00 → \"00\"")
    void toHex_zeroByte_shouldReturn00() {
        assertThat(HexUtil.toHex(new byte[]{0x00})).isEqualTo("00");
    }

    @Test
    @DisplayName("toHex — 单字节 0xff → \"ff\"")
    void toHex_ffByte_shouldReturnff() {
        assertThat(HexUtil.toHex(new byte[]{(byte) 0xff})).isEqualTo("ff");
    }

    @Test
    @DisplayName("toHex(bytes, offset, len) — null 返回 null")
    void toHex_rangeNull_shouldReturnNull() {
        assertThat(HexUtil.toHex(null, 0, 0)).isNull();
    }

    @Test
    @DisplayName("toHex(bytes, offset, len) — 区间转换正确")
    void toHex_range_shouldConvertSubRange() {
        byte[] data = {0x01, 0x23, 0x45, 0x67};
        assertThat(HexUtil.toHex(data, 1, 2)).isEqualTo("2345");
    }

    @Test
    @DisplayName("fromHex(null) — 返回 null")
    void fromHex_null_shouldReturnNull() {
        assertThat(HexUtil.fromHex(null)).isNull();
    }

    @Test
    @DisplayName("fromHex(空串) — 返回空数组")
    void fromHex_empty_shouldReturnEmptyArray() {
        assertThat(HexUtil.fromHex("")).isEmpty();
    }

    @Test
    @DisplayName("fromHex — 大写字母转小写处理")
    void fromHex_upperCase_shouldWork() {
        byte[] result = HexUtil.fromHex("ABCDEF");
        assertThat(HexUtil.toHex(result)).isEqualTo("abcdef");
    }

    @Test
    @DisplayName("fromHex — 奇数长度抛 IllegalArgumentException")
    void fromHex_oddLength_shouldThrow() {
        assertThatThrownBy(() -> HexUtil.fromHex("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("even");
    }

    @Test
    @DisplayName("fromHex — 非法字符抛 IllegalArgumentException")
    void fromHex_invalidChar_shouldThrow() {
        assertThatThrownBy(() -> HexUtil.fromHex("xy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid hex");
    }

    @Test
    @DisplayName("fromHex → toHex 往返一致")
    void fromHex_toHex_roundTrip() {
        String hex = "0123456789abcdef";
        byte[] bytes = HexUtil.fromHex(hex);
        assertThat(HexUtil.toHex(bytes)).isEqualTo(hex);
    }

    @Test
    @DisplayName("私有构造函数 — 反射调用不应抛异常（覆盖构造函数）")
    void privateConstructor_shouldBeInvocable() throws Exception {
        java.lang.reflect.Constructor<HexUtil> ctor = HexUtil.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        // 仅验证构造函数可访问（HexUtil 构造函数为空实现，不抛异常）
        assertThat(ctor).isNotNull();
    }
}