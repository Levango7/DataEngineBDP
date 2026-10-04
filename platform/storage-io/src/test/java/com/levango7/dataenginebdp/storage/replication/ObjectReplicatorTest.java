package com.levango7.dataenginebdp.storage.replication;

import com.levango7.dataenginebdp.storage.api.ObjectMetadata;
import com.levango7.dataenginebdp.storage.api.ObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ObjectReplicator 单元测试（内存 ObjectStore 替身，无 Docker / 无真实网络）。
 *
 * <p>覆盖：COPY / OVERWRITE / SKIP / FAILED 四类动作、计数与字节统计、流式 sha256 正确性、
 * 前缀过滤、幂等复跑、源对象消失、目标内容不一致、自定义冲突策略注入。
 *
 * <p><b>依赖注入说明</b>：被测类 {@link ObjectReplicator} 通过构造器注入两个
 * {@link com.levango7.dataenginebdp.storage.api.ObjectStore} 与 {@link ConflictPolicy}，
 * 无 Spring 容器。本类注入的是有状态的内存 {@link InMemoryObjectStore}（Fake），
 * 而非 Mockito Mock —— 因为 sha256 校验与 LWW 都依赖真实字节读回与真实时间戳状态，
 * Mock 的 thenReturn 会使校验变成自证。需要强制异常或交互验证的场景见
 * {@link ObjectReplicatorMockTest}。
 */
class ObjectReplicatorTest {

    private static final String PREFIX = "_system/xdomain/demo/";
    private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2020-01-01T00:00:10Z");

    private InMemoryObjectStore source;
    private InMemoryObjectStore target;
    private ObjectReplicator replicator;

    @BeforeEach
    void setUp() {
        source = new InMemoryObjectStore("mem://source");
        target = new InMemoryObjectStore("mem://target");
        replicator = new ObjectReplicator(source, target);
    }

    @Test
    @DisplayName("目标不存在 → COPY，内容一致且 sha256 校验通过")
    void copy_newObject() throws Exception {
        byte[] content = bytes("hello-cross-domain", 4096);
        source.putAt(PREFIX + "a.bin", content, T0);

        ReplicationReport report = replicator.replicate(PREFIX);

        assertThat(report.getMode()).isEqualTo("real");
        assertThat(report.getSourceEndpoint()).isEqualTo("mem://source");
        assertThat(report.getTargetEndpoint()).isEqualTo("mem://target");
        assertThat(report.getTotalObjects()).isEqualTo(1);
        assertThat(report.getCopied()).isEqualTo(1);
        assertThat(report.getOverwritten()).isZero();
        assertThat(report.getSkipped()).isZero();
        assertThat(report.getFailed()).isZero();
        assertThat(report.getVerified()).isEqualTo(1);
        assertThat(report.getTotalBytes()).isEqualTo(content.length);
        assertThat(report.isAllVerified()).isTrue();

        ReplicationItemResult item = report.getItems().get(0);
        assertThat(item.getAction()).isEqualTo(ReplicationAction.COPY);
        assertThat(item.getSourceSha256()).hasSize(64).isEqualTo(sha256(content));
        assertThat(item.getTargetSha256()).isEqualTo(item.getSourceSha256());
        assertThat(item.isVerified()).isTrue();
        assertThat(target.getObjectAsBytes(PREFIX + "a.bin")).isEqualTo(content);
    }

    @Test
    @DisplayName("目标更旧 → OVERWRITE（LWW 源优先），目标被覆盖")
    void overwrite_whenSourceNewer() {
        byte[] newer = bytes("source-newer", 2048);
        byte[] older = bytes("target-older", 1024);
        target.putAt(PREFIX + "b.bin", older, T0);
        source.putAt(PREFIX + "b.bin", newer, T1);

        ReplicationReport report = replicator.replicate(PREFIX);

        assertThat(report.getOverwritten()).isEqualTo(1);
        assertThat(report.getCopied()).isZero();
        assertThat(report.getFailed()).isZero();
        assertThat(report.getVerified()).isEqualTo(1);
        assertThat(report.getTotalBytes()).isEqualTo(newer.length);
        assertThat(target.getObjectAsBytes(PREFIX + "b.bin")).isEqualTo(newer);
    }

    @Test
    @DisplayName("目标更新 → SKIP，不回退且不传字节")
    void skip_whenTargetNewer() {
        byte[] older = bytes("source-older", 1024);
        byte[] newer = bytes("target-newer", 3072);
        source.putAt(PREFIX + "c.bin", older, T0);
        target.putAt(PREFIX + "c.bin", newer, T1);

        ReplicationReport report = replicator.replicate(PREFIX);

        assertThat(report.getSkipped()).isEqualTo(1);
        assertThat(report.getCopied()).isZero();
        assertThat(report.getOverwritten()).isZero();
        assertThat(report.getFailed()).isZero();
        assertThat(report.getVerified()).isZero();
        assertThat(report.getTotalBytes()).isZero();
        assertThat(report.isAllVerified()).isTrue();
        assertThat(target.getObjectAsBytes(PREFIX + "c.bin")).isEqualTo(newer);

        ReplicationItemResult item = report.getItems().get(0);
        assertThat(item.getAction()).isEqualTo(ReplicationAction.SKIP);
        assertThat(item.isVerified()).isFalse();
        assertThat(item.getTargetSize()).isEqualTo(newer.length);
    }

    @Test
    @DisplayName("目标写入内容不一致 → FAILED，不通过校验且不计字节")
    void fail_whenTargetContentMismatch() {
        source.putAt(PREFIX + "d.bin", bytes("payload", 1024), T0);
        target.corruptOnPut = true;

        ReplicationReport report = replicator.replicate(PREFIX);

        assertThat(report.getFailed()).isEqualTo(1);
        assertThat(report.getCopied()).isZero();
        assertThat(report.getVerified()).isZero();
        assertThat(report.getTotalBytes()).isZero();
        assertThat(report.isAllVerified()).isFalse();

        ReplicationItemResult item = report.getItems().get(0);
        assertThat(item.getAction()).isEqualTo(ReplicationAction.FAILED);
        assertThat(item.getError()).contains("sha256 不一致");
        assertThat(item.isVerified()).isFalse();
    }

    @Test
    @DisplayName("源对象在 list 与 stat 之间消失 → FAILED 且记录原因")
    void fail_whenSourceVanished() {
        source.addPhantomKey(PREFIX + "ghost.bin");

        ReplicationReport report = replicator.replicate(PREFIX);

        assertThat(report.getTotalObjects()).isEqualTo(1);
        assertThat(report.getFailed()).isEqualTo(1);
        assertThat(report.getItems().get(0).getError()).contains("源对象不存在");
    }

    @Test
    @DisplayName("仅处理指定前缀，前缀外对象不参与")
    void onlyReplicatesRequestedPrefix() {
        source.putAt(PREFIX + "in.bin", bytes("in", 128), T0);
        source.putAt("_system/xdomain/other/out.bin", bytes("out", 128), T0);

        ReplicationReport report = replicator.replicate(PREFIX);

        assertThat(report.getTotalObjects()).isEqualTo(1);
        assertThat(report.getItems().get(0).getKey()).isEqualTo(PREFIX + "in.bin");
    }

    @Test
    @DisplayName("幂等：复跑已一致对象全部 SKIP，零字节传输")
    void idempotent_secondRunAllSkip() {
        source.putAt(PREFIX + "e.bin", bytes("stable", 2048), T0);

        ReplicationReport first = replicator.replicate(PREFIX);
        assertThat(first.getCopied()).isEqualTo(1);

        ReplicationReport second = replicator.replicate(PREFIX);
        assertThat(second.getCopied()).isZero();
        assertThat(second.getOverwritten()).isZero();
        assertThat(second.getSkipped()).isEqualTo(1);
        assertThat(second.getTotalBytes()).isZero();
        assertThat(second.isAllVerified()).isTrue();
    }

    @Test
    @DisplayName("可注入自定义冲突策略（构造器重载）")
    void customizableConflictPolicy() {
        source.putAt(PREFIX + "f.bin", bytes("x", 64), T0);
        ConflictPolicy alwaysSkip = (src, tgt) -> ReplicationAction.SKIP;
        ObjectReplicator skipReplicator = new ObjectReplicator(source, target, alwaysSkip);

        ReplicationReport report = skipReplicator.replicate(PREFIX);

        assertThat(report.getSkipped()).isEqualTo(1);
        assertThat(target.existsObject(PREFIX + "f.bin")).isFalse();
    }

    // -------------------- helpers --------------------

    private static byte[] bytes(String seed, int size) {
        byte[] data = new byte[size];
        byte[] s = seed.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < size; i++) {
            data[i] = s[i % s.length];
        }
        return data;
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 内存 ObjectStore 测试替身：真实读写字节、可注入 lastModified、可模拟写入篡改与“幻影键”。
     * 用于在无 Docker、无网络的前提下真实执行 ObjectReplicator 的分支。
     */
    static class InMemoryObjectStore implements ObjectStore {

        private final String endpoint;
        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final List<String> phantomKeys = new ArrayList<>();
        /** 显式强制时间戳（putAt 作用域内有效）；为 null 时按真实存储语义取写入当前时刻。 */
        private Instant forcedLastModified;
        boolean corruptOnPut = false;

        InMemoryObjectStore(String endpoint) {
            this.endpoint = endpoint;
        }

        /** 以指定 lastModified 写入（模拟历史版本）。 */
        void putAt(String key, byte[] data, Instant lastModified) {
            Instant previous = forcedLastModified;
            forcedLastModified = lastModified;
            putObject(key, new ByteArrayInputStream(data), data.length, "application/octet-stream");
            forcedLastModified = previous;
        }

        /** 注册只出现在 listObjects、却无实体对象的键（模拟 list 与 stat 之间被删除）。 */
        void addPhantomKey(String key) {
            phantomKeys.add(key);
        }

        @Override
        public void putObject(String key, InputStream inputStream, long contentLength, String contentType) {
            try {
                byte[] data = inputStream.readAllBytes();
                if (corruptOnPut && data.length > 0) {
                    data[data.length - 1] = (byte) (data[data.length - 1] ^ 0xFF);
                }
                // 真实存储语义：写入即刷新 lastModified 为当前时刻；putAt 可显式覆盖以模拟历史版本
                Instant lastModified = forcedLastModified != null ? forcedLastModified : Instant.now();
                entries.put(key, new Entry(data, lastModified, contentType));
            } catch (Exception e) {
                throw new IllegalStateException("内存 store 写入失败", e);
            }
        }

        @Override
        public InputStream getObject(String key) {
            Entry entry = entries.get(key);
            return entry == null ? null : new ByteArrayInputStream(entry.data);
        }

        @Override
        public byte[] getObjectAsBytes(String key) {
            Entry entry = entries.get(key);
            return entry == null ? null : entry.data.clone();
        }

        @Override
        public void deleteObject(String key) {
            entries.remove(key);
        }

        @Override
        public List<String> listObjects(String prefix) {
            List<String> keys = new ArrayList<>();
            for (String key : entries.keySet()) {
                if (key.startsWith(prefix)) {
                    keys.add(key);
                }
            }
            for (String key : phantomKeys) {
                if (key.startsWith(prefix)) {
                    keys.add(key);
                }
            }
            return keys;
        }

        @Override
        public boolean existsObject(String key) {
            return entries.containsKey(key);
        }

        @Override
        public ObjectMetadata statObject(String key) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return null;
            }
            return ObjectMetadata.builder()
                    .key(key)
                    .size(entry.data.length)
                    .lastModified(entry.lastModified)
                    .contentType(entry.contentType)
                    .build();
        }

        @Override
        public String endpoint() {
            return endpoint;
        }

        @Override
        public void createBucketIfNotExists(String bucket) {
            // 内存实现：无 bucket 概念
        }

        @Override
        public void close() {
            entries.clear();
        }

        static class Entry {
            final byte[] data;
            final Instant lastModified;
            final String contentType;

            Entry(byte[] data, Instant lastModified, String contentType) {
                this.data = data;
                this.lastModified = lastModified;
                this.contentType = contentType;
            }
        }
    }
}
