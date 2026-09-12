package com.levango7.dataenginebdp.datastandard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据引擎大数据平台 - 数据标准治理模块主入口（L3.2）。
 *
 * <p>提供数据标准的定义、分类、版本管理与 JSON Schema 约束维护，
 * 为数据质量检查、数据集成、数据服务提供统一的标准定义源。</p>
 */
@SpringBootApplication
public class DataStandardApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataStandardApplication.class, args);
    }
}