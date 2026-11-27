package com.levango7.dataenginebdp.sqlgateway.service;

import com.levango7.dataenginebdp.sqlgateway.config.BackendProperties;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackendProxyServicePreparedStatementTest {

    @Test
    @DisplayName("Doris 侧 params 非空时必须走 PreparedStatement 绑定")
    void dorisParams_shouldBindPreparedStatement() {
        BackendProperties props = new BackendProperties();
        props.getDoris().setUrl("jdbc:h2:mem:params?DB_CLOSE_DELAY=-1");
        BackendProxyService service =
                new BackendProxyService(WebClient.builder(), props);

        SqlExecuteResponse response = service.proxyToDoris(
                "SELECT ?", "t1", 10, List.of("bound-value")).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getColumns()).containsExactly("?1");
        assertThat(response.getRows()).containsExactly(List.of("bound-value"));
    }
}
