package com.levango7.dataenginebdp.governance.lineage.controller;

import com.levango7.dataenginebdp.governance.lineage.service.LineageGraphWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查控制器。
 *
 * <p>提供 {@code GET /api/v1/health} 端点，返回服务状态与图谱规模。
 * 同时实现 {@link HealthIndicator} 供 Actuator {@code /actuator/health} 集成。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController implements HealthIndicator {

    private final LineageGraphWriter graphWriter;

    /**
     * 构造控制器。
     *
     * @param graphWriter 图谱写入器
     */
    @Autowired
    public HealthController(LineageGraphWriter graphWriter) {
        this.graphWriter = graphWriter;
    }

    /**
     * REST 健康端点。
     *
     * @return 状态信息
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("service", "lineage-analyzer");
        result.put("knownTables", graphWriter.getKnownTables().size());
        result.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(result);
    }

    /**
     * Actuator HealthIndicator 实现。
     *
     * @return Health 指标
     */
    @Override
    public Health health() {
        return Health.up()
                .withDetail("service", "lineage-analyzer")
                .withDetail("knownTables", graphWriter.getKnownTables().size())
                .build();
    }
}
