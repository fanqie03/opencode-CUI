package com.opencode.cui.skill.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 内容转换配置属性。
 * 通过 {@code skill.content-convert.*} 配置前缀绑定。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "skill.content-convert")
public class ContentConvertProperties {

    /** 最大输入长度（字符数），默认 10000 */
    private int maxContentLength = 10000;
}
