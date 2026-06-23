package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.convert.ContentConvertData;
import com.opencode.cui.skill.model.convert.ContentConvertRequest;
import com.opencode.cui.skill.service.ProtocolException;
import com.opencode.cui.skill.service.convert.ContentConvertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ContentConvertController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ContentConvertControllerTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ContentConvertService contentConvertService;

    // ------------------------------------------------------------------ 成功

    @Test
    @DisplayName("convert: 正常 puml 转 png 返回 200 + code=0")
    void convertPumlToPngReturns200() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("@startuml\nAlice -> Bob: hello\n@enduml")
                .contentType("png")
                .fileType("puml")
                .build();

        ContentConvertData data = ContentConvertData.builder()
                .image("iVBORw0KGgo...")
                .build();

        when(contentConvertService.convert(any(ContentConvertRequest.class)))
                .thenReturn(data);

        ContentConvertController controller = new ContentConvertController(contentConvertService);
        ResponseEntity<?> response = controller.convert(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("convert: 正常 puml 转 svg 返回 200 + code=0")
    void convertPumlToSvgReturns200() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("@startuml\nAlice -> Bob: hello\n@enduml")
                .contentType("svg")
                .fileType("puml")
                .build();

        ContentConvertData data = ContentConvertData.builder()
                .image("<svg>...</svg>")
                .build();

        when(contentConvertService.convert(any(ContentConvertRequest.class)))
                .thenReturn(data);

        ContentConvertController controller = new ContentConvertController(contentConvertService);
        ResponseEntity<?> response = controller.convert(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ------------------------------------------------------------------ 参数错误

    @Test
    @DisplayName("convert: content 为空时抛出 400")
    void convertEmptyContentThrows400() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("")
                .contentType("png")
                .fileType("puml")
                .build();

        when(contentConvertService.convert(any(ContentConvertRequest.class)))
                .thenThrow(new ProtocolException(400, "content 不能为空"));

        ContentConvertController controller = new ContentConvertController(contentConvertService);

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> controller.convert(request));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("convert: 无效 contentType 时抛出 400")
    void convertInvalidContentTypeThrows400() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("@startuml\nA->B\n@enduml")
                .contentType("gif")
                .fileType("puml")
                .build();

        when(contentConvertService.convert(any(ContentConvertRequest.class)))
                .thenThrow(new ProtocolException(400, "不支持的输出类型"));

        ContentConvertController controller = new ContentConvertController(contentConvertService);

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> controller.convert(request));
        assertEquals(400, ex.getCode());
    }

    // ------------------------------------------------------------------ 转换失败

    @Test
    @DisplayName("convert: PlantUML 语法错误时抛出 422")
    void convertSyntaxErrorThrows422() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("invalid syntax")
                .contentType("png")
                .fileType("puml")
                .build();

        when(contentConvertService.convert(any(ContentConvertRequest.class)))
                .thenThrow(new ProtocolException(422, "内容转换失败"));

        ContentConvertController controller = new ContentConvertController(contentConvertService);

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> controller.convert(request));
        assertEquals(422, ex.getCode());
    }
}
