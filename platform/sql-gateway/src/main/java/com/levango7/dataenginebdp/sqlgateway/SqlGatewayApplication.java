package com.levango7.dataenginebdp.sqlgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数擎大数据平台 - 统一SQL网关 (SQL Gateway) 主入口。
 *
 * <p>负责将用户 SQL 请求路由到 Trino（交互查询）或 Doris（OLAP）后端。
 * 同时承载虚拟表（数据虚拟化）模块，支持 MySQL/Oracle/JDBC/Kafka/REST
 * 五种外部数据源的虚拟表注册、元数据缓存、物化策略与 SQL 重写。</p>
 *
 * <p>{@code @EnableScheduling} 启用物化表定时刷新调度器。</p>
 *
 * @author shuqing-bigdata
 */
@SpringBootApplication
@EnableScheduling
public class SqlGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlGatewayApplication.class, args);
    }
}