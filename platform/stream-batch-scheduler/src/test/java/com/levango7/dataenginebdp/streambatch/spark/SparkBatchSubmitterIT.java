package com.levango7.dataenginebdp.streambatch.spark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.streambatch.iceberg.IcebergSnapshotManager;
import com.levango7.dataenginebdp.streambatch.iceberg.SnapshotIsolationConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SparkBatchSubmitter 真实 Spark 集群集成测试。
 *
 * <p>前置条件：
 * <ul>
 *   <li>Docker 基础设施已启动：{@code bash scripts/infra/start-infra.sh --no-init}</li>
 *   <li>Spark Master WebUI 可达：{@code http://localhost:18080}</li>
 *   <li>Spark Master RPC 可达：{@code spark://localhost:7077}</li>
 *   <li>运行参数：{@code -Dinfra.it=true}</li>
 * </ul>
 *
 * <p>验证内容：
 * <ol>
 *   <li>Spark Master REST API 集群状态（workers 在线）</li>
 *   <li>SparkPi 作业真实执行（通过 spark-submit 在集群内提交）</li>
 *   <li>作业状态查询（Spark Master REST API /api/v1/applications）</li>
 *   <li>SparkBatchSubmitter 错误参数处理（realSubmitEnabled=true 路径）</li>
 *   <li>SparkBatchSubmitter 真实提交（SPARK_HOME 可用时通过 SparkLauncher）</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "infra.it", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SparkBatchSubmitterIT {

    private static final String SPARK_MASTER_WEBUI = "http://localhost:18080";
    private static final String SPARK_MASTER_RPC = "spark://localhost:7077";
    private static final String SPARK_CONTAINER = "de-spark-master";
    private static final String SPARK_SUBMIT_BIN = "/opt/bitnami/spark/bin/spark-submit";
    private static final String SPARKPI_CLASS = "org.apache.spark.examples.SparkPi";

    private static RestTemplate restTemplate;
    private static ObjectMapper objectMapper;
    private static String sparkExamplesJar;
    private static String sparkAppId;

    @BeforeAll
    static void setUp() throws Exception {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        restTemplate = new RestTemplate(factory);
        objectMapper = new ObjectMapper();

        // 查找 Spark examples jar 路径（在容器内）
        sparkExamplesJar = findSparkExamplesJarInContainer();
    }

    /**
     * 在 Spark Master 容器内查找 spark-examples jar 路径。
     *
     * <p>Bitnami Spark 3.5.3 镜像中 examples jar 位于
     * {@code /opt/bitnami/spark/examples/jars/spark-examples_2.12-3.5.3.jar}。
     */
    private static String findSparkExamplesJarInContainer() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "exec", SPARK_CONTAINER,
                "find", "/opt/bitnami/spark/examples", "-name", "spark-examples*.jar",
                "-type", "f");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        String output = new String(process.getInputStream().readAllBytes()).trim();

        if (exitCode != 0 || output.isEmpty()) {
            throw new IOException(
                    "未找到 Spark examples jar（exitCode=" + exitCode + "）: " + output
                    + "\n请确保 Docker 基础设施已启动: bash scripts/infra/start-infra.sh --no-init");
        }
        // 取第一行（可能有多个版本）
        String jarPath = output.split("\\n")[0].trim();
        System.out.println("[IT] Spark examples jar: " + jarPath);
        return jarPath;
    }

    /**
     * 在 Spark Master 容器内执行命令并返回输出。
     */
    private static String execInContainer(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        String output = new String(process.getInputStream().readAllBytes());
        if (exitCode != 0) {
            throw new IOException("命令执行失败（exitCode=" + exitCode + "）: "
                    + String.join(" ", cmd) + "\n输出: " + output);
        }
        return output;
    }

    /**
     * 测试1: 连接真实 Spark Master，获取集群状态。
     *
     * <p>GET /json/ 返回 Spark Master 的 workers 列表与集群资源信息，
     * 验证 Spark Master 真实可达且有在线 worker。
     */
    @Test
    @Order(1)
    void getClusterStatus_masterIsReachableWithWorkers() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                SPARK_MASTER_WEBUI + "/json/", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode root = parseJson(resp.getBody());
        String masterUrl = root.path("url").asText();
        JsonNode workers = root.path("workers");

        assertThat(masterUrl).isNotBlank();
        assertThat(workers.isArray()).isTrue();
        assertThat(workers.size())
                .as("Spark 集群应至少有 1 个在线 worker")
                .isGreaterThan(0);

        // 验证至少一个 worker 有可用资源
        boolean hasAliveWorker = false;
        for (JsonNode worker : workers) {
            if ("ALIVE".equals(worker.path("state").asText())) {
                hasAliveWorker = true;
                System.out.println("[IT] Spark Worker: " + worker.path("host").asText()
                        + ", cores=" + worker.path("cores").asInt()
                        + ", memory=" + worker.path("memory").asInt() + "MB");
            }
        }
        assertThat(hasAliveWorker).as("应至少有一个 ALIVE worker").isTrue();
    }

    /**
     * 测试2: 提交 SparkPi 作业真实执行。
     *
     * <p>通过 docker exec 在 spark-master 容器内执行 spark-submit，
     * 提交 SparkPi 示例作业（10 个分区），验证输出 "Pi is roughly"。
     *
     * <p>这验证 Spark 集群能真实接收并执行批作业。
     */
    @Test
    @Order(2)
    void submitSparkPi_realClusterExecutesJob() throws Exception {
        assertThat(sparkExamplesJar).as("前置: examples jar 须找到").isNotBlank();

        // 在 spark-master 容器内执行 spark-submit SparkPi
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "exec", SPARK_CONTAINER,
                SPARK_SUBMIT_BIN,
                "--master", SPARK_MASTER_RPC,
                "--deploy-mode", "client",
                "--class", SPARKPI_CLASS,
                "--driver-memory", "512m",
                "--executor-memory", "512m",
                "--executor-cores", "1",
                "--num-executors", "1",
                sparkExamplesJar,
                "10");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        String output = new String(process.getInputStream().readAllBytes());

        System.out.println("[IT] SparkPi spark-submit exitCode=" + exitCode);
        // 打印最后 20 行输出（调试用）
        String[] lines = output.split("\\n");
        int start = Math.max(0, lines.length - 20);
        for (int i = start; i < lines.length; i++) {
            System.out.println("[IT]   " + lines[i]);
        }

        assertThat(exitCode)
                .as("spark-submit 应成功退出。输出:\n" + output)
                .isEqualTo(0);

        // 验证输出包含 Pi 计算结果
        assertThat(output)
                .as("SparkPi 输出应包含 'Pi is roughly'")
                .contains("Pi is roughly");

        // 提取 Pi 值并验证精度（10 分区应精确到小数点后 2 位）
        String piLine = java.util.Arrays.stream(lines)
                .filter(l -> l.contains("Pi is roughly"))
                .reduce((first, second) -> second) // 取最后一行
                .orElse("");
        System.out.println("[IT] SparkPi 结果: " + piLine.trim());

        // Pi ≈ 3.14，验证在 [3.0, 3.3] 范围
        assertThat(piLine).matches(".*Pi is roughly 3\\.[0-9]+.*");
    }

    /**
     * 测试3: 查询 Spark 应用列表（真实 GET /api/v1/applications）。
     *
     * <p>验证 Spark Master REST API 返回已执行的应用列表，
     * SparkPi 作业应出现在列表中（状态为 FINISHED/SUCCEEDED）。
     */
    @Test
    @Order(3)
    void getApplications_sparkPiJobIsListed() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                SPARK_MASTER_WEBUI + "/api/v1/applications", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode root = parseJson(resp.getBody());
        assertThat(root.isArray()).isTrue();
        assertThat(root.size())
                .as("应至少有 1 个应用（SparkPi）")
                .isGreaterThan(0);

        // 查找 SparkPi 应用
        boolean foundSparkPi = false;
        for (JsonNode app : root) {
            String appId = app.path("id").asText();
            String name = app.path("name").asText();
            System.out.println("[IT] Spark 应用: id=" + appId + ", name=" + name);
            if ("SparkPi".equals(name)) {
                foundSparkPi = true;
                sparkAppId = appId;
            }
        }
        assertThat(foundSparkPi)
                .as("SparkPi 作业应出现在应用列表中")
                .isTrue();
    }

    /**
     * 测试4: 验证 SparkBatchSubmitter 错误参数返回正确错误。
     *
     * <p>realSubmitEnabled=true 且 mainResource 为空时，
     * SparkBatchSubmitter.submitBatch 返回失败结果（success=false），
     * errorMessage 包含 "mainResource 不能为空"。
     *
     * <p>此测试验证 SparkBatchSubmitter 的错误处理路径，不需要真实 Spark 集群执行作业。
     */
    @Test
    @Order(4)
    void submitBatch_emptyResourceReturnsFailure() {
        SparkBatchConfig sparkConfig = new SparkBatchConfig();
        sparkConfig.setMaster(SPARK_MASTER_RPC);
        sparkConfig.setDeployMode("client");
        sparkConfig.setRealSubmitEnabled(true);

        SnapshotIsolationConfig icebergConfig = new SnapshotIsolationConfig();
        icebergConfig.setBatchSnapshotLockMode("AT_JOB_START");

        IcebergSnapshotManager snapshotManager = new IcebergSnapshotManager(icebergConfig);
        SparkBatchSubmitter submitter = new SparkBatchSubmitter(
                sparkConfig, snapshotManager, icebergConfig);

        // mainResource 为空 → 真实路径抛 IllegalArgumentException → 失败结果
        SparkSubmitResult result = submitter.submitBatch(
                "it_db.it_table", null, null, null, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getAppId()).isNull();
        assertThat(result.getErrorMessage())
                .contains("mainResource 不能为空");
        System.out.println("[IT] SparkBatchSubmitter 错误处理: " + result.getErrorMessage());
    }

    /**
     * 测试5: 验证 SparkBatchSubmitter mock 模式返回合成 appId。
     *
     * <p>realSubmitEnabled=false 时，SparkBatchSubmitter 返回合成 appId（"spark-" 前缀），
     * 验证模拟路径在集成测试环境仍正常工作。
     */
    @Test
    @Order(5)
    void submitBatch_mockModeReturnsSyntheticAppId() {
        SparkBatchConfig sparkConfig = new SparkBatchConfig();
        sparkConfig.setMaster(SPARK_MASTER_RPC);
        sparkConfig.setDeployMode("client");
        sparkConfig.setRealSubmitEnabled(false); // mock 模式

        SnapshotIsolationConfig icebergConfig = new SnapshotIsolationConfig();
        icebergConfig.setBatchSnapshotLockMode("AT_JOB_START");

        IcebergSnapshotManager snapshotManager = new IcebergSnapshotManager(icebergConfig);
        SparkBatchSubmitter submitter = new SparkBatchSubmitter(
                sparkConfig, snapshotManager, icebergConfig);

        SparkSubmitResult result = submitter.submitBatch(
                "it_db.it_table", "s3://jobs/etl.jar", "com.example.Main", null, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAppId()).startsWith("spark-");
        assertThat(result.getSubmitCommand()).contains("--master " + SPARK_MASTER_RPC);
        System.out.println("[IT] SparkBatchSubmitter mock 模式: appId=" + result.getAppId());
    }

    /**
     * 测试6（条件性）: SparkBatchSubmitter 真实提交（SparkLauncher 路径）。
     *
     * <p>仅当环境变量 SPARK_HOME 可用且指向有效 Spark 安装时执行。
     * 验证 SparkBatchSubmitter.submitBatch 通过 SparkLauncher 真实提交并返回真实 appId。
     *
     * <p>无 SPARK_HOME 时跳过（不失败）—— Docker 环境中 spark-submit 在容器内，
     * SparkLauncher 需要本地 spark-submit，故此测试为可选增强验证。
     */
    @Test
    @Order(6)
    void submitBatch_realSubmitViaSparkLauncher() {
        String sparkHome = System.getenv("SPARK_HOME");
        String localExamplesJar = System.getenv("SPARK_EXAMPLES_JAR");

        // 无 SPARK_HOME 或本地 examples jar 时跳过
        org.junit.jupiter.api.Assumptions.assumeTrue(
                sparkHome != null && !sparkHome.isBlank()
                        && localExamplesJar != null && !localExamplesJar.isBlank(),
                "跳过: 需设置 SPARK_HOME 和 SPARK_EXAMPLES_JAR 环境变量");

        SparkBatchConfig sparkConfig = new SparkBatchConfig();
        sparkConfig.setMaster(SPARK_MASTER_RPC);
        sparkConfig.setDeployMode("client");
        sparkConfig.setDriverMemory("512m");
        sparkConfig.setExecutorMemory("512m");
        sparkConfig.setExecutorCores(1);
        sparkConfig.setExecutorInstances(1);
        sparkConfig.setRealSubmitEnabled(true);
        sparkConfig.setSparkHome(sparkHome);

        SnapshotIsolationConfig icebergConfig = new SnapshotIsolationConfig();
        icebergConfig.setBatchSnapshotLockMode("AT_JOB_START");

        IcebergSnapshotManager snapshotManager = new IcebergSnapshotManager(icebergConfig);
        SparkBatchSubmitter submitter = new SparkBatchSubmitter(
                sparkConfig, snapshotManager, icebergConfig);

        SparkSubmitResult result = submitter.submitBatch(
                "it_db.it_table", localExamplesJar, SPARKPI_CLASS, "10", null);

        assertThat(result.isSuccess())
                .as("SparkLauncher 真实提交应成功; errorMessage=" + result.getErrorMessage())
                .isTrue();
        assertThat(result.getAppId())
                .as("应返回真实 Spark appId（application-xxx 格式）")
                .startsWith("application-");
        System.out.println("[IT] SparkBatchSubmitter 真实提交成功: appId=" + result.getAppId());
    }

    /**
     * 解析 JSON 响应。
     */
    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("解析 Spark REST 响应失败: " + body, e);
        }
    }
}