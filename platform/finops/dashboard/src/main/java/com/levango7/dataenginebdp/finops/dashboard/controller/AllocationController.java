package com.levango7.dataenginebdp.finops.dashboard.controller;

import com.levango7.dataenginebdp.finops.dashboard.model.AllocationConfig;
import com.levango7.dataenginebdp.finops.dashboard.model.AllocationItem;
import com.levango7.dataenginebdp.finops.dashboard.model.DashboardResponse;
import com.levango7.dataenginebdp.finops.dashboard.model.ResourceCostDetail;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.finops.dashboard.service.AllocationService;
import com.levango7.dataenginebdp.finops.dashboard.service.CostDataService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 分账到子工作空间 REST API。
 *
 * <p>提供分账配置管理与分账执行端点：</p>
 * <ul>
 *   <li>GET  /api/v1/allocation/configs  — 列出所有分账配置</li>
 *   <li>GET  /api/v1/allocation/configs/{id} — 获取指定分账配置</li>
 *   <li>POST /api/v1/allocation/configs  — 新建/更新分账配置</li>
 *   <li>DELETE /api/v1/allocation/configs/{id} — 删除分账配置</li>
 *   <li>GET  /api/v1/allocation/execute  — 执行分账</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/allocation")
public class AllocationController {

    private static final Logger log = LoggerFactory.getLogger(AllocationController.class);

    private final AllocationService allocationService;
    private final CostDataService costDataService;

    public AllocationController(AllocationService allocationService,
                                CostDataService costDataService) {
        this.allocationService = allocationService;
        this.costDataService = costDataService;
    }

    /**
     * 列出所有分账配置。
     */
    @GetMapping("/configs")
    public ResponseEntity<List<AllocationConfig>> listConfigs() {
        return ResponseEntity.ok(allocationService.listConfigs());
    }

    /**
     * 获取指定分账配置。
     */
    @GetMapping("/configs/{id}")
    public ResponseEntity<AllocationConfig> getConfig(@PathVariable String id) {
        return ResponseEntity.ok(allocationService.getConfig(id));
    }

    /**
     * 新建或更新分账配置。
     */
    @PostMapping("/configs")
    public ResponseEntity<AllocationConfig> saveConfig(@Valid @RequestBody AllocationConfig config) {
        log.info("保存分账配置: id={}, parent={}, dimension={}, ratios={}",
                config.getId(), config.getParentWorkspace(), config.getDimension(), config.getRatios());
        return ResponseEntity.ok(allocationService.saveConfig(config));
    }

    /**
     * 删除分账配置。
     */
    @DeleteMapping("/configs/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable String id) {
        allocationService.deleteConfig(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 执行分账。
     */
    @GetMapping("/execute")
    public ResponseEntity<DashboardResponse<AllocationItem>> execute(
            @RequestParam String configId,
            @RequestParam(required = false) String namespace,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {

        String tenant = TenantContext.getTenantId();
        log.info("分账执行请求: configId={}, tenant={}, 窗口=[{},{}]", configId, tenant, start, end);

        List<ResourceCostDetail> details = costDataService.getCostDetails(tenant, namespace, start, end);
        List<AllocationItem> items = allocationService.allocate(configId, details);

        DashboardResponse<AllocationItem> resp = DashboardResponse.<AllocationItem>builder()
                .items(items)
                .total(items.size())
                .start(start)
                .end(end)
                .tenant(tenant)
                .summary(java.util.Map.of(
                        "configId", configId,
                        "allocationItemCount", items.size()
                ))
                .build();
        return ResponseEntity.ok(resp);
    }
}