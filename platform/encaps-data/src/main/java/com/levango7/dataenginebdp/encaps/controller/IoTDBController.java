package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.DataSourceEntity;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.engine.EngineUnavailableException;
import com.levango7.dataenginebdp.encaps.service.engine.IoTDBClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * IoTDB 引擎端点（ROADMAP 前后端接线：前端 /iotdb）。
 *
 * <p>提供 IoTDB 存储组、设备、时序列表与 SQL 查询、写入吞吐查询。
 * 统一前缀：{@code /api/v1/iotdb}</p>
 *
 * <p>id 对应数据源表中 type=iotdb 的记录 ID，从中读取连接信息（host/port/username/password）。</p>
 *
 * <ul>
 *   <li>GET  /{id}/storage-groups    — 存储组列表</li>
 *   <li>GET  /{id}/devices           — 设备列表</li>
 *   <li>GET  /{id}/timeseries        — 时序列表（参数：device）</li>
 *   <li>POST /{id}/query             — 执行 SQL 查询（任务要求）</li>
 *   <li>GET  /{id}/write-throughput  — 写入吞吐</li>
 * </ul>
 */
@Slf4j
@RestController
@Tag(name = "封装数据-IoTDB引擎", description = "IoTDB时序数据查询与管理")
@RequiredArgsConstructor
@RequestMapping("/api/v1/iotdb")
public class IoTDBController {

    private final IoTDBClient ioTdbClient;
    private final DataSourceRepository dataSourceRepository;

    /** 存储组列表。 */
    @GetMapping("/{id}/storage-groups")
    public ResponseEntity<?> listStorageGroups(@PathVariable String id) {
        log.info("列出 IoTDB 存储组: id={}, tenant={}", id, TenantContext.getTenantId());
        try {
            var conn = resolveConn(id);
            return ResponseEntity.ok(ioTdbClient.listStorageGroups(conn));
        } catch (EngineUnavailableException e) {
            log.warn("IoTDB 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "IoTDB 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 设备列表。 */
    @GetMapping("/{id}/devices")
    public ResponseEntity<?> listDevices(@PathVariable String id) {
        log.info("列出 IoTDB 设备: id={}, tenant={}", id, TenantContext.getTenantId());
        try {
            var conn = resolveConn(id);
            return ResponseEntity.ok(ioTdbClient.listDevices(conn));
        } catch (EngineUnavailableException e) {
            log.warn("IoTDB 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "IoTDB 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 时序列表。 */
    @GetMapping("/{id}/timeseries")
    public ResponseEntity<?> listTimeseries(
            @PathVariable String id,
            @RequestParam(required = false) String device) {
        log.info("列出 IoTDB 时序: id={}, device={}, tenant={}",
                id, device, TenantContext.getTenantId());
        try {
            var conn = resolveConn(id);
            return ResponseEntity.ok(ioTdbClient.listTimeseries(conn, device));
        } catch (EngineUnavailableException e) {
            log.warn("IoTDB 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "IoTDB 引擎不可用", "message", e.getMessage()));
        }
    }

    /** SQL 查询请求体。 */
    public record QueryRequest(String sql) {
    }

    /** 执行 SQL 查询（任务要求）。 */
    @PostMapping("/{id}/query")
    public ResponseEntity<?> executeQuery(@PathVariable String id,
                                          @RequestBody QueryRequest req) {
        log.info("执行 IoTDB SQL: id={}, tenant={}", id, TenantContext.getTenantId());
        try {
            var conn = resolveConn(id);
            return ResponseEntity.ok(ioTdbClient.executeQuery(conn, req.sql()));
        } catch (EngineUnavailableException e) {
            log.warn("IoTDB 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "IoTDB 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 写入吞吐。 */
    @GetMapping("/{id}/write-throughput")
    public ResponseEntity<?> getWriteThroughput(@PathVariable String id) {
        log.info("查询 IoTDB 写入吞吐: id={}, tenant={}", id, TenantContext.getTenantId());
        try {
            var conn = resolveConn(id);
            List<Map<String, Object>> throughput = ioTdbClient.getWriteThroughput(conn);
            return ResponseEntity.ok(throughput);
        } catch (EngineUnavailableException e) {
            log.warn("IoTDB 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "IoTDB 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 根据 id 解析 IoTDB 连接参数 */
    private IoTDBClient.ConnParams resolveConn(String id) {
        String tenantId = TenantContext.getTenantId();
        Long pk;
        try {
            pk = Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new EngineUnavailableException("无效的实例 ID: " + id);
        }
        DataSourceEntity ds = dataSourceRepository.findByIdAndTenantId(pk, tenantId)
                .orElseThrow(() -> new EngineUnavailableException("IoTDB 实例不存在: " + id));
        if (!"iotdb".equalsIgnoreCase(ds.getType())) {
            throw new EngineUnavailableException("数据源 " + id + " 不是 IoTDB 类型");
        }
        String jdbcUrl = "jdbc:iotdb://" + ds.getHost() + ":" + ds.getPort() + "/";
        String user = ds.getUsername() != null ? ds.getUsername() : "root";
        String pass = ds.getPassword() != null ? ds.getPassword() : "root";
        return ioTdbClient.connParams(jdbcUrl, user, pass);
    }
}
