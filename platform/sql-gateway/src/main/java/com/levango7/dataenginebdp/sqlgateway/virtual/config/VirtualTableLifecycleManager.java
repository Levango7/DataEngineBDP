package com.levango7.dataenginebdp.sqlgateway.virtual.config;

import com.levango7.dataenginebdp.sqlgateway.virtual.DataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 虚拟表模块生命周期管理。
 *
 * <p>在应用启动与关闭时管理虚拟表模块的资源：</p>
 * <ul>
 *   <li>启动时：日志记录模块就绪；</li>
 *   <li>关闭时：关闭全部连接池，避免连接泄漏。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class VirtualTableLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(VirtualTableLifecycleManager.class);

    private final DataSourceManager dataSourceManager;

    /**
     * 构造生命周期管理器。
     *
     * @param dataSourceManager 数据源连接池管理器
     */
    public VirtualTableLifecycleManager(DataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    /**
     * 应用就绪时记录日志。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("虚拟表模块就绪：支持 MYSQL/ORACLE/JDBC/KAFKA/REST 五种数据源");
    }

    /**
     * 应用关闭时清理资源。
     *
     * <p>通过 JVM shutdown hook 确保连接池被关闭。
     * Spring 容器关闭时会自动调用 {@code @PreDestroy}，本方法作为兜底。</p>
     */
    @EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void onContextClosed() {
        log.info("虚拟表模块关闭：清理连接池资源");
        dataSourceManager.closeAll();
    }
}