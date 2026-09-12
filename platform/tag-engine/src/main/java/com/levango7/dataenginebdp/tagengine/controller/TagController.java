package com.levango7.dataenginebdp.tagengine.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.tagengine.model.BatchComputeResult;
import com.levango7.dataenginebdp.tagengine.model.ComputeRequest;
import com.levango7.dataenginebdp.tagengine.model.TagComputeResult;
import com.levango7.dataenginebdp.tagengine.model.TagDefinition;
import com.levango7.dataenginebdp.tagengine.model.TagDefinitionRequest;
import com.levango7.dataenginebdp.tagengine.model.TagRule;
import com.levango7.dataenginebdp.tagengine.model.TagRuleRequest;
import com.levango7.dataenginebdp.tagengine.service.ComputeService;
import com.levango7.dataenginebdp.tagengine.service.TagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

/**
 * 标签定义与计算 REST 控制器。
 *
 * <p>统一前缀：{@code /api/v1/tags}</p>
 * <ul>
 *   <li>POST   /                       — 创建标签定义，返回 201</li>
 *   <li>GET    /                       — 列出当前租户的标签，返回 200</li>
 *   <li>GET    /{id}                   — 标签详情，返回 200 或 404</li>
 *   <li>DELETE /{id}                   — 删除标签，返回 204 或 404</li>
 *   <li>POST   /{id}/rules             — 添加标签规则，返回 201</li>
 *   <li>GET    /{id}/rules             — 标签规则列表，返回 200</li>
 *   <li>POST   /{id}/compute           — 计算标签，返回 200</li>
 *   <li>POST   /batch-compute          — 批量计算，返回 200</li>
 * </ul>
 *
 * <p><b>多租户隔离（R10 安全修复）</b>：所有端点通过 {@link #requireTenant()}
 * 从 {@link TenantContext}（由 JwtAuthFilter 设置）取得租户 ID，缺失则 fail-closed
 * （抛 {@link IllegalStateException}，禁止匿名跨租户访问）。list 按 tenantId 过滤；
 * get/delete/addRule/listRules/compute/batchCompute 按 (id, tenantId) 联合校验；
 * create 时强制写入 TenantContext 的 tenantId，忽略请求体中的 tenantId。
 * 移除了 list 端点的 {@code @RequestParam tenantId}，防止客户端伪造租户 ID 越权查询。</p>
 *
 * <p>对应详细设计 §6 接口契约。</p>
 */
@RestController
@Tag(name = "标签引擎-标签管理", description = "标签定义CRUD与计算")
@RequestMapping("/api/v1/tags")
@PreAuthorize("isAuthenticated()")
public class TagController {

    private final TagService tagService;
    private final ComputeService computeService;

    public TagController(TagService tagService, ComputeService computeService) {
        this.tagService = tagService;
        this.computeService = computeService;
    }

    /**
     * 创建标签定义。
     *
     * <p>强制写入当前租户 ID（来自 {@link TenantContext}），忽略请求体中的 tenantId，
     * 防止跨租户创建。</p>
     *
     * @param req 创建请求
     * @return 已创建的标签定义
     */
    @Operation(summary = "创建标签定义")
    @PostMapping
    public ResponseEntity<TagDefinition> create(@Valid @RequestBody TagDefinitionRequest req) {
        String tenantId = requireTenant();
        req.setTenantId(tenantId);
        TagDefinition created = tagService.createTagDefinition(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 列出当前租户的标签定义。
     *
     * <p>租户 ID 从 {@link TenantContext} 获取，不接受客户端传入的 tenantId，
     * 防止越权查询其他租户的标签。</p>
     *
     * @return 标签定义列表
     */
    @Operation(summary = "列出当前租户的标签定义")
    @GetMapping
    public ResponseEntity<List<TagDefinition>> list() {
        String tenantId = requireTenant();
        return ResponseEntity.ok(tagService.listTagDefinitions(tenantId));
    }

    /**
     * 获取标签详情（按租户隔离：仅返回属于当前租户的标签）。
     *
     * @param id 标签 ID
     * @return 标签定义
     */
    @Operation(summary = "获取标签详情")
    @GetMapping("/{id}")
    public ResponseEntity<TagDefinition> get(@PathVariable String id) {
        String tenantId = requireTenant();
        return tagService.getTagDefinition(id, tenantId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 删除标签（按租户隔离：仅允许删除属于当前租户的标签）。
     *
     * @param id 标签 ID
     * @return 204 或 404
     */
    @Operation(summary = "删除标签")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        String tenantId = requireTenant();
        if (tagService.deleteTagDefinition(id, tenantId)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 添加标签规则（按租户隔离：仅允许为属于当前租户的标签添加规则）。
     *
     * @param id  标签 ID
     * @param req 规则创建请求
     * @return 已创建的规则
     */
    @Operation(summary = "添加标签规则")
    @PostMapping("/{id}/rules")
    public ResponseEntity<TagRule> addRule(@PathVariable String id,
                                           @Valid @RequestBody TagRuleRequest req) {
        String tenantId = requireTenant();
        req.setTenantId(tenantId);
        TagRule created = tagService.createTagRule(id, req, tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 列出标签规则（按租户隔离：仅返回属于当前租户的标签的规则）。
     *
     * @param id 标签 ID
     * @return 规则列表
     */
    @Operation(summary = "列出标签规则")
    @GetMapping("/{id}/rules")
    public ResponseEntity<List<TagRule>> listRules(@PathVariable String id) {
        String tenantId = requireTenant();
        return ResponseEntity.ok(tagService.getTagRules(id, tenantId));
    }

    /**
     * 计算单个标签（按租户隔离：仅允许计算属于当前租户的标签）。
     *
     * @param id  标签 ID
     * @param req 计算请求
     * @return 计算结果
     */
    @Operation(summary = "计算单个标签")
    @PostMapping("/{id}/compute")
    public ResponseEntity<TagComputeResult> compute(@PathVariable String id,
                                                    @RequestBody ComputeRequest req) {
        String tenantId = requireTenant();
        return ResponseEntity.ok(computeService.computeTag(id, req, tenantId));
    }

    /**
     * 批量计算标签（按租户隔离：仅计算属于当前租户的标签）。
     *
     * @param body 请求体（含 tagIds 与计算参数）
     * @return 批量计算结果
     */
    @Operation(summary = "批量计算标签")
    @PostMapping("/batch-compute")
    public ResponseEntity<BatchComputeResult> batchCompute(@RequestBody BatchComputeBody body) {
        String tenantId = requireTenant();
        ComputeRequest req = body.req() != null ? body.req() : new ComputeRequest();
        return ResponseEntity.ok(computeService.batchCompute(body.tagIds(), req, tenantId));
    }

    /**
     * 从 {@link TenantContext} 获取租户 ID，缺失则 fail-closed。
     *
     * @return 当前请求的租户 ID
     * @throws IllegalStateException 若 TenantContext 未设置租户 ID
     */
    private static String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /**
     * 批量计算请求体。
     *
     * @param tagIds 标签 ID 列表
     * @param req    计算请求
     */
    public record BatchComputeBody(List<String> tagIds, ComputeRequest req) {
    }
}
