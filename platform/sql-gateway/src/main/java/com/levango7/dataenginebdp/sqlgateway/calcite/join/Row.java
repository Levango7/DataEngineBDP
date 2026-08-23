package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Join 归并的基本行单元。
 */
public class Row {
    private final Object[] values;
    private volatile boolean matched = false;

    public Row(Object[] values) {
        this.values = Objects.requireNonNull(values).clone();
    }

    public Row(List<Object> values) {
        this.values = values.toArray();
    }

    public Object get(int index) {
        return values[index];
    }

    public void set(int index, Object value) {
        values[index] = value;
    }

    public int size() {
        return values.length;
    }

    public Object[] getValues() {
        return values.clone();
    }

    public List<Object> toList() {
        return Arrays.asList(values);
    }

    public boolean isMatched() {
        return matched;
    }

    public void setMatched(boolean matched) {
        this.matched = matched;
    }

    /**
     * 估算行大小（字节）。
     */
    public long estimatedSize() {
        long size = 16; // 对象头
        for (Object v : values) {
            if (v == null) {
                size += 8;
            } else if (v instanceof String s) {
                size += 40 + s.length() * 2L;
            } else if (v instanceof Number) {
                size += 16;
            } else if (v instanceof byte[] bytes) {
                size += 16 + bytes.length;
            } else {
                size += 32;
            }
        }
        return size;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Row row)) return false;
        return Arrays.equals(values, row.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
