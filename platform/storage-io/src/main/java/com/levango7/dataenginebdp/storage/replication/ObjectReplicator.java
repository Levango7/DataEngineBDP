package com.levango7.dataenginebdp.storage.replication;

import com.levango7.dataenginebdp.storage.api.ObjectMetadata;
import com.levango7.dataenginebdp.storage.api.ObjectStore;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨域对象复制器：将源 {@link ObjectStore} 指定前缀下的对象复制到目标 {@link ObjectStore}。
 *
 * <p>链路（单链路纵向打通）：
 * <ol>
 *   <li>列出源前缀下全部对象键</li>
 *   <li>逐键 {@code statObject} 取源 / 目标元数据</li>
 *   <li>按 {@link ConflictPolicy}（默认 {@link LastWriteWinsPolicy}）决策 COPY / OVERWRITE / SKIP</li>
 *   <li>写入时用 {@link DigestInputStream} 边传边算源 sha256（流式，不整对象驻留内存）</li>
 *   <li>从目标读回内容再算 sha256，与源比对，得到 verified</li>
 * </ol>
 *
 * <p><b>一致性模型</b>：最终一致。复制幂等——重复执行同一前缀，已一致对象按 LWW 收敛为 SKIP。
 *
 * <p><b>隐式依赖</b>：本类只操作 {@link ObjectStore} 暴露的相对键；租户隔离由
 * {@code TenantPathMapper} 承担。若调用方未绑定租户上下文，<b>必须</b>使用 {@code _system/}
 * 前缀（该前缀在 {@code TenantPathMapper} 中直通、不叠加租户前缀），否则访问会 fail-closed。
 */
@Slf4j
public class ObjectReplicator {

    private static final int BUFFER_SIZE = 8192;
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final String SHA256 = "SHA-256";

    private final ObjectStore source;
    private final ObjectStore target;
    private final ConflictPolicy conflictPolicy;

    public ObjectReplicator(ObjectStore source, ObjectStore target) {
        this(source, target, new LastWriteWinsPolicy());
    }

    public ObjectReplicator(ObjectStore source, ObjectStore target, ConflictPolicy conflictPolicy) {
        this.source = source;
        this.target = target;
        this.conflictPolicy = conflictPolicy;
    }

    /**
     * 复制源前缀下的全部对象到目标。
     *
     * @param prefix 源相对键前缀（系统级复制请使用 {@code _system/} 前缀）
     * @return 真实汇总报告
     */
    public ReplicationReport replicate(String prefix) {
        long start = System.currentTimeMillis();
        List<String> keys = source.listObjects(prefix);

        int copied = 0;
        int overwritten = 0;
        int skipped = 0;
        int failed = 0;
        int verified = 0;
        long totalBytes = 0L;
        List<ReplicationItemResult> items = new ArrayList<>();

        for (String key : keys) {
            ReplicationItemResult item = replicateOne(key);
            items.add(item);
            switch (item.getAction()) {
                case COPY -> copied++;
                case OVERWRITE -> overwritten++;
                case SKIP -> skipped++;
                case FAILED -> failed++;
            }
            if (item.isVerified()) {
                verified++;
            }
            if (item.getAction() == ReplicationAction.COPY || item.getAction() == ReplicationAction.OVERWRITE) {
                totalBytes += item.getSourceSize();
            }
        }

        ReplicationReport report = ReplicationReport.builder()
                .mode("real")
                .sourceEndpoint(source.endpoint())
                .targetEndpoint(target.endpoint())
                .sourcePrefix(prefix)
                .startedAt(Instant.now())
                .finishedAt(Instant.now())
                .elapsedMs(System.currentTimeMillis() - start)
                .totalObjects(keys.size())
                .copied(copied)
                .overwritten(overwritten)
                .skipped(skipped)
                .failed(failed)
                .verified(verified)
                .totalBytes(totalBytes)
                .items(items)
                .build();

        log.info("跨域复制完成: prefix={} total={} copied={} overwritten={} skipped={} failed={} "
                        + "verified={} bytes={} elapsed={}ms",
                prefix, report.getTotalObjects(), copied, overwritten, skipped, failed,
                verified, totalBytes, report.getElapsedMs());
        return report;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private ReplicationItemResult replicateOne(String key) {
        long start = System.currentTimeMillis();
        try {
            ObjectMetadata sourceMeta = source.statObject(key);
            if (sourceMeta == null) {
                return failure(key, start, "源对象不存在（list 与 stat 之间被删除）");
            }
            ObjectMetadata targetMeta = target.statObject(key);
            ReplicationAction action = conflictPolicy.decide(sourceMeta, targetMeta);

            if (action == ReplicationAction.SKIP) {
                log.debug("LWW 跳过（目标更新）: key={}", key);
                return ReplicationItemResult.builder()
                        .key(key)
                        .action(ReplicationAction.SKIP)
                        .sourceSize(sourceMeta.getSize())
                        .targetSize(targetMeta != null ? targetMeta.getSize() : 0L)
                        .verified(false)
                        .elapsedMs(System.currentTimeMillis() - start)
                        .build();
            }
            return copyAndVerify(key, sourceMeta, action, start);
        } catch (Exception e) {
            log.error("复制对象失败: key={} err={}", key, e.getMessage(), e);
            return failure(key, start, e.getMessage());
        }
    }

    private ReplicationItemResult copyAndVerify(String key, ObjectMetadata sourceMeta,
                                                ReplicationAction action, long start) throws Exception {
        String contentType = sourceMeta.getContentType() != null
                ? sourceMeta.getContentType() : DEFAULT_CONTENT_TYPE;

        // 流式复制：读取源对象的同时计算 sha256，避免整对象驻留内存
        MessageDigest digest = MessageDigest.getInstance(SHA256);
        InputStream raw = source.getObject(key);
        if (raw == null) {
            return failure(key, start, "源对象读取返回 null");
        }

        String sourceSha;
        try (DigestInputStream in = new DigestInputStream(raw, digest)) {
            target.putObject(key, in, sourceMeta.getSize(), contentType);
            sourceSha = toHex(digest.digest());
        }

        // 从目标独立读回，再次计算 sha256 并与源比对
        String targetSha = sha256Of(target.getObject(key));
        boolean ok = sourceSha.equals(targetSha);

        return ReplicationItemResult.builder()
                .key(key)
                .action(ok ? action : ReplicationAction.FAILED)
                .sourceSize(sourceMeta.getSize())
                .targetSize(sourceMeta.getSize())
                .sourceSha256(sourceSha)
                .targetSha256(targetSha)
                .verified(ok)
                .elapsedMs(System.currentTimeMillis() - start)
                .error(ok ? null : "sha256 不一致: source=" + sourceSha + " target=" + targetSha)
                .build();
    }

    private static String sha256Of(InputStream in) throws Exception {
        if (in == null) {
            return null;
        }
        MessageDigest digest = MessageDigest.getInstance(SHA256);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream stream = in) {
            int n;
            while ((n = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static ReplicationItemResult failure(String key, long start, String error) {
        return ReplicationItemResult.builder()
                .key(key)
                .action(ReplicationAction.FAILED)
                .elapsedMs(System.currentTimeMillis() - start)
                .error(error)
                .build();
    }
}
