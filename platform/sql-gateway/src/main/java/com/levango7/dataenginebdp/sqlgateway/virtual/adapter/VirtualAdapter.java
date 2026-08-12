package com.levango7.dataenginebdp.sqlgateway.virtual.adapter;

import com.levango7.dataenginebdp.sqlgateway.virtual.ColumnDefinition;
import com.levango7.dataenginebdp.sqlgateway.virtual.VirtualTableDefinition;

import java.util.List;
import java.util.Map;

/**
 * 虚拟表数据源适配器统一接口。
 *
 * <p>每种外部数据源（MySQL/Oracle/JDBC/Kafka/REST）实现该接口，
 * 由 {@link VirtualAdapterRegistry} 按数据源类型分发。
 * SQL 网关通过本接口透明地访问外部源，对上层屏蔽异构数据源差异。</p>
 *
 * <p>核心方法：</p>
 * <ul>
 *   <li>{@link #getSchema}：获取虚拟表 schema（列定义），用于元数据缓存与 SQL 解析；</li>
 *   <li>{@link #query}：执行查询，返回列名与行数据；</li>
 *   <li>{@link #close}：关闭资源（连接池、客户端等）。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public interface VirtualAdapter {

    /**
     * 获取虚拟表的 schema（列定义列表）。
     *
     * <p>首次调用时访问外部源获取元数据，后续可由缓存层加速。
     * 用于 SQL 解析、校验与计划生成。</p>
     *
     * @param definition 虚拟表定义
     * @return 列定义列表
     * @throws VirtualAdapterException 若获取 schema 失败
     */
    List<ColumnDefinition> getSchema(VirtualTableDefinition definition) throws VirtualAdapterException;

    /**
     * 执行查询，返回结果列与行数据。
     *
     * <p>查询可包含谓词下推（将 WHERE 条件翻译为外部源原生查询），
     * 以减少回传数据量。当前实现返回全部匹配行，由网关层做投影/过滤。</p>
     *
     * @param definition 虚拟表定义
     * @param predicate  查询谓词（可选，如 {@code id > 100}）；{@code null} 表示全表扫描
     * @param limit      返回行数上限；{@code null} 或 {@code <=0} 表示不限制
     * @return 查询结果
     * @throws VirtualAdapterException 若查询失败
     */
    QueryResult query(VirtualTableDefinition definition, String predicate, Integer limit)
            throws VirtualAdapterException;

    /**
     * 测试连接是否可用。
     *
     * @param definition 虚拟表定义
     * @return {@code true} 表示连接正常
     */
    boolean testConnection(VirtualTableDefinition definition);

    /**
     * 关闭适配器持有的资源（连接池、HTTP 客户端等）。
     *
     * <p>通常在应用关闭或虚拟表删除时调用。</p>
     */
    void close();

    /**
     * 查询结果。
     *
     * @param columns 列名列表（顺序与 rows 中每行数据一致）
     * @param rows    行数据列表，每行为列值数组
     */
    record QueryResult(List<String> columns, List<List<Object>> rows) {

        /**
         * 转换为 Map 列表（便于 JSON 序列化）。
         *
         * @return Map 列表，每个 Map 的 key 为列名，value 为列值
         */
        public List<Map<String, Object>> toMapList() {
            return rows.stream()
                    .map(row -> {
                        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
                        for (int i = 0; i < columns.size() && i < row.size(); i++) {
                            map.put(columns.get(i), row.get(i));
                        }
                        return map;
                    })
                    .toList();
        }

        /**
         * 获取行数。
         *
         * @return 行数
         */
        public int rowCount() {
            return rows == null ? 0 : rows.size();
        }
    }
}