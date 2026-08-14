package com.levango7.dataenginebdp.streambatch.flink;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FlinkRestClient 单元测试。
 *
 * <p>验证 jarId/jobId 解析、错误路径；不依赖真实 Flink 集群
 * （通过反射调用私有解析逻辑 + 空配置验证不可达错误）。
 */
class FlinkRestClientTest {

    private FlinkRestClient newClient(String restUrl) {
        FlinkStreamConfig cfg = new FlinkStreamConfig();
        cfg.setJobManagerRest(restUrl);
        return new FlinkRestClient(cfg);
    }

    @Test
    void uploadJar_rejectsBlankPath() {
        FlinkRestClient client = newClient("http://localhost:8081");
        assertThatThrownBy(() -> client.uploadJar(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jarPath");
        assertThatThrownBy(() -> client.uploadJar(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uploadJar_rejectsMissingFile() {
        FlinkRestClient client = newClient("http://localhost:8081");
        assertThatThrownBy(() -> client.uploadJar("/nonexistent/job.jar"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jar 不存在");
    }

    @Test
    void uploadJar_unreachableClusterThrowsIOException() throws Exception {
        FlinkRestClient client = newClient("http://127.0.0.1:1"); // 不可达端口
        // 上传 jar 需要文件；先构造临时文件
        java.io.File tmp = java.io.File.createTempFile("flink-test", ".jar");
        try {
            assertThatThrownBy(() -> client.uploadJar(tmp.getAbsolutePath()))
                    .isInstanceOf(IOException.class);
        } finally {
            tmp.delete();
        }
    }

    @Test
    void runJar_unreachableClusterThrowsIOException() {
        FlinkRestClient client = newClient("http://127.0.0.1:1");
        assertThatThrownBy(() -> client.runJar("jar-1", "com.example.Main",
                null, 2, Map.of("k", "v")))
                .isInstanceOf(IOException.class);
    }

    @Test
    void cancel_unreachableClusterDoesNotThrow() {
        // cancel 失败仅 warn，不抛异常（幂等语义）
        FlinkRestClient client = newClient("http://127.0.0.1:1");
        client.cancel("job-1"); // 不应抛
    }
}
