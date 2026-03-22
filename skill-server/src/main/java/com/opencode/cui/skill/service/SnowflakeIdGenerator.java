package com.opencode.cui.skill.service;

import com.opencode.cui.skill.config.SnowflakeProperties;
import org.springframework.stereotype.Component;

/**
 * 雪花算法 ID 生成器（Skill Server 版本）。
 * 基于 {@link SnowflakeProperties} 配置的位分配，生成全局唯一且单调递增的 64 位 ID。
 *
 * <p>
 * 线程安全：通过 synchronized 保证同一实例内的 ID 唯一性。
 * </p>
 */
@Component
public class SnowflakeIdGenerator {

    private final SnowflakeProperties properties;
    /** 上次生成 ID 的时间戳 */
    private long lastTimestamp = -1L;
    /** 同一毫秒内的序列号 */
    private long sequence = 0L;

    public SnowflakeIdGenerator(SnowflakeProperties properties) {
        this.properties = properties;
    }

    /**
     * 生成下一个全局唯一 ID。
     * 线程安全，单实例内保证唯一性和递增性。
     *
     * TODO: 高 QPS 场景考虑 ThreadLocal 分片（当前 synchronized 适用于 < 10K/s）
     */
    public synchronized long nextId() {
        long timestamp = currentTimeMillis();
        if (timestamp < lastTimestamp) {
            timestamp = handleClockBackwards(timestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & properties.maxSequence();
            if (sequence == 0) {
                // 同一毫秒内序列号溢出，等待下一毫秒
                timestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;
        return composeId(timestamp, sequence);
    }

    /** 将时间戳、服务码、工作节点 ID、序列号组合为 64 位 ID。 */
    long composeId(long timestamp, long currentSequence) {
        long timestampPart = (timestamp - properties.getEpochMs()) << properties.timestampShift();
        long servicePart = properties.getServiceCode() << properties.serviceCodeShift();
        long workerPart = properties.getWorkerId() << properties.workerShift();
        return timestampPart | servicePart | workerPart | currentSequence;
    }

    /** 获取当前时间戳（毫秒），子类可覆写用于测试。 */
    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 处理时钟回退。
     * 根据配置策略选择等待恢复或直接拒绝。
     */
    private long handleClockBackwards(long timestamp) {
        long diff = lastTimestamp - timestamp;
        if (properties.getClockBackwardsStrategy() == SnowflakeProperties.ClockBackwardsStrategy.REJECT
                || diff > properties.getMaxBackwardMs()) {
            throw new IllegalStateException("Clock moved backwards by " + diff + "ms");
        }
        // WAIT 策略：休眠等待时钟恢复
        try {
            Thread.sleep(diff);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for clock recovery", ex);
        }
        long recovered = currentTimeMillis();
        if (recovered < lastTimestamp) {
            throw new IllegalStateException("Clock did not recover after waiting");
        }
        return recovered;
    }

    /** 自旋等待直到下一毫秒。 */
    private long waitUntilNextMillis(long currentTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= currentTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }
}
