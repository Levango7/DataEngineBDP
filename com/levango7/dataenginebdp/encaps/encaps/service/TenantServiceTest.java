package com.shuqing.bigdata.encaps.service;

import com.shuqing.bigdata.encaps.model.Tenant;
import com.shuqing.bigdata.encaps.repository.TenantRepository;
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
 * TenantService 单元测试。
 *
 * <p>使用 Mockito 模拟 TenantRepository，测试业务逻辑。</p>
 */
@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @InjectMocks
    private TenantService tenantService;

    @Test
    @DisplayName("create — 设置时间戳和默认状态后保存")
    void create_shouldSetTimestampsAndDefaultStatus() {
        Tenant input = new Tenant();
        input.setName("new-tenant");

        when(tenantRepository.save(any(Tenant.class))).thenAnswer(invocation -> {
            Tenant t = invocation.getArgument(0);
            t.setId(1L);
            return t;
        });

        Tenant result = tenantService.create(input);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();
        verify(tenantRepository).save(any(Tenant.class));
    }

    @Test
    @DisplayName("create — 已有status时不覆盖")
    void create_shouldPreserveExistingStatus() {
        Tenant input = new Tenant();
        input.setName("new-tenant");
        input.setStatus("INACTIVE");

        when(tenantRepository.save(any(Tenant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Tenant result = tenantService.create(input);

        assertThat(result.getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("list — 返回全部租户列表")
    void list_shouldReturnAllTenants() {
        Tenant t1 = new Tenant();
        t1.setId(1L);
        t1.setName("t1");
        Tenant t2 = new Tenant();
        t2.setId(2L);
        t2.setName("t2");

        when(tenantRepository.findAll()).thenReturn(List.of(t1, t2));

        List<Tenant> result = tenantService.list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("t1");
    }

    @Test
    @DisplayName("get — 存在时返回Optional含值")
    void get_existingId_shouldReturnTenant() {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName("found");

        when(tenantRepository.findById(1L)).thenReturn(Optional.of(tenant));

        Optional<Tenant> result = tenantService.get(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("found");
    }

    @Test
    @DisplayName("get — 不存在时返回Optional空")
    void get_nonExistingId_shouldReturnEmpty() {
        when(tenantRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<Tenant> result = tenantService.get(999L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("update — 存在时更新并保留createdAt")
    void update_existingId_shouldUpdateAndPreserveCreatedAt() {
        Tenant existing = new Tenant();
        existing.setId(1L);
        existing.setName("old");
        existing.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));

        Tenant input = new Tenant();
        input.setName("new-name");

        when(tenantRepository.existsById(1L)).thenReturn(true);
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<Tenant> result = tenantService.update(1L, input);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("new-name");
        assertThat(result.get().getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
        assertThat(result.get().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("update — 不存在时返回Optional空")
    void update_nonExistingId_shouldReturnEmpty() {
        when(tenantRepository.existsById(999L)).thenReturn(false);

        Optional<Tenant> result = tenantService.update(999L, new Tenant());

        assertThat(result).isEmpty();
        verify(tenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("delete — 存在时删除并返回true")
    void delete_existingId_shouldDeleteAndReturnTrue() {
        when(tenantRepository.existsById(1L)).thenReturn(true);

        boolean result = tenantService.delete(1L);

        assertThat(result).isTrue();
        verify(tenantRepository).deleteById(1L);
    }

    @Test
    @DisplayName("delete — 不存在时返回false")
    void delete_nonExistingId_shouldReturnFalse() {
        when(tenantRepository.existsById(999L)).thenReturn(false);

        boolean result = tenantService.delete(999L);

        assertThat(result).isFalse();
        verify(tenantRepository, never()).deleteById(any());
    }
}