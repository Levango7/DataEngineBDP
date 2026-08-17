package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.MaskPolicyEntity;
import com.levango7.dataenginebdp.encaps.repository.MaskPolicyRepository;
import com.levango7.dataenginebdp.encaps.security.AuditLog;
import com.levango7.dataenginebdp.encaps.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据安全端点（ROADMAP 前后端接线：前端 /sec）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/sec")
public class SecController {

    private final MaskPolicyRepository repository;

    /** 权限审批流内存存储：tenantId -> 审批记录列表。 */
    private static final Map<String, List<Map<String, Object>>> APPROVALS = new ConcurrentHashMap<>();

    /** 创建/更新请求体（对齐前端 CreateMaskPolicyParams）。 */
    public record MaskPolicyRequest(
            @NotBlank String fieldName,
            @NotBlank String assetName,
            @NotBlank String strategy,
            @NotBlank String algorithm) {
    }

    /** 策略列表。 */
    @GetMapping("/policies")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listPolicies(
            @RequestParam(required = false) String assetName) {
        String tenantId = requireTenant();
        List<MaskPolicyEntity> list = (assetName == null || assetName.isBlank())
                ? repository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : repository.findByTenantIdAndAssetNameOrderByCreatedAtDesc(tenantId, assetName);
        return ResponseEntity.ok(list.stream().map(this::toView).toList());
    }

    /** 策略详情。 */
    @GetMapping("/policies/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getPolicy(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId)
                .map(p -> ResponseEntity.ok((Object) toView(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建策略。 */
    @AuditLog(action = "CREATE_MASK_POLICY", resource = "mask_policy")
    @PostMapping("/policies")
    @Transactional
    public ResponseEntity<Map<String, Object>> createPolicy(@Valid @RequestBody MaskPolicyRequest req) {
        String tenantId = requireTenant();
        MaskPolicyEntity entity = MaskPolicyEntity.builder()
                .fieldName(req.fieldName())
                .assetName(req.assetName())
                .strategy(req.strategy())
                .algorithm(req.algorithm())
                .status("active")
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        MaskPolicyEntity saved = repository.save(entity);
        log.info("创建脱敏策略: id={}, field={}, asset={}, tenant={}",
                saved.getId(), saved.getFieldName(), saved.getAssetName(), tenantId);
        return ResponseEntity.ok(toView(saved));
    }

    /** 更新策略。 */
    @AuditLog(action = "UPDATE_MASK_POLICY", resource = "mask_policy")
    @PutMapping("/policies/{id}")
    @Transactional
    public ResponseEntity<?> updatePolicy(@PathVariable Long id, @Valid @RequestBody MaskPolicyRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setFieldName(req.fieldName());
            entity.setAssetName(req.assetName());
            entity.setStrategy(req.strategy());
            entity.setAlgorithm(req.algorithm());
            entity.setUpdatedAt(Instant.now());
            return ResponseEntity.ok((Object) toView(repository.save(entity)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除策略。 */
    @AuditLog(action = "DELETE_MASK_POLICY", resource = "mask_policy")
    @DeleteMapping("/policies/{id}")
    @Transactional
    public ResponseEntity<?> deletePolicy(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            repository.delete(entity);
            log.info("删除脱敏策略: id={}, tenant={}", id, tenantId);
            return ResponseEntity.ok(Map.of("deleted", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 查询权限申请列表。
     *
     * <p>对齐前端 {@code sec.ts} 的 {@code listApprovals}。
     * 从内存审批流存储查询，支持 status 过滤。</p>
     *
     * @param status 状态过滤（可选）
     * @return 200 + 审批列表
     */
    @GetMapping("/approvals")
    public ResponseEntity<List<Map<String, Object>>> listApprovals(
            @RequestParam(required = false) String status) {
        String tenantId = requireTenant();
        List<Map<String, Object>> records = APPROVALS.getOrDefault(tenantId, List.of());
        List<Map<String, Object>> filtered = (status == null || status.isBlank())
                ? new ArrayList<>(records)
                : records.stream().filter(r -> status.equals(r.get("status"))).toList();
        log.info("查询权限申请: status={}, tenant={}, size={}", status, tenantId, filtered.size());
        return ResponseEntity.ok(filtered);
    }

    /**
     * 批准权限申请。
     *
     * <p>对齐前端 {@code sec.ts} 的 {@code approveApproval}。
     * 更新内存审批流中对应记录的状态为 approved。</p>
     *
     * @param id 申请 ID
     * @return 200
     */
    @AuditLog(action = "APPROVE_PERMISSION", resource = "approval")
    @PostMapping("/approvals/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable Long id) {
        String tenantId = requireTenant();
        updateApprovalStatus(tenantId, id, "approved");
        log.info("批准权限申请: id={}, tenant={}", id, tenantId);
        return ResponseEntity.ok().build();
    }

    /**
     * 拒绝权限申请。
     *
     * <p>对齐前端 {@code sec.ts} 的 {@code rejectApproval}。
     * 更新内存审批流中对应记录的状态为 rejected。</p>
     *
     * @param id 申请 ID
     * @return 200
     */
    @AuditLog(action = "REJECT_PERMISSION", resource = "approval")
    @PostMapping("/approvals/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable Long id) {
        String tenantId = requireTenant();
        updateApprovalStatus(tenantId, id, "rejected");
        log.info("拒绝权限申请: id={}, tenant={}", id, tenantId);
        return ResponseEntity.ok().build();
    }

    /** 更新内存审批流中指定记录的状态。 */
    private void updateApprovalStatus(String tenantId, Long id, String status) {
        List<Map<String, Object>> records = APPROVALS.get(tenantId);
        if (records == null) {
            return;
        }
        String idStr = String.valueOf(id);
        synchronized (records) {
            for (Map<String, Object> r : records) {
                if (idStr.equals(String.valueOf(r.get("id")))) {
                    r.put("status", status);
                    r.put("updatedAt", Instant.now().toString());
                    break;
                }
            }
        }
    }

    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /** 视图映射。 */
    private Map<String, Object> toView(MaskPolicyEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("fieldName", e.getFieldName());
        m.put("assetName", e.getAssetName());
        m.put("strategy", e.getStrategy());
        m.put("algorithm", e.getAlgorithm());
        m.put("status", e.getStatus());
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }
}
