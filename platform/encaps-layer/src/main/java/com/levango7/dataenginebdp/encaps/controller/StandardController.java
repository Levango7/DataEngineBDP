package com.levango7.dataenginebdp.encaps.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.model.AssetEntity;
import com.levango7.dataenginebdp.encaps.model.StandardEntity;
import com.levango7.dataenginebdp.encaps.repository.AssetRepository;
import com.levango7.dataenginebdp.encaps.repository.StandardRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主数据标准端点（ROADMAP 前后端接线：前端 /standards）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/standards")
@Tag(name = "标准管理", description = "数据标准管理")
public class StandardController {

    private final StandardRepository repository;
    private final AssetRepository assetRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 创建/更新请求体（对齐前端 CreateStandardParams）。 */
    public record StandardRequest(
            @NotBlank String name,
            @NotBlank String type,
            String rule,
            String description) {
    }

    /** 列表（分页契约 + type 过滤）。 */
    @Operation(summary = "查询标准列表", description = "分页查询，支持 type 过滤")
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = requireTenant();
        List<StandardEntity> all = (type == null || type.isBlank())
                ? repository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : repository.findByTenantIdAndTypeOrderByCreatedAtDesc(tenantId, type);
        int total = all.size();
        int start = Math.min((page - 1) * size, total);
        int end = Math.min(start + size, total);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", all.subList(start, end).stream().map(this::toView).toList());
        body.put("total", total);
        body.put("page", page);
        body.put("size", size);
        return ResponseEntity.ok(body);
    }

    /** 详情。 */
    @Operation(summary = "查询标准详情", description = "按 ID 获取标准详情")
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> get(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId)
                .map(s -> ResponseEntity.ok((Object) toView(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。 */
    @Operation(summary = "创建标准", description = "创建数据标准（status=active）")
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody StandardRequest req) {
        String tenantId = requireTenant();
        StandardEntity entity = StandardEntity.builder()
                .name(req.name())
                .type(req.type())
                .rule(req.rule())
                .description(req.description())
                .status("active")
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        StandardEntity saved = repository.save(entity);
        log.info("创建标准: id={}, name={}, type={}, tenant={}",
                saved.getId(), saved.getName(), saved.getType(), tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(saved));
    }

    /** 更新。 */
    @Operation(summary = "更新标准", description = "按 ID 更新标准（name/type/rule/description）")
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody StandardRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setName(req.name());
            entity.setType(req.type());
            entity.setRule(req.rule());
            entity.setDescription(req.description());
            entity.setUpdatedAt(Instant.now());
            return ResponseEntity.ok((Object) toView(repository.save(entity)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除。 */
    @Operation(summary = "删除标准", description = "按 ID 删除标准（租户隔离）")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            repository.delete(entity);
            log.info("删除标准: id={}, tenant={}", id, tenantId);
            return ResponseEntity.ok(Map.of("deleted", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 查询落标率统计。
     *
     * <p>对齐前端 {@code standard.ts} 的 {@code getSummary}。
     * 已落标数 = 当前租户标准中被资产 fullJson.standardId 引用的标准数；
     * 落标率 = applied / total * 100。AssetEntity 暂无 standardId 列，
     * 故从 fullJson 解析关联。</p>
     *
     * @return 200 + 落标率统计
     */
    @Operation(summary = "查询落标率统计", description = "统计当前租户标准的落标率"
            + "（已落标数 / 总数 × 100，关联来自资产 fullJson.standardId）")

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> summary() {
        String tenantId = requireTenant();
        List<StandardEntity> all = repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        int total = all.size();
        // 计算已落标数：遍历资产 fullJson 中的 standardId 字段，统计被引用的标准数
        Set<String> appliedStandardIds = new HashSet<>();
        List<AssetEntity> assets = assetRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        for (AssetEntity a : assets) {
            try {
                JsonNode full = objectMapper.readTree(a.getFullJson());
                JsonNode sid = full.get("standardId");
                if (sid != null && !sid.isNull()) {
                    appliedStandardIds.add(sid.asText());
                }
            } catch (Exception ignored) {
                // fullJson 非法时跳过
            }
        }
        int applied = (int) all.stream()
                .filter(s -> appliedStandardIds.contains(String.valueOf(s.getId())))
                .count();
        double applyRate = total == 0 ? 0.0 : (applied * 100.0 / total);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("applied", applied);
        summary.put("applyRate", applyRate);
        return ResponseEntity.ok(summary);
    }

    /**
     * 标准符合性检查（标准落地校验）。
     *
     * <p>背景：此前标准只做存储与引用统计（summary 的落标率=是否被引用），
     * 从不校验资产内容是否<b>符合</b>标准——"主数据标准"线的最后一步为空。
     * 本端点把标准的 rule 真实作用于资产数据，按标准类型执行检查：</p>
     * <ul>
     *   <li><b>enum / dict</b>：rule 为逗号分隔码值集；资产 fullJson 中
     *       {@code values[]} 每项须属于码值集（资产级聚合校验）</li>
     *   <li><b>string / primary_key</b>：rule 为命名正则（如
     *       {@code ^[a-z][a-z0-9_]*$}）；资产 name 须匹配</li>
     *   <li><b>amount</b>：rule 为格式描述（amount/decimal）；资产 fullJson
     *       {@code amount} 字段须为合法数值</li>
     *   <li><b>date</b>：资产 fullJson {@code date} 字段须为 ISO 日期/时间</li>
     * </ul>
     *
     * <p>返回每条标准的 checked/violations/compliant 标记与总体符合率；
     * 资产 fullJson 缺少对应字段视为"不适用"（skipped），不计入违反。</p>
     */
    @Operation(summary = "标准符合性检查", description = "按标准类型校验资产数据是否符合标准规则，"
            + "返回逐标准明细与总体符合率（enum/dict=码值校验，string/primary_key=命名正则，"
            + "amount=数值格式，date=ISO 日期格式）")
    @GetMapping("/compliance")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> compliance() {
        String tenantId = requireTenant();
        List<StandardEntity> standards = repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        List<AssetEntity> assets = assetRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);

        List<Map<String, Object>> items = new java.util.ArrayList<>();
        int totalChecked = 0;
        int totalViolations = 0;

        for (StandardEntity std : standards) {
            int checked = 0;
            List<Map<String, Object>> violations = new java.util.ArrayList<>();
            for (AssetEntity asset : assets) {
                ComplianceOutcome outcome = checkOne(std, asset);
                if (outcome.kind() == OutcomeKind.COMPLIANT) {
                    checked++;
                } else if (outcome.kind() == OutcomeKind.VIOLATION) {
                    checked++;
                    totalViolations++;
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("assetId", String.valueOf(asset.getId()));
                    v.put("assetName", asset.getName());
                    v.put("reason", outcome.reason);
                    violations.add(v);
                }
                // SKIPPED：字段缺失不适用，不计入
            }
            totalChecked += checked;
            int vcount = violations.size();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("standardId", String.valueOf(std.getId()));
            item.put("standardName", std.getName());
            item.put("type", std.getType());
            item.put("checked", checked);
            item.put("violations", vcount);
            item.put("compliant", vcount == 0);
            // 违反明细最多带 20 条，防大资产集响应膨胀；全量走逐资产审计
            item.put("violationSamples", violations.subList(0, Math.min(vcount, 20)));
            items.add(item);
        }

        double complianceRate = totalChecked == 0 ? 100.0
                : ((totalChecked - totalViolations) * 100.0 / totalChecked);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("standards", items);
        body.put("totalChecked", totalChecked);
        body.put("totalViolations", totalViolations);
        body.put("complianceRate", Math.round(complianceRate * 10) / 10.0);
        log.info("标准符合性检查: tenant={}, standards={}, checked={}, violations={}, rate={}%",
                tenantId, standards.size(), totalChecked, totalViolations,
                Math.round(complianceRate * 10) / 10.0);
        return ResponseEntity.ok(body);
    }

    /** 单资产对单标准的检查结果。 */
    private enum OutcomeKind { COMPLIANT, VIOLATION, SKIPPED }

    /** 检查结果（携带违反原因）。 */
    private record ComplianceOutcome(OutcomeKind kind, String reason) {
        static ComplianceOutcome compliant() { return new ComplianceOutcome(OutcomeKind.COMPLIANT, null); }
        static ComplianceOutcome violation(String r) { return new ComplianceOutcome(OutcomeKind.VIOLATION, r); }
        static ComplianceOutcome skipped() { return new ComplianceOutcome(OutcomeKind.SKIPPED, null); }
    }

    /** 按标准类型分派检查逻辑。 */
    private ComplianceOutcome checkOne(StandardEntity std, AssetEntity asset) {
        String rule = std.getRule() == null ? "" : std.getRule().trim();
        String type = std.getType() == null ? "" : std.getType();
        JsonNode full;
        try {
            full = (asset.getFullJson() == null || asset.getFullJson().isBlank())
                    ? objectMapper.createObjectNode() : objectMapper.readTree(asset.getFullJson());
        } catch (Exception e) {
            return ComplianceOutcome.violation("资产 fullJson 非法 JSON: " + e.getMessage());
        }
        switch (type) {
            case "enum", "dict" -> {
                // rule=逗号分隔码值集；资产 values[] 全部落在集合内才符合
                JsonNode values = full.get("values");
                if (values == null || !values.isArray() || values.isEmpty()) {
                    return ComplianceOutcome.skipped();
                }
                Set<String> allowed = new HashSet<>(
                        java.util.Arrays.stream(rule.split(","))
                                .map(String::trim).filter(s -> !s.isEmpty()).toList());
                if (allowed.isEmpty()) {
                    return ComplianceOutcome.skipped();
                }
                for (JsonNode v : values) {
                    if (!allowed.contains(v.asText())) {
                        return ComplianceOutcome.violation("码值不在标准集内: " + v.asText());
                    }
                }
                return ComplianceOutcome.compliant();
            }
            case "string", "primary_key" -> {
                // rule=命名正则；资产名匹配
                if (rule.isEmpty()) {
                    return ComplianceOutcome.skipped();
                }
                try {
                    return (asset.getName() != null && asset.getName().matches(rule))
                            ? ComplianceOutcome.compliant()
                            : ComplianceOutcome.violation("资产名不匹配命名标准: " + asset.getName());
                } catch (java.util.regex.PatternSyntaxException e) {
                    return ComplianceOutcome.violation("标准 rule 正则非法: " + e.getMessage());
                }
            }
            case "amount" -> {
                JsonNode amount = full.get("amount");
                if (amount == null || amount.isNull()) {
                    return ComplianceOutcome.skipped();
                }
                try {
                    Double.parseDouble(amount.asText().replace(",", ""));
                    return ComplianceOutcome.compliant();
                } catch (NumberFormatException e) {
                    return ComplianceOutcome.violation("amount 非数值: " + amount.asText());
                }
            }
            case "date" -> {
                JsonNode date = full.get("date");
                if (date == null || date.isNull()) {
                    return ComplianceOutcome.skipped();
                }
                String s = date.asText();
                boolean ok = s.matches("\\d{4}-\\d{2}-\\d{2}(T.*)?")
                        || s.matches("\\d{4}/\\d{2}/\\d{2}.*")
                        || s.matches("\\d{8}");
                return ok ? ComplianceOutcome.compliant()
                        : ComplianceOutcome.violation("date 非 ISO 格式: " + s);
            }
            default -> {
                return ComplianceOutcome.skipped();
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
    private Map<String, Object> toView(StandardEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("type", e.getType());
        m.put("rule", e.getRule());
        m.put("description", e.getDescription());
        m.put("status", e.getStatus());
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }
}
