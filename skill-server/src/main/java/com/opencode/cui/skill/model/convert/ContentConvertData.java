package com.opencode.cui.skill.model.convert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容转换响应数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentConvertData {

    /** 转换结果：PNG 为 Base64 字符串，SVG 为 XML 字符串 */
    private String image;
}
