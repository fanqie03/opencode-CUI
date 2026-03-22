package com.opencode.cui.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 雪花算法配置属性。
 * 通过 {@code gateway.snowflake.*} 配置前缀绑定，控制 ID 生成器的位分配和容错策略。
 *
 * <p>
 * ID 结构（默认布局）：
 * {@code [1位符号] [时间戳] [4位服务码] [10位工作节点] [12位序列号]}
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.snowflake")
public class SnowflakeProperties {

    /** 纪元起始时间戳（毫秒），默认 2025-01-01 00:00:00 UTC */
    private long epochMs = 1735689600000L;

    /** 服务编码，用于区分不同服务 */
    private long serviceCode = 2L;

    /** 工作节点 ID */
    private long workerId = 1L;

    /** 服务编码占用位数 */
    private int serviceBits = 4;

    /** 工作节点 ID 占用位数 */
    private int workerBits = 10;

    /** 序列号占用位数 */
    private int sequenceBits = 12;

    /** 时钟回拨处理策略 */
    private ClockBackwardsStrategy clockBackwardsStrategy = ClockBackwardsStrategy.WAIT;

    /** 允许的最大时钟回拨时间（毫秒） */
    private long maxBackwardMs = 5L;

    /**
     * 启动时校验配置合法性。
     * 检查位分配不超过 long 容量，serviceCode 和 workerId 不超过各自位数上限。
     */
    @PostConstruct
    public void validateLayout() {
        if (epochMs < 0 || serviceCode < 0 || workerId < 0 || maxBackwardMs < 0) {
            throw new IllegalStateException("Snowflake numeric properties must be non-negative");
        }
        if (serviceBits <= 0 || workerBits <= 0 || sequenceBits <= 0) {
            throw new IllegalStateException("Snowflake bit allocation must be positive");
        }
        int allocatedBits = serviceBits + workerBits + sequenceBits;
        if (allocatedBits >= Long.SIZE - 1) {
            throw new IllegalStateException("Snowflake bit allocation exceeds signed long capacity");
        }
        long maxServiceCode = maxValueForBits(serviceBits);
        if (serviceCode > maxServiceCode) {
            throw new IllegalStateException("gateway.snowflake.service-code exceeds " + maxServiceCode);
        }
        long maxWorkerId = maxValueForBits(workerBits);
        if (workerId > maxWorkerId) {
            throw new IllegalStateException("gateway.snowflake.worker-id exceeds " + maxWorkerId);
        }
    }

    /** 计算序列号最大值 */
    public long maxSequence() {
        return maxValueForBits(sequenceBits);
    }

    /** 计算时间戳左移位数 */
    public long timestampShift() {
        return serviceBits + workerBits + sequenceBits;
    }

    /** 计算服务编码左移位数 */
    public long serviceCodeShift() {
        return workerBits + sequenceBits;
    }

    /** 计算工作节点 ID 左移位数 */
    public long workerShift() {
        return sequenceBits;
    }

    /** 计算指定位数能表示的最大值 */
    private long maxValueForBits(int bits) {
        return (1L << bits) - 1;
    }

    /**
     * 时钟回拨处理策略枚举。
     */
    public enum ClockBackwardsStrategy {
        /** 等待策略：等待时钟追上后继续 */
        WAIT,
        /** 拒绝策略：直接抛出异常 */
        REJECT
    }
}
