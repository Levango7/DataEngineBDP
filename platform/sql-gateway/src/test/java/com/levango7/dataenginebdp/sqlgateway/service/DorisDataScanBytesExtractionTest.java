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
 * Doris screenshotBytes 提取逻辑测试（反射调用私有 extractDorisScanBytes）。
 */
class DorisDataScanBytesExtractionTest {

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
                .getDeclaredMethod("extractDorisScanBytes", JsonNode.class);
        m.setAccessible(true);
        JsonNode root = objectMapper.readTree(json);
        return (Long) m.invoke(service, root);
    }

    @Test
    void extractsScanBytesFromRoot() throws Exception {
        String json = "{\"code\":0,\"scanBytes\":987654321}";
        assertThat(invokeExtract(json)).isEqualTo(987654321L);
    }

    @Test
    void extractsScanBytesFromDataNode() throws Exception {
        String json = "{\"code\":0,\"data\":{\"ScanBytes\":12345}}";
        assertThat(invokeExtract(json)).isEqualTo(12345L);
    }

    @Test
    void prefersNonZeroOverMissing() throws Exception {
        String json = "{\"code\":0,\"processedBytes\":0,\"bytesRead\":777}";
        assertThat(invokeExtract(json)).isEqualTo(777L);
    }

    @Test
    void returnsNullWhenAbsent() throws Exception {
        assertThat(invokeExtract("{\"code\":0,\"data\":{\"columns\":[],\"rows\":[]}}")).isNull();
    }

    @Test
    void returnsNullOnNullRoot() throws Exception {
        Method m = BackendProxyService.class
                .getDeclaredMethod("extractDorisScanBytes", JsonNode.class);
        m.setAccessible(true);
        assertThat(m.invoke(service, (Object) null)).isNull();
    }
}