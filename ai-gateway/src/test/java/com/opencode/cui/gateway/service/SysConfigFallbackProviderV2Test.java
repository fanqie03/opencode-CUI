package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SysConfigFallbackProviderV2} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>(cloudProfile, scope) → SS key 拼接（{@code {cloudProfile}:{shortName}}）</li>
 *   <li>JSON 完整 → 构造 CallbackConfig 成功</li>
 *   <li>JSON 字段缺失 → 返回 null（warn）</li>
 *   <li>JSON 解析失败 → 返回 null（warn）</li>
 *   <li>SysConfig 返回 null → 返回 null</li>
 *   <li>scope 不在白名单 → 返回 null（不打 SS）</li>
 *   <li>businessTag 为 null/blank → 返回 null（不打 SS）</li>
 *   <li>TTL 窗口内重复 load 仅打 SS 一次（in-mem 缓存）</li>
 *   <li>独立缓存 entry（按 businessTag + scope 维度）</li>
 *   <li>不回查旧接口</li>
 * </ul>
 */
class SysConfigFallbackProviderV2Test {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void load_chatScope_jsonComplete_returnsConfig() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback_v2", "assistant_square:chat"))
                .thenReturn("{\"channelAddress\":\"http://x/chat\",\"channelType\":\"sse\",\"authType\":\"soa\"}");
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:chat", "assistant_square");

        assertThat(cfg).isNotNull();
        assertThat(cfg.getAk()).isEqualTo("AK1");
        assertThat(cfg.getScope()).isEqualTo("callback:weagent:chat");
        assertThat(cfg.getChannelAddress()).isEqualTo("http://x/chat");
        assertThat(cfg.getChannelType()).isEqualTo("sse");
        assertThat(cfg.getAuthType()).isEqualTo("soa");
        assertThat(cfg.getAppId()).isNull();
    }

    @Test
    void load_questionScope_jsonComplete_returnsConfig() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback_v2", "assistant_square:question"))
                .thenReturn("{\"channelAddress\":\"http://x/q\",\"channelType\":\"webhook\",\"authType\":\"soa\"}");
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:question_reply", "assistant_square");

        assertThat(cfg).isNotNull();
        assertThat(cfg.getChannelAddress()).isEqualTo("http://x/q");
        assertThat(cfg.getChannelType()).isEqualTo("webhook");
        assertThat(cfg.getAuthType()).isEqualTo("soa");
    }

    @Test
    void load_permissionScope_jsonComplete_returnsConfig() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback_v2", "assistant_square:permission"))
                .thenReturn("{\"channelAddress\":\"http://x/p\",\"channelType\":\"webhook\",\"authType\":\"apig\"}");
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:permission_reply", "assistant_square");

        assertThat(cfg).isNotNull();
        assertThat(cfg.getChannelAddress()).isEqualTo("http://x/p");
        assertThat(cfg.getChannelType()).isEqualTo("webhook");
        assertThat(cfg.getAuthType()).isEqualTo("apig");
    }

    @Test
    void load_jsonMissingField_returnsNull() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        // 缺 authType
        when(ss.getConfigValue("cloud_route_fallback_v2", "assistant_square:chat"))
                .thenReturn("{\"channelAddress\":\"http://x\",\"channelType\":\"sse\"}");
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:chat", "assistant_square");

        assertThat(cfg).isNull();
    }

    @Test
    void load_jsonParseError_returnsNull() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback_v2", "assistant_square:chat"))
                .thenReturn("not-a-json{{");
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:chat", "assistant_square");

        assertThat(cfg).isNull();
    }

    @Test
    void load_sysConfigReturnsNull_returnsNull() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue(any(), any())).thenReturn(null);
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:chat", "assistant_square");

        assertThat(cfg).isNull();
    }

    @Test
    void load_scopeNotInWhitelist_returnsNullWithoutCallingSs() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig cfg = p.load("AK1", "callback:unknown:scope", "assistant_square");

        assertThat(cfg).isNull();
        verify(ss, times(0)).getConfigValue(any(), any());
    }

    @Test
    void load_cloudProfileNull_returnsNullWithoutCallingSs() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:chat", null);

        assertThat(cfg).isNull();
        verify(ss, times(0)).getConfigValue(any(), any());
    }

    @Test
    void load_cloudProfileBlank_returnsNullWithoutCallingSs() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:chat", "");

        assertThat(cfg).isNull();
        verify(ss, times(0)).getConfigValue(any(), any());
    }

    @Test
    void load_repeatedWithinTtl_callsSsOnlyOnce() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback_v2", "assistant_square:chat"))
                .thenReturn("{\"channelAddress\":\"http://x\",\"channelType\":\"sse\",\"authType\":\"soa\"}");
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig c1 = p.load("AK1", "callback:weagent:chat", "assistant_square");
        CallbackConfig c2 = p.load("AK2", "callback:weagent:chat", "assistant_square");
        CallbackConfig c3 = p.load("AK3", "callback:weagent:chat", "assistant_square");

        assertThat(c1).isNotNull();
        assertThat(c2).isNotNull();
        assertThat(c3).isNotNull();
        // 缓存按 (cloudProfile, scope 短名) 维度，3 次 load 命中同一 cache，仅打 SS 1 次
        verify(ss, times(1)).getConfigValue("cloud_route_fallback_v2", "assistant_square:chat");
        // 但 ak/scope 必须各自独立填充（不串号）
        assertThat(c1.getAk()).isEqualTo("AK1");
        assertThat(c2.getAk()).isEqualTo("AK2");
        assertThat(c3.getAk()).isEqualTo("AK3");
    }

    @Test
    void load_independentCachePerCloudProfile() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback_v2", "assistant_square:chat"))
                .thenReturn("{\"channelAddress\":\"http://as\",\"channelType\":\"sse\",\"authType\":\"soa\"}");
        when(ss.getConfigValue("cloud_route_fallback_v2", "other_profile:chat"))
                .thenReturn("{\"channelAddress\":\"http://other\",\"channelType\":\"webhook\",\"authType\":\"apig\"}");
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig c1 = p.load("AK1", "callback:weagent:chat", "assistant_square");
        CallbackConfig c2 = p.load("AK1", "callback:weagent:chat", "other_profile");

        assertThat(c1.getChannelAddress()).isEqualTo("http://as");
        assertThat(c2.getChannelAddress()).isEqualTo("http://other");
        verify(ss, times(1)).getConfigValue("cloud_route_fallback_v2", "assistant_square:chat");
        verify(ss, times(1)).getConfigValue("cloud_route_fallback_v2", "other_profile:chat");
    }

    @Test
    void load_independentCachePerScope() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback_v2", "assistant_square:chat"))
                .thenReturn("{\"channelAddress\":\"http://chat\",\"channelType\":\"sse\",\"authType\":\"soa\"}");
        when(ss.getConfigValue("cloud_route_fallback_v2", "assistant_square:question"))
                .thenReturn("{\"channelAddress\":\"http://q\",\"channelType\":\"webhook\",\"authType\":\"soa\"}");
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig c1 = p.load("AK1", "callback:weagent:chat", "assistant_square");
        CallbackConfig c2 = p.load("AK1", "callback:weagent:question_reply", "assistant_square");

        assertThat(c1.getChannelAddress()).isEqualTo("http://chat");
        assertThat(c2.getChannelAddress()).isEqualTo("http://q");
        verify(ss, times(1)).getConfigValue("cloud_route_fallback_v2", "assistant_square:chat");
        verify(ss, times(1)).getConfigValue("cloud_route_fallback_v2", "assistant_square:question");
    }

    @Test
    void load_abortScope_jsonComplete_returnsConfig() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback_v2", "assistant_square:abort"))
                .thenReturn("{\"channelAddress\":\"http://x/stop\",\"channelType\":\"webhook\",\"authType\":\"soa\"}");
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:abort", "assistant_square");

        assertThat(cfg).isNotNull();
        assertThat(cfg.getChannelAddress()).isEqualTo("http://x/stop");
        assertThat(cfg.getChannelType()).isEqualTo("webhook");
        assertThat(cfg.getAuthType()).isEqualTo("soa");
    }

    @Test
    void load_doesNotFallthroughToOldFallbackKey() {
        // 关键 AC：V2 provider miss 时 **不** 回查 cloud_route_fallback:{scope}
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        // 新 key（cloud_route_fallback_v2）miss
        when(ss.getConfigValue("cloud_route_fallback_v2", "assistant_square:chat")).thenReturn(null);
        // 老 key（cloud_route_fallback）即便有值也不应被读
        when(ss.getConfigValue("cloud_route_fallback", "chat"))
                .thenReturn("{\"channelAddress\":\"http://leaked\",\"channelType\":\"sse\",\"authType\":\"none\"}");
        SysConfigFallbackProviderV2 p = new SysConfigFallbackProviderV2(ss, mapper, 300_000L);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:chat", "assistant_square");

        assertThat(cfg).isNull();
        // 只读了新 key，老 key 一次都没读
        verify(ss, times(1)).getConfigValue("cloud_route_fallback_v2", "assistant_square:chat");
        verify(ss, times(0)).getConfigValue("cloud_route_fallback", "chat");
    }
}
