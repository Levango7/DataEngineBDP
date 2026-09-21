package com.levango7.dataenginebdp.masterdata.service;

import com.levango7.dataenginebdp.masterdata.model.dto.CreateMasterDataModelDTO;
import com.levango7.dataenginebdp.masterdata.model.entity.MasterDataModel;
import com.levango7.dataenginebdp.masterdata.repository.MasterDataModelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MasterDataModelService 的列表 / 创建 / 按编码查询测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>listAll：按租户返回全部模型</li>
 *   <li>create：全字段落库、租户写入、createdAt 与 updatedAt 一致、返回仓储结果</li>
 *   <li>getByCode：code / tenantId 为 null、未命中、命中</li>
 * </ul>
 *
 * <p>使用 Mockito mock Repository，纯 Service 逻辑测试，不依赖数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasterDataModelService 模型管理")
class MasterDataModelServiceTest {

    @Mock
    private MasterDataModelRepository modelRepository;

    @InjectMocks
    private MasterDataModelService modelService;

    @Test
    @DisplayName("listAll: 返回该租户下的全部模型")
    void listAllShouldReturnModelsOfTenant() {
        MasterDataModel first = new MasterDataModel();
        first.setCode("md-org");
        first.setName("组织");
        MasterDataModel second = new MasterDataModel();
        second.setCode("md-material");
        second.setName("物料");
        when(modelRepository.findByTenantId("tenant-A")).thenReturn(List.of(first, second));

        List<MasterDataModel> models = modelService.listAll("tenant-A");

        assertThat(models).hasSize(2);
        assertThat(models).extracting(MasterDataModel::getCode)
                .containsExactly("md-org", "md-material");
        verify(modelRepository).findByTenantId("tenant-A");
    }

    @Test
    @DisplayName("listAll: 无模型时返回空列表")
    void listAllShouldReturnEmptyListWhenNoModel() {
        when(modelRepository.findByTenantId("tenant-empty")).thenReturn(List.of());

        assertThat(modelService.listAll("tenant-empty")).isEmpty();
    }

    @Test
    @DisplayName("create: 全字段落库并写入租户与时间戳")
    void createShouldPersistAllFields() {
        CreateMasterDataModelDTO dto = new CreateMasterDataModelDTO();
        dto.setCode("md-org");
        dto.setName("组织");
        dto.setFieldsSchema("[{\"name\":\"orgCode\",\"type\":\"string\"}]");
        dto.setDescription("组织主数据模型");
        when(modelRepository.save(any(MasterDataModel.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MasterDataModel created = modelService.create(dto, "tenant-A");

        ArgumentCaptor<MasterDataModel> captor = ArgumentCaptor.forClass(MasterDataModel.class);
        verify(modelRepository).save(captor.capture());
        MasterDataModel persisted = captor.getValue();
        assertThat(persisted.getCode()).isEqualTo("md-org");
        assertThat(persisted.getName()).isEqualTo("组织");
        assertThat(persisted.getFieldsSchema()).isEqualTo("[{\"name\":\"orgCode\",\"type\":\"string\"}]");
        assertThat(persisted.getDescription()).isEqualTo("组织主数据模型");
        assertThat(persisted.getTenantId()).isEqualTo("tenant-A");
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isEqualTo(persisted.getCreatedAt());
        assertThat(created).isSameAs(persisted);
    }

    @Test
    @DisplayName("create: 返回仓储持久化后的实体")
    void createShouldReturnPersistedEntity() {
        CreateMasterDataModelDTO dto = new CreateMasterDataModelDTO();
        dto.setCode("md-customer");
        dto.setName("客户");
        MasterDataModel persisted = new MasterDataModel();
        persisted.setId(42L);
        when(modelRepository.save(any(MasterDataModel.class))).thenReturn(persisted);

        MasterDataModel created = modelService.create(dto, "tenant-B");

        assertThat(created).isSameAs(persisted);
        assertThat(created.getId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("getByCode: code 为 null 返回 null 且不查询仓储")
    void getByCodeShouldReturnNullWhenCodeNull() {
        assertThat(modelService.getByCode(null, "tenant-A")).isNull();
        verifyNoInteractions(modelRepository);
    }

    @Test
    @DisplayName("getByCode: tenantId 为 null 返回 null 且不查询仓储")
    void getByCodeShouldReturnNullWhenTenantNull() {
        assertThat(modelService.getByCode("md-org", null)).isNull();
        verifyNoInteractions(modelRepository);
    }

    @Test
    @DisplayName("getByCode: 未命中返回 null")
    void getByCodeShouldReturnNullWhenNotFound() {
        when(modelRepository.findByCodeAndTenantId("md-org", "tenant-A")).thenReturn(Optional.empty());

        assertThat(modelService.getByCode("md-org", "tenant-A")).isNull();
    }

    @Test
    @DisplayName("getByCode: 命中返回模型")
    void getByCodeShouldReturnModelWhenFound() {
        MasterDataModel model = new MasterDataModel();
        model.setId(5L);
        model.setCode("md-org");
        model.setName("组织");
        when(modelRepository.findByCodeAndTenantId("md-org", "tenant-A")).thenReturn(Optional.of(model));

        MasterDataModel result = modelService.getByCode("md-org", "tenant-A");

        assertThat(result).isSameAs(model);
        assertThat(result.getName()).isEqualTo("组织");
    }
}
