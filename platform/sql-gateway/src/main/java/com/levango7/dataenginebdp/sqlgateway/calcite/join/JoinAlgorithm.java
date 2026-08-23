package com.levango7.dataenginebdp.sqlgateway.calcite.join;

/**
 * Join 算法枚举。
 */
public enum JoinAlgorithm {
    /** Broadcast Hash Join：小表广播 */
    BROADCAST("Broadcast Hash Join"),
    /** Shuffle Hash Join：按 Key 分区，含 spill */
    SHUFFLE("Shuffle Hash Join：按 Key 分区，含 spill"),
    /** Nested Loop Join：嵌套循环（非等值 Join） */
    NESTED_LOOP("Nested Loop Join");

    private final String description;

    JoinAlgorithm(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
