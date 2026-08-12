package com.levango7.dataenginebdp.finops.controller;

import com.levango7.dataenginebdp.finops.model.PricingConfig;
import com.levango7.dataenginebdp.finops.service.PricingConfigService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定价配置 REST API 控制器。
 *
 * <p>支持动态配置单价（通过 API 或配置文件）。端点：</p>
 * <ul>
 *   <li>GET  /api/v1/pricing           — 列出所有定价配置名</li>
 *   <li>GET  /api/v1/pricing/{name}    — 获取指定定价配置</li>
 *   <li>POST /api/v1/pricing           — 新建定价配置</li>
 *   <li>PUT  /api/v1/pricing/{name}    — 更新定价配置</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/pricing")
public class PricingController {

    private final PricingConfigService pricingConfigService;

    public PricingController(PricingConfigService pricingConfigService) {
        this.pricingConfigService = pricingConfigService;
    }

    /** 列出所有定价配置名 */
    @GetMapping
    public ResponseEntity<List<String>> listNames() {
        return ResponseEntity.ok(pricingConfigService.listNames());
    }

    /** 获取指定定价配置 */
    @GetMapping("/{name}")
    public ResponseEntity<PricingConfig> get(@PathVariable String name) {
        return ResponseEntity.ok(pricingConfigService.getByName(name));
    }

    /** 新建定价配置 */
    @PostMapping
    public ResponseEntity<PricingConfig> create(@Valid @RequestBody PricingConfig config) {
        return ResponseEntity.ok(pricingConfigService.save(config));
    }

    /** 更新定价配置 */
    @PutMapping("/{name}")
    public ResponseEntity<PricingConfig> update(@PathVariable String name,
                                                @Valid @RequestBody PricingConfig config) {
        config.setName(name);
        return ResponseEntity.ok(pricingConfigService.save(config));
    }
}