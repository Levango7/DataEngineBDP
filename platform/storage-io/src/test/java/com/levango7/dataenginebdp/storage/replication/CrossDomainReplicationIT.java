package com.levango7.dataenginebdp.storage.replication;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.levango7.dataenginebdp.storage.ObjectStoreFactory;
import com.levango7.dataenginebdp.storage.TenantPathMapper;
import com.levango7.dataenginebdp.storage.api.ObjectStore;
import com.levango7.dataenginebdp.storage.api.StorageProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨域数据面 POC 集成测试：真实双 S3 兼容端点上打通“源域写入 → 跨域复制 → 目标域读取 → sha256 校验”。
 *
 * <p>仅在 {@code -Dreplication.it=true} 时运行，需要两个真实 S3 兼容端点（compose 缺省为 MinIO，受限环境亦可换用 LocalStack 等）：
 * <pre>
 * docker compose -f platform/storage-io/docker/docker-compose.replication.yml up -d
 * mvn -pl platform/storage-io test -Dtest=CrossDomainReplicationIT -Dreplication.it=true
 * </pre>
 * 或直接运行编排脚本 {@code scripts/infra/test-replication-it.sh}。
 *
 * <p>产物：真实运行的 JSON + Markdown 报告（含逐对象 sha256、字节数、耗时），
 * 写入 {@code tests/cross-domain-replication/results/}，供验证脚本断言“非 simulate”。
 */
@EnabledIfSystemProperty(named = "replication.it", matches = "true")
class CrossDomainReplicationIT {

    /** 系统级复制前缀：无租户上下文时必须走 _system/，否则存储访问 fail-closed。 */
    private static final String PREFIX = "_system/xdomain/demo/";
    private static final String KEY_STABLE = PREFIX + "stable.bin";
    private static final String KEY_OVERWRITE = PREFIX + "conflict-overwrite.bin";
    private static final String KEY_SKIP = PREFIX + "conflict-skip.bin";

    private String sourceEndpoint;
    private String targetEndpoint;
    private String bucket;

    private ObjectStore source;
    private ObjectStore target;

    @BeforeEach
    void setUp() {
        sourceEndpoint = env("CDR_SOURCE_ENDPOINT", "http://localhost:9100");
        targetEndpoint = env("CDR_TARGET_ENDPOINT", "http://localhost:9110");
        String accessKey = env("CDR_ACCESS_KEY", "minioadmin");
        String secretKey = env("CDR_SECRET_KEY", "minioadmin");
        bucket = env("CDR_BUCKET", "xdomain");

        // 系统级复制：显式传入 null 租户上下文，配合 _system/ 前缀直通
        TenantPathMapper tenantMapper = new TenantPathMapper((String) null);
        source = ObjectStoreFactory.create(profile(sourceEndpoint, accessKey, secretKey), tenantMapper);
        target = ObjectStoreFactory.create(profile(targetEndpoint, accessKey, secretKey), tenantMapper);

        clearPrefix(source, PREFIX);
        clearPrefix(target, PREFIX);
    }

    @Test
    @DisplayName("真实跨域复制：COPY + LWW 覆盖 + LWW 跳过 + sha256 端到端校验 + 幂等复跑")
    void replicate_realCrossDomainLink() throws Exception {
        byte[] stable = bytes("stable-content", 4096);
        byte[] oldSource = bytes("old-source", 2048);
        byte[] newSource = bytes("new-source", 3072);
        byte[] newerTarget = bytes("target-newer", 5120);

        // 1) 常规对象：源有、目标无 → COPY
        put(source, KEY_STABLE, stable);

        // 2) 冲突-源更新：先写目标（旧），后写源（新）→ 源 lastModified 更晚 → OVERWRITE
        put(target, KEY_OVERWRITE, oldSource);
        sleepForSecondBoundary();
        put(source, KEY_OVERWRITE, newSource);

        // 3) 冲突-目标更新：先写源（旧），后写目标（新）→ 目标 lastModified 更晚 → SKIP
        put(source, KEY_SKIP, bytes("skip-old-source", 1024));
        sleepForSecondBoundary();
        put(target, KEY_SKIP, newerTarget);

        ObjectReplicator replicator = new ObjectReplicator(source, target);
        ReplicationReport report = replicator.replicate(PREFIX);

        assertThat(report.getMode()).isEqualTo("real");
        assertThat(report.getSourceEndpoint()).isEqualTo(sourceEndpoint);
        assertThat(report.getTargetEndpoint()).isEqualTo(targetEndpoint);
        assertThat(report.getTotalObjects()).isEqualTo(3);
        assertThat(report.getCopied()).isEqualTo(1);
        assertThat(report.getOverwritten()).isEqualTo(1);
        assertThat(report.getSkipped()).isEqualTo(1);
        assertThat(report.getFailed()).isZero();
        assertThat(report.getVerified()).isEqualTo(2);
        assertThat(report.isAllVerified()).isTrue();

        // 端到端独立读回校验（不复用复制器内部 digest）
        assertThat(target.getObjectAsBytes(KEY_STABLE)).isEqualTo(stable);
        assertThat(target.getObjectAsBytes(KEY_OVERWRITE)).isEqualTo(newSource);
        assertThat(target.getObjectAsBytes(KEY_SKIP)).isEqualTo(newerTarget);
        assertThat(sha256(target.getObjectAsBytes(KEY_STABLE))).isEqualTo(sha256(stable));
        assertThat(sha256(target.getObjectAsBytes(KEY_OVERWRITE))).isEqualTo(sha256(newSource));

        // 幂等复跑：已一致对象全部收敛为 SKIP，不再传输字节
        ReplicationReport second = replicator.replicate(PREFIX);
        assertThat(second.getFailed()).isZero();
        assertThat(second.getCopied()).isZero();
        assertThat(second.getOverwritten()).isZero();
        assertThat(second.getSkipped()).isEqualTo(3);
        assertThat(second.getTotalBytes()).isZero();

        writeArtifacts(report, second);
    }

    // ------------------------------------------------------------------
    // 断言辅助 / 产物输出
    // ------------------------------------------------------------------

    private void writeArtifacts(ReplicationReport firstRun, ReplicationReport secondRun) throws Exception {
        Path dir = resolveResultsDir();
        Files.createDirectories(dir);

        ObjectMapper json = new ObjectMapper();
        json.enable(SerializationFeature.INDENT_OUTPUT);
        json.registerModule(instantModule());

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        writeArtifact(dir, json, firstRun, secondRun, "cdr-report-" + stamp);
        writeArtifact(dir, json, firstRun, secondRun, "cdr-report-latest");
    }

    private void writeArtifact(Path dir, ObjectMapper json, ReplicationReport firstRun,
                               ReplicationReport secondRun, String baseName) throws Exception {
        Files.writeString(dir.resolve(baseName + ".json"), json.writeValueAsString(firstRun));
        Files.writeString(dir.resolve(baseName + ".md"), toMarkdown(firstRun, secondRun));
    }

    /**
     * 注册 Instant → ISO-8601 字符串的极简序列化模块。
     *
     * <p><b>隐式依赖</b>：报告模型含 {@link Instant}。Jackson 2 在未注册
     * jackson-datatype-jsr310 时会抛 InvalidDefinitionException；此处仅用已声明的
     * jackson-databind 自建模块，避免为产物输出额外引入运行期依赖。
     */
    private static SimpleModule instantModule() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
        return module;
    }

    private String toMarkdown(ReplicationReport first, ReplicationReport second) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 跨域数据面复制验证报告（真实运行产物）\n\n");
        sb.append("> 模式: real（非 simulate）｜ 源: ").append(sourceEndpoint)
                .append(" ｜ 目标: ").append(targetEndpoint)
                .append(" ｜ 桶: ").append(bucket).append("\n\n");

        sb.append("## 1. 环境\n\n");
        sb.append("| 项 | 值 |\n| --- | --- |\n");
        sb.append("| 源端点 | ").append(sourceEndpoint).append(" |\n");
        sb.append("| 目标端点 | ").append(targetEndpoint).append(" |\n");
        sb.append("| 桶 | ").append(bucket).append(" |\n");
        sb.append("| 复制前缀 | ").append(first.getSourcePrefix()).append(" |\n");
        sb.append("| 开始 | ").append(first.getStartedAt()).append(" |\n");
        sb.append("| 结束 | ").append(first.getFinishedAt()).append(" |\n");
        sb.append("| 耗时 | ").append(first.getElapsedMs()).append(" ms |\n\n");

        sb.append("## 2. 汇总\n\n");
        sb.append("| 指标 | 值 |\n| --- | --- |\n");
        sb.append("| 扫描对象数 | ").append(first.getTotalObjects()).append(" |\n");
        sb.append("| COPY | ").append(first.getCopied()).append(" |\n");
        sb.append("| OVERWRITE（LWW 源更新） | ").append(first.getOverwritten()).append(" |\n");
        sb.append("| SKIP（LWW 目标更新） | ").append(first.getSkipped()).append(" |\n");
        sb.append("| FAILED | ").append(first.getFailed()).append(" |\n");
        sb.append("| sha256 校验通过 | ").append(first.getVerified()).append(" |\n");
        sb.append("| 传输字节 | ").append(first.getTotalBytes()).append(" |\n");
        sb.append("| 全部校验通过 | ").append(first.isAllVerified()).append(" |\n\n");

        sb.append("## 3. 逐对象结果\n\n");
        sb.append("| 对象键 | 动作 | 源字节 | 目标字节 | 源 sha256 | 目标 sha256 | 校验 | 耗时(ms) |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (ReplicationItemResult item : first.getItems()) {
            sb.append("| ").append(item.getKey())
                    .append(" | ").append(item.getAction())
                    .append(" | ").append(item.getSourceSize())
                    .append(" | ").append(item.getTargetSize())
                    .append(" | ").append(item.getSourceSha256())
                    .append(" | ").append(item.getTargetSha256())
                    .append(" | ").append(item.isVerified())
                    .append(" | ").append(item.getElapsedMs()).append(" |\n");
        }

        sb.append("\n> 说明：SKIP 行未传输字节，故源/目标 sha256 为空、校验列恒为 false，")
                .append("表示“不适用”，并非“校验失败”；仅 COPY / OVERWRITE 参与 sha256 校验。\n\n");

        sb.append("## 4. 幂等复跑（第二次执行）\n\n");
        sb.append("| 指标 | 值 |\n| --- | --- |\n");
        sb.append("| COPY | ").append(second.getCopied()).append(" |\n");
        sb.append("| OVERWRITE | ").append(second.getOverwritten()).append(" |\n");
        sb.append("| SKIP | ").append(second.getSkipped()).append(" |\n");
        sb.append("| FAILED | ").append(second.getFailed()).append(" |\n");
        sb.append("| 传输字节 | ").append(second.getTotalBytes()).append(" |\n\n");

        sb.append("> 本报告由 CrossDomainReplicationIT 在真实 S3 兼容端点上运行后生成（端点见上表），")
                .append("sha256 / 字节数 / 耗时均来自实际数据，无 simulate 分支。\n");
        return sb.toString();
    }

    private static Path resolveResultsDir() {
        String configured = System.getProperty("cdr.resultsDir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("CDR_RESULTS_DIR");
        }
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        // 默认：maven 在模块目录执行，../../tests/... 即仓库根下
        return Paths.get(System.getProperty("user.dir"), "..", "..",
                "tests", "cross-domain-replication", "results").toAbsolutePath().normalize();
    }

    private StorageProfile profile(String endpoint, String accessKey, String secretKey) {
        return StorageProfile.builder()
                .storageType("minio")
                .endpoint(endpoint)
                .bucket(bucket)
                .accessKey(accessKey)
                .secretKey(secretKey)
                .region("us-east-1")
                .pathStyleAccess(true)
                .build();
    }

    private static void clearPrefix(ObjectStore store, String prefix) {
        for (String key : store.listObjects(prefix)) {
            store.deleteObject(key);
        }
    }

    private static void put(ObjectStore store, String key, byte[] data) {
        store.putObject(key, new ByteArrayInputStream(data), data.length, "application/octet-stream");
    }

    private static byte[] bytes(String seed, int size) {
        byte[] data = new byte[size];
        byte[] s = seed.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < size; i++) {
            data[i] = s[i % s.length];
        }
        return data;
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest.digest(data)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** S3 Last-Modified 秒级精度，跨过 1s 边界确保 LWW 版本可区分。 */
    private static void sleepForSecondBoundary() throws InterruptedException {
        Thread.sleep(1100L);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
