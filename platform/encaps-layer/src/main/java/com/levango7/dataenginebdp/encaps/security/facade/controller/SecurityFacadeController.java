package com.levango7.dataenginebdp.encaps.security.facade.controller;

import com.levango7.dataenginebdp.encaps.security.facade.SecurityFacade;
import com.levango7.dataenginebdp.encaps.security.facade.assessment.AssessmentReport;
import com.levango7.dataenginebdp.encaps.security.facade.assessment.AssessmentType;
import com.levango7.dataenginebdp.encaps.security.facade.audit.AuditEvent;
import com.levango7.dataenginebdp.encaps.security.facade.audit.AuditFacade;
import com.levango7.dataenginebdp.encaps.security.facade.audit.AuditLevel;
import com.levango7.dataenginebdp.encaps.security.facade.auth.AuthResult;
import com.levango7.dataenginebdp.encaps.security.facade.evidence.EvidenceItem;
import com.levango7.dataenginebdp.encaps.security.facade.mask.MaskFacade;
import com.levango7.dataenginebdp.encaps.security.facade.mask.MaskType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SecurityFacade REST API 控制器。
 *
 * <p>统一暴露 SecurityFacade 的核心能力为 HTTP 端点，便于上层平台与运维工具集成。</p>
 *
 * <h3>端点清单</h3>
 * <table>
 *   <caption>表：SecurityFacadeController 端点说明表</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/v1/security/status</td><td>查询 SecurityFacade 状态</td></tr>
 *   <tr><td>POST</td><td>/api/v1/security/mask</td><td>执行脱敏</td></tr>
 *   <tr><td>GET</td><td>/api/v1/security/audit/events</td><td>查询审计事件</td></tr>
 *   <tr><td>GET</td><td>/api/v1/security/auth/check</td><td>鉴权检查</td></tr>
 *   <tr><td>POST</td><td>/api/v1/security/evidence/collect</td><td>收集并归档证据</td></tr>
 *   <tr><td>POST</td><td>/api/v1/security/assessment/export</td><td>导出测评报告</td></tr>
 * </table>
 *
 * <h3>认证</h3>
 * <p>所有端点均要求 JWT 认证（由 {@link com.levango7.dataenginebdp.encaps.security.JwtAuthFilter} 处理），
 * 除 {@code /status} 端点外均需有效租户上下文。</p>
 */
@RestController
@RequestMapping("/api/v1/security")
@Tag(name = "安全门面", description = "安全统一门面接口")
public class SecurityFacadeController {

    private static final Logger log = LoggerFactory.getLogger(SecurityFacadeController.class);

    private final SecurityFacade securityFacade;

    /**
     * 构造控制器。
     *
     * @param securityFacade SecurityFacade 统一入口
     */
    public SecurityFacadeController(SecurityFacade securityFacade) {
        this.securityFacade = securityFacade;
    }

    /**
     * 查询 SecurityFacade 状态。
     *
     * @return 状态信息
     */
    @Operation(summary = "查询 SecurityFacade 状态", description = "查询 crypto/mask/audit/auth 各能力启用状态与当前上下文")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", securityFacade.isEnabled());

        Map<String, Object> crypto = new LinkedHashMap<>();
        crypto.put("enabled", securityFacade.getConfig().getCrypto().isEnabled());
        crypto.put("provider", safeProviderName());
        crypto.put("profile", safeProfileName());
        crypto.put("availableProviders", securityFacade.crypto().availableProviderNames());
        body.put("crypto", crypto);

        Map<String, Object> mask = new LinkedHashMap<>();
        mask.put("enabled", securityFacade.getConfig().getMask().isEnabled());
        mask.put("builtInTypes", securityFacade.mask().builtInTypes());
        mask.put("customRules", securityFacade.mask().customRuleNames());
        body.put("mask", mask);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("enabled", securityFacade.getConfig().getAudit().isEnabled());
        audit.put("currentSize", securityFacade.audit().size());
        audit.put("maxRetained", securityFacade.getConfig().getAudit().getMaxEventsRetained());
        body.put("audit", audit);

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("enabled", securityFacade.getConfig().getAuth().isEnabled());
        auth.put("requireTenant", securityFacade.getConfig().getAuth().isRequireTenant());
        auth.put("currentPrincipal", securityFacade.auth().currentPrincipal());
        auth.put("currentTenant", securityFacade.auth().currentTenant());
        body.put("auth", auth);

        return ResponseEntity.ok(body);
    }

    /**
     * 执行脱敏。
     *
     * <p>请求体：{@code {"input": "13812345678", "type": "PHONE"}}</p>
     *
     * @param request 脱敏请求
     * @return 脱敏结果
     */
    @Operation(summary = "执行脱敏", description = "按脱敏类型（PHONE/IDCARD 等）对输入执行脱敏")
    @PostMapping("/mask")
    public ResponseEntity<Map<String, Object>> mask(@RequestBody MaskRequest request) {
        MaskType type = MaskType.valueOf(request.type().toUpperCase());
        String masked = securityFacade.mask().mask(request.input(), type);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type.name());
        body.put("masked", masked);
        return ResponseEntity.ok(body);
    }

    /**
     * 查询审计事件。
     *
     * @param level 可选级别过滤
     * @return 事件列表
     */
    @Operation(summary = "查询审计事件", description = "查询审计事件列表，支持 level 过滤")
    @GetMapping("/audit/events")
    public ResponseEntity<List<Map<String, Object>>> auditEvents(
            @RequestParam(required = false) String level) {
        List<AuditEvent> events;
        if (level != null && !level.isBlank()) {
            events = securityFacade.audit().listByLevel(AuditLevel.valueOf(level.toUpperCase()));
        } else {
            events = securityFacade.audit().list();
        }
        List<Map<String, Object>> body = events.stream().map(AuditEvent::toMap).toList();
        return ResponseEntity.ok(body);
    }

    /**
     * 鉴权检查。
     *
     * @return 鉴权结果
     */
    @Operation(summary = "鉴权检查", description = "检查当前 principal 是否具备完整访问权限")
    @GetMapping("/auth/check")
    public ResponseEntity<Map<String, Object>> authCheck() {
        AuthResult result = securityFacade.auth().checkFullAccess();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allowed", result.isAllowed());
        body.put("principal", result.getPrincipal());
        body.put("grantedAuthorities", result.getGrantedAuthorities());
        if (!result.isAllowed()) {
            body.put("reason", result.getReason());
        }
        return ResponseEntity.ok(body);
    }

    /**
     * 收集并归档证据。
     *
     * @return 归档结果（证据数量与 ID 列表）
     * @throws IOException 归档失败
     */
    @Operation(summary = "收集并归档证据", description = "收集安全证据并归档到 evidence.archiveDir")
    @PostMapping("/evidence/collect")
    public ResponseEntity<Map<String, Object>> collectEvidence() throws IOException {
        List<EvidenceItem> archived = securityFacade.collectAndArchiveEvidence();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collected", archived.size());
        body.put("evidenceIds", archived.stream().map(EvidenceItem::getId).toList());
        body.put("archiveDir", securityFacade.getConfig().getEvidence().getArchiveDir());
        return ResponseEntity.ok(body);
    }

    /**
     * 导出测评报告。
     *
     * <p>请求体：{@code {"type": "dengbao-2.0", "systemName": "数据引擎大数据平台"}}</p>
     *
     * @param request 导出请求
     * @return 报告路径与摘要
     * @throws IOException 导出失败
     */
    @Operation(summary = "导出测评报告", description = "按测评类型（dengbao-2.0 等）生成并落盘测评报告，返回报告摘要")
    @PostMapping("/assessment/export")
    public ResponseEntity<Map<String, Object>> exportAssessment(
            @RequestBody AssessmentExportRequest request) throws IOException {
        AssessmentType type = AssessmentType.fromCode(request.type());
        String systemName = request.systemName() != null ? request.systemName() : "shuqing-bigdata-platform";

        // 先收集证据
        List<EvidenceItem> evidence = securityFacade.collectEvidence();
        // 生成报告对象（用于响应摘要）
        AssessmentReport report = securityFacade.generateAssessment(type, evidence, systemName);
        // 落盘
        Path path = securityFacade.exportAssessment(type, evidence, systemName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportId", report.getId());
        body.put("type", type.getCode());
        body.put("systemName", systemName);
        body.put("complianceRate", report.complianceRate());
        body.put("controlItemCount", report.getControlItems().size());
        body.put("evidenceCount", evidence.size());
        body.put("filePath", path.toString());
        body.put("summary", report.getSummary());
        return ResponseEntity.ok(body);
    }

    // ===== 异常处理 =====

    /**
     * 处理非法参数异常。
     *
     * @param e 异常
     * @return 400 响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "bad_request");
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 处理能力禁用异常。
     *
     * @param e 异常
     * @return 503 响应
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("Service unavailable: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "service_unavailable");
        body.put("message", e.getMessage());
        return ResponseEntity.status(503).body(body);
    }

    /**
     * 处理 IO 异常。
     *
     * @param e 异常
     * @return 500 响应
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIOException(IOException e) {
        log.error("IO error", e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "internal_error");
        body.put("message", e.getMessage());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.internalServerError().headers(headers).body(body);
    }

    // ===== 请求体 DTO =====

    /**
     * 脱敏请求体。
     *
     * @param input 原始输入
     * @param type  脱敏类型名
     */
    public record MaskRequest(String input, String type) {
    }

    /**
     * 测评导出请求体。
     *
     * @param type       测评类型代码
     * @param systemName 系统名称
     */
    public record AssessmentExportRequest(String type, String systemName) {
    }

    // ===== 内部 =====

    private String safeProviderName() {
        try {
            return securityFacade.crypto().currentProviderName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String safeProfileName() {
        try {
            return securityFacade.crypto().currentProfile().name();
        } catch (Exception e) {
            return "unknown";
        }
    }
}