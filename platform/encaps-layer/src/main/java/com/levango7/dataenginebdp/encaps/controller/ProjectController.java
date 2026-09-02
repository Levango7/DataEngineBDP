package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.common.ApiResponseAdvice;
import com.levango7.dataenginebdp.encaps.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 项目管理端点（ROADMAP 前后端接线：前端 project.ts 调用 /api/v1/projects）。
 *
 * <p>stub Controller，返回空数据/桩数据，经 {@link ApiResponseAdvice} 自动包装为
 * {@link ApiResponse} 格式（{@code code:0, message:"OK", data:..., success:true, timestamp:...}）。
 * 供 Nightly E2E Playwright 测试接线使用，后续接入真实业务时替换为持久化实现。</p>

 * <p>跨进程守卫（Sprint 2.2 L4-0 模式复用）：encaps-tenant 依赖 encaps-layer（同包
 * 组件扫描会带入本 Controller），而 encaps-tenant 自身有 /api/v1/projects 的真实
 * 实现——同 JVM 双注册会 ambiguous mapping 启动失败。encaps-tenant 侧已配置
 * {@code app.tenant.controller.enabled=false} 关闭本 stub。
 * （nightly-e2e 场景中 encaps-layer 独立进程运行，matchIfMissing=true 默认启用不受影响。）</p>
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "app.tenant.controller.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/projects")
@Tag(name = "项目管理", description = "项目 CRUD 与关联资源查询")
public class ProjectController {

    /** 列表（空分页）。 */
    @Operation(summary = "查询项目列表", description = "stub：返回空分页结果 {list:[], total:0, page:1, pageSize:10}")
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", List.of());
        body.put("total", 0);
        body.put("page", 1);
        body.put("pageSize", 10);
        return body;
    }

    /** 详情（404）。 */
    @Operation(summary = "查询项目详情", description = "stub：抛出 NoSuchElementException 返回 404")
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        throw new NoSuchElementException("项目不存在: " + id);
    }

    /** 项目下数据集（空数组）。 */
    @Operation(summary = "查询项目数据集", description = "stub：返回空数组")
    @GetMapping("/{id}/datasets")
    public List<Object> datasets(@PathVariable Long id) {
        return List.of();
    }

    /** 项目下作业（空数组）。 */
    @Operation(summary = "查询项目作业", description = "stub：返回空数组")
    @GetMapping("/{id}/jobs")
    public List<Object> jobs(@PathVariable Long id) {
        return List.of();
    }

    /** 项目成员（空数组）。 */
    @Operation(summary = "查询项目成员", description = "stub：返回空数组")
    @GetMapping("/{id}/members")
    public List<Object> members(@PathVariable Long id) {
        return List.of();
    }

    /** 创建项目（stub）。 */
    @Operation(summary = "创建项目", description = "stub：返回桩项目对象")
    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> req) {
        log.info("创建项目(stub): req={}", req);
        return stubProject();
    }

    /** 更新项目（stub）。 */
    @Operation(summary = "更新项目", description = "stub：返回桩项目对象")
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> req) {
        log.info("更新项目(stub): id={}, req={}", id, req);
        return stubProject();
    }

    /** 删除项目（204 No Content）。 */
    @Operation(summary = "删除项目", description = "stub：返回 204 No Content")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.info("删除项目(stub): id={}", id);
        return ResponseEntity.noContent().build();
    }

    /** 桩项目对象。 */
    private Map<String, Object> stubProject() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "stub-project");
        m.put("name", "stub-project");
        m.put("description", "");
        m.put("status", "active");
        m.put("members", List.of());
        m.put("datasets", List.of());
        m.put("jobs", List.of());
        return m;
    }
}