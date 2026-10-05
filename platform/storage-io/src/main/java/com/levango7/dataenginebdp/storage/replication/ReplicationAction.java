package com.levango7.dataenginebdp.storage.replication;

/**
 * 单个对象的复制决策 / 结果动作。
 */
public enum ReplicationAction {

    /** 目标不存在，首次复制。 */
    COPY,

    /** 目标存在且源版本不旧于目标，按 LWW 覆盖。 */
    OVERWRITE,

    /** 目标存在且更新，按 LWW 跳过（不回退）。 */
    SKIP,

    /** 出错（读源 / 写目标 / sha256 校验不一致）。 */
    FAILED
}
