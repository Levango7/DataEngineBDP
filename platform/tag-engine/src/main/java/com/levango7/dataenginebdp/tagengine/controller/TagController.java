package com.levango7.dataenginebdp.tagengine.controller;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签定义与计算 REST 控制器。
 *
 * <p>统一前缀：{@code /api/v1/tags}</p>
 * <ul>
 *   <li>POST   /                       — 创建标签定义，返回 201</li>
 *   <li>GET    /?tenantId=xxx          — 列出指定租户的标签，返回 200</li>
 *   <li>GET    /{id}                   — 标签详情，返回 200 或 404</li>
 *   <li>DELETE /{id}                   — 删除标签，返回 204 或 404</li>
 *   <li>POST   /{id}/rules             — 添加标签规则，返回 201</li>
 *   <li>GET    /{id}/rules             — 标签规则列表，返回 200</li>
 *   <li>POST   /{id}/compute           — 计算标签，返回 200</li>
 *   <li>POST   /batch-compute          — 批量计算，返回 200</li>
 * </ul>
 *
 * <p>对应详细设计 §6 接口契约。</p>
 */
@RestController
@RequestMapping("/api/v1/tags")
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
     * @param req 创建请求
     * @return 已创建的标签定义
     */
    @PostMapping
    public ResponseEntity<TagDefinition> create(@Valid @RequestBody TagDefinitionRequest req) {
        TagDefinition created = tagService.createTagDefinition(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 列出指定租户的标签定义。
     *
     * @param tenantId 租户 ID
     * @return 标签定义列表
     */
    @GetMapping
    public ResponseEntity<List<TagDefinition>> list(@RequestParam String tenantId) {
        return ResponseEntity.ok(tagService.listTagDefinitions(tenantId));
    }

    /**
     * 获取标签详情。
     *
     * @param id 标签 ID
     * @return 标签定义
     */
    @GetMapping("/{id}")
    public ResponseEntity<TagDefinition> get(@PathVariable String id) {
        return tagService.getTagDefinition(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 删除标签。
     *
     * @param id 标签 ID
     * @return 204 或 404
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (tagService.deleteTagDefinition(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 添加标签规则。
     *
     * @param id  标签 ID
     * @param req 规则创建请求
     * @return 已创建的规则
     */
    @PostMapping("/{id}/rules")
    public ResponseEntity<TagRule> addRule(@PathVariable String id,
                                           @Valid @RequestBody TagRuleRequest req) {
        TagRule created = tagService.createTagRule(id, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 列出标签规则。
     *
     * @param id 标签 ID
     * @return 规则列表
     */
    @GetMapping("/{id}/rules")
    public ResponseEntity<List<TagRule>> listRules(@PathVariable String id) {
        return ResponseEntity.ok(tagService.getTagRules(id));
    }

    /**
     * 计算单个标签。
     *
     * @param id  标签 ID
     * @param req 计算请求
     * @return 计算结果
     */
    @PostMapping("/{id}/compute")
    public ResponseEntity<TagComputeResult> compute(@PathVariable String id,
                                                    @RequestBody ComputeRequest req) {
        return ResponseEntity.ok(computeService.computeTag(id, req));
    }

    /**
     * 批量计算标签。
     *
     * @param body 请求体（含 tagIds 与计算参数）
     * @return 批量计算结果
     */
    @PostMapping("/batch-compute")
    public ResponseEntity<BatchComputeResult> batchCompute(@RequestBody BatchComputeBody body) {
        ComputeRequest req = body.req() != null ? body.req() : new ComputeRequest();
        return ResponseEntity.ok(computeService.batchCompute(body.tagIds(), req));
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