package com.levango7.dataenginebdp.encaps;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数擎大数据平台 - 封装层（Encaps Layer）启动主类。
 *
 * <p>封装层负责向上屏蔽底层 K8s/大数据组件差异，向下统一调度资源供应原语。</p>
 */
@SpringBootApplication
public class EncapsLayerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EncapsLayerApplication.class, args);
    }
}