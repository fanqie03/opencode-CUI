package com.opencode.cui.skill.service.convert;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.core.DiagramDescription;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * PlantUML 文本转换服务。
 * 将 PlantUML / Graphviz 文本转换为 PNG Base64 或 SVG 字符串。
 */
@Slf4j
@Service
public class PlantUmlConvertService {

    /**
     * 将 PlantUML 文本转换为指定格式。
     *
     * @param content     待转换文本
     * @param fileType    输入类型（puml / plantuml / gv）
     * @param contentType 输出类型（png / svg）
     * @return PNG 返回 Base64 字符串，SVG 返回 XML 字符串
     * @throws RuntimeException 转换失败时抛出
     */
    public String convert(String content, String fileType, String contentType) {
        String normalized = normalizeContent(content, fileType);
        log.info("PlantUML convert start: fileType={}, contentType={}, contentLength={}",
                fileType, contentType, normalized.length());

        SourceStringReader reader = new SourceStringReader(normalized);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        FileFormat format = "svg".equalsIgnoreCase(contentType) ? FileFormat.SVG : FileFormat.PNG;
        FileFormatOption option = new FileFormatOption(format);

        try {
            DiagramDescription desc = reader.outputImage(output, option);

            if (desc == null || desc.getDescription() == null
                    || desc.getDescription().contains("(Error)")) {
                String descStr = desc != null ? desc.getDescription() : "null";
                log.error("PlantUML convert failed: description={}", descStr);
                throw new RuntimeException("PlantUML conversion failed: " + descStr);
            }

            String result;
            if (format == FileFormat.PNG) {
                result = Base64.getEncoder().encodeToString(output.toByteArray());
            } else {
                result = output.toString(StandardCharsets.UTF_8);
            }

            log.info("PlantUML convert success: fileType={}, contentType={}, resultLength={}",
                    fileType, contentType, result.length());
            return result;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("PlantUML convert exception: fileType={}, contentType={}", fileType, contentType, e);
            throw new RuntimeException("PlantUML conversion failed: " + e.getMessage(), e);
        }
    }

    /**
     * 输入标准化。
     * gv 类型自动包裹 @startuml / @enduml，puml / plantuml 原样返回。
     */
    String normalizeContent(String content, String fileType) {
        if ("gv".equalsIgnoreCase(fileType)) {
            return "@startuml\n" + content.trim() + "\n@enduml";
        }
        return content;
    }
}
