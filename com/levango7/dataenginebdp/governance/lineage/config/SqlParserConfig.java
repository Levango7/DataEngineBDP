package com.shuqing.bigdata.governance.lineage.config;

import com.shuqing.bigdata.sqlgateway.parser.SqlParserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SQL 解析器 Bean 配置。
 *
 * <p>sql-gateway 的 {@link SqlParserService} 是无状态手写解析器，
 * 此处将其暴露为 Spring Bean，供血缘提取器注入。</p>
 *
 * @author shuqing-bigdata
 */
@Configuration
public class SqlParserConfig {

    /**
     * 声明 SqlParserService 单例。
     *
     * @return 解析器实例
     */
    @Bean
    public SqlParserService sqlParserService() {
        return new SqlParserService();
    }
}