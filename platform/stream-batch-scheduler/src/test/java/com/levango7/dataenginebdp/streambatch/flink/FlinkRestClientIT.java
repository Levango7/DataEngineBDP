package com.levango7.dataenginebdp.streambatch.flink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FlinkRestClient 真实 Flink 集群集成测试。
 *
 * <p>前置条件：
 * <ul>
 *   <li>Docker 基础设施已启动：{@code bash scripts/infra/start-infra.sh --no-init}</li>
 *   <li>Flink JobManager REST 可达：{@code http://localhost:8081}</li>
 *   <li>运行参数：{@code -Dinfra.it=true}</li>
 * </ul>
 *
 * <p>验证 FlinkRestClient 真实 REST API 调用链：
 * <ol>
 *   <li>GET /overview 集群概览</li>
 *   <li>POST /jars/upload 上传作业 jar</li>
 *   <li>POST /jars/{jarId}/run 提交运行</li>
 *   <li>GET /jobs/{jobId} 查询作业状态</li>
 *   <li>PATCH /jobs/{jobId}/cancel 取消作业</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "infra.it", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlinkRestClientIT {

    private static final String FLINK_REST = "http://localhost:8081";
    private static final String FLINK_CONTAINER = "de-flink-jobmanager";
    private static final String EXAMPLE_JAR_IN_CONTAINER =
            "/opt/flink/examples/flink-examples-streaming_2.12-1.20.0.jar";
    private static final String WORDCOUNT_CLASS =
            "org.apache.flink.streaming.examples.wordcount.WordCount";

    private static RestTemplate restTemplate;
    private static ObjectMapper objectMapper;
    private static FlinkRestClient flinkRestClient;
    private static Path localExampleJar;
    private static String uploadedJarId;
    private static String submittedJobId;

    @BeforeAll
    static void setUp() throws Exception {
        // 1. 初始化 RestTemplate（带超时）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        restTemplate = new RestTemplate(factory);
        objectMapper = new ObjectMapper();

        // 2. 构造 FlinkRestClient（realSubmitEnabled=true 路径）
        FlinkStreamConfig config = new FlinkStreamConfig();
        config.setJobManagerRest(FLINK_REST);
        config.setRealSubmitEnabled(true);
        flinkRestClient = new FlinkRestClient(config);

        // 3. 从 Docker 容器复制 Flink example jar 到本地临时目录
        localExampleJar = Files.createTempFile("flink-it-example-", ".jar");
        localExampleJar.toFile().delete(); // docker cp 需要目标不存在或覆盖
        copyJarFromContainer();
    }

    @AfterAll
    static void tearDown() throws IOException {
        // 清理：取消可能残留的作业
        if (submittedJobId != null) {
            try {
                flinkRestClient.cancel(submittedJobId);
            } catch (Exception ignored) {
                // 作业可能已结束，忽略
            }
        }
        // 删除本地临时 jar
        if (localExampleJar != null) {
            Files.deleteIfExists(localExampleJar);
        }
    }

    /**
     * 从 Docker 容器复制 Flink example jar 到本地。
     *
     * <p>使用 {@code docker cp} 命令；若 Docker 不可用或容器未运行则跳过
     * （后续测试会因集群不可达而失败，给出明确错误）。
     */
    private static void copyJarFromContainer() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "cp",
                FLINK_CONTAINER + ":" + EXAMPLE_JAR_IN_CONTAINER,
                localExampleJar.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new IOException(
                    "docker cp 失败（exitCode=" + exitCode + "）: " + output
                    + "\n请确保 Docker 基础设施已启动: bash scripts/infra/start-infra.sh --no-init");
        }
        if (!localExampleJar.toFile().exists() || localExampleJar.toFile().length() == 0) {
            throw new IOException("docker cp 后 jar 文件不存在或为空: " + localExampleJar);
        }
    }

    /**
     * 测试1: 连接真实 Flink REST API，获取集群概览。
     *
     * <p>GET /overview 返回集群 taskSlotsTotal/taskSlotsAvailable 等信息，
     * 验证 Flink JobManager 真实可达。
     */
    @Test
    @Order(1)
    void getOverview_clusterIsReachable() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                FLINK_REST + "/overview", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode root = parseJson(resp.getBody());
        int taskSlotsTotal = root.path("taskslots-total").asInt();
        int taskSlotsAvailable = root.path("taskslots-available").asInt();

        assertThat(taskSlotsTotal)
                .as("Flink 集群应有可用 task slots（需启动 TaskManager）")
                .isGreaterThan(0);
        assertThat(taskSlotsAvailable).isGreaterThanOrEqualTo(0);
        System.out.println("[IT] Flink 集群概览: taskSlotsTotal=" + taskSlotsTotal
                + ", taskSlotsAvailable=" + taskSlotsAvailable);
    }

    /**
     * 测试2: 上传 Flink example jar 到 JobManager（真实 POST /jars/upload）。
     *
     * <p>验证 FlinkRestClient.uploadJar 返回非空 jarId。
     */
    @Test
    @Order(2)
    void uploadJar_realClusterReturnsJarId() throws IOException {
        uploadedJarId = flinkRestClient.uploadJar(localExampleJar.toAbsolutePath().toString());

        assertThat(uploadedJarId)
                .as("上传 jar 后应返回非空 jarId")
                .isNotBlank();
        assertThat(uploadedJarId).endsWith(".jar");
        System.out.println("[IT] Flink jar 上传成功: jarId=" + uploadedJarId);
    }

    /**
     * 测试3: 提交 WordCount 作业运行（真实 POST /jars/{jarId}/run）。
     *
     * <p>验证 FlinkRestClient.runJar 返回真实 Flink jobId（UUID 格式）。
     * 作业读取容器内 LICENSE 文件，输出到 /tmp（容器内路径）。
     */
    @Test
    @Order(3)
    void runJar_realClusterReturnsJobId() throws IOException {
        assertThat(uploadedJarId).as("前置测试 uploadJar 须先通过").isNotBlank();

        // WordCount 参数：input/output 均为 JobManager 容器内路径
        String programArgs = "--input /opt/flink/LICENSE --output /tmp/wc-output-it";
        submittedJobId = flinkRestClient.runJar(
                uploadedJarId, WORDCOUNT_CLASS, programArgs, 1, Map.of());

        assertThat(submittedJobId)
                .as("提交作业后应返回真实 Flink jobId")
                .isNotBlank();
        // Flink jobId 为 32 字符 hex UUID
        assertThat(submittedJobId).matches("[0-9a-f]{32}");
        System.out.println("[IT] Flink 作业已提交: jobId=" + submittedJobId);
    }

    /**
     * 测试4: 查询作业状态（真实 GET /jobs/{jobId}）。
     *
     * <p>验证提交的作业在 Flink 集群中可查询，状态为 RUNNING/FINISHED/FAILED 等。
     */
    @Test
    @Order(4)
    void getJobStatus_submittedJobIsQueryable() {
        assertThat(submittedJobId).as("前置测试 runJar 须先通过").isNotBlank();

        ResponseEntity<String> resp = restTemplate.getForEntity(
                FLINK_REST + "/jobs/" + submittedJobId, String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode root = parseJson(resp.getBody());
        String jobId = root.path("jid").asText();
        String state = root.path("state").asText();

        assertThat(jobId).isEqualTo(submittedJobId);
        assertThat(state)
                .as("作业状态应为 Flink 有效状态之一")
                .isIn("INITIALIZING", "CREATED", "RUNNING", "FAILING", "FAILED",
                        "CANCELLING", "CANCELED", "FINISHED", "RESTARTING");
        System.out.println("[IT] Flink 作业状态: jobId=" + jobId + ", state=" + state);
    }

    /**
     * 测试5: 取消作业（真实 PATCH /jobs/{jobId}/cancel）。
     *
     * <p>验证 FlinkRestClient.cancel 对真实作业执行取消，不抛异常。
     * 作业可能已 FINISHED（WordCount 是有界作业），cancel 仍应幂等不抛。
     */
    @Test
    @Order(5)
    void cancel_realJobDoesNotThrow() {
        assertThat(submittedJobId).as("前置测试 runJar 须先通过").isNotBlank();

        // cancel 内部捕获 RestClientException，不抛异常（幂等语义）
        flinkRestClient.cancel(submittedJobId);
        System.out.println("[IT] Flink 作业取消请求已发送: jobId=" + submittedJobId);
    }

    /**
     * 测试6: 验证错误 jar 路径返回正确错误。
     *
     * <p>uploadJar 对不存在的 jar 路径抛 IOException（"jar 不存在"）。
     */
    @Test
    @Order(6)
    void uploadJar_nonexistentPathThrowsIOException() {
        String nonexistentPath = "/tmp/nonexistent-flink-job-" + System.currentTimeMillis() + ".jar";

        assertThatThrownBy(() -> flinkRestClient.uploadJar(nonexistentPath))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jar 不存在");
    }

    /**
     * 测试7: 验证空 jar 路径返回 IllegalArgumentException。
     */
    @Test
    @Order(7)
    void uploadJar_blankPathThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> flinkRestClient.uploadJar(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jarPath");
    }

    /**
     * 解析 JSON 响应。
     */
    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("解析 Flink REST 响应失败: " + body, e);
        }
    }
}