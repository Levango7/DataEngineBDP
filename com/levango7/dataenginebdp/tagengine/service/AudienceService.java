package com.shuqing.bigdata.tagengine.service;

import com.shuqing.bigdata.tagengine.model.AudienceRequest;
import com.shuqing.bigdata.tagengine.model.AudienceResult;
import com.shuqing.bigdata.tagengine.store.TagStore;
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
     * 人群圈选。
     *
     * @param req 圈选请求
     * @return 圈选结果
     */
    public AudienceResult selectAudience(AudienceRequest req) {
        // 兜底限制：req.limit 不超过配置上限
        if (req.getLimit() == null || req.getLimit() > maxResultSize) {
            req.setLimit(maxResultSize);
        }
        log.info("AudienceService.selectAudience: tenant={}, returnIds={}, limit={}",
                req.getTenantId(), req.isReturnIds(), req.getLimit());
        return tagStore.selectAudience(req);
    }
}