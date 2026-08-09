package com.levango7.dataenginebdp.governance.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数擎大数据平台 - 元数据自动采集服务启动主类。
 *
 * <p>本服务负责从 Hive/Doris/Kafka/HDFS 等数据源采集元数据，
 * 写入 Catalog 服务，并支持手动触发与 cron 定时调度。</p>
 *
 * <p>端口默认 8084，REST API 前缀 {@code /api/v1/metadata}。</p>
 */
@SpringBootApplication
public class MetadataCollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetadataCollectorApplication.class, args);
    }
}