package com.levango7.dataenginebdp.encaps.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A2 错误国际化端到端：GlobalExceptionHandler 失败响应必须携带 messageKey，
 * 供前端 vue-i18n 按当前语种翻译。
 */
class GlobalExceptionHandlerMessageKeyTest {

    /** 测试专用 Controller：抛业务异常模拟失败路径。 */
    @RestController
    static class ThrowingController {
        @GetMapping("/test/conflict")
        public void conflict() {
            throw new IllegalStateException("duplicate name");
        }

        @GetMapping("/test/badarg")
        public void badArg() {
            throw new IllegalArgumentException("negative id");
        }

        @GetMapping("/test/boom")
        public void boom() {
            throw new RuntimeException("npe somewhere");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("409 冲突响应携带 messageKey=error.resource.conflict")
    void conflictCarriesMessageKey() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.messageKey").value("error.resource.conflict"))
                .andExpect(jsonPath("$.message").value("资源冲突: duplicate name"));
    }

    @Test
    @DisplayName("400 参数非法响应携带 messageKey=error.param.invalid")
    void badArgCarriesMessageKey() throws Exception {
        mockMvc.perform(get("/test/badarg"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.messageKey").value("error.param.invalid"));
    }

    @Test
    @DisplayName("500 兜底响应携带 messageKey=error.internal（不泄露堆栈细节进 messageKey）")
    void internalCarriesMessageKey() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.messageKey").value("error.internal"));
    }

    @Test
    @DisplayName("ErrorCode 枚举全部错误码均有非空 messageKey（SUCCESS 除外）")
    void allErrorCodesHaveMessageKey() {
        for (ErrorCode ec : ErrorCode.values()) {
            if (ec == ErrorCode.SUCCESS) {
                assertThat(ec.getMessageKey()).as("SUCCESS 无需 messageKey").isNull();
            } else {
                assertThat(ec.getMessageKey())
                        .as("%s 应携带 messageKey", ec.name())
                        .isNotNull()
                        .startsWith("error.");
            }
        }
    }
}
