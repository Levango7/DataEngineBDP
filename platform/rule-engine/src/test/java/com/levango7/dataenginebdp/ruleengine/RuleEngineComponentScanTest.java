package com.levango7.dataenginebdp.ruleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.levango7.dataenginebdp.ruleengine.orchestrator.controller.OrchestratorController;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 编排 API 的运行时装配守卫。
 *
 * <p>历史背景（2026-10-12 已根治）：编排引擎原在
 * {@code ...dataenginebdp.rule.engine.orchestrator}（{@code rule.engine} ≠ {@code ruleengine}），
 * 与启动类不同根，默认组件扫描够不着，整组 {@code @RestController}/{@code @Service} 不会注册
 * ——单元测试是纯 POJO 构造，看不见这件事，只有真实上下文能暴露。包名现已并入
 * {@code ...dataenginebdp.ruleengine.orchestrator}（落在默认扫描根内，启动类的
 * {@code scanBasePackages} 补丁随之移除）。本测试继续作为**运行期事实**的守卫
 * （断言 Bean 与请求映射都在，而非类的存在性）——将来若有人再把包移出扫描根，它会红。</p>
 */
@SpringBootTest
class RuleEngineComponentScanTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("编排控制器必须被组件扫描注册为 Bean")
    void orchestratorController_isRegisteredAsBean() {
        assertThat(context.getBeanNamesForType(OrchestratorController.class))
                .as("orchestrator 包必须落在启动类的扫描根内，否则编排 API 运行时 404")
                .isNotEmpty();
    }

    @Test
    @DisplayName("编排 API 的请求映射必须真实注册")
    void orchestratorEndpoints_areMapped() {
        RequestMappingHandlerMapping mapping =
                context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        Set<String> patterns = mapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .collect(Collectors.toSet());

        assertThat(patterns)
                .as("GET /api/v1/orchestrator/dags 是前端编排可视化页依赖的端点")
                .anyMatch(p -> p.startsWith("/api/v1/orchestrator/dags"));
    }
}
