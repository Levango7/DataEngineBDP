package com.shuqing.bigdata.governance.lineage.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 血缘查询结果（上下游/影响分析）。
 *
 * <p>封装从指定表出发，按指定深度遍历得到的上下游节点列表及路径。</p>
 *
 * @author shuqing-bigdata
 */
public class LineageQueryResult {

    /** 查询方向 */
    public enum Direction {
        /** 上游：依赖的源表 */
        UPSTREAM,
        /** 下游：被影响的目标表 */
        DOWNSTREAM,
        /** 影响分析：所有下游（递归到底） */
        IMPACT
    }

    private final String rootTable;
    private final Direction direction;
    private final int depth;
    private final List<String> tables;
    private final List<String> paths;
    private final long queryTimeMs;

    /**
     * 构造查询结果。
     *
     * @param rootTable   起始表
     * @param direction   查询方向
     * @param depth       实际遍历深度
     * @param tables      命中表列表
     * @param paths       路径列表（每条路径形如 "a -> b -> c"）
     * @param queryTimeMs 查询耗时（毫秒）
     */
    public LineageQueryResult(String rootTable, Direction direction, int depth,
                              List<String> tables, List<String> paths, long queryTimeMs) {
        this.rootTable = rootTable;
        this.direction = direction;
        this.depth = depth;
        this.tables = tables != null ? tables : new ArrayList<>();
        this.paths = paths != null ? paths : new ArrayList<>();
        this.queryTimeMs = queryTimeMs;
    }

    public String getRootTable() {
        return rootTable;
    }

    public Direction getDirection() {
        return direction;
    }

    public int getDepth() {
        return depth;
    }

    public List<String> getTables() {
        return tables;
    }

    public List<String> getPaths() {
        return paths;
    }

    public long getQueryTimeMs() {
        return queryTimeMs;
    }

    @Override
    public String toString() {
        return "LineageQueryResult{root=" + rootTable + ", dir=" + direction
                + ", depth=" + depth + ", tables=" + tables.size() + '}';
    }
}