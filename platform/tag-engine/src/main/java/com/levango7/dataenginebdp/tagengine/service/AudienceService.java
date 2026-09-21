package com.levango7.dataenginebdp.tagengine.service;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.tagengine.model.AudienceRequest;
import com.levango7.dataenginebdp.tagengine.model.AudienceResult;
import com.levango7.dataenginebdp.tagengine.store.TagStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 人群圈选服务。
 *
 * <p>对应详细设计 §5 人群圈选、§6 接口 {@code POST /api/tag/v1/segment}。
 * 业务人员在圈选 UI 拖拽标签条件，本服务翻译为底层查询并返回 user_id 列表或统计计数。</p>
 *
 * <p>安全约束：</p>
 * <ul>
 *   <li>租户隔离：tenantId 强制注入，不可越权跨租户圈选</li>
 *   <li>结果上限：单次返回 user_id 数不超过 {@code app.audience.max-result-size}</li>
 *   <li>SQL 注入：条件走参数化 SQL（DorisSqlGenerator）</li>
 * </ul>
 */
@Service
public class AudienceService {

    private static final Logger log = LoggerFactory.getLogger(AudienceService.class);

    private final TagStore tagStore;

    @Value("${app.audience.max-result-size:10000}")
    private int maxResultSize;

    public AudienceService(TagStore tagStore) {
        this.tagStore = tagStore;
    }

    /**
     * 人群圈选（租户隔离：tenantId 强制取当前租户）。
     *
     * <p>R8 租户隔离补齐：请求体与其 include/exclude 子查询里的 {@code tenantId}
     * 一律被当前租户覆盖，防止调用方（含非 HTTP 调用方）圈选到别的租户的人群。</p>
     *
     * @param req 圈选请求
     * @return 圈选结果（仅当前租户）
     * @throws IllegalStateException 缺少租户上下文（fail-closed）
     */
    public AudienceResult selectAudience(AudienceRequest req) {
        String tenantId = requireTenant();
        req.setTenantId(tenantId);
        if (req.getInclude() != null) {
            req.getInclude().setTenantId(tenantId);
        }
        if (req.getExclude() != null) {
            req.getExclude().setTenantId(tenantId);
        }
        // 兜底限制：req.limit 不超过配置上限
        if (req.getLimit() == null || req.getLimit() > maxResultSize) {
            req.setLimit(maxResultSize);
        }
        log.info("AudienceService.selectAudience: tenant={}, returnIds={}, limit={}",
                req.getTenantId(), req.isReturnIds(), req.getLimit());
        return tagStore.selectAudience(req);
    }

    /**
     * 取当前租户 ID；缺失时 fail-closed 抛异常。
     *
     * <p>Service 层不信任入参/请求体里的 tenantId，一律以 {@link TenantContext} 为准。</p>
     *
     * @return 当前租户 ID
     * @throws IllegalStateException 租户上下文缺失
     */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("AudienceService: 缺少租户上下文，拒绝圈选");
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }
}