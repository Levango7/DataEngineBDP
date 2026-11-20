package com.levango7.dataenginebdp.sqlgateway.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * SQL 执行响应 POJO。
 *
 * @author shuqing-bigdata
 */
@Data
@Builder
public class SqlExecuteResponse {

    /**
     * 查询唯一 ID（由网关生成）。
     */
    private String queryId;

    /**
     * 查询状态：{@code SIMULATED} / {@code SUCCESS} / {@code FAILED} 等。
     */
    private String status;

    /**
     * 结果列名列表。
     */
    private List<String> columns;

    /**
     * 结果行集合，每行为一个 Object 列表。
     */
    private List<List<Object>> rows;

    /**
     * 执行耗时（毫秒）。
     */
    private Long durationMs;

    /**
     * 实际执行引擎：{@code trino} / {@code doris}。
     */
    private String engine;

    /**
     * 真实扫描字节数（来自 Trino stats.rawInputBytes；null 表示未获取或需估算）。
     * <p>用于计量计费：为 null 时按估算计费（est=true），非 null 时按真实值（est=false）。</p>
     */
    private Long rawInputBytes;

    /** 是否命中查询缓存（任务 D：SQL 网关查询结果缓存）。 */
    private boolean cached;

    /**
     * 结果是否被截断。
     * <p>true 表示返回行数未覆盖完整结果集：触发 limit/maxRows 行数上限，
     * 或 Trino 仅取首页结果（仍有 nextUri 未拉取）。null 表示引擎未报告。</p>
     */
    private Boolean truncated;

    /**
     * 附加信息：降级原因、截断说明、拒绝原因等，供调用方直接展示/排障。
     * <p>成功且未截断时为 null。</p>
     */
    private String message;

    /**
     * SQL 涉及表全名 → 数据源标识映射（问数可解释，契约 docs/SQL-EXECUTE-LINEAGE-CONTRACT.md）。
     *
     * <p>由网关本地 AST 提取 + catalog 前缀映射得到，不调用 lineage-analyzer。
     * 取值规则：表名含 catalog 前缀 → 映射源；无前缀 → 默认源；无表 SQL → 空 map；
     * 未注入解析器 / AST 解析失败 / 执行失败 → null（JSON 缺席）。best-effort，不影响查询主链路。</p>
     */
    private Map<String, String> tableToSource;

    /**
     * 结果行数：始终由 {@link #rows} 推导（rows 缺席或为空 ⇒ 0），不单独存储字段。
     *
     * <p>为什么这样实现：对外契约 {@code docs/user-guide/api-reference.md} §4.1
     * 「POST /api/v1/sql/execute — 执行 SQL」的响应示例（200 OK）已声明 {@code "rowCount": 2}，
     * 而本 DTO 原先没有该字段 ⇒ 实现与已发布契约不一致（台账 #125 登记的 chain1 判据③
     * 恒假即源于此）。</p>
     *
     * <p>为什么用派生 getter 而不是新增可写字段：行数与行集合必须同源。若两处分别赋值，
     * 任何一条构造路径漏设就会再次出现「rows 与 rowCount 漂移」；派生写法让全部
     * {@code SqlExecuteResponse.builder()} 构造点（主代码 9 处：BackendProxyService 6 处 /
     * SqlRoutingService 3 处）自动满足「rowCount == rows.size()」，且空行集合恒为 0。</p>
     *
     * <p>序列化：Jackson 走 getter ⇒ 该字段恒出现在 JSON 中（不会出现 null 或缺席）。</p>
     */
    public Integer getRowCount() {
        return rows == null ? 0 : rows.size();
    }
}