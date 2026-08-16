package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * IoTDB 引擎端点（ROADMAP 前后端接线：前端 /iotdb）。
 *
 * <p>提供 IoTDB 存储组、设备、时序列表与写入吞吐查询。
 * 统一前缀：{@code /api/v1/iotdb}</p>
 *
 * <ul>
 *   <li>GET /{id}/storage-groups    — 存储组列表</li>
 *   <li>GET /{id}/devices           — 设备列表</li>
 *   <li>GET /{id}/timeseries        — 时序列表（参数：device）</li>
 *   <li>GET /{id}/write-throughput  — 写入吞吐</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/iotdb")
public class IoTDBController {

    /** 存储组列表。 */
    @GetMapping("/{id}/storage-groups")
    public ResponseEntity<List<String>> listStorageGroups(@PathVariable String id) {
        // TODO: 接入 IoTDB Session.executeQuery("SHOW STORAGE GROUP")
        log.info("列出 IoTDB 存储组: id={}, tenant={}", id, TenantContext.getTenantId());
        return ResponseEntity.ok(List.of());
    }

    /** 设备列表。 */
    @GetMapping("/{id}/devices")
    public ResponseEntity<List<String>> listDevices(@PathVariable String id) {
        // TODO: 接入 IoTDB Session.executeQuery("SHOW DEVICES")
        log.info("列出 IoTDB 设备: id={}, tenant={}", id, TenantContext.getTenantId());
        return ResponseEntity.ok(List.of());
    }

    /** 时序列表。 */
    @GetMapping("/{id}/timeseries")
    public ResponseEntity<List<Map<String, Object>>> listTimeseries(
            @PathVariable String id,
            @RequestParam(required = false) String device) {
        // TODO: 接入 IoTDB Session.executeQuery("SHOW TIMESERIES <device>")
        log.info("列出 IoTDB 时序: id={}, device={}, tenant={}",
                id, device, TenantContext.getTenantId());
        return ResponseEntity.ok(List.of());
    }

    /** 写入吞吐。 */
    @GetMapping("/{id}/write-throughput")
    public ResponseEntity<List<Map<String, Object>>> getWriteThroughput(@PathVariable String id) {
        // TODO: 接入 IoTDB 监控指标
        log.info("查询 IoTDB 写入吞吐: id={}, tenant={}", id, TenantContext.getTenantId());
        return ResponseEntity.ok(List.of());
    }
}