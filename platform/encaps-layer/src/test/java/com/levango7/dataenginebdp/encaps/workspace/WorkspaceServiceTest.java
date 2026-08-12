package com.levango7.dataenginebdp.encaps.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WorkspaceService 单元测试。
 *
 * <p>使用 Mockito 模拟 {@link WorkspaceRepository} 与 {@link K8sWorkspaceTranslator}，
 * 验证业务编排逻辑：</p>
 * <ul>
 *   <li>createWorkspace — CREATING → 翻译 → ACTIVE/DELETED 状态流转</li>
 *   <li>deleteWorkspace — DELETING → 删 K8s → DELETED</li>
 *   <li>listWorkspaces — 按 tenantId 过滤</li>
 *   <li>updateWorkspace — 仅更新可变字段</li>
 *   <li>K8s 翻译失败不抛异常，状态置为 DELETED</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private K8sWorkspaceTranslator k8sTranslator;

    @InjectMocks
    private WorkspaceService workspaceService;

    private Workspace sampleCreateRequest() {
        Workspace ws = new Workspace();
        ws.setName("test-ws");
        ws.setTenantId(100L);
        ws.setDescription("test workspace");
        return ws;
    }

    @Test
    @DisplayName("createWorkspace — K8s 翻译成功时状态为 ACTIVE")
    void createWorkspace_translationSuccess_shouldBeActive() {
        Workspace req = sampleCreateRequest();

        // 模拟第一次 save（CREATING）返回带 id 的对象，第二次 save（ACTIVE）也返回
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace w = invocation.getArgument(0);
            if (w.getId() == null) {
                w.setId(1L);
            }
            return w;
        });
        // K8s 翻译全部成功（默认不抛异常）

        Workspace result = workspaceService.createWorkspace(req);

        assertThat(result.getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getNamespace()).startsWith("ws-100-");
        assertThat(result.getNetworkPolicy()).isEqualTo("tenant-isolated");
        assertThat(result.getResourceQuota()).contains("cpu");
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();

        // 验证 K8s 翻译全部被调用
        verify(k8sTranslator).createNamespace(any());
        verify(k8sTranslator).createNetworkPolicy(any());
        verify(k8sTranslator).createRBAC(any());
        verify(k8sTranslator).createResourceQuota(any());
        // 验证 DB 保存两次（CREATING + ACTIVE）
        verify(workspaceRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("createWorkspace — K8s 翻译失败时状态为 DELETED 且不抛异常")
    void createWorkspace_translationFailed_shouldBeDeleted() {
        Workspace req = sampleCreateRequest();

        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace w = invocation.getArgument(0);
            if (w.getId() == null) {
                w.setId(1L);
            }
            return w;
        });
        // K8s 创建 Namespace 失败
        doThrow(new K8sWorkspaceTranslator.K8sTranslationException("ns conflict",
                new RuntimeException("ns conflict")))
                .when(k8sTranslator).createNamespace(any());

        Workspace result = workspaceService.createWorkspace(req);

        assertThat(result.getStatus()).isEqualTo(Workspace.WorkspaceStatus.DELETED);
        // 后续翻译不应被调用
        verify(k8sTranslator, never()).createNetworkPolicy(any());
        verify(k8sTranslator, never()).createRBAC(any());
        verify(k8sTranslator, never()).createResourceQuota(any());
    }

    @Test
    @DisplayName("createWorkspace — 入参已带 namespace 时不覆盖")
    void createWorkspace_customNamespace_shouldPreserve() {
        Workspace req = sampleCreateRequest();
        req.setNamespace("my-custom-ns");
        req.setNetworkPolicy("deny-all");
        req.setResourceQuota("cpu=2,memory=4Gi");

        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace w = invocation.getArgument(0);
            if (w.getId() == null) {
                w.setId(1L);
            }
            return w;
        });

        Workspace result = workspaceService.createWorkspace(req);

        assertThat(result.getNamespace()).isEqualTo("my-custom-ns");
        assertThat(result.getNetworkPolicy()).isEqualTo("deny-all");
        assertThat(result.getResourceQuota()).isEqualTo("cpu=2,memory=4Gi");
    }

    @Test
    @DisplayName("deleteWorkspace — 存在时 DELETING → 删 K8s → DELETED")
    void deleteWorkspace_existing_shouldDeleteAndReturnTrue() {
        Workspace existing = new Workspace();
        existing.setId(1L);
        existing.setName("test-ws");
        existing.setTenantId(100L);
        existing.setNamespace("ws-100-test-ws");
        existing.setStatus(Workspace.WorkspaceStatus.ACTIVE);

        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
        when(k8sTranslator.deleteNamespace(any())).thenReturn(true);

        boolean result = workspaceService.deleteWorkspace(1L);

        assertThat(result).isTrue();
        assertThat(existing.getStatus()).isEqualTo(Workspace.WorkspaceStatus.DELETED);
        verify(k8sTranslator).deleteNamespace(any());
        verify(workspaceRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("deleteWorkspace — K8s 删除失败时仍置为 DELETED")
    void deleteWorkspace_k8sFailed_shouldStillBeDeleted() {
        Workspace existing = new Workspace();
        existing.setId(1L);
        existing.setName("test-ws");
        existing.setTenantId(100L);
        existing.setNamespace("ws-100-test-ws");
        existing.setStatus(Workspace.WorkspaceStatus.ACTIVE);

        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new K8sWorkspaceTranslator.K8sTranslationException("delete failed",
                new RuntimeException("delete failed")))
                .when(k8sTranslator).deleteNamespace(any());

        boolean result = workspaceService.deleteWorkspace(1L);

        assertThat(result).isTrue();
        assertThat(existing.getStatus()).isEqualTo(Workspace.WorkspaceStatus.DELETED);
    }

    @Test
    @DisplayName("deleteWorkspace — 不存在时返回 false 且不调用 K8s")
    void deleteWorkspace_nonExisting_shouldReturnFalse() {
        when(workspaceRepository.findById(999L)).thenReturn(Optional.empty());

        boolean result = workspaceService.deleteWorkspace(999L);

        assertThat(result).isFalse();
        verify(k8sTranslator, never()).deleteNamespace(any());
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    @DisplayName("listWorkspaces — tenantId 为 null 时返回全部")
    void listWorkspaces_nullTenantId_shouldReturnAll() {
        Workspace w1 = new Workspace();
        w1.setId(1L);
        w1.setName("w1");
        Workspace w2 = new Workspace();
        w2.setId(2L);
        w2.setName("w2");

        when(workspaceRepository.findAll()).thenReturn(List.of(w1, w2));

        List<Workspace> result = workspaceService.listWorkspaces(null);

        assertThat(result).hasSize(2);
        verify(workspaceRepository).findAll();
        verify(workspaceRepository, never()).findByTenantId(any());
    }

    @Test
    @DisplayName("listWorkspaces — 指定 tenantId 时按租户过滤")
    void listWorkspaces_withTenantId_shouldFilter() {
        Workspace w1 = new Workspace();
        w1.setId(1L);
        w1.setName("w1");
        w1.setTenantId(100L);

        when(workspaceRepository.findByTenantId(100L)).thenReturn(List.of(w1));

        List<Workspace> result = workspaceService.listWorkspaces(100L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTenantId()).isEqualTo(100L);
        verify(workspaceRepository).findByTenantId(100L);
        verify(workspaceRepository, never()).findAll();
    }

    @Test
    @DisplayName("getWorkspace — 存在时返回 Optional 含值")
    void getWorkspace_existing_shouldReturn() {
        Workspace ws = new Workspace();
        ws.setId(1L);
        ws.setName("found");

        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(ws));

        Optional<Workspace> result = workspaceService.getWorkspace(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("found");
    }

    @Test
    @DisplayName("getWorkspace — 不存在时返回 Optional 空")
    void getWorkspace_nonExisting_shouldReturnEmpty() {
        when(workspaceRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<Workspace> result = workspaceService.getWorkspace(999L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("updateWorkspace — 存在时仅更新可变字段并保留 createdAt")
    void updateWorkspace_existing_shouldUpdateMutableFields() {
        Workspace existing = new Workspace();
        existing.setId(1L);
        existing.setName("old-name");
        existing.setDescription("old-desc");
        existing.setTenantId(100L);
        existing.setNamespace("ws-100-old");
        existing.setResourceQuota("cpu=1");
        existing.setNetworkPolicy("deny-all");
        existing.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));

        Workspace update = new Workspace();
        update.setName("new-name");
        update.setDescription("new-desc");
        update.setResourceQuota("cpu=8,memory=16Gi");

        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Workspace> result = workspaceService.updateWorkspace(1L, update);

        assertThat(result).isPresent();
        Workspace updated = result.get();
        assertThat(updated.getName()).isEqualTo("new-name");
        assertThat(updated.getDescription()).isEqualTo("new-desc");
        assertThat(updated.getResourceQuota()).isEqualTo("cpu=8,memory=16Gi");
        // namespace、tenantId 不变
        assertThat(updated.getNamespace()).isEqualTo("ws-100-old");
        assertThat(updated.getTenantId()).isEqualTo(100L);
        // createdAt 保留
        assertThat(updated.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
        assertThat(updated.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("updateWorkspace — 不存在时返回 Optional 空")
    void updateWorkspace_nonExisting_shouldReturnEmpty() {
        when(workspaceRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<Workspace> result = workspaceService.updateWorkspace(999L, new Workspace());

        assertThat(result).isEmpty();
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    @DisplayName("getK8sStatus — 存在时委托翻译器查询")
    void getK8sStatus_existing_shouldDelegate() {
        Workspace ws = new Workspace();
        ws.setId(1L);
        ws.setNamespace("ws-100-test");

        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(ws));
        when(k8sTranslator.getNamespaceStatus(ws)).thenReturn("Active");

        String status = workspaceService.getK8sStatus(1L);

        assertThat(status).isEqualTo("Active");
    }

    @Test
    @DisplayName("getK8sStatus — Workspace 不存在时返回 NotFound")
    void getK8sStatus_nonExisting_shouldReturnNotFound() {
        when(workspaceRepository.findById(999L)).thenReturn(Optional.empty());

        String status = workspaceService.getK8sStatus(999L);

        assertThat(status).isEqualTo("NotFound");
        verify(k8sTranslator, never()).getNamespaceStatus(any());
    }
}