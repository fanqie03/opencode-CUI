package com.opencode.cui.skill.service.task;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 异步任务租约/取消信号管理器。
 * 基于 Redis 实现跨 JVM 的任务中断信号，供所有 TaskProcessor 共享使用。
 *
 * <p>超时回收流程：
 * <ol>
 *   <li>调度器发现 PROCESSING 超时任务，调用 {@link #signalCancel(long)} 设置取消标记</li>
 *   <li>Processor 在长循环中通过 {@link #isCancelled(long)} 检测信号</li>
 *   <li>Processor 检测到取消后自行终止并清理，调度器负责重置 DB 状态</li>
 *   <li>任务结束后（正常/取消），由 Processor 调用 {@link #clearCancel(long)} 清理</li>
 * </ol>
 */
@Slf4j
@Component
public class TaskLeaseManager {

    private static final String CANCEL_KEY_PREFIX = "ss:async-task-cancel:";
    private static final Duration CANCEL_SIGNAL_TTL = Duration.ofMinutes(5);

    private final RedissonClient redissonClient;

    public TaskLeaseManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 向指定任务发出取消信号 */
    public void signalCancel(long taskId) {
        redissonClient.getBucket(CANCEL_KEY_PREFIX + taskId)
                .set("1", CANCEL_SIGNAL_TTL);
        log.info("Cancel signal sent: taskId={}", taskId);
    }

    /** 检查当前任务是否已被取消 */
    public boolean isCancelled(long taskId) {
        return redissonClient.getBucket(CANCEL_KEY_PREFIX + taskId).isExists();
    }

    /** 清理取消信号（任务正常结束或被取消后调用） */
    public void clearCancel(long taskId) {
        redissonClient.getKeys().delete(CANCEL_KEY_PREFIX + taskId);
    }
}
