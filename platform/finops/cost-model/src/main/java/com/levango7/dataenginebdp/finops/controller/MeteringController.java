package com.levango7.dataenginebdp.finops.controller;

import com.levango7.dataenginebdp.finops.model.QueryMeteringRecord;
import com.levango7.dataenginebdp.finops.model.QueryMeteringRequest;
import com.levango7.dataenginebdp.finops.repository.QueryMeteringRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 查询计量上报入口（cost-model 侧接收 sql-gateway 的查询计量）。
 *
 * <p>幂等：同一 (tenantId, clientRequestId) 重复上报不重复记账。
 */
@Slf4j
@RestController
@Tag(name = "成本运营-计量上报", description = "查询计量幂等上报")
@RequiredArgsConstructor
@RequestMapping("/api/v1/finops/metering")
public class MeteringController {

    private final QueryMeteringRepository meteringRepository;

    /**
     * 接收单条查询计量。
     *
     * @param request 计量请求
     * @return 201（首次落库）或 200（已存在幂等跳过）
     */
    @Operation(summary = "接收单条查询计量")
    @PostMapping("/query")
    @Transactional
    public ResponseEntity<Map<String, Object>> recordQuery(@Valid @RequestBody QueryMeteringRequest request) {
        Optional<QueryMeteringRecord> existing = meteringRepository
                .findByTenantIdAndClientRequestId(request.getTenantId(), request.getClientRequestId());
        if (existing.isPresent()) {
            return ResponseEntity.ok(Map.of(
                    "duplicate", true,
                    "meteringId", existing.get().getId()));
        }

        QueryMeteringRecord record = QueryMeteringRecord.builder()
                .tenantId(request.getTenantId())
                .namespace(request.getNamespace())
                .engine(request.getEngine())
                .sqlHash(request.getSqlHash())
                .bytesScanned(request.getBytesScanned() == null ? 0L : request.getBytesScanned())
                .estimated(request.isEstimated())
                .durationMs(request.getDurationMs())
                .clientRequestId(request.getClientRequestId())
                .createdAt(Instant.now())
                .build();
        QueryMeteringRecord saved = meteringRepository.save(record);

        log.info("查询计量已落库: tenant={}, engine={}, bytes={}, est={}, requestId={}",
                saved.getTenantId(), saved.getEngine(), saved.getBytesScanned(),
                saved.isEstimated(), saved.getClientRequestId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "duplicate", false,
                "meteringId", saved.getId()));
    }
}