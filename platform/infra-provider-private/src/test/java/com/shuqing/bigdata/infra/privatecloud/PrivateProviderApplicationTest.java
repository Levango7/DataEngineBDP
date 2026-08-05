package com.shuqing.bigdata.infra.privatecloud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Spring Boot 上下文加载测试。
 *
 * <p>验证所有 Bean（Provider、Client、Service、Controller、Security）
 * 能在测试配置下正确装配，无循环依赖、无缺失 Bean。</p>
 *
 * @author shuqing-bigdata
 */
@SpringBootTest
class PrivateProviderApplicationTest {

    @Test
    @DisplayName("Spring 上下文加载 — 无异常")
    void contextLoads() {
        assertDoesNotThrow(() -> {
            // Spring 自动验证上下文加载
        });
    }
}