package com.levango7.dataenginebdp.encaps;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.levango7.dataenginebdp.encaps.tenant.controller.AccountController;
import com.levango7.dataenginebdp.encaps.tenant.controller.TenantController;
import com.levango7.dataenginebdp.encaps.security.AuditLog;
import com.levango7.dataenginebdp.encaps.security.AuditLogAspect;
import com.levango7.dataenginebdp.encaps.tenant.service.TenantService;
import com.levango7.dataenginebdp.encaps.workspace.WorkspaceController;

import static org.assertj.core.api.Assertions.assertThat;
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
 *
 * <p><b>2026-10-15 追加两类断言</b>（崩溃修复的回归防线 + 等保合规契约）：</p>
 * <ul>
 *   <li>{@link #contextStillScansTenantOwnedBeans()}：tenant 自有的
 *       {@code TenantService} 与 Controller（2026-10-15 起居于专属包
 *       {@code ...encaps.tenant.*}）必须被 {@code @ComponentScan} 白名单扫到。
 *       防的是将来调整白名单时漏列自有包：这些 bean 一消失，
 *       /api/v1/accounts、/api/v1/projects、租户写路径立刻断。</li>
 *   <li>{@link #auditAspectAndAnnotationsSurvive()}：等保 2.0
 *       （GB/T 22239-2019 8.1.4.3）审计切面 {@link AuditLogAspect} 与租户域写路径的
 *       {@code @AuditLog} 标注必须常在。组件扫描白名单一旦把 {@code ...encaps.security}
 *       去掉，切面 bean 静默消失、审计无痕 ⇒ 这是合规回归，测试必须拦住。</li>
 * </ul>
 *
 * <p>另注：本类曾被 surefire 排除在外（本仓无 failsafe，{@code *IT} 不在默认
 * includes 里 ⇒ 任何生命周期都不跑），崩溃因此直漏到 CI docker 栈。
 * 2026-10-15 已把 {@code *IT} 显式纳入 tenant 的 surefire includes。</p>
 */
@SpringBootTest(
        classes = EncapsTenantApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class EncapsTenantApplicationIT {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStartsAndUnknownRouteIs404() throws Exception {
        // 全 context 启动成功本身即通过（ambiguous mapping 会在启动期直接失败）

        // 未知路由返回 404（GlobalExceptionHandler 兜底，验证 NoResourceFoundException 处理链生效）
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mockMvc.perform(get("/api/v1/not-exist-route"))
                .andExpect(status().isNotFound());
    }

    /**
     * tenant 自有 bean（2026-10-15 起位于专属包 ...encaps.tenant.* 与 quota/workspace）
     * 必须被 @ComponentScan 白名单扫到。防的是将来调整白名单时漏列自有包：
     * 这些 bean 一消失，/api/v1/accounts、/api/v1/projects、租户写路径立刻断。
     */
    @Test
    void contextStillScansTenantOwnedBeans() {
        assertThat(context.getBean(TenantService.class)).isNotNull();
        assertThat(context.getBean(AccountController.class)).isNotNull();
        assertThat(context.getBean(WorkspaceController.class)).isNotNull();
        // 迁移后 tenant 侧 ProjectController/TenantController 位于 ...encaps.tenant.controller，
        // 与 layer 侧同名类不再撞名（FQN 唯一）⇒ 可以直接按类型断言
        assertThat(context.getBean(TenantController.class)).isNotNull();
    }

    /**
     * 等保 2.0（GB/T 22239-2019 8.1.4.3）安全审计合规契约：
     * ① AuditLogAspect 切面 bean 仍在本上下文（2026-10-15 的扫描收窄只排除了
     *    EncapsLayerApplication 一个类，未碰 ...encaps.security）；
     * ② 租户域写路径的 @AuditLog 标注未随重构丢失。
     * 取 tenant 独有 FQN 的控制器（Account/Workspace），避开 Project/TenantController
     * 两个同名类的 classloader 歧义。
     */
    @Test
    void auditAspectAndAnnotationsSurvive() {
        Map<String, AuditLogAspect> aspects = context.getBeansOfType(AuditLogAspect.class);
        assertThat(aspects)
                .as("AuditLogAspect 必须在本上下文：否则 @AuditLog 静默失效，等保审计无痕")
                .isNotEmpty();

        for (Class<?> controller : new Class<?>[]{AccountController.class, WorkspaceController.class}) {
            boolean annotated = Arrays.stream(controller.getMethods())
                    .anyMatch(m -> m.isAnnotationPresent(AuditLog.class));
            assertThat(annotated)
                    .as("%s 至少一个写方法必须保留 @AuditLog 标注", controller.getSimpleName())
                    .isTrue();
        }

        // 这三个动作正是等保审计要求的租户域关键操作（建/改/删租户）
        assertThat(Arrays.stream(TenantController.class.getMethods())
                .filter(m -> m.isAnnotationPresent(AuditLog.class))
                .map(m -> m.getAnnotation(AuditLog.class).action())
                .toList())
                .contains("CREATE_TENANT", "UPDATE_TENANT", "DELETE_TENANT");
    }
}
