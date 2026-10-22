package com.levango7.dataenginebdp.federated;

import com.levango7.dataenginebdp.federated.transaction.ClusterTransactionClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 上下文装配守卫。
 *
 * <p>本用例会在整条组件扫描 + 自动装配链上真正启动一次上下文。它的存在理由是
 * 一次真实缺陷：{@code InMemoryClusterTransactionClient} 曾把
 * {@code @ConditionalOnMissingBean} 直接标在 {@code @Component} 上，扫描阶段判定
 * 不匹配，{@code TwoPhaseCommitProtocol} 找不到 {@code ClusterTransactionClient}，
 * 应用启动即 APPLICATION FAILED TO START —— 而当时的测试全是纯单测，全部通过。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.security.jwt.secret=it-test-jwt-secret-at-least-32-bytes-long"
        })
class FederatedQueryApplicationTests {

    @Autowired
    private ClusterTransactionClient clusterTransactionClient;

    @Test
    void contextLoadsWithTransactionClient() {
        assertNotNull(clusterTransactionClient,
                "ClusterTransactionClient 未能装配：应用会启动失败");
    }
}
