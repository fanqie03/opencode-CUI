package com.opencode.cui.gateway.service;

import com.opencode.cui.gateway.config.SnowflakeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SnowflakeIdGenerator 单元测试：验证 ID 布局、服务编码嵌入和参数校验。 */
class SnowflakeIdGeneratorTest {

    @Test
    @DisplayName("same layout produces positive long ids and embeds service code")
    void generateIdWithExpectedLayout() {
        SnowflakeProperties properties = new SnowflakeProperties();
        properties.validateLayout();
        TestSnowflakeIdGenerator generator = new TestSnowflakeIdGenerator(properties, 1735689601000L);

        long id = generator.nextId();

        assertTrue(id > 0);
        assertEquals(properties.getServiceCode(),
                extractServiceCode(id, properties));
        assertEquals(properties.getWorkerId(),
                extractWorkerId(id, properties));
    }

    @Test
    @DisplayName("different service codes do not collide under same timestamp")
    void differentServiceCodesDoNotCollide() {
        SnowflakeProperties gatewayProps = new SnowflakeProperties();
        gatewayProps.setServiceCode(2L);
        gatewayProps.validateLayout();
        SnowflakeProperties otherProps = new SnowflakeProperties();
        otherProps.setServiceCode(3L);
        otherProps.validateLayout();

        long now = 1735689602000L;
        long gatewayId = new TestSnowflakeIdGenerator(gatewayProps, now).nextId();
        long otherId = new TestSnowflakeIdGenerator(otherProps, now).nextId();

        assertNotEquals(gatewayId, otherId);
    }

    @Test
    @DisplayName("rejects invalid worker id outside configured bit range")
    void rejectsInvalidWorkerId() {
        SnowflakeProperties properties = new SnowflakeProperties();
        properties.setWorkerId(2048L);

        assertThrows(IllegalStateException.class, properties::validateLayout);
    }

    private long extractServiceCode(long id, SnowflakeProperties properties) {
        long mask = (1L << properties.getServiceBits()) - 1;
        return (id >> properties.serviceCodeShift()) & mask;
    }

    private long extractWorkerId(long id, SnowflakeProperties properties) {
        long mask = (1L << properties.getWorkerBits()) - 1;
        return (id >> properties.workerShift()) & mask;
    }

    private static final class TestSnowflakeIdGenerator extends SnowflakeIdGenerator {
        private final long fixedNow;

        private TestSnowflakeIdGenerator(SnowflakeProperties properties, long fixedNow) {
            super(properties);
            this.fixedNow = fixedNow;
        }

        @Override
        protected long currentTimeMillis() {
            return fixedNow;
        }
    }
}
