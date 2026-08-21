package com.shuqing.bigdata.finops.dashboard.service;

import com.shuqing.bigdata.finops.dashboard.model.AllocationConfig;
import com.shuqing.bigdata.finops.dashboard.model.AllocationItem;
import com.shuqing.bigdata.finops.dashboard.model.ResourceCostDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 分账服务。
 *
 * <p>按 namespace 或工作空间标签将父工作空间成本分配到子工作空间。
 * 分账比例可配置，合计需 = 1.0。</p>
 */
@Service
public class AllocationService {

    private static final Logger log = LoggerFactory.getLogger(AllocationService.class);

    private final Map<String, AllocationConfig> configs = new ConcurrentHashMap<>();
    private final String defaultDimension;
    private final Map<String, Double> defaultRatios;

    public AllocationService(@Value("${app.finops.allocation.default-dimension:namespace}") String defaultDimension,
                             @Value("${app.finops.allocation.default-ratios:default=1.0}") String defaultRatiosStr) {
        this.defaultDimension = defaultDimension;
        this.defaultRatios = parseRatios(defaultRatiosStr);
        // 初始化默认分账配置
        AllocationConfig defaultConfig = AllocationConfig.builder()
                .id("default")
                .parentWorkspace("default")
                .dimension(defaultDimension)
                .ratios(this.defaultRatios)
                .enabled(true)
                .remark("默认分账配置")
                .build();
        configs.put("default", defaultConfig);
        log.info("分账服务已初始化: 默认维度={}, 默认比例={}", defaultDimension, this.defaultRatios);
    }

    /**
     * 执行分账。
     *
     * @param configId 分账配置 ID
     * @param details  待分账的成本明细
     * @return 分账结果列表
     */
    public List<AllocationItem> allocate(String configId, List<ResourceCostDetail> details) {
        AllocationConfig config = configs.get(configId);
        if (config == null) {
            throw new IllegalArgumentException("分账配置不存在: " + configId);
        }
        if (!config.isEnabled()) {
            throw new IllegalStateException("分账配置已禁用: " + configId);
        }
        validateRatios(config.getRatios());

        // 按父工作空间聚合成本
        Map<String, BigDecimal> parentTotals = new HashMap<>();
        Map<String, Map<String, BigDecimal>> parentDimensionCosts = new HashMap<>();
        for (ResourceCostDetail d : details) {
            String parent = resolveParentWorkspace(d, config.getDimension());
            parentTotals.merge(parent, d.getTotalCost(), BigDecimal::add);
            Map<String, BigDecimal> dimCosts = parentDimensionCosts
                    .computeIfAbsent(parent, k -> new HashMap<>());
            if (d.getDimensionCosts() != null) {
                for (Map.Entry<String, BigDecimal> e : d.getDimensionCosts().entrySet()) {
                    dimCosts.merge(e.getKey(), e.getValue(), BigDecimal::add);
                }
            }
        }

        // 按比例分账
        List<AllocationItem> items = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : parentTotals.entrySet()) {
            String parent = entry.getKey();
            BigDecimal total = entry.getValue();
            Map<String, BigDecimal> dimCosts = parentDimensionCosts.getOrDefault(parent, new HashMap<>());

            for (Map.Entry<String, Double> ratio : config.getRatios().entrySet()) {
                String subWs = ratio.getKey();
                double r = ratio.getValue();
                BigDecimal allocated = total.multiply(BigDecimal.valueOf(r))
                        .setScale(4, RoundingMode.HALF_UP);
                Map<String, BigDecimal> allocatedDimCosts = new HashMap<>();
                for (Map.Entry<String, BigDecimal> dc : dimCosts.entrySet()) {
                    allocatedDimCosts.put(dc.getKey(),
                            dc.getValue().multiply(BigDecimal.valueOf(r))
                                    .setScale(4, RoundingMode.HALF_UP));
                }
                items.add(AllocationItem.builder()
                        .parentWorkspace(parent)
                        .subWorkspace(subWs)
                        .ratio(r)
                        .originalCost(total)
                        .allocatedCost(allocated)
                        .dimensionAllocatedCosts(allocatedDimCosts)
                        .dimension(config.getDimension())
                        .build());
            }
        }
        log.info("分账完成: configId={}, 父工作空间数={}, 分账项数={}",
                configId, parentTotals.size(), items.size());
        return items;
    }

    /**
     * 保存分账配置。
     */
    public AllocationConfig saveConfig(AllocationConfig config) {
        validateRatios(config.getRatios());
        configs.put(config.getId(), config);
        log.info("分账配置已保存: id={}, parent={}, dimension={}, ratios={}",
                config.getId(), config.getParentWorkspace(), config.getDimension(), config.getRatios());
        return config;
    }

    /**
     * 获取分账配置。
     */
    public AllocationConfig getConfig(String id) {
        AllocationConfig config = configs.get(id);
        if (config == null) {
            throw new IllegalArgumentException("分账配置不存在: " + id);
        }
        return config;
    }

    /**
     * 列出所有分账配置。
     */
    public List<AllocationConfig> listConfigs() {
        return new ArrayList<>(configs.values());
    }

    /**
     * 删除分账配置。
     */
    public void deleteConfig(String id) {
        configs.remove(id);
    }

    /**
     * 解析父工作空间名。
     */
    private String resolveParentWorkspace(ResourceCostDetail d, String dimension) {
        if ("workspace_label".equalsIgnoreCase(dimension)) {
            return d.getWorkspace() != null ? d.getWorkspace() : "default";
        }
        // 默认按 namespace
        return d.getNamespace();
    }

    /**
     * 校验分账比例合计 = 1.0。
     */
    private void validateRatios(Map<String, Double> ratios) {
        if (ratios == null || ratios.isEmpty()) {
            throw new IllegalArgumentException("分账比例不能为空");
        }
        double sum = ratios.values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(sum - 1.0) > 0.0001) {
            throw new IllegalArgumentException("分账比例合计必须 = 1.0，当前合计 = " + sum);
        }
        for (Map.Entry<String, Double> e : ratios.entrySet()) {
            if (e.getValue() < 0 || e.getValue() > 1.0) {
                throw new IllegalArgumentException(
                        "分账比例必须在 [0, 1] 范围内: " + e.getKey() + "=" + e.getValue());
            }
        }
    }

    /**
     * 解析配置字符串 "k1=v1,k2=v2" 为 Map。
     */
    private Map<String, Double> parseRatios(String str) {
        Map<String, Double> result = new HashMap<>();
        if (str == null || str.isBlank()) {
            result.put("default", 1.0);
            return result;
        }
        for (String pair : str.split(",")) {
            String[] kv = pair.split("=");
            if (kv.length == 2) {
                result.put(kv[0].trim(), Double.parseDouble(kv[1].trim()));
            }
        }
        return result;
    }
}