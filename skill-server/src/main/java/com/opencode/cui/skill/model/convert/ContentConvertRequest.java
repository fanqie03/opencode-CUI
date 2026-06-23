package com.opencode.cui.skill.model.convert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容转换请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentConvertRequest {

    /** 待转换文本 */
    private String content;

    /** 输出类型：png 或 svg */
    private String contentType;

    /** 输入类型：puml、plantuml 或 gv */
    private String fileType;
}
