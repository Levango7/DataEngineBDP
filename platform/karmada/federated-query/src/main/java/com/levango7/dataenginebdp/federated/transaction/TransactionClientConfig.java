package com.levango7.dataenginebdp.federated.transaction;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 事务客户端默认实现的装配。
 *
 * <p>{@code @ConditionalOnMissingBean} 只能可靠地用在配置类里的 {@code @Bean} 方法上；
 * 它原先直接标在 {@code @Component} 的实现类上，组件扫描阶段判定为不匹配，于是
 * {@link TwoPhaseCommitProtocol} 找不到 {@link ClusterTransactionClient}，应用启动即失败
 * （本机实测：APPLICATION FAILED TO START, required a bean of type ClusterTransactionClient）。
 */
@Configuration
public class TransactionClientConfig {

    @Bean
    @ConditionalOnMissingBean(ClusterTransactionClient.class)
    public ClusterTransactionClient inMemoryClusterTransactionClient() {
        return new InMemoryClusterTransactionClient();
    }
}
