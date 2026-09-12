package com.levango7.dataenginebdp.masterdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据引擎大数据平台 - 主数据管理模块主入口（L3.6）。
 *
 * <p>提供主数据模型定义与主数据记录的 CRUD 管理，
 * 为数据集成、数据质量、数据服务提供统一的主数据源。</p>
 */
@SpringBootApplication
public class MasterDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(MasterDataApplication.class, args);
    }
}