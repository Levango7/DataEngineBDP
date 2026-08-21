package com.shuqing.bigdata.encaps.security.facade.mask;

import com.shuqing.bigdata.encaps.security.facade.config.SecurityFacadeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MaskFacade} 单元测试。
 *
 * <p>覆盖内置规则、自定义规则注册/注销、禁用异常等。</p>
 */
class MaskFacadeTest {

    private MaskFacade maskFacade;
    private SecurityFacadeConfig config;

    @BeforeEach
    void setUp() {
        config = new SecurityFacadeConfig();
        maskFacade = new MaskFacade(config);
    }

    // ===== 内置规则 =====

    @Test
    @DisplayName("PHONE — 13812345678 → 138****5678")
    void phone_shouldMaskMiddleDigits() {
        assertThat(maskFacade.mask("13812345678", MaskType.PHONE)).isEqualTo("138****5678");
    }

    @Test
    @DisplayName("ID_CARD — 18位身份证保留前6后4")
    void idCard_shouldMaskMiddle() {
        String id = "110101199001011234";
        String masked = maskFacade.mask(id, MaskType.ID_CARD);
        assertThat(masked).hasSize(18);
        assertThat(masked).startsWith("110101");
        assertThat(masked).endsWith("1234");
        assertThat(masked.substring(6, 14)).matches("\\*+");
    }

    @Test
    @DisplayName("BANK_CARD — 16位卡号保留前4后4")
    void bankCard_shouldMaskMiddle() {
        String card = "6222021234567890";
        String masked = maskFacade.mask(card, MaskType.BANK_CARD);
        assertThat(masked).startsWith("6222");
        assertThat(masked).endsWith("7890");
        assertThat(masked.substring(4, 12)).matches("\\*+");
    }

    @Test
    @DisplayName("EMAIL — zhangsan@example.com → z********@example.com")
    void email_shouldMaskLocalPart() {
        assertThat(maskFacade.mask("zhangsan@example.com", MaskType.EMAIL))
                .isEqualTo("z*******@example.com");
    }

    @Test
    @DisplayName("NAME — 张三 → 张*")
    void name_shouldMaskGivenName() {
        assertThat(maskFacade.mask("张三", MaskType.NAME)).isEqualTo("张*");
        assertThat(maskFacade.mask("欧阳锋", MaskType.NAME)).isEqualTo("欧**");
    }

    @Test
    @DisplayName("ADDRESS — 保留前6字符")
    void address_shouldKeepFirst6() {
        String addr = "北京市海淀区中关村大街1号";
        String masked = maskFacade.mask(addr, MaskType.ADDRESS);
        assertThat(masked).startsWith("北京市海淀区");
        assertThat(masked.substring(6)).matches("\\*+");
    }

    @Test
    @DisplayName("IP — 192.168.1.100 → 192.168.*.*")
    void ip_shouldMaskLastTwoOctets() {
        assertThat(maskFacade.mask("192.168.1.100", MaskType.IP)).isEqualTo("192.168.*.*");
    }

    @Test
    @DisplayName("FULL — 全部替换为 *")
    void full_shouldReplaceAll() {
        assertThat(maskFacade.mask("secret123", MaskType.FULL)).isEqualTo("*********");
    }

    // ===== 空安全 =====

    @Test
    @DisplayName("null 输入 — 返回 null")
    void nullInput_shouldReturnNull() {
        assertThat(maskFacade.mask(null, MaskType.PHONE)).isNull();
        assertThat(maskFacade.mask(null, MaskType.FULL)).isNull();
        assertThat(maskFacade.mask(null, MaskType.EMAIL)).isNull();
    }

    @Test
    @DisplayName("短输入 — 原样返回（不够脱敏长度）")
    void shortInput_shouldReturnAsIs() {
        assertThat(maskFacade.mask("123", MaskType.PHONE)).isEqualTo("123");
        assertThat(maskFacade.mask("ab", MaskType.ID_CARD)).isEqualTo("ab");
    }

    // ===== 自定义规则 =====

    @Test
    @DisplayName("自定义规则 — 注册并使用")
    void customRule_shouldRegisterAndUse() {
        maskFacade.registerCustom("myRule", new MaskRule() {
            @Override
            public MaskType supportedType() { return MaskType.CUSTOM; }

            @Override
            public String mask(String input) {
                return input.substring(0, 2) + "***";
            }
        });

        assertThat(maskFacade.maskCustom("abcdef", "myRule")).isEqualTo("ab***");
        assertThat(maskFacade.customRuleNames()).contains("myRule");
    }

    @Test
    @DisplayName("自定义规则 — 未注册抛 IllegalArgumentException")
    void customRule_notRegistered_shouldThrow() {
        assertThatThrownBy(() -> maskFacade.maskCustom("test", "notExist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("自定义规则 — 注销")
    void customRule_unregister() {
        maskFacade.registerCustom("temp", new MaskRule() {
            @Override
            public MaskType supportedType() { return MaskType.CUSTOM; }

            @Override
            public String mask(String input) { return "masked"; }
        });
        assertThat(maskFacade.customRuleNames()).contains("temp");

        assertThat(maskFacade.unregisterCustom("temp")).isTrue();
        assertThat(maskFacade.customRuleNames()).doesNotContain("temp");
    }

    @Test
    @DisplayName("CUSTOM 类型直接调用 mask 抛异常")
    void customTypeWithMask_shouldThrow() {
        assertThatThrownBy(() -> maskFacade.mask("test", MaskType.CUSTOM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 禁用 =====

    @Test
    @DisplayName("禁用后调用抛 IllegalStateException")
    void disabled_shouldThrow() {
        config.setEnabled(false);
        assertThatThrownBy(() -> maskFacade.mask("13812345678", MaskType.PHONE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("仅脱敏禁用 — 抛 IllegalStateException")
    void maskDisabled_shouldThrow() {
        config.getMask().setEnabled(false);
        assertThatThrownBy(() -> maskFacade.mask("13812345678", MaskType.PHONE))
                .isInstanceOf(IllegalStateException.class);
    }

    // ===== 内置类型列表 =====

    @Test
    @DisplayName("builtInTypes — 返回 8 种内置类型")
    void builtInTypes_shouldReturn8Types() {
        assertThat(maskFacade.builtInTypes())
                .containsExactlyInAnyOrder(
                        MaskType.PHONE, MaskType.ID_CARD, MaskType.BANK_CARD,
                        MaskType.EMAIL, MaskType.NAME, MaskType.ADDRESS,
                        MaskType.IP, MaskType.FULL);
    }
}