package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import java.util.Arrays;
import java.util.Objects;

/**
 * Join Key 的 Hash 包装——用于 Hash 表查找。
 */
final class JoinKeyHash {
    private final Object[] keyValues;
    private final int hash;

    JoinKeyHash(Object[] keyValues) {
        this.keyValues = keyValues;
        this.hash = Arrays.hashCode(keyValues);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JoinKeyHash that)) return false;
        return Arrays.equals(keyValues, that.keyValues);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
