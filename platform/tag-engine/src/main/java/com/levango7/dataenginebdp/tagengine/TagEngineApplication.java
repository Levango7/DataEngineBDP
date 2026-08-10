package com.levango7.dataenginebdp.tagengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据引擎大数据平台 - 标签画像引擎（Tag &amp; Profile Engine，L4.5.5）启动主类。
 *
 * <p>标签画像引擎负责标签定义/规则管理、标签计算、画像聚合与人群圈选。
 * 通过 {@code TagStore} 接口抽象底层存储，默认 Mock 内存实现，
 * 真实环境通过配置注入 Doris 实现（标签宽表 + 向量化查询）。</p>
 *
 * <p>对标：阿里 DataPhin 标签画像 / 神策用户标签 / GrowingIO 圈选。</p>
 */
@SpringBootApplication
public class TagEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(TagEngineApplication.class, args);
    }
}