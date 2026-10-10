package com.levango7.dataenginebdp.encaps;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * encaps-data 应用上下文加载守卫（台账 #57① 扩栈实测新增）。
 *
 * <p>背景：本模块从未部署过（在 {@code docs/deployable-backlog.yaml} 的缺口清单里），
 * 因此没有任何测试真正加载过它的 Spring 上下文——直到扩栈把它拉进 nightly 栈时，
 * 进程一启动就抛：</p>
 *
 * <pre>
 * ConflictingBeanDefinitionException: bean name 'globalExceptionHandler' for bean class
 * [com.levango7.dataenginebdp.encaps.controller.GlobalExceptionHandler] conflicts with
 * existing, non-compatible bean definition of same name and class
 * [com.levango7.dataenginebdp.encaps.common.GlobalExceptionHandler]
 * </pre>
 *
 * <p>成因：本模块 classpath 带 encaps-layer（其 pom 依赖），两者同根包 {@code ...encaps}，
 * 各有一个同名异常处理器，默认 bean 名相同。修复 = 给本模块的处理器显式命名。</p>
 *
 * <p>注意 {@code classes} 必须显式指定：classpath 上同时存在本模块与 encaps-layer 的
 * {@code @SpringBootConfiguration}，按包扫描会报 "Found multiple ... annotated classes"。
 *
 * <p>本测试就是该缺陷的回归位：任何"上下文起不来"的问题（含将来新引入的同名 bean、
 * 缺失配置绑定等）都会在这里立刻失败，而不是等到容器部署时才炸。</p>
 */
@SpringBootTest(
        classes = EncapsDataApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class EncapsDataApplicationContextTest {

    @Test
    void contextLoads() {
        // 能走到这里即代表 Bean 定义无冲突、配置可绑定（断言由"上下文加载成功"本身承担）
    }
}
