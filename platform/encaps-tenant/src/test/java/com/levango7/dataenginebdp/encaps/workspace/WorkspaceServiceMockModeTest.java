package com.levango7.dataenginebdp.encaps.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkspaceService K8s mock 模式单元测试（{@code app.k8s.mock-enabled=true}）。
 *
 * <p>背景：mock 模式下 K8sClientConfig 返回的 client 未连接真实集群，
 * 调用翻译会阻塞至读超时（IT 实测 10s 后 ReadTimeout）。mock 模式语义为
 * "无集群可用"，因此三处 K8s 调用（创建翻译、删除翻译、状态查询）全部短路。</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceMockModeTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private K8sWorkspaceTranslator k8sTranslator;

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(workspaceRepository, k8sTranslator, true);
    }

    private Workspace sampleRequest() {
        Workspace ws = new Workspace();
        ws.setName("mock-ws");
        ws.setTenantId("1");
        return ws;
    }

    @Test
    @DisplayName("createWorkspace — mock 模式跳过 K8s 翻译，状态直接 ACTIVE")
    void createWorkspace_mockMode_shouldSkipTranslationAndBeActive() {
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace w = invocation.getArgument(0);
            if (w.getId() == null) {
                w.setId(1L);
            }
            return w;
        });

        Workspace result = workspaceService.createWorkspace(sampleRequest());

        assertThat(result.getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
        verify(k8sTranslator, never()).createNamespace(any());
        verify(k8sTranslator, never()).createNetworkPolicy(any());
        verify(k8sTranslator, never()).createRBAC(any());
        verify(k8sTranslator, never()).createResourceQuota(any());
    }

    @Test
    @DisplayName("deleteWorkspace — mock 模式跳过 Namespace 删除，状态置 DELETED")
    void deleteWorkspace_mockMode_shouldSkipDeleteAndBeDeleted() {
        Workspace ws = sampleRequest();
        ws.setId(1L);
        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(ws));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));

        boolean deleted = workspaceService.deleteWorkspace(1L);

        assertThat(deleted).isTrue();
        assertThat(ws.getStatus()).isEqualTo(Workspace.WorkspaceStatus.DELETED);
        verify(k8sTranslator, never()).deleteNamespace(any());
    }

    @Test
    @DisplayName("getK8sStatus — mock 模式无集群可查，返回 Unknown 且不调用翻译器")
    void getK8sStatus_mockMode_shouldReturnUnknown() {
        Workspace ws = sampleRequest();
        ws.setId(1L);
        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(ws));

        String status = workspaceService.getK8sStatus(1L);

        assertThat(status).isEqualTo("Unknown");
        verify(k8sTranslator, never()).getNamespaceStatus(any());
    }
}
