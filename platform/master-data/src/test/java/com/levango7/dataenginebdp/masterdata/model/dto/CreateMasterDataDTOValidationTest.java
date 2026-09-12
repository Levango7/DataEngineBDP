package com.levango7.dataenginebdp.masterdata.model.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CreateMasterDataDTO Bean Validation 测试。
 *
 * <p>验证 status 字段的 @Pattern 枚举校验（ACTIVE / INACTIVE / DEPRECATED）。
 * 纯 Bean Validation 测试，不依赖 Spring 上下文。</p>
 */
@DisplayName("CreateMasterDataDTO @Pattern 校验")
class CreateMasterDataDTOValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    @DisplayName("合法 status ACTIVE 通过校验")
    void shouldAcceptActiveStatus() {
        CreateMasterDataDTO dto = new CreateMasterDataDTO();
        dto.setModelCode("md-org");
        dto.setDataKey("ORG-001");
        dto.setDataValue("总部");
        dto.setStatus("ACTIVE");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("合法 status INACTIVE 通过校验")
    void shouldAcceptInactiveStatus() {
        CreateMasterDataDTO dto = new CreateMasterDataDTO();
        dto.setModelCode("md-org");
        dto.setDataKey("ORG-001");
        dto.setDataValue("总部");
        dto.setStatus("INACTIVE");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("合法 status DEPRECATED 通过校验")
    void shouldAcceptDeprecatedStatus() {
        CreateMasterDataDTO dto = new CreateMasterDataDTO();
        dto.setModelCode("md-org");
        dto.setDataKey("ORG-001");
        dto.setDataValue("总部");
        dto.setStatus("DEPRECATED");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("null status 通过校验（服务层回退 ACTIVE）")
    void shouldAcceptNullStatus() {
        CreateMasterDataDTO dto = new CreateMasterDataDTO();
        dto.setModelCode("md-org");
        dto.setDataKey("ORG-001");
        dto.setDataValue("总部");
        dto.setStatus(null);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("非法 status 被拒绝")
    void shouldRejectInvalidStatus() {
        CreateMasterDataDTO dto = new CreateMasterDataDTO();
        dto.setModelCode("md-org");
        dto.setDataKey("ORG-001");
        dto.setDataValue("总部");
        dto.setStatus("DELETED");
        Set<ConstraintViolation<CreateMasterDataDTO>> violations = validator.validate(dto);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("ACTIVE / INACTIVE / DEPRECATED");
    }

    @Test
    @DisplayName("空 modelCode 被拒绝")
    void shouldRejectBlankModelCode() {
        CreateMasterDataDTO dto = new CreateMasterDataDTO();
        dto.setModelCode("");
        dto.setDataKey("ORG-001");
        dto.setDataValue("总部");
        assertThat(validator.validate(dto)).isNotEmpty();
    }
}