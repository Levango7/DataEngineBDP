package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.common.ApiResponse;
import com.levango7.dataenginebdp.encaps.common.ApiResponseAdvice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索端点（ROADMAP 前后端接线：前端 search.ts 调用 /api/v1/search）。
 *
 * <p>stub Controller，返回空数据/桩数据，经 {@link ApiResponseAdvice} 自动包装为
 * {@link ApiResponse} 格式（{@code code:0, message:"OK", data:..., success:true, timestamp:...}）。
 * 供 Nightly E2E Playwright 测试接线使用，后续接入真实业务时替换为搜索引擎实现。</p>

 * <p>跨进程守卫（Sprint 2.2 L4-0 模式复用）：encaps-tenant 依赖 encaps-layer（同包
 * 组件扫描会带入本 Controller），而 encaps-tenant 自身有 /api/v1/projects 的真实
 * 实现——同 JVM 双注册会 ambiguous mapping 启动失败。encaps-tenant 侧已配置
 * {@code app.tenant.controller.enabled=false} 关闭本 stub。
 * （nightly-e2e 场景中 encaps-layer 独立进程运行，matchIfMissing=true 默认启用不受影响。）</p>
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "app.tenant.controller.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/search")
@Tag(name = "搜索", description = "全文检索、历史、facets 与建议")
public class SearchController {

    /** 搜索（空分页）。 */
    @Operation(summary = "执行搜索", description = "stub：返回空搜索结果 {list:[], total:0, page:1, pageSize:10}")
    @PostMapping
    public Map<String, Object> search(@RequestBody(required = false) Map<String, Object> req) {
        log.info("搜索(stub): req={}", req);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", List.of());
        body.put("total", 0);
        body.put("page", 1);
        body.put("pageSize", 10);
        return body;
    }

    /** 搜索历史（空数组）。 */
    @Operation(summary = "查询搜索历史", description = "stub：返回空数组")
    @GetMapping("/history")
    public List<Object> history() {
        return List.of();
    }

    /** facets（空对象）。 */
    @Operation(summary = "查询搜索 facets", description = "stub：返回空对象")
    @GetMapping("/facets")
    public Map<String, Object> facets() {
        return new LinkedHashMap<>();
    }

    /** 搜索建议（空数组）。 */
    @Operation(summary = "搜索建议", description = "stub：按 keyword 返回空数组")
    @GetMapping("/suggest")
    public List<Object> suggest(@RequestParam(required = false) String keyword) {
        return List.of();
    }

    /** 导出（stub）。 */
    @Operation(summary = "导出搜索结果", description = "stub：返回桩导出结果")
    @PostMapping("/export")
    public Map<String, Object> export(@RequestBody(required = false) Map<String, Object> req) {
        log.info("导出搜索结果(stub): req={}", req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", "");
        m.put("format", "csv");
        return m;
    }

    /** 清空历史（204 No Content）。 */
    @Operation(summary = "清空搜索历史", description = "stub：返回 204 No Content")
    @PostMapping("/history/clear")
    public ResponseEntity<Void> clearHistory() {
        log.info("清空搜索历史(stub)");
        return ResponseEntity.noContent().build();
    }

    /** 删除单条历史（204 No Content）。 */
    @Operation(summary = "删除单条搜索历史", description = "stub：返回 204 No Content")
    @PostMapping("/history/{id}/delete")
    public ResponseEntity<Void> deleteHistory(@PathVariable String id) {
        log.info("删除搜索历史(stub): id={}", id);
        return ResponseEntity.noContent().build();
    }
}