package com.levango7.dataenginebdp.encaps;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全 context 启动冒烟测试（Sprint 2.2 L4-0）。
 *
 * <p>目的：验证封装层租户域服务能以完整 Spring 上下文启动——这是 Docker 镜像与
 * Docker 集成测试的前置条件。本模块依赖 encaps-layer（同包
 * {@code com.levango7.dataenginebdp.encaps}），组件扫描会带入 encaps-layer 的全部
 * 组件（含其 TenantController），与本模块 TenantController 在
 * {@code /api/v1/tenants} 形成 ambiguous mapping 启动失败。</p>
 *
 * <p>两处关键配置（缺一即启动失败）：</p>
 * <ul>
 *   <li>classes 显式指定 {@code EncapsTenantApplication}：classpath 上同包存在两个
 *       @SpringBootApplication（本模块 + encaps-layer jar），自动探测会报
 *       "Found multiple @SpringBootConfiguration"。生产 fat jar 与此测试同布局。</li>
 *   <li>yml {@code app.tenant.controller.enabled=false}：关闭 encaps-layer 侧
 *       TenantController 守卫（Sprint 2.1 加入的 @ConditionalOnProperty），
 *       消除 /api/v1/tenants 双注册。</li>
 * </ul>
 *
 * <p>历史上本模块全部测试为 standaloneSetup 单测（无全 context 先例），
 * 上述两类启动失败从未被任何测试暴露——这正是本测试存在的意义。</p>
 */
@SpringBootTest(
        classes = EncapsTenantApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class EncapsTenantApplicationIT {

    @Autowired
    private WebApplicationContext wac;

    @Test
    void contextStartsAndUnknownRouteIs404() throws Exception {
        // 全 context 启动成功本身即通过（ambiguous mapping 会在启动期直接失败）

        // 未知路由返回 404（GlobalExceptionHandler 兜底，验证 NoResourceFoundException 处理链生效）
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mockMvc.perform(get("/api/v1/not-exist-route"))
                .andExpect(status().isNotFound());
    }
}
