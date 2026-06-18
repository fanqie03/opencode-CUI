package com.opencode.cui.skill.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MdcConstants 常量定义测试。
 * 确保 MDC key 定义正确且与 logback-spring.xml 中的占位符一致。
 */
class MdcConstantsTest {

    @Test
    void constantsShouldHaveExpectedValues() {
        assertEquals("traceId", MdcConstants.TRACE_ID);
        assertEquals("sessionId", MdcConstants.SESSION_ID);
        assertEquals("ak", MdcConstants.AK);
        assertEquals("userId", MdcConstants.USER_ID);
        assertEquals("scenario", MdcConstants.SCENARIO);
    }

    @Test
    void allKeysShouldReturnAllDefinedKeys() {
        var keys = MdcConstants.ALL_KEYS;
        assertNotNull(keys);
        assertTrue(keys.contains(MdcConstants.TRACE_ID));
        assertTrue(keys.contains(MdcConstants.SESSION_ID));
        assertTrue(keys.contains(MdcConstants.AK));
        assertTrue(keys.contains(MdcConstants.USER_ID));
        assertTrue(keys.contains(MdcConstants.SCENARIO));
        assertTrue(keys.contains(MdcConstants.BUSINESS_DOMAIN));
        assertEquals(6, keys.size());
    }
}
