package com.shuqing.bigdata.finops.dashboard.service;

import com.shuqing.bigdata.finops.dashboard.model.BillSummary;
import com.shuqing.bigdata.finops.dashboard.model.ResourceCostDetail;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 账单汇总服务。
 *
 * <p>按 tenant / namespace / 工作空间聚合成本明细，生成账单汇总。</p>
 */
@Service
public class BillSummaryService {

    /**
     * 按 tenant 聚合。
     */
    public List<BillSummary> summarizeByTenant(List<ResourceCostDetail> details) {
        return summarize(details, "TENANT", ResourceCostDetail::getTenant);
    }

    /**
     * 按 namespace 聚合。
     */
    public List<BillSummary> summarizeByNamespace(List<ResourceCostDetail> details) {
        return summarize(details, "NAMESPACE", ResourceCostDetail::getNamespace);
    }

    /**
     * 按工作空间聚合。
     */
    public List<BillSummary> summarizeByWorkspace(List<ResourceCostDetail> details) {
        return summarize(details, "WORKSPACE",
                d -> d.getWorkspace() != null ? d.getWorkspace() : "default");
    }

    /**
     * 通用聚合方法。
     */
    private List<BillSummary> summarize(List<ResourceCostDetail> details,
                                        String groupBy,
                                        java.util.function.Function<ResourceCostDetail, String> keyExtractor) {
        Map<String, List<ResourceCostDetail>> grouped = details.stream()
                .collect(Collectors.groupingBy(keyExtractor));

        return grouped.entrySet().stream()
                .map(entry -> {
                    String key = entry.getKey();
                    List<ResourceCostDetail> group = entry.getValue();
                    BigDecimal total = BigDecimal.ZERO;
                    Map<String, BigDecimal> dimensionCosts = new HashMap<>();
                    for (ResourceCostDetail d : group) {
                        total = total.add(d.getTotalCost());
                        if (d.getDimensionCosts() != null) {
                            for (Map.Entry<String, BigDecimal> dc : d.getDimensionCosts().entrySet()) {
                                dimensionCosts.merge(dc.getKey(), dc.getValue(), BigDecimal::add);
                            }
                        }
                    }
                    return BillSummary.builder()
                            .groupBy(groupBy)
                            .groupKey(key)
                            .totalCost(total)
                            .dimensionCosts(dimensionCosts)
                            .resourceCount(group.size())
                            .detailCount(group.size())
                            .build();
                })
                .sorted((a, b) -> b.getTotalCost().compareTo(a.getTotalCost()))
                .collect(Collectors.toList());
    }
}