package com.levango7.dataenginebdp.datastandard.controller;

import com.levango7.dataenginebdp.datastandard.model.dto.CreateStandardDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 单元测试（直接调用 @ExceptionHandler 方法）。
 *
 * <p>验证每种异常类型到 HTTP 状态码与错误码的映射，包括字段错误为空、
 * NoSuchElementException 无 message 两个回退分支。所有异常均为真实实例，
 * 校验失败场景由真实的 Bean Validation 产生，而非 Mock。</p>
 */
@DisplayName("GlobalExceptionHandler 异常映射测试")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    @DisplayName("IllegalStateException → 400 precondition_failed")
    void shouldMapIllegalStateTo400() {
        ResponseEntity<Map<String, String>> response =
                handler.handleIllegalState(new IllegalStateException("缺少租户上下文"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "precondition_failed");
        assertThat(response.getBody()).containsEntry("message", "缺少租户上下文");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException（真实校验失败）→ 400 validation_failed 且带字段明细")
    void shouldMapValidationFailureTo400WithFieldDetail() throws Exception {
        // 只让 name 违规（status 合法），使 findFirst() 命中的字段错误是确定性的
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("");
        dto.setStatus("DRAFT");

        BindingResult bindingResult = new BeanPropertyBindingResult(dto, "createStandardDTO");
        validator.validate(dto, bindingResult);

        ResponseEntity<Map<String, String>> response =
                handler.handleValidation(new MethodArgumentNotValidException(
                        standardControllerCreateParameter(), bindingResult));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "validation_failed");
        assertThat(response.getBody().get("message"))
                .contains("name")
                .contains("标准名称不能为空");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException（无字段错误）→ 400 且回退为 validation failed")
    void shouldMapValidationFailureTo400WithFallbackMessage() throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new CreateStandardDTO(), "createStandardDTO");
        bindingResult.addError(new ObjectError("createStandardDTO", "global rejection"));

        ResponseEntity<Map<String, String>> response =
                handler.handleValidation(new MethodArgumentNotValidException(
                        standardControllerCreateParameter(), bindingResult));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "validation failed");
    }

    @Test
    @DisplayName("ConstraintViolationException（真实校验违规）→ 400 constraint_violation 且带属性明细")
    void shouldMapConstraintViolationTo400() {
        CreateStandardDTO dto = new CreateStandardDTO();
        dto.setName("客户命名规范");
        dto.setStatus("ARCHIVED");
        Set<ConstraintViolation<CreateStandardDTO>> violations = validator.validate(dto);

        ResponseEntity<Map<String, String>> response =
                handler.handleConstraintViolation(new ConstraintViolationException(violations));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "constraint_violation");
        assertThat(response.getBody().get("message"))
                .contains("status")
                .contains("DRAFT / PUBLISHED / DEPRECATED");
    }

    @Test
    @DisplayName("ConstraintViolationException（空违规集合）→ 400 且回退为 constraint violation")
    void shouldMapEmptyConstraintViolationToFallbackMessage() {
        ResponseEntity<Map<String, String>> response =
                handler.handleConstraintViolation(new ConstraintViolationException(Collections.emptySet()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "constraint violation");
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException → 400 type_mismatch 并回显参数名")
    void shouldMapTypeMismatchTo400() throws Exception {
        MethodParameter parameter = new MethodParameter(
                StandardController.class.getDeclaredMethod("getById", Long.class), 0);

        ResponseEntity<Map<String, String>> response = handler.handleTypeMismatch(
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", parameter,
                        new NumberFormatException("For input string: \"abc\"")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "type_mismatch");
        assertThat(response.getBody()).containsEntry("message", "参数 id 类型不匹配");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException → 400 malformed_json")
    void shouldMapNotReadableTo400() {
        ResponseEntity<Map<String, String>> response = handler.handleNotReadable(
                new HttpMessageNotReadableException("broken json",
                        new MockHttpInputMessage("not-json".getBytes())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "malformed_json");
        assertThat(response.getBody()).containsEntry("message", "请求体 JSON 格式错误");
    }

    @Test
    @DisplayName("NoSuchElementException（有 message）→ 404 not_found 并回显 message")
    void shouldMapNoSuchElementTo404WithMessage() {
        ResponseEntity<Map<String, String>> response =
                handler.handleNotFound(new NoSuchElementException("Standard 9 not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "not_found");
        assertThat(response.getBody()).containsEntry("message", "Standard 9 not found");
    }

    @Test
    @DisplayName("NoSuchElementException（无 message）→ 404 not_found 且回退为 resource not found")
    void shouldMapNoSuchElementTo404WithoutMessage() {
        ResponseEntity<Map<String, String>> response = handler.handleNotFound(new NoSuchElementException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("message", "resource not found");
    }

    @Test
    @DisplayName("OptimisticLockingFailureException → 409 conflict")
    void shouldMapOptimisticLockTo409() {
        ResponseEntity<Map<String, String>> response =
                handler.handleOptimisticLock(new OptimisticLockingFailureException("row updated concurrently"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "conflict");
        assertThat(response.getBody()).containsEntry("message", "资源已被并发修改");
    }

    @Test
    @DisplayName("DataAccessException → 503 data_access_error")
    void shouldMapDataAccessTo503() {
        ResponseEntity<Map<String, String>> response =
                handler.handleDataAccess(new DataAccessResourceFailureException("connection refused"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "data_access_error");
        assertThat(response.getBody()).containsEntry("message", "数据访问失败");
    }

    @Test
    @DisplayName("兜底 Exception → 500 internal_error")
    void shouldMapUnexpectedTo500() {
        ResponseEntity<Map<String, String>> response = handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "internal_error");
        assertThat(response.getBody()).containsEntry("message", "服务内部错误");
    }

    private static MethodParameter standardControllerCreateParameter() throws NoSuchMethodException {
        Method method = StandardController.class.getDeclaredMethod("create", CreateStandardDTO.class);
        return new MethodParameter(method, 0);
    }
}
