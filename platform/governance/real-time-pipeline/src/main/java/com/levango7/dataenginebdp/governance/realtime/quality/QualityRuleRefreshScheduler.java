package com.levango7.dataenginebdp.governance.realtime.quality;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 质量规则来源的启动与定时刷新（台账 #6 阶段 3）。
 *
 * <p>{@code @EnableScheduling} 已在 {@code RealTimeGovernanceApplication} 打开。
 * 来源未启用时（默认）本组件对引擎是 no-op，行为与整改前一致。
 */
@Component
public class QualityRuleRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(QualityRuleRefreshScheduler.class);

    private final StreamingQualityRuleEngine engine;
    private final QualityRuleSource source;

    public QualityRuleRefreshScheduler(StreamingQualityRuleEngine engine, QualityRuleSource source) {
        this.engine = engine;
        this.source = source;
    }

    /** 启动即拉一次（来源启用时）；失败降级为"沿用上次缓存"（首次为空则跳过评估并告警）。 */
    @PostConstruct
    public void refreshOnStartup() {
        if (source.isEnabled()) {
            log.info("质量规则来源已启用，启动时拉取一次");
            engine.refreshFromSource(source);
        }
    }

    /** 周期刷新；rule-engine 不可用时引擎内部降级，不抛。 */
    @Scheduled(
            fixedDelayString = "${governance.quality.refresh-ms:60000}",
            initialDelayString = "${governance.quality.refresh-initial-delay-ms:5000}")
    public void refreshPeriodically() {
        engine.refreshFromSource(source);
    }
}
