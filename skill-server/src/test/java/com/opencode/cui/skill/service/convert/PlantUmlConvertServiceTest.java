package com.opencode.cui.skill.service.convert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlantUmlConvertService 单元测试。
 */
class PlantUmlConvertServiceTest {

    private final PlantUmlConvertService service = new PlantUmlConvertService();

    // ------------------------------------------------------------------ normalizeContent

    @Test
    @DisplayName("normalizeContent: gv 类型自动包裹 @startuml / @enduml")
    void normalizeContentGvWraps() {
        String result = service.normalizeContent("digraph G { A -> B; }", "gv");
        assertTrue(result.startsWith("@startuml\n"));
        assertTrue(result.endsWith("\n@enduml"));
        assertTrue(result.contains("digraph G { A -> B; }"));
    }

    @Test
    @DisplayName("normalizeContent: gv 类型大小写不敏感")
    void normalizeContentGvCaseInsensitive() {
        String result = service.normalizeContent("digraph G { A -> B; }", "GV");
        assertTrue(result.startsWith("@startuml\n"));
    }

    @Test
    @DisplayName("normalizeContent: puml 类型原样返回")
    void normalizeContentPumlPassthrough() {
        String content = "@startuml\nAlice -> Bob: hello\n@enduml";
        assertEquals(content, service.normalizeContent(content, "puml"));
    }

    @Test
    @DisplayName("normalizeContent: plantuml 类型原样返回")
    void normalizeContentPlantumlPassthrough() {
        String content = "@startuml\nAlice -> Bob: hello\n@enduml";
        assertEquals(content, service.normalizeContent(content, "plantuml"));
    }

    // ------------------------------------------------------------------ convert

    @Test
    @DisplayName("convert: puml 转 png 返回 Base64")
    void convertPumlToPng() {
        String content = "@startuml\nAlice -> Bob: hello\n@enduml";
        String result = service.convert(content, "puml", "png");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // Base64 只包含合法字符
        assertTrue(result.matches("^[A-Za-z0-9+/=]+$"));
    }

    @Test
    @DisplayName("convert: puml 转 svg 返回 XML 字符串")
    void convertPumlToSvg() {
        String content = "@startuml\nAlice -> Bob: hello\n@enduml";
        String result = service.convert(content, "puml", "svg");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.startsWith("<"));
    }

    @Test
    @DisplayName("convert: gv 包裹后转 png")
    void convertGvToPng() {
        // gv 只做包裹，内容本身需是合法 PlantUML
        String content = "Alice -> Bob: hello";
        String result = service.convert(content, "gv", "png");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.matches("^[A-Za-z0-9+/=]+$"));
    }

    @Test
    @DisplayName("convert: gv 包裹后转 svg")
    void convertGvToSvg() {
        String content = "Alice -> Bob: hello";
        String result = service.convert(content, "gv", "svg");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.startsWith("<"));
    }

    @Test
    @DisplayName("convert: 非法 PlantUML 语法抛出 RuntimeException")
    void convertInvalidSyntaxThrows() {
        String content = "not a valid uml diagram @@@";
        assertThrows(RuntimeException.class,
                () -> service.convert(content, "puml", "png"));
    }

    @Test
    @DisplayName("convert: 空内容抛出异常")
    void convertEmptyContentThrows() {
        assertThrows(RuntimeException.class,
                () -> service.convert("", "puml", "png"));
    }
}
