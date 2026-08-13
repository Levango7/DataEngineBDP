package com.levango7.dataenginebdp.sqlgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.sqlgateway.config.BackendProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trino rawInputBytes 提取逻辑测试（反射调用私有 extractRawInputBytes）。
 */
class TrinoRawInputBytesExtractionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BackendProxyService service;

    @BeforeEach
    void setUp() {
        BackendProperties props = new BackendProperties();
        BackendProperties.BackendConfig trino = new BackendProperties.BackendConfig();
        trino.setUrl("http://trino:8080");
        trino.setTimeout(5);
        BackendProperties.BackendConfig doris = new BackendProperties.BackendConfig();
        doris.setUrl("http://doris:9030");
        doris.setTimeout(5);
        props.setTrino(trino);
        props.setDoris(doris);
        service = new BackendProxyService(WebClient.builder(), props);
    }

    private Long invokeExtract(String json) throws Exception {
        Method m = BackendProxyService.class
                .getDeclaredMethod("extractRawInputBytes", JsonNode.class);
        m.setAccessible(true);
        JsonNode root = objectMapper.readTree(json);
        return (Long) m.invoke(service, root);
    }

    @Test
    void extractsRawInputBytesFromTrinoStats() throws Exception {
        String json = "{\"stats\":{\"state\":\"FINISHED\",\"rawInputBytes\":123456789}}";
        assertThat(invokeExtract(json)).isEqualTo(123456789L);
    }

    @Test
    void returnsNullWhenStatsMissing() throws Exception {
        assertThat(invokeExtract("{\"data\":[]}")).isNull();
    }

    @Test
    void returnsNullWhenRawInputBytesNotNumeric() throws Exception {
        String json = "{\"stats\":{\"rawInputBytes\":\"unknown\"}}";
        assertThat(invokeExtract(json)).isNull();
    }

    @Test
    void returnsNullOnNullRoot() throws Exception {
        Method m = BackendProxyService.class
                .getDeclaredMethod("extractRawInputBytes", JsonNode.class);
        m.setAccessible(true);
        assertThat(m.invoke(service, (Object) null)).isNull();
    }
}