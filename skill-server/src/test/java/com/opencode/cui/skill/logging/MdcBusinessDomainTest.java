package com.opencode.cui.skill.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class MdcBusinessDomainTest {

    @AfterEach
    void clearMdc() {
        MdcHelper.clearAll();
    }

    @Test
    void putBusinessDomain_setsValue() {
        MdcHelper.putBusinessDomain("im_group_chat");
        assertEquals("im_group_chat", MDC.get(MdcConstants.BUSINESS_DOMAIN));
    }

    @Test
    void putBusinessDomain_nullRemoves() {
        MDC.put(MdcConstants.BUSINESS_DOMAIN, "im_group_chat");
        MdcHelper.putBusinessDomain(null);
        assertNull(MDC.get(MdcConstants.BUSINESS_DOMAIN));
    }

    @Test
    void putBusinessDomain_blankRemoves() {
        MDC.put(MdcConstants.BUSINESS_DOMAIN, "im_group_chat");
        MdcHelper.putBusinessDomain("  ");
        assertNull(MDC.get(MdcConstants.BUSINESS_DOMAIN));
    }

    @Test
    void clearAll_clearsBusinessDomain() {
        MdcHelper.putBusinessDomain("gateway_ws_invoke");
        MdcHelper.clearAll();
        assertNull(MDC.get(MdcConstants.BUSINESS_DOMAIN));
    }

    @Test
    void businessDomainIsInAllKeys() {
        assertTrue(MdcConstants.ALL_KEYS.contains(MdcConstants.BUSINESS_DOMAIN));
    }
}