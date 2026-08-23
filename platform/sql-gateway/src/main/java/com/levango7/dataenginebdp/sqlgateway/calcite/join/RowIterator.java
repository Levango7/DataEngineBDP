package com.levango7.dataenginebdp.sqlgateway.calcite.join;

/**
 * 行迭代器——从数据源流式读取行。
 */
public interface RowIterator extends AutoCloseable {
    boolean hasNext();
    Row next();
    /** 估算总大小（字节），未知返回 -1 */
    long estimatedSize();

    @Override
    default void close() {}
}
