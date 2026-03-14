package com.opencode.cui.gateway.service;

import com.opencode.cui.gateway.config.SnowflakeProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.LockSupport;

@Component
public class SnowflakeIdGenerator {

    /** 忙等待期间 park 间隔：100 微秒 */
    private static final long PARK_NANOS = 100_000L;

    private final SnowflakeProperties properties;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(SnowflakeProperties properties) {
        this.properties = properties;
    }

    public synchronized long nextId() {
        long timestamp = currentTimeMillis();
        if (timestamp < lastTimestamp) {
            timestamp = handleClockBackwards(timestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & properties.maxSequence();
            if (sequence == 0) {
                timestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;
        return composeId(timestamp, sequence);
    }

    long composeId(long timestamp, long currentSequence) {
        long timestampPart = (timestamp - properties.getEpochMs()) << properties.timestampShift();
        long servicePart = properties.getServiceCode() << properties.serviceCodeShift();
        long workerPart = properties.getWorkerId() << properties.workerShift();
        return timestampPart | servicePart | workerPart | currentSequence;
    }

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    private long handleClockBackwards(long timestamp) {
        long diff = lastTimestamp - timestamp;
        if (properties.getClockBackwardsStrategy() == SnowflakeProperties.ClockBackwardsStrategy.REJECT
                || diff > properties.getMaxBackwardMs()) {
            throw new IllegalStateException("Clock moved backwards by " + diff + "ms");
        }
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

    private long waitUntilNextMillis(long currentTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= currentTimestamp) {
            LockSupport.parkNanos(PARK_NANOS);
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }
}
