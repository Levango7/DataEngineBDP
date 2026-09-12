package com.levango7.dataenginebdp.datastandard.model.dto;

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
 * CreateStandardDTO Bean Validation 测试。
 *
 * <p>验证 status 字段的 @Pattern 枚举校验（修复 P2: status 字段无枚举校验）。
 * 纯 Bean Validation 测试，不依赖 Spring 上下文。</p>
 */
@DisplayName("CreateStandardDTO @Pattern 校验")
class CreateStandardDTOValidationTest {

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
    @DisplayName("合法 status DRAFT 通过校验")
    void shouldAcceptDraftStatus() {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("test");
        dto.setStatus("DRAFT");
        Set<ConstraintViolation<CreateStandardDTO>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("合法 status PUBLISHED 通过校验")
    void shouldAcceptPublishedStatus() {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("test");
        dto.setStatus("PUBLISHED");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("合法 status DEPRECATED 通过校验")
    void shouldAcceptDeprecatedStatus() {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("test");
        dto.setStatus("DEPRECATED");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("null status 通过校验（服务层回退 DRAFT）")
    void shouldAcceptNullStatus() {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("test");
        dto.setStatus(null);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("非法 status 被拒绝")
    void shouldRejectInvalidStatus() {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("test");
        dto.setStatus("INVALID");
        Set<ConstraintViolation<CreateStandardDTO>> violations = validator.validate(dto);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("DRAFT / PUBLISHED / DEPRECATED");
    }

    @Test
    @DisplayName("小写 status 被拒绝（大小写敏感）")
    void shouldRejectLowercaseStatus() {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("test");
        dto.setStatus("draft");
        assertThat(validator.validate(dto)).hasSize(1);
    }

    @Test
    @DisplayName("空 name 被拒绝")
    void shouldRejectBlankName() {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("");
        assertThat(validator.validate(dto)).isNotEmpty();
    }
}