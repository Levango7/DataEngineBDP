package com.shuqing.bigdata.sqlgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数擎大数据平台 - 统一SQL网关 (SQL Gateway) 主入口。
 *
 * <p>负责将用户 SQL 请求路由到 Trino（交互查询）或 Doris（OLAP）后端。</p>
 *
 * @author shuqing-bigdata
 */
@SpringBootApplication
public class SqlGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlGatewayApplication.class, args);
    }
}