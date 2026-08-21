package com.shuqing.bigdata.streambatch.service;

import com.shuqing.bigdata.streambatch.router.BiViewRouter;
import com.shuqing.bigdata.streambatch.router.QueryMode;
import com.shuqing.bigdata.streambatch.router.ViewSelectionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 视图路由服务。
 *
 * <p>封装 BI 视图路由器的业务逻辑，提供查询路由 API。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViewRouterService {

    private final BiViewRouter biViewRouter;

    /**
     * 路由查询到合适的视图。
     *
     * @param table               查询的 Iceberg 表全名
     * @param queryMode           查询模式
     * @param originalSql         原始 SQL
     * @param latencyRequirementMs 延迟要求（毫秒，AUTO 模式用）
     * @return 视图选择结果
     */
    public ViewSelectionResult routeQuery(
            String table,
            QueryMode queryMode,
            String originalSql,
            Long latencyRequirementMs) {
        log.info("视图路由查询: table={}, mode={}", table, queryMode);
        return biViewRouter.route(table, queryMode, originalSql, latencyRequirementMs);
    }
}