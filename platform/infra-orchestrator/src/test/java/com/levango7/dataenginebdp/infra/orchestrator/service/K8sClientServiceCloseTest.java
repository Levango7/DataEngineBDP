package com.levango7.dataenginebdp.infra.orchestrator.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link K8sClientService} 资源关闭测试。
 *
 * <p>验证 {@code @PreDestroy} 方法 {@link K8sClientService#destroy()} 在应用停机时
 * 正确关闭 fabric8 {@link KubernetesClient}，释放底层 HTTP 连接池资源，避免资源泄漏。
 * 参考项目内 {@code encaps-layer/K8sClientService} 的关闭实现。</p>
 *
 * <p>测试策略：构造 {@code mockEnabled=true} 的服务实例（不连接真实集群），
 * 再通过反射注入 mock 的 {@link KubernetesClient}，以隔离对 K8s 集群的依赖。</p>
 */
class K8sClientServiceCloseTest {

    /**
     * 调用 destroy() 后应触发 client.close() 并将 client 字段置 null。
     */
    @Test
    void shouldCloseClientOnDestroy() throws Exception {
        K8sClientService service = new K8sClientService(true);
        KubernetesClient mockClient = mock(KubernetesClient.class);
        injectClient(service, mockClient);

        service.destroy();

        verify(mockClient).close();
        assertThat(service.getClient()).isNull();
    }

    /**
     * mock 模式下 client 为 null，destroy() 应安全无操作且不抛异常。
     */
    @Test
    void shouldNotFailWhenClientIsNullOnDestroy() {
        K8sClientService service = new K8sClientService(true);

        service.destroy();

        // client 仍为 null，未发生任何异常
        assertThat(service.getClient()).isNull();
    }

    /**
     * client.close() 抛异常时，destroy() 应捕获并记录警告，不向上抛出，且仍将 client 置 null。
     */
    @Test
    void shouldSwallowCloseExceptionAndStillNullifyClient() throws Exception {
        K8sClientService service = new K8sClientService(true);
        KubernetesClient mockClient = mock(KubernetesClient.class);
        doThrow(new RuntimeException("连接池已关闭")).when(mockClient).close();
        injectClient(service, mockClient);

        service.destroy();

        verify(mockClient).close();
        assertThat(service.getClient()).isNull();
    }

    /**
     * 重复调用 destroy() 应安全：第二次调用时 client 已为 null，不会再次调用 close()。
     */
    @Test
    void shouldNotCloseAgainOnRepeatedDestroy() throws Exception {
        K8sClientService service = new K8sClientService(true);
        KubernetesClient mockClient = mock(KubernetesClient.class);
        injectClient(service, mockClient);

        service.destroy();
        service.destroy();

        // close() 仅被调用一次
        verify(mockClient).close();
        verifyNoMoreInteractions(mockClient);
        assertThat(service.getClient()).isNull();
    }

    /**
     * 通过反射注入 mock client 字段。
     */
    private static void injectClient(K8sClientService service, KubernetesClient client) throws Exception {
        Field field = K8sClientService.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(service, client);
    }
}