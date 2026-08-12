package com.levango7.dataenginebdp.sqlgateway.virtual.adapter;

import com.levango7.dataenginebdp.sqlgateway.virtual.DataSourceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MySQL 虚拟表查询适配器。
 *
 * <p>继承 {@link JdbcVirtualAdapter}，仅覆盖默认驱动为 MySQL Connector/J。
 * 连接配置 JSON 格式：</p>
 * <pre>{@code
 * {
 *   "url": "jdbc:mysql://host:3306/db?useSSL=false",
 *   "username": "root",
 *   "password": "xxx",
 *   "driver": "com.mysql.cj.jdbc.Driver"
 * }
 * }</pre>
 *
 * @author shuqing-bigdata
 */
@Component
public class MysqlVirtualAdapter extends JdbcVirtualAdapter {

    /**
     * MySQL Connector/J 驱动类名。
     */
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

    /**
     * Spring 构造器注入构造器。
     *
     * @param dataSourceManager HikariCP 连接池管理器
     */
    @Autowired
    public MysqlVirtualAdapter(DataSourceManager dataSourceManager) {
        super(dataSourceManager);
        this.defaultDriver = MYSQL_DRIVER;
    }

    /**
     * 无参构造器（供无 Spring 环境的单元测试使用）。
     */
    protected MysqlVirtualAdapter() {
        this.defaultDriver = MYSQL_DRIVER;
    }
}
