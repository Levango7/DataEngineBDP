package com.levango7.dataenginebdp.datastandard.service;

import com.levango7.dataenginebdp.datastandard.model.entity.StandardCategory;
import com.levango7.dataenginebdp.datastandard.repository.StandardCategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * StandardCategoryService 单元测试（租户隔离 + 创建时强制清空 ID）。
 *
 * <p>Repository 用 Mockito 隔离，重点验证：listAll 按租户过滤、
 * create 强制清空客户端传入的 ID 并写入租户与时间戳、getById 的 null 守卫。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StandardCategoryService 单元测试")
class StandardCategoryServiceTest {

    private static final String TENANT = "tenant-a";

    @Mock
    private StandardCategoryRepository categoryRepository;

    @InjectMocks
    private StandardCategoryService categoryService;

    @Test
    @DisplayName("listAll — 返回该租户下的全部分类")
    void listAllShouldReturnTenantCategories() {
        StandardCategory first = category(1L, "用户域", "user-domain");
        StandardCategory second = category(2L, "订单域", "order-domain");
        when(categoryRepository.findByTenantId(TENANT)).thenReturn(List.of(first, second));

        List<StandardCategory> result = categoryService.listAll(TENANT);

        assertThat(result).containsExactly(first, second);
        verify(categoryRepository).findByTenantId(TENANT);
    }

    @Test
    @DisplayName("create — 强制清空客户端传入的 ID，写入租户与创建/更新时间")
    void createShouldResetIdAndStampTenant() {
        StandardCategory incoming = category(999L, "用户域", "user-domain");

        when(categoryRepository.save(any(StandardCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        StandardCategory created = categoryService.create(incoming, TENANT);

        ArgumentCaptor<StandardCategory> captor = ArgumentCaptor.forClass(StandardCategory.class);
        verify(categoryRepository).save(captor.capture());

        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(captor.getValue().getCreatedAt());
        assertThat(created).isSameAs(incoming);
    }

    @Test
    @DisplayName("getById — id 或 tenantId 为 null 时返回 null 且不访问数据库")
    void getByIdShouldReturnNullOnNullGuards() {
        assertThat(categoryService.getById(null, TENANT)).isNull();
        assertThat(categoryService.getById(1L, null)).isNull();
        verifyNoInteractions(categoryRepository);
    }

    @Test
    @DisplayName("getById — 命中且租户匹配时返回分类")
    void getByIdShouldReturnEntityWhenFound() {
        StandardCategory found = category(3L, "用户域", "user-domain");
        when(categoryRepository.findByIdAndTenantId(3L, TENANT)).thenReturn(Optional.of(found));

        assertThat(categoryService.getById(3L, TENANT)).isSameAs(found);
    }

    @Test
    @DisplayName("getById — 不存在或不属于该租户时返回 null")
    void getByIdShouldReturnNullWhenNotFound() {
        when(categoryRepository.findByIdAndTenantId(3L, TENANT)).thenReturn(Optional.empty());

        assertThat(categoryService.getById(3L, TENANT)).isNull();
    }

    private static StandardCategory category(Long id, String name, String code) {
        StandardCategory c = new StandardCategory();
        c.setId(id);
        c.setName(name);
        c.setCode(code);
        c.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return c;
    }
}
