package com.shuqing.bigdata.sqlgateway.virtual.adapter;

import com.shuqing.bigdata.sqlgateway.virtual.DataSourceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Oracle 虚拟表查询适配器。
 *
 * <p>继承 {@link JdbcVirtualAdapter}，仅覆盖默认驱动为 Oracle JDBC Driver。
 * 连接配置 JSON 格式：</p>
 * <pre>{@code
 * {
 *   "url": "jdbc:oracle:thin:@host:1521:orcl",
 *   "username": "system",
 *   "password": "xxx",
 *   "driver": "oracle.jdbc.OracleDriver"
 * }
 * }</pre>
 *
 * @author shuqing-bigdata
 */
@Component
public class OracleVirtualAdapter extends JdbcVirtualAdapter {

    /**
     * Oracle JDBC 驱动类名。
     */
    private static final String ORACLE_DRIVER = "oracle.jdbc.OracleDriver";

    /**
     * Spring 构造器注入构造器。
     *
     * @param dataSourceManager HikariCP 连接池管理器
     */
    @Autowired
    public OracleVirtualAdapter(DataSourceManager dataSourceManager) {
        super(dataSourceManager);
        this.defaultDriver = ORACLE_DRIVER;
    }

    /**
     * 无参构造器（供无 Spring 环境的单元测试使用）。
     */
    protected OracleVirtualAdapter() {
        this.defaultDriver = ORACLE_DRIVER;
    }
}
