package com.levango7.dataenginebdp.governance.lineage.service;

import com.levango7.dataenginebdp.governance.lineage.model.LineageQueryResult;
import com.levango7.dataenginebdp.governance.lineage.model.LineageQueryResult.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 血缘查询服务：上下游遍历与影响分析。
 *
 * <p>基于 {@link LineageGraphWriter} 维护的内存邻接表执行 BFS：
 * <ul>
 *   <li>{@link #getUpstream(String, int)}：沿 upstreamMap 反向 BFS</li>
 *   <li>{@link #getDownstream(String, int)}：沿 downstreamMap 正向 BFS</li>
 *   <li>{@link #impactAnalysis(String)}：等价于 {@code getDownstream(table, maxDepth)}，
 *       并返回所有受影响表与路径</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Service
public class LineageQueryService {

    private static final Logger log = LoggerFactory.getLogger(LineageQueryService.class);

    private final LineageGraphWriter graphWriter;

    /** 查询深度上限 */
    @Value("${lineage.max-depth:10}")
    private int maxDepth;

    /** 影响分析最大节点数 */
    @Value("${lineage.max-impact-nodes:1000}")
    private int maxImpactNodes;

    /**
     * 构造查询服务。
     *
     * @param graphWriter 图谱写入器（提供邻接表）
     */
    @Autowired
    public LineageQueryService(LineageGraphWriter graphWriter) {
        this.graphWriter = graphWriter;
    }

    /**
     * 查询上游依赖表。
     *
     * @param table 起始表全名
     * @param depth 遍历深度；{@code <=0} 或超过上限时使用上限
     * @return 上游查询结果
     */
    public LineageQueryResult getUpstream(String table, int depth) {
        long start = System.currentTimeMillis();
        if (table == null || table.isBlank()) {
            return new LineageQueryResult(table, Direction.UPSTREAM, 0,
                    Collections.emptyList(), Collections.emptyList(), 0);
        }
        int effectiveDepth = sanitizeDepth(depth);
        Map<String, Set<String>> upstream = graphWriter.getUpstreamMap();
        BfsResult result = bfs(table, upstream, effectiveDepth, maxImpactNodes);
        long elapsed = System.currentTimeMillis() - start;
        log.info("上游查询: {} 深度 {} → {} 个表, 耗时 {}ms",
                table, effectiveDepth, result.tables.size(), elapsed);
        return new LineageQueryResult(table, Direction.UPSTREAM, effectiveDepth,
                result.tables, result.paths, elapsed);
    }

    /**
     * 查询下游依赖表。
     *
     * @param table 起始表全名
     * @param depth 遍历深度；{@code <=0} 或超过上限时使用上限
     * @return 下游查询结果
     */
    public LineageQueryResult getDownstream(String table, int depth) {
        long start = System.currentTimeMillis();
        if (table == null || table.isBlank()) {
            return new LineageQueryResult(table, Direction.DOWNSTREAM, 0,
                    Collections.emptyList(), Collections.emptyList(), 0);
        }
        int effectiveDepth = sanitizeDepth(depth);
        Map<String, Set<String>> downstream = graphWriter.getDownstreamMap();
        BfsResult result = bfs(table, downstream, effectiveDepth, maxImpactNodes);
        long elapsed = System.currentTimeMillis() - start;
        log.info("下游查询: {} 深度 {} → {} 个表, 耗时 {}ms",
                table, effectiveDepth, result.tables.size(), elapsed);
        return new LineageQueryResult(table, Direction.DOWNSTREAM, effectiveDepth,
                result.tables, result.paths, elapsed);
    }

    /**
     * 影响分析：变更 table 会影响哪些下游表。
     *
     * @param table 起始表全名
     * @return 影响分析结果
     */
    public LineageQueryResult impactAnalysis(String table) {
        long start = System.currentTimeMillis();
        if (table == null || table.isBlank()) {
            return new LineageQueryResult(table, Direction.IMPACT, 0,
                    Collections.emptyList(), Collections.emptyList(), 0);
        }
        Map<String, Set<String>> downstream = graphWriter.getDownstreamMap();
        BfsResult result = bfs(table, downstream, maxDepth, maxImpactNodes);
        long elapsed = System.currentTimeMillis() - start;
        log.info("影响分析: {} → {} 个受影响表, 耗时 {}ms",
                table, result.tables.size(), elapsed);
        return new LineageQueryResult(table, Direction.IMPACT, maxDepth,
                result.tables, result.paths, elapsed);
    }

    /**
     * BFS 遍历邻接表。
     *
     * @param start    起始节点
     * @param adj      邻接表
     * @param depth    最大深度
     * @param maxNodes 最大节点数
     * @return 遍历结果
     */
    private BfsResult bfs(String start, Map<String, Set<String>> adj, int depth, int maxNodes) {
        List<String> tables = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        // BFS 队列：节点 + 路径
        Deque<BfsNode> queue = new ArrayDeque<>();
        queue.offer(new BfsNode(start, 0, start));
        visited.add(start);

        while (!queue.isEmpty()) {
            BfsNode cur = queue.poll();
            if (cur.depth >= depth) {
                continue;
            }
            Set<String> neighbors = adj.getOrDefault(cur.node, Collections.emptySet());
            for (String next : neighbors) {
                if (visited.contains(next)) {
                    continue;
                }
                visited.add(next);
                String newPath = cur.path + " -> " + next;
                tables.add(next);
                paths.add(newPath);
                if (tables.size() >= maxNodes) {
                    return new BfsResult(tables, paths);
                }
                queue.offer(new BfsNode(next, cur.depth + 1, newPath));
            }
        }
        return new BfsResult(tables, paths);
    }

    /**
     * 校验深度：<=0 或超过上限时使用上限。
     *
     * @param depth 输入深度
     * @return 有效深度
     */
    private int sanitizeDepth(int depth) {
        if (depth <= 0 || depth > maxDepth) {
            return maxDepth;
        }
        return depth;
    }

    /** BFS 内部节点 */
    private static final class BfsNode {
        final String node;
        final int depth;
        final String path;

        BfsNode(String node, int depth, String path) {
            this.node = node;
            this.depth = depth;
            this.path = path;
        }
    }

    /** BFS 结果 */
    private static final class BfsResult {
        final List<String> tables;
        final List<String> paths;

        BfsResult(List<String> tables, List<String> paths) {
            this.tables = tables;
            this.paths = paths;
        }
    }
}