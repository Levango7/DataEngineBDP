package com.levango7.dataenginebdp.storage.replication;

import com.levango7.dataenginebdp.storage.api.ObjectMetadata;

import java.time.Instant;

/**
 * Last-Write-Wins 冲突解决（最终一致模型）。
 *
 * <p>规则：
 * <ul>
 *   <li>目标不存在 → {@link ReplicationAction#COPY}</li>
 *   <li>源 {@code lastModified} ≥ 目标 → {@link ReplicationAction#OVERWRITE}
 *       （同版本源优先，保证重复执行可收敛、幂等）</li>
 *   <li>源 {@code lastModified} &lt; 目标 → {@link ReplicationAction#SKIP}（目标更新，不回退）</li>
 * </ul>
 *
 * <p><b>隐式依赖</b>：依赖 S3 {@code HeadObject} 的 {@code lastModified}（秒级精度）。
 * 缺任一 {@code lastModified} 时按“源优先”处理，避免因元数据缺失导致复制停滞。
 */
public class LastWriteWinsPolicy implements ConflictPolicy {

    @Override
    public ReplicationAction decide(ObjectMetadata source, ObjectMetadata target) {
        if (target == null) {
            return ReplicationAction.COPY;
        }
        Instant src = source.getLastModified();
        Instant tgt = target.getLastModified();
        if (src == null || tgt == null) {
            return ReplicationAction.OVERWRITE;
        }
        return src.isBefore(tgt) ? ReplicationAction.SKIP : ReplicationAction.OVERWRITE;
    }
}
