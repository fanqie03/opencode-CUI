package com.opencode.cui.skill.service.convert;

import com.opencode.cui.skill.config.ContentConvertProperties;
import com.opencode.cui.skill.model.convert.ContentConvertData;
import com.opencode.cui.skill.model.convert.ContentConvertRequest;
import com.opencode.cui.skill.service.ProtocolException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 内容转换编排服务。
 * 负责参数校验、调用 PlantUML 转换、记录耗时日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentConvertService {

    private static final Set<String> VALID_CONTENT_TYPES = Set.of("png", "svg");
    private static final Set<String> VALID_FILE_TYPES = Set.of("puml", "plantuml", "gv");

    private final PlantUmlConvertService plantUmlConvertService;
    private final ContentConvertProperties properties;

    /**
     * 执行内容转换。
     *
     * @param request 转换请求
     * @return 转换结果
     */
    public ContentConvertData convert(ContentConvertRequest request) {
        validate(request);

        long start = System.currentTimeMillis();
        try {
            String image = plantUmlConvertService.convert(
                    request.getContent(),
                    request.getFileType(),
                    request.getContentType());
            long elapsed = System.currentTimeMillis() - start;
            log.info("Content convert completed: fileType={}, contentType={}, elapsedMs={}",
                    request.getFileType(), request.getContentType(), elapsed);
            return ContentConvertData.builder().image(image).build();
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Content convert failed: fileType={}, contentType={}, elapsedMs={}",
                    request.getFileType(), request.getContentType(), elapsed, e);
            throw new ProtocolException(422, "内容转换失败，请检查语法");
        }
    }

    /**
     * 参数校验。
     */
    private void validate(ContentConvertRequest request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new ProtocolException(400, "content 不能为空");
        }
        if (request.getContent().length() > properties.getMaxContentLength()) {
            throw new ProtocolException(400,
                    "content 超过最大长度限制 " + properties.getMaxContentLength());
        }
        if (request.getContentType() == null
                || !VALID_CONTENT_TYPES.contains(request.getContentType().toLowerCase())) {
            throw new ProtocolException(400, "不支持的输出类型，仅支持 png、svg");
        }
        if (request.getFileType() == null
                || !VALID_FILE_TYPES.contains(request.getFileType().toLowerCase())) {
            throw new ProtocolException(400, "不支持的文件类型，仅支持 puml、plantuml、gv");
        }
    }
}
