package com.shuqing.bigdata.sqlgateway.calcite.adapter;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;
import com.shuqing.bigdata.sqlgateway.calcite.config.OptimizerConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 适配器注册中心——基于 YAML 声明式配置创建并注册数据源适配器。
 *
 * <p>本类是 {@link OptimizerConfig} 与具体适配器实现之间的桥梁，根据
 * {@link DataSourceConfig.Type} 自动实例化对应的 {@link AbstractBaseAdapter} 子类，
 * 实现 YAML 声明式注册：</p>
 *
 * <p><b>典型 YAML 配置：</b></p>
 * <pre>
 * sql-gateway:
 *   optimizer:
 *     data-sources:
 *       - name: iceberg_lake
 *         type: ICEBERG
 *         jdbc-url: "jdbc:hive2://hive:10000"
 *         dialect: HIVE
 *         properties:
 *           partition-column: dt
 *           stats.iceberg_lake.orders.rowCount: "1000000"
 *           stats.iceberg_lake.orders.partitionCount: "30"
 *
 *       - name: doris_olap
 *         type: DORIS
 *         jdbc-url: "jdbc:mysql://doris-fe:9030"
 *         dialect: DORIS
 *         properties:
 *           stats.doris_olap.orders.rowCount: "5000000"
 *           stats.doris_olap.orders.tabletCount: "64"
 *
 *       - name: trino_hive
 *         type: TRINO
 *         jdbc-url: "jdbc:trino://trino:8080"
 *         dialect: TRINO
 *         properties:
 *           workerCount: "20"
 *
 *       - name: iotdb_ts
 *         type: IOTDB
 *         endpoint: "http://iotdb:18080"
 *         properties:
 *           stats.iotdb_ts.devices.deviceCount: "1000"
 *           stats.iotdb_ts.devices.sensorCount: "50"
 *
 *       - name: es_search
 *         type: ELASTICSEARCH
 *         endpoint: "http://es:9200"
 *         properties:
 *           indices: "es_orders,es_users,es_products"
 * </pre>
 *
 * <p><b>注册流程：</b></p>
 * <pre>
 *   OptimizerConfig (YAML 反序列化)
 *     │
 *     ▼  AdapterRegistry.create(config)
 *   Map&lt;String, BaseAdapter&gt;  （name → adapter 实例）
 *     │
 *     ▼  注册到 CalciteOptimizer
 *   联邦查询可访问所有已注册数据源
 * </pre>
 *
 * <p>本类对应 Spring Boot {@code @Configuration} + {@code @Bean} 的声明式注册模式，
 * 但保持框架无关（不依赖 Spring），可独立用于测试与命令行场景。</p>
 *
 * @author shuqing-bigdata
 */
public class AdapterRegistry {

    /** 已注册的适配器：数据源名 → 适配器实例 */
    private final Map<String, BaseAdapter> adapters = new LinkedHashMap<>();

    /**
     * 构造空的注册中心。
     */
    public AdapterRegistry() {
    }

    /**
     * 从优化器配置构造注册中心，自动创建所有合法数据源的适配器。
     *
     * @param config 优化器配置
     */
    public AdapterRegistry(OptimizerConfig config) {
        Objects.requireNonNull(config, "config");
        registerAll(config.getDataSources());
    }

    /**
     * 从数据源配置列表创建适配器并注册。
     *
     * @param dataSources 数据源配置列表
     */
    public void registerAll(List<DataSourceConfig> dataSources) {
        if (dataSources == null) {
            return;
        }
        for (DataSourceConfig ds : dataSources) {
            if (ds != null && ds.isValid()) {
                BaseAdapter adapter = createAdapter(ds);
                if (adapter != null) {
                    adapters.put(ds.getName(), adapter);
                }
            }
        }
    }

    /**
     * 注册单个适配器。
     *
     * @param adapter 适配器实例
     */
    public void register(BaseAdapter adapter) {
        if (adapter != null && adapter.getDataSourceConfig() != null) {
            adapters.put(adapter.getDataSourceConfig().getName(), adapter);
        }
    }

    /**
     * 根据数据源配置创建对应的适配器实例（工厂方法）。
     *
     * <p>按 {@link DataSourceConfig.Type} 分发到具体实现类：</p>
     * <ul>
     *   <li>ICEBERG → {@link IcebergAdapterImpl}</li>
     *   <li>DORIS → {@link DorisAdapterImpl}</li>
     *   <li>TRINO → {@link TrinoAdapterImpl}</li>
     *   <li>IOTDB → {@link IoTDBAdapterImpl}</li>
     *   <li>ELASTICSEARCH → {@link ElasticsearchAdapterImpl}</li>
     * </ul>
     *
     * @param config 数据源配置
     * @return 适配器实例，类型不支持时返回 null
     */
    public static BaseAdapter createAdapter(DataSourceConfig config) {
        Objects.requireNonNull(config, "config");
        if (!config.isValid()) {
            throw new IllegalArgumentException("数据源配置不合法: " + config);
        }
        return switch (config.getType()) {
            case ICEBERG -> new IcebergAdapterImpl(config);
            case DORIS -> new DorisAdapterImpl(config);
            case TRINO -> new TrinoAdapterImpl(config);
            case IOTDB -> new IoTDBAdapterImpl(config);
            case ELASTICSEARCH -> new ElasticsearchAdapterImpl(config);
        };
    }

    /**
     * 按数据源名查找适配器。
     *
     * @param name 数据源名
     * @return 适配器实例，未找到返回 null
     */
    public BaseAdapter getAdapter(String name) {
        return adapters.get(name);
    }

    /**
     * 按数据源类型查找所有该类型的适配器。
     *
     * @param type 数据源类型
     * @return 适配器列表
     */
    public List<BaseAdapter> getAdaptersByType(DataSourceConfig.Type type) {
        List<BaseAdapter> result = new java.util.ArrayList<>();
        for (BaseAdapter adapter : adapters.values()) {
            if (adapter.getAdapterType() == type) {
                result.add(adapter);
            }
        }
        return result;
    }

    /**
     * 获取所有已注册适配器。
     *
     * @return 适配器集合（不可变）
     */
    public Map<String, BaseAdapter> getAllAdapters() {
        return Collections.unmodifiableMap(adapters);
    }

    /**
     * 获取已注册适配器数量。
     *
     * @return 适配器数量
     */
    public int size() {
        return adapters.size();
    }

    /**
     * 判断是否已注册指定数据源名的适配器。
     *
     * @param name 数据源名
     * @return true 表示已注册
     */
    public boolean contains(String name) {
        return adapters.containsKey(name);
    }

    /**
     * 移除指定数据源名的适配器。
     *
     * @param name 数据源名
     * @return 被移除的适配器，未找到返回 null
     */
    public BaseAdapter remove(String name) {
        return adapters.remove(name);
    }

    /**
     * 清空所有已注册适配器。
     */
    public void clear() {
        adapters.clear();
    }

    @Override
    public String toString() {
        return "AdapterRegistry{size=" + adapters.size()
                + ", sources=" + adapters.keySet() + '}';
    }
}