package com.levango7.dataenginebdp.sqlgateway.calcite.join;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 基于列表的行迭代器实现。
 */
public class ListRowIterator implements RowIterator {
    private final Iterator<Row> iterator;
    private final long estimatedSize;

    public ListRowIterator(java.util.List<Row> rows) {
        this(rows, -1);
    }

    public ListRowIterator(java.util.List<Row> rows, long estimatedSize) {
        this.iterator = rows.iterator();
        this.estimatedSize = estimatedSize;
    }

    @Override
    public boolean hasNext() {
        return iterator.hasNext();
    }

    @Override
    public Row next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return iterator.next();
    }

    @Override
    public long estimatedSize() {
        return estimatedSize;
    }
}
