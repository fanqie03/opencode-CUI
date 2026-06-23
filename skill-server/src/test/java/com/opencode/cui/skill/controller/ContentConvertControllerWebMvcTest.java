package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.config.GlobalExceptionHandler;
import com.opencode.cui.skill.service.convert.ContentConvertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ContentConvertController WebMvc 测试。
 *
 * <p>聚焦 HTTP 层：验证空请求体 / JSON {@code null} / 非法 JSON 经
 * {@link GlobalExceptionHandler} 转为 400 + ApiResponse 错误结构，
 * 而非落入兜底 {@code @ExceptionHandler(Exception.class)} 返回 500。
 *
 * <p>采用 {@code standaloneSetup} 而非 {@code @WebMvcTest}：后者会触发完整
 * {@code @SpringBootApplication} 扫描，拉起 MyBatis mapper（如
 * {@code SkillDefinitionRepository}）而因缺少 {@code sqlSessionFactory} 失败。
 * {@code standaloneSetup} 手动装配 Controller + ControllerAdvice，无需 Spring 上下文，
 * 仍走真实 {@code @RequestBody} 解析与异常处理路径，更轻量也更稳定。
 */
@ExtendWith(MockitoExtension.class)
class ContentConvertControllerWebMvcTest {

    private static final String ENDPOINT = "/api/content/convert/text";

    @Mock
    private ContentConvertService contentConvertService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ContentConvertController controller = new ContentConvertController(contentConvertService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("空请求体: 返回 400 + ApiResponse.code=400")
    void emptyBodyReturns400() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errormsg").value("请求体不能为空或格式错误"));
    }

    @Test
    @DisplayName("JSON null: 返回 400 + ApiResponse.code=400")
    void jsonNullReturns400() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errormsg").value("请求体不能为空或格式错误"));
    }

    @Test
    @DisplayName("非法 JSON: 返回 400 + ApiResponse.code=400")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errormsg").value("请求体不能为空或格式错误"));
    }
}
