package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.convert.ContentConvertData;
import com.opencode.cui.skill.model.convert.ContentConvertRequest;
import com.opencode.cui.skill.service.convert.ContentConvertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容转换接口。
 * 将 PlantUML / Graphviz 文本转换为 PNG 或 SVG。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ContentConvertController {

    private final ContentConvertService contentConvertService;

    /**
     * POST /api/content/convert/text
     * 将 PlantUML / Graphviz 文本转换为图片。
     *
     * @param request 转换请求（content + contentType + fileType）
     * @return 转换结果，包含 Base64 或 SVG 字符串
     */
    @PostMapping("/api/content/convert/text")
    public ResponseEntity<ApiResponse<ContentConvertData>> convert(
            @RequestBody ContentConvertRequest request) {
        log.info("[ENTRY] contentConvert: fileType={}, contentType={}, contentLength={}",
                request.getFileType(), request.getContentType(),
                request.getContent() != null ? request.getContent().length() : 0);
        ContentConvertData data = contentConvertService.convert(request);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }
}
