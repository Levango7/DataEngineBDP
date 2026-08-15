package com.levango7.dataenginebdp.sqlgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.sqlgateway.config.BackendProperties;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链路4 集成测试：sql-gateway → 真实 Trino 容器（tpch.tiny）。
 *
 * <p>需要本地 Trino 容器（18082）运行；通过 {@code -Dtrino.it=true} 启用，
 * 默认跳过（CI 无容器不阻塞）。</p>
 *
 * <p>运行：{@code mvn test -Dtest=SqlGatewayTrinoIT -Dtrino.it=true}</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "trino.it", matches = "true")
class SqlGatewayTrinoIT {

    private BackendProxyService service;

    @BeforeAll
    void setUp() {
        BackendProperties props = new BackendProperties();
        BackendProperties.BackendConfig trino = new BackendProperties.BackendConfig();
        trino.setUrl("http://127.0.0.1:18082");
        trino.setTimeout(30);
        props.setTrino(trino);
        props.setDoris(new BackendProperties.BackendConfig());
        service = new BackendProxyService(WebClient.builder(), props);
    }

    @Test
    void proxyToTrino_realContainer_returnsTpchData() {
        SqlExecuteResponse resp = service.proxyToTrino(
                "SELECT regionkey, name FROM tpch.tiny.region ORDER BY regionkey", "it-tenant").block();

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus()).isEqualTo("SUCCESS");
        assertThat(resp.getEngine()).isEqualTo("trino");
        assertThat(resp.getColumns()).contains("regionkey", "name");
        assertThat(resp.getRows()).isNotEmpty();
        // tpch.tiny.region 有 5 行
        assertThat(resp.getRows()).hasSize(5);
    }

    @Test
    void proxyToTrino_realContainer_aggregationQuery() {
        SqlExecuteResponse resp = service.proxyToTrino(
                "SELECT COUNT(*) AS cnt FROM tpch.tiny.orders", "it-tenant").block();

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus()).isEqualTo("SUCCESS");
        // tpch.tiny.orders 共 15000 行
        assertThat(resp.getRows()).isNotEmpty();
        assertThat(resp.getRows().get(0)).contains(15000L);
    }
}
