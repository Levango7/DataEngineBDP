package com.shuqing.bigdata.tagengine.store.doris;

import com.shuqing.bigdata.tagengine.model.TagDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Doris 标签宽表 DDL 管理器。
 *
 * <p>负责标签宽表的建表、加列、删列等 DDL 操作。
 * 对应详细设计 §3 标签宽表 DDL。</p>
 *
 * <p>宽表结构（{@code dws_user_tag_wide}）：</p>
 * <pre>
 * CREATE TABLE dws_user_tag_wide (
 *   user_id      BIGINT,
 *   tenant_id    INT,
 *   &lt;tag_column&gt; &lt;type&gt;,
 *   ...
 *   tag_version  VARCHAR(64),
 *   update_ts    DATETIME
 * ) ENGINE=OLAP
 * DUPLICATE KEY(user_id, tenant_id)
 * DISTRIBUTED BY HASH(user_id) BUCKETS 32
 * PROPERTIES("replication_num"="3");
 * </pre>
 */
@Component
public class DorisDdlManager {

    private static final Logger log = LoggerFactory.getLogger(DorisDdlManager.class);

    /** 默认分桶数 */
    private static final int DEFAULT_BUCKETS = 32;

    /** 默认副本数 */
    private static final int DEFAULT_REPLICATION = 3;

    /**
     * 生成建表 DDL。
     *
     * @param database    库名
     * @param wideTable   表名
     * @param tagColumns  标签列定义（columnName -> dorisType）
     * @return DDL SQL
     */
    public String buildCreateTableDdl(String database, String wideTable,
                                      List<TagColumn> tagColumns) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(quote(database)).append(".").append(quote(wideTable)).append(" (\n");
        sb.append("  user_id      BIGINT,\n");
        sb.append("  tenant_id    INT,\n");
        for (TagColumn c : tagColumns) {
            sb.append("  ").append(quote(c.columnName())).append("  ").append(c.dorisType()).append(",\n");
        }
        sb.append("  tag_version  VARCHAR(64),\n");
        sb.append("  update_ts    DATETIME\n");
        sb.append(") ENGINE=OLAP\n");
        sb.append("DUPLICATE KEY(user_id, tenant_id)\n");
        sb.append("DISTRIBUTED BY HASH(user_id) BUCKETS ").append(DEFAULT_BUCKETS).append("\n");
        sb.append("PROPERTIES(\"replication_num\"=\"").append(DEFAULT_REPLICATION).append("\");");
        return sb.toString();
    }

    /**
     * 生成加列 ALTER DDL。
     *
     * @param database   库名
     * @param wideTable  表名
     * @param column     新增标签列
     * @return DDL SQL
     */
    public String buildAddColumnDdl(String database, String wideTable, TagColumn column) {
        return "ALTER TABLE " + quote(database) + "." + quote(wideTable)
                + " ADD COLUMN " + quote(column.columnName()) + " " + column.dorisType();
    }

    /**
     * 生成删列 ALTER DDL。
     *
     * @param database  库名
     * @param wideTable 表名
     * @param columnName 列名
     * @return DDL SQL
     */
    public String buildDropColumnDdl(String database, String wideTable, String columnName) {
        return "ALTER TABLE " + quote(database) + "." + quote(wideTable)
                + " DROP COLUMN " + quote(columnName);
    }

    /**
     * 由 TagDefinition 推导 Doris 列类型。
     * <p>枚举型用 VARCHAR(64)，数值型用 DECIMAL(18,2) 或 INT，时间型用 DATETIME。</p>
     */
    public String deriveDorisType(TagDefinition def) {
        if (def.getValueDomain() != null && !def.getValueDomain().isEmpty()) {
            return "VARCHAR(64)";
        }
        String name = def.getName() == null ? "" : def.getName().toLowerCase();
        if (name.contains("days") || name.contains("count") || name.contains("level") || name.contains("risk")) {
            return "INT";
        }
        if (name.contains("amount") || name.contains("price") || name.contains("money")) {
            return "DECIMAL(18,2)";
        }
        if (name.contains("ts") || name.contains("time") || name.contains("date")) {
            return "DATETIME";
        }
        return "VARCHAR(64)";
    }

    /**
     * 构造建表所需的初始标签列（含基础事实列）。
     */
    public List<TagColumn> bootstrapColumns() {
        List<TagColumn> cols = new ArrayList<>();
        cols.add(new TagColumn("reg_days", "INT"));
        cols.add(new TagColumn("last_order_ts", "DATETIME"));
        cols.add(new TagColumn("total_amount", "DECIMAL(18,2)"));
        cols.add(new TagColumn("user_level", "TINYINT"));
        cols.add(new TagColumn("churn_risk", "TINYINT"));
        cols.add(new TagColumn("price_sens", "TINYINT"));
        cols.add(new TagColumn("cate_cluster", "INT"));
        return cols;
    }

    private String quote(String ident) {
        return "`" + ident.replace("`", "") + "`";
    }

    /**
     * 标签列定义。
     *
     * @param columnName 列名
     * @param dorisType  Doris 类型字符串
     */
    public record TagColumn(String columnName, String dorisType) {
    }
}