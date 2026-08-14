package com.levango7.dataenginebdp.sqlgateway.virtual.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.sqlgateway.virtual.DataSourceType;
import com.levango7.dataenginebdp.sqlgateway.virtual.VirtualTableDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 虚拟表适配器注册中心。
 *
 * <p>按数据源类型分发到对应的 {@link VirtualAdapter} 实现。
 * 在构造时注入全部适配器 Bean，运行期通过 {@link #getAdapter(DataSourceType)} 获取。</p>
 *
 * <p>同时提供连接配置 JSON 解析工具方法，供各适配器复用。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class VirtualAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(VirtualAdapterRegistry.class);

    private final Map<DataSourceType, VirtualAdapter> adapters;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造注册中心，注入五种适配器。
     *
     * @param mysqlAdapter  MySQL 适配器
     * @param oracleAdapter Oracle 适配器
     * @param jdbcAdapter   通用 JDBC 适配器
     * @param kafkaAdapter  Kafka 适配器
     * @param restAdapter   REST 适配器
     */
    public VirtualAdapterRegistry(MysqlVirtualAdapter mysqlAdapter,
                                  OracleVirtualAdapter oracleAdapter,
                                  @org.springframework.beans.factory.annotation.Qualifier("jdbcVirtualAdapter")
                                  JdbcVirtualAdapter jdbcAdapter,
                                  KafkaVirtualAdapter kafkaAdapter,
                                  RestVirtualAdapter restAdapter) {
        this.adapters = Map.of(
                DataSourceType.MYSQL, mysqlAdapter,
                DataSourceType.ORACLE, oracleAdapter,
                DataSourceType.JDBC, jdbcAdapter,
                DataSourceType.KAFKA, kafkaAdapter,
                DataSourceType.REST, restAdapter
        );
        log.info("虚拟表适配器注册完成 types={}", adapters.keySet());
    }

    /**
     * 按数据源类型获取适配器。
     *
     * @param type 数据源类型
     * @return 适配器实例
     * @throws IllegalArgumentException 若类型不支持
     */
    public VirtualAdapter getAdapter(DataSourceType type) {
        VirtualAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("不支持的数据源类型: " + type);
        }
        return adapter;
    }

    /**
     * 按虚拟表定义获取对应适配器。
     *
     * @param definition 虚拟表定义
     * @return 适配器实例
     */
    public VirtualAdapter getAdapter(VirtualTableDefinition definition) {
        return getAdapter(definition.getDataSourceType());
    }

    /**
     * 解析连接配置 JSON 为 Map。
     *
     * @param connectionConfig JSON 字符串
     * @return 配置 Map
     * @throws VirtualAdapterException 若解析失败
     */
    public Map<String, Object> parseConnectionConfig(String connectionConfig) {
        try {
            return objectMapper.readValue(connectionConfig, new TypeReference<>() {});
        } catch (Exception e) {
            throw new VirtualAdapterException("CONFIG_PARSE_FAILED",
                    "连接配置 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 列出全部已注册的数据源类型。
     *
     * @return 数据源类型列表
     */
    public java.util.List<DataSourceType> listSupportedTypes() {
        return java.util.List.copyOf(adapters.keySet());
    }
}