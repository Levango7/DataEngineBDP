package com.shuqing.bigdata.sqlgateway.virtual.adapter;

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
     * 构造适配器，设置默认驱动为 MySQL。
     */
    public MysqlVirtualAdapter() {
        this.defaultDriver = MYSQL_DRIVER;
    }
}