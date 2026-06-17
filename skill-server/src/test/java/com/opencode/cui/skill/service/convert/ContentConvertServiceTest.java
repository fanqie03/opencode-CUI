package com.opencode.cui.skill.service.convert;

import com.opencode.cui.skill.config.ContentConvertProperties;
import com.opencode.cui.skill.model.convert.ContentConvertData;
import com.opencode.cui.skill.model.convert.ContentConvertRequest;
import com.opencode.cui.skill.service.ProtocolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ContentConvertService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ContentConvertServiceTest {

    @Mock
    private PlantUmlConvertService plantUmlConvertService;

    private ContentConvertProperties properties;
    private ContentConvertService service;

    @BeforeEach
    void setUp() {
        properties = new ContentConvertProperties();
        properties.setMaxContentLength(10000);
        service = new ContentConvertService(plantUmlConvertService, properties);
    }

    // ------------------------------------------------------------------ 参数校验

    @Test
    @DisplayName("convert: content 为 null 抛出 400")
    void convertNullContentThrows400() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content(null)
                .contentType("png")
                .fileType("puml")
                .build();

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> service.convert(request));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("content"));
    }

    @Test
    @DisplayName("convert: content 为空字符串抛出 400")
    void convertBlankContentThrows400() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("   ")
                .contentType("png")
                .fileType("puml")
                .build();

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> service.convert(request));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("convert: content 超过最大长度抛出 400")
    void convertContentTooLongThrows400() {
        properties.setMaxContentLength(10);
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("a".repeat(11))
                .contentType("png")
                .fileType("puml")
                .build();

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> service.convert(request));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("超过最大长度"));
    }

    @Test
    @DisplayName("convert: 无效 contentType 抛出 400")
    void convertInvalidContentTypeThrows400() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("@startuml\nA->B\n@enduml")
                .contentType("jpg")
                .fileType("puml")
                .build();

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> service.convert(request));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("png"));
    }

    @Test
    @DisplayName("convert: contentType 为 null 抛出 400")
    void convertNullContentTypeThrows400() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("@startuml\nA->B\n@enduml")
                .contentType(null)
                .fileType("puml")
                .build();

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> service.convert(request));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("convert: 无效 fileType 抛出 400")
    void convertInvalidFileTypeThrows400() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("@startuml\nA->B\n@enduml")
                .contentType("png")
                .fileType("mermaid")
                .build();

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> service.convert(request));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("puml"));
    }

    @Test
    @DisplayName("convert: fileType 为 null 抛出 400")
    void convertNullFileTypeThrows400() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("@startuml\nA->B\n@enduml")
                .contentType("png")
                .fileType(null)
                .build();

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> service.convert(request));
        assertEquals(400, ex.getCode());
    }

    // ------------------------------------------------------------------ 正常转换

    @Test
    @DisplayName("convert: 正常 puml 转 png 返回 Base64")
    void convertPumlToPngSuccess() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("@startuml\nAlice -> Bob: hello\n@enduml")
                .contentType("png")
                .fileType("puml")
                .build();

        when(plantUmlConvertService.convert(anyString(), anyString(), anyString()))
                .thenReturn("iVBORw0KGgoAAAANSUhEUgAA...");

        ContentConvertData data = service.convert(request);
        assertNotNull(data);
        assertTrue(data.getImage().startsWith("iVBOR"));
        verify(plantUmlConvertService).convert(
                request.getContent(), request.getFileType(), request.getContentType());
    }

    @Test
    @DisplayName("convert: 正常 puml 转 svg 返回 XML")
    void convertPumlToSvgSuccess() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("@startuml\nAlice -> Bob: hello\n@enduml")
                .contentType("svg")
                .fileType("puml")
                .build();

        when(plantUmlConvertService.convert(anyString(), anyString(), anyString()))
                .thenReturn("<svg xmlns=\"...\">...</svg>");

        ContentConvertData data = service.convert(request);
        assertNotNull(data);
        assertTrue(data.getImage().contains("<svg"));
    }

    @Test
    @DisplayName("convert: 转换失败抛出 422")
    void convertFailureThrows422() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("invalid")
                .contentType("png")
                .fileType("puml")
                .build();

        when(plantUmlConvertService.convert(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("conversion error"));

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> service.convert(request));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("转换失败"));
    }

    @Test
    @DisplayName("convert: contentType 大小写不敏感")
    void convertContentTypeCaseInsensitive() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("@startuml\nA->B\n@enduml")
                .contentType("PNG")
                .fileType("puml")
                .build();

        when(plantUmlConvertService.convert(anyString(), anyString(), anyString()))
                .thenReturn("base64data");

        ContentConvertData data = service.convert(request);
        assertNotNull(data);
    }

    @Test
    @DisplayName("convert: fileType 大小写不敏感")
    void convertFileTypeCaseInsensitive() {
        ContentConvertRequest request = ContentConvertRequest.builder()
                .content("@startuml\nA->B\n@enduml")
                .contentType("png")
                .fileType("PUML")
                .build();

        when(plantUmlConvertService.convert(anyString(), anyString(), anyString()))
                .thenReturn("base64data");

        ContentConvertData data = service.convert(request);
        assertNotNull(data);
    }
}
