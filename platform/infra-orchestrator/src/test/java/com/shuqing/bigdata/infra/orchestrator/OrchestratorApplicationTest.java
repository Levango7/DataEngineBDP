package com.shuqing.bigdata.infra.orchestrator;

import com.shuqing.bigdata.infra.orchestrator.registry.ProviderRegistry;
import com.shuqing.bigdata.infra.orchestrator.service.ProviderRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrchestratorApplication} Spring Boot 上下文加载测试。
 *
 * <p>验证应用上下文能成功启动，全部 Bean 装配完成，7 种环境 Provider 自动注册。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.orchestrator.providers.xinchang.base-url=http://localhost:8090",
        "app.orchestrator.providers.baremetal.base-url=http://localhost:8091",
        "app.orchestrator.providers.cloud.base-url=http://localhost:8092",
        "app.orchestrator.providers.private.base-url=http://localhost:8093",
        "app.orchestrator.poll.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OrchestratorApplicationTest {

    @Autowired
    private ProviderRegistry providerRegistry;

    @Autowired
    private ProviderRegistryService providerRegistryService;

    @Test
    void contextLoads() {
        assertThat(providerRegistry).isNotNull();
        assertThat(providerRegistryService).isNotNull();
    }

    @Test
    void shouldAutoRegisterAllSevenProviders() {
        assertThat(providerRegistry.size()).isEqualTo(7);
        assertThat(providerRegistry.missingEnvironments()).isEmpty();
    }

    @Test
    void shouldHaveAllProvidersEnabled() {
        assertThat(providerRegistry.listEnabledProviders()).hasSize(7);
    }
}