package com.levango7.dataenginebdp.storage.replication;

import com.levango7.dataenginebdp.storage.api.ObjectMetadata;

/**
 * 跨域复制冲突解决策略：依据源 / 目标元数据决定单对象动作。
 *
 * <p>策略只做“是否写入”的判定，不执行 IO，便于单元测试。
 */
@FunctionalInterface
public interface ConflictPolicy {

    /**
     * 决策。
     *
     * @param source 源对象元数据（非 null）
     * @param target 目标对象元数据；目标不存在时为 null
     * @return COPY / OVERWRITE / SKIP
     */
    ReplicationAction decide(ObjectMetadata source, ObjectMetadata target);
}
