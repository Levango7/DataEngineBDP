package com.levango7.dataenginebdp.governance.lineage.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * nGQL 注入防护（等价覆盖补回）。
 *
 * <p>背景：real-time-pipeline 侧的 {@code NebulaLineageGraphClient} 及其注入单测曾于
 * 2026-10-04 随"统一写入者"重构（裁决 Q2 / B1）被删除——写入路径收敛到本模块。
 * 但删除时本模块**没有**对等的注入防护测试，等于把该保护清零。本类补回。
 *
 * <p>被测对象：{@link NebulaGraphClient#writeNode} / {@code writeEdge} 在拼 nGQL 前
 * 统一调用私有静态 {@code escape(String)}。判定面是"净化结果"：
 * 反斜杠与双引号必须被转义，否则恶意 fullName（如 {@code x"; DROP TAG lineage_node; --}）
 * 可逃出字符串字面量并改写语句。
 *
 * <p>用反射调用私有方法：这是**实现细节级**的守护（与安全属性直接相关），
 * 且不引入需要真实 Nebula 连接的依赖；若将来改为参数化查询，本类应整体替换为
 * "对抗输入不产生语句拼接"的断言。
 */
class NGqlEscapeInjectionTest {

    private static String escape(String s) throws Exception {
        Method m = NebulaGraphClient.class.getDeclaredMethod("escape", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    @Test
    @DisplayName("双引号必须被转义——否则可逃出字符串字面量")
    void escapesDoubleQuote() throws Exception {
        assertThat(escape("x\"; DROP TAG lineage_node; --")).isEqualTo("x\\\"; DROP TAG lineage_node; --");
    }

    @Test
    @DisplayName("反斜杠必须被转义——否则可构造 '\\\"' 组合绕过引号转义")
    void escapesBackslash() throws Exception {
        assertThat(escape("a\\b")).isEqualTo("a\\\\b");
        // 组合攻击：以反斜杠结尾的内容会让后续闭合引号被吃掉
        assertThat(escape("a\\")).isEqualTo("a\\\\");
    }

    @Test
    @DisplayName("转义后不含未转义的引号（字面量不可逃逸）")
    void noUnescapedQuoteRemains() throws Exception {
        String[] adversarial = {
                "\"",
                "a\"b",
                "}\\n\"); DROP SPACE lineage; --",
                "SELECT * FROM x; \"",
        };
        for (String in : adversarial) {
            String out = escape(in);
            // 逐字符扫描：引号必须紧跟在反斜杠之后
            for (int i = 0; i < out.length(); i++) {
                if (out.charAt(i) == '"') {
                    assertThat(i)
                            .as("input=%s output=%s：引号必须被转义", in, out)
                            .isGreaterThan(0);
                    assertThat(out.charAt(i - 1))
                            .as("input=%s output=%s：引号前必须是反斜杠", in, out)
                            .isEqualTo('\\');
                }
            }
        }
    }

    @Test
    @DisplayName("null 与空串安全（不得返回 null 导致拼接出 \"null\" 字面量）")
    void handlesNullAndEmpty() throws Exception {
        assertThat(escape(null)).isEmpty();
        assertThat(escape("")).isEmpty();
    }
}
