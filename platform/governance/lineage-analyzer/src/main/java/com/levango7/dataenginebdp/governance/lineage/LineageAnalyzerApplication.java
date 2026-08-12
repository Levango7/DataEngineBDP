package com.levango7.dataenginebdp.governance.lineage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 数据血缘分析引擎启动入口。
 *
 * <p>基于 Spring Boot 3.2.5，复用 sql-gateway 的 SQL 解析器 AST，
 * 提供表级 + 字段级血缘提取、图谱持久化与上下游查询 REST API。</p>
 *
 * @author shuqing-bigdata
 */
@SpringBootApplication
@EntityScan(basePackages = "com.levango7.dataenginebdp.governance.lineage.model")
@EnableJpaRepositories(basePackages = "com.levango7.dataenginebdp.governance.lineage.service")
public class LineageAnalyzerApplication {

    /**
     * 主入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(LineageAnalyzerApplication.class, args);
    }
}