package com.shuqing.bigdata.sqlgateway.calcite.explain;

import com.shuqing.bigdata.sqlgateway.calcite.adapter.BaseAdapter;
import com.shuqing.bigdata.sqlgateway.calcite.config.OptimizerConfig;
import com.shuqing.bigdata.sqlgateway.calcite.rel.CustomRelNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cost 估算可视化器——将 {@link BaseAdapter.Cost} 转换为结构化指标 Map，
 * 并按 {@link OptimizerConfig} 的权重计算加权总 Cost。
 *
 * <p>本类提供以下维度的 Cost 可视化：</p>
 * <ul>
 *   <li><b>三维 Cost</b>：CPU/IO/Network 各维度的绝对值与占比</li>
 *   <li><b>加权总 Cost</b>：按配置权重（cpu/io/network）计算的综合 Cost</li>
 *   <li><b>估算行数</b>：Cost 模型估算的结果行数</li>
 *   <li><b>Cost 占比图</b>：三维 Cost 的 ASCII 占比条</li>
 *   <li><b>瓶颈维度</b>：占比最大的 Cost 维度（用于调优建议）</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>
 *   CostVisualizer visualizer = new CostVisualizer(optimizerConfig);
 *   Map&lt;String, Object&gt; stats = visualizer.visualize(cost, relNode, adapters);
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class CostVisualizer {

    /** 默认 Cost 配置（当未提供 OptimizerConfig 时使用） */
    private final OptimizerConfig config;

    /**
     * 构造 Cost 可视化器。
     *
     * @param config 优化器配置（提供 Cost 权重，null 使用默认配置）
     */
    public CostVisualizer(OptimizerConfig config) {
        this.config = config == null ? new OptimizerConfig() : config;
    }

    /**
     * 构造 Cost 可视化器（默认权重）。
     */
    public CostVisualizer() {
        this(new OptimizerConfig());
    }

    /**
     * 可视化 Cost 估算结果。
     *
     * @param cost     Cost 估算结果（null 视为零 Cost）
     * @param relNode  RelNode 树（用于节点级 Cost 累加，null 跳过）
     * @param adapters 数据源适配器列表（用于按数据源分类 Cost，null 跳过）
     * @return 结构化指标 Map
     */
    public Map<String, Object> visualize(BaseAdapter.Cost cost, CustomRelNode relNode,
                                         List<BaseAdapter> adapters) {
        Map<String, Object> stats = new LinkedHashMap<>();

        // 顶层 Cost 指标
        putCostStats(stats, cost, "cost");

        // 节点级 Cost 累加
        putNodeCostStats(stats, relNode, adapters);

        return stats;
    }

    /**
     * 仅可视化单个 Cost（便捷方法）。
     *
     * @param cost Cost 估算结果
     * @return 结构化指标 Map
     */
    public Map<String, Object> visualize(BaseAdapter.Cost cost) {
        return visualize(cost, null, null);
    }

    // ===================== Cost 指标 =====================

    /**
     * 将 Cost 写入指标 Map。
     *
     * @param stats 指标 Map
     * @param cost  Cost
     * @param prefix 键前缀
     */
    private void putCostStats(Map<String, Object> stats, BaseAdapter.Cost cost, String prefix) {
        if (cost == null) {
            cost = BaseAdapter.Cost.zero();
        }

        double cpu = cost.getCpuCost();
        double io = cost.getIoCost();
        double network = cost.getNetworkCost();
        double rows = cost.getRows();
        double total = cpu + io + network;

        stats.put(prefix + ".cpu", cpu);
        stats.put(prefix + ".io", io);
        stats.put(prefix + ".network", network);
        stats.put(prefix + ".rows", rows);
        stats.put(prefix + ".total", total);

        // 加权总 Cost
        double cpuWeight = config.getCostWeight("cpu");
        double ioWeight = config.getCostWeight("io");
        double networkWeight = config.getCostWeight("network");
        double weighted = cost.weightedTotal(cpuWeight, ioWeight, networkWeight);
        stats.put(prefix + ".weighted", weighted);
        stats.put(prefix + ".weights.cpu", cpuWeight);
        stats.put(prefix + ".weights.io", ioWeight);
        stats.put(prefix + ".weights.network", networkWeight);

        // 三维占比
        if (total > 0) {
            double cpuPct = cpu / total;
            double ioPct = io / total;
            double netPct = network / total;
            stats.put(prefix + ".share.cpu", cpuPct);
            stats.put(prefix + ".share.io", ioPct);
            stats.put(prefix + ".share.network", netPct);
            stats.put(prefix + ".share.cpuPct", formatPct(cpuPct));
            stats.put(prefix + ".share.ioPct", formatPct(ioPct));
            stats.put(prefix + ".share.networkPct", formatPct(netPct));

            // 占比条
            stats.put(prefix + ".shareBar", shareBar(cpu, io, network, 30));

            // 瓶颈维度
            String bottleneck = bottleneck(cpu, io, network);
            stats.put(prefix + ".bottleneck", bottleneck);
        } else {
            stats.put(prefix + ".bottleneck", "NONE");
        }

        // 人类可读的 Cost 格式
        stats.put(prefix + ".readable", humanReadable(total));
        stats.put(prefix + ".cpuReadable", humanReadable(cpu));
        stats.put(prefix + ".ioReadable", humanReadable(io));
        stats.put(prefix + ".networkReadable", humanReadable(network));
    }

    // ===================== 节点级 Cost =====================

    /**
     * 累加 RelNode 树中各节点的 Cost（按数据源分类）。
     *
     * @param stats    指标 Map
     * @param relNode  RelNode 树
     * @param adapters 适配器列表
     */
    private void putNodeCostStats(Map<String, Object> stats, CustomRelNode relNode,
                                  List<BaseAdapter> adapters) {
        String prefix = "cost.byNode";
        if (relNode == null) {
            stats.put(prefix + ".totalNodes", 0);
            return;
        }

        // 累加节点数与已估算 Cost 的节点数
        int[] counts = new int[2]; // totalNodes, estimatedNodes
        double[] totals = new double[4]; // cpu, io, network, rows
        accumulateNodeCost(relNode, adapters, counts, totals);

        stats.put(prefix + ".totalNodes", counts[0]);
        stats.put(prefix + ".estimatedNodes", counts[1]);
        stats.put(prefix + ".totalCpu", totals[0]);
        stats.put(prefix + ".totalIo", totals[1]);
        stats.put(prefix + ".totalNetwork", totals[2]);
        stats.put(prefix + ".totalRows", totals[3]);

        double sumTotal = totals[0] + totals[1] + totals[2];
        stats.put(prefix + ".totalCost", sumTotal);
        if (sumTotal > 0) {
            stats.put(prefix + ".totalReadable", humanReadable(sumTotal));
        }

        // 按数据源分类 Cost
        if (adapters != null && !adapters.isEmpty()) {
            putSourceCostStats(stats, relNode, adapters);
        }
    }

    /**
     * 递归累加节点 Cost。
     *
     * @param node     当前节点
     * @param adapters 适配器列表
     * @param counts   计数数组
     * @param totals   累加数组
     */
    private void accumulateNodeCost(CustomRelNode node, List<BaseAdapter> adapters,
                                    int[] counts, double[] totals) {
        if (node == null) {
            return;
        }
        counts[0]++;
        if (node.getEstimatedCost() > 0) {
            counts[1]++;
        }
        if (node.getEstimatedRows() > 0) {
            totals[3] += node.getEstimatedRows();
        }

        // 通过适配器估算 Cost（若节点有对应数据源）
        BaseAdapter adapter = findAdapter(node, adapters);
        if (adapter != null) {
            BaseAdapter.Cost cost = adapter.costEstimate(node);
            totals[0] += cost.getCpuCost();
            totals[1] += cost.getIoCost();
            totals[2] += cost.getNetworkCost();
        }

        for (CustomRelNode child : node.getChildren()) {
            accumulateNodeCost(child, adapters, counts, totals);
        }
    }

    /**
     * 按数据源分类 Cost 统计。
     *
     * @param stats    指标 Map
     * @param relNode  RelNode 树
     * @param adapters 适配器列表
     */
    private void putSourceCostStats(Map<String, Object> stats, CustomRelNode relNode,
                                    List<BaseAdapter> adapters) {
        String prefix = "cost.bySource";
        for (BaseAdapter adapter : adapters) {
            if (adapter == null || adapter.getDataSourceConfig() == null) {
                continue;
            }
            String sourceName = adapter.getDataSourceConfig().getName();
            double[] totals = new double[4];
            accumulateSourceCost(relNode, adapter, totals);

            String key = prefix + "." + sourceName;
            stats.put(key + ".cpu", totals[0]);
            stats.put(key + ".io", totals[1]);
            stats.put(key + ".network", totals[2]);
            stats.put(key + ".rows", totals[3]);
            double total = totals[0] + totals[1] + totals[2];
            stats.put(key + ".total", total);
            if (total > 0) {
                stats.put(key + ".readable", humanReadable(total));
            }
        }
    }

    /**
     * 递归累加指定数据源的 Cost。
     *
     * @param node    当前节点
     * @param adapter 目标适配器
     * @param totals  累加数组
     */
    private void accumulateSourceCost(CustomRelNode node, BaseAdapter adapter,
                                      double[] totals) {
        if (node == null) {
            return;
        }
        String mySource = adapter.getDataSourceConfig() == null
                ? null : adapter.getDataSourceConfig().getName();
        if (mySource != null && mySource.equals(node.getSourceName())) {
            BaseAdapter.Cost cost = adapter.costEstimate(node);
            totals[0] += cost.getCpuCost();
            totals[1] += cost.getIoCost();
            totals[2] += cost.getNetworkCost();
            totals[3] += cost.getRows();
        }
        for (CustomRelNode child : node.getChildren()) {
            accumulateSourceCost(child, adapter, totals);
        }
    }

    /**
     * 查找节点对应的数据源适配器。
     *
     * @param node     节点
     * @param adapters 适配器列表
     * @return 适配器（null 表示未找到）
     */
    private BaseAdapter findAdapter(CustomRelNode node, List<BaseAdapter> adapters) {
        if (node == null || adapters == null || node.getSourceName() == null) {
            return null;
        }
        for (BaseAdapter adapter : adapters) {
            if (adapter != null && adapter.getDataSourceConfig() != null
                    && node.getSourceName().equals(adapter.getDataSourceConfig().getName())) {
                return adapter;
            }
        }
        return null;
    }

    // ===================== 辅助方法 =====================

    /**
     * 生成三维 Cost 占比条。
     *
     * @param cpu    CPU Cost
     * @param io     IO Cost
     * @param network Network Cost
     * @param length 总长度
     * @return 占比条字符串（如 "[CCCCCCCCCC|IIIIII|NNNNNNNNNNNNNN]"）
     */
    public static String shareBar(double cpu, double io, double network, int length) {
        double total = cpu + io + network;
        if (total <= 0) {
            return "[" + " ".repeat(length) + "]";
        }
        int cpuLen = (int) Math.round(cpu / total * length);
        int ioLen = (int) Math.round(io / total * length);
        int netLen = length - cpuLen - ioLen;
        if (netLen < 0) {
            netLen = 0;
        }
        StringBuilder sb = new StringBuilder("[");
        sb.append("C".repeat(Math.max(0, cpuLen)));
        sb.append("|");
        sb.append("I".repeat(Math.max(0, ioLen)));
        sb.append("|");
        sb.append("N".repeat(netLen));
        sb.append(']');
        return sb.toString();
    }

    /**
     * 识别 Cost 瓶颈维度。
     *
     * @param cpu    CPU Cost
     * @param io     IO Cost
     * @param network Network Cost
     * @return 瓶颈维度名（CPU/IO/NETWORK/NONE）
     */
    public static String bottleneck(double cpu, double io, double network) {
        if (cpu == 0 && io == 0 && network == 0) {
            return "NONE";
        }
        if (cpu >= io && cpu >= network) {
            return "CPU";
        }
        if (io >= cpu && io >= network) {
            return "IO";
        }
        return "NETWORK";
    }

    /**
     * 将数值转为人类可读格式（K/M/B/T）。
     *
     * @param value 数值
     * @return 可读字符串（如 "1.23M"）
     */
    public static String humanReadable(double value) {
        if (value < 0) {
            return "-" + humanReadable(-value);
        }
        if (value < 1_000) {
            return String.format("%.2f", value);
        }
        if (value < 1_000_000) {
            return String.format("%.2fK", value / 1_000);
        }
        if (value < 1_000_000_000) {
            return String.format("%.2fM", value / 1_000_000);
        }
        if (value < 1_000_000_000_000L) {
            return String.format("%.2fB", value / 1_000_000_000);
        }
        return String.format("%.2fT", value / 1_000_000_000_000L);
    }

    /**
     * 格式化百分比。
     *
     * @param rate 比率
     * @return 百分比字符串
     */
    public static String formatPct(double rate) {
        return String.format("%.2f%%", rate * 100);
    }
}