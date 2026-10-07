package com.levango7.dataenginebdp.ruleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.levango7.dataenginebdp.rule.engine.orchestrator.controller.OrchestratorController;
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
 * <p>本模块的启动类在 {@code ...dataenginebdp.ruleengine}，而编排引擎整棵包树在
 * {@code ...dataenginebdp.rule.engine.orchestrator}（{@code rule.engine} ≠ {@code ruleengine}）。
 * Spring Boot 的默认组件扫描根是启动类所在包及其子包，因此这一批 {@code @RestController}/
 * {@code @Service} 不会被注册——单元测试是纯 POJO 构造，看不见这件事，只有真实上下文能暴露。
 * 这里断言的是运行期事实（Bean 与请求映射都在），不是类的存在性。</p>
 */
@SpringBootTest
class RuleEngineComponentScanTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("编排控制器必须被组件扫描注册为 Bean")
    void orchestratorController_isRegisteredAsBean() {
        assertThat(context.getBeanNamesForType(OrchestratorController.class))
                .as("rule.engine.orchestrator 包必须落在启动类的扫描根内，否则编排 API 运行时 404")
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
