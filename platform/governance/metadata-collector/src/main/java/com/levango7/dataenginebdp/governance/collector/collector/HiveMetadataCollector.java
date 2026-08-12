package com.levango7.dataenginebdp.governance.collector.collector;

import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import com.levango7.dataenginebdp.governance.collector.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Hive 元数据采集器。
 *
 * <p>通过 JDBC 连接 HiveServer2，采集：
 * <ol>
 *   <li>数据库列表（{@code SHOW DATABASES}）</li>
 *   <li>表列表（{@code SHOW TABLES IN &lt;db&gt;}）</li>
 *   <li>列信息：名称/类型/注释（{@code DESCRIBE &lt;db&gt;.&lt;table&gt;}）</li>
 *   <li>分区信息：DESCRIBE 输出的 Partition Information 段</li>
 *   <li>表统计信息：numRows/totalSize/numFiles（{@code SHOW TBLPROPERTIES}）</li>
 * </ol></p>
 *
 * <p>JDBC URL 格式：{@code jdbc:hive2://host:port/db}，
 * 驱动：{@code org.apache.hive.jdbc.HiveDriver}。</p>
 */
@Component
public class HiveMetadataCollector extends AbstractJdbcMetadataCollector {

    private static final Logger log = LoggerFactory.getLogger(HiveMetadataCollector.class);

    /** Hive JDBC 驱动类名 */
    private static final String HIVE_DRIVER = "org.apache.hive.jdbc.HiveDriver";

    /** 默认 Hive JDBC 端口 */
    private static final int DEFAULT_PORT = 10000;

    /** HiveServer2 默认数据库 */
    private final String defaultDatabase;

    /**
     * 构造 Hive 采集器。
     *
     * @param defaultDatabase 默认数据库，从配置 {@code app.collector.hive.default-database} 读取
     */
    public HiveMetadataCollector(
            @Value("${app.collector.hive.default-database:default}") String defaultDatabase) {
        this.defaultDatabase = defaultDatabase;
    }

    @Override
    public String getType() {
        return MetadataSource.TYPE_HIVE;
    }

    @Override
    protected String getDriverClass() {
        return HIVE_DRIVER;
    }

    @Override
    protected String buildJdbcUrl(MetadataSource source) {
        // source.url 已是完整 jdbc:hive2://... 形式；否则按 host:port 拼装
        if (source.getUrl() != null && source.getUrl().startsWith("jdbc:hive2://")) {
            return source.getUrl();
        }
        // 兼容仅填 host:port 的场景
        String hostPort = source.getUrl() == null ? "localhost:" + DEFAULT_PORT : source.getUrl();
        return "jdbc:hive2://" + hostPort + "/" + defaultDatabase;
    }

    @Override
    protected void enrichTable(TableMetadata metadata, Connection conn,
                               String database, String table) throws SQLException {
        // Hive 表类型通过 SHOW TABLE EXTENDED 或 TBLPROPERTIES 获取
        // 这里从已采集的 properties 中提取 tableType/EXTERNAL/owner 等
        if (metadata.getProperties() != null) {
            String external = metadata.getProperties().get("EXTERNAL");
            if ("TRUE".equalsIgnoreCase(external)) {
                metadata.setTableType("EXTERNAL_TABLE");
            } else {
                metadata.setTableType("MANAGED_TABLE");
            }
        }
    }
}