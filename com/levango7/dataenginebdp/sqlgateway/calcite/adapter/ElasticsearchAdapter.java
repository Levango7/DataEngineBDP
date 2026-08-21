package com.shuqing.bigdata.sqlgateway.calcite.adapter;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;

/**
 * Elasticsearch 数据源适配器接口——对接 Elasticsearch 检索引擎。
 *
 * <p>Elasticsearch 擅长全文检索与聚合分析，本适配器在 {@link BaseAdapter} 之上
 * 扩展 ES 特有的下推能力：</p>
 * <ul>
 *   <li>查询 DSL 下推（Query DSL Pushdown）：将 filter 谓词转为 ES Query DSL</li>
 *   <li>聚合下推（Aggregation Pushdown）：将 GROUP BY + 聚合转为 ES Aggregation</li>
 *   <li>排序下推（Sort Pushdown）：将 ORDER BY 转为 ES sort 参数</li>
 *   <li>分页下推（Pagination Pushdown）：将 LIMIT/OFFSET 转为 ES from/size</li>
 *   <li>全文检索下推（Full-text Pushdown）：将 MATCH/QUERY 谓词转为 ES match query</li>
 * </ul>
 *
 * <p>对应 Calcite 中通过自定义 {@code ElasticsearchRel} 系列节点实现下推，
 * 将 SQL 谓词翻译为 ES Query DSL JSON。</p>
 *
 * @author shuqing-bigdata
 */
public interface ElasticsearchAdapter extends BaseAdapter {

    /**
     * 将 SQL 谓词翻译为 ES Query DSL JSON 片段。
     *
     * <p>如 {@code age > 18 AND name LIKE '%张%'} 翻译为：
     * <pre>{"bool":{"must":[{"range":{"age":{"gt":18}}},{"wildcard":{"name":"*张*"}}]}}</pre>
     * </p>
     *
     * @param predicate SQL 谓词
     * @return ES Query DSL JSON
     */
    String toQueryDsl(String predicate);

    /**
     * 将 GROUP BY + 聚合转为 ES Aggregation DSL。
     *
     * @param groupBy  分组列
     * @param aggFuncs 聚合函数列表
     * @return ES Aggregation DSL JSON
     */
    String toAggregationDsl(java.util.List<String> groupBy, java.util.List<String> aggFuncs);

    /**
     * 将 ORDER BY 转为 ES sort 参数。
     *
     * @param sortKeys 排序键列表（如 ["age DESC", "name ASC"]）
     * @return ES sort JSON 数组
     */
    String toSortDsl(java.util.List<String> sortKeys);

    /**
     * 将 LIMIT/OFFSET 转为 ES from/size 参数。
     *
     * @param limit  行数
     * @param offset 偏移
     * @return ES from/size JSON（如 {"from":0,"size":100}）
     */
    String toPaginationDsl(long limit, long offset);

    /**
     * 判断某索引是否启用并可用。
     *
     * @param indexName ES 索引名
     * @return {@code true} 表示索引存在且可查询
     */
    boolean isIndexAvailable(String indexName);

    @Override
    default DataSourceConfig.Type getAdapterType() {
        return DataSourceConfig.Type.ELASTICSEARCH;
    }
}