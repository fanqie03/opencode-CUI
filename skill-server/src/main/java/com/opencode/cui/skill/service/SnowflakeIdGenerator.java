package com.opencode.cui.skill.service;

import com.opencode.cui.skill.config.SnowflakeProperties;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdGenerator {

    private final SnowflakeProperties properties;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(SnowflakeProperties properties) {
        this.properties = properties;
    }

    // TODO: Consider ThreadLocal sharding for high-QPS scenarios (current
    // synchronized is fine for < 10K/s)
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
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }
}
