package com.opencode.cui.skill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "skill.multi-sync")
public class MultiSyncProperties {

    /** 同步模式：ws | im，默认 ws */
    private String mode = "ws";

    private Im im = new Im();

    @Data
    public static class Im {
        private AppNotify appNotify = new AppNotify();

        @Data
        public static class AppNotify {
            /** IM app-notify 完整请求 URL，默认通过 skill.im.api-url 拼接 */
            private String url = "";
            private String tenant;
            private String module;
            private int scope = 2;
        }
    }
}
