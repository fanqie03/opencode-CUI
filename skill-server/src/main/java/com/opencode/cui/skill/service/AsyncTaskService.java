package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.AsyncTask;
import com.opencode.cui.skill.model.enums.AsyncTaskStatus;
import com.opencode.cui.skill.model.enums.AsyncTaskType;
import com.opencode.cui.skill.repository.AsyncTaskRepository;
import com.opencode.cui.skill.service.task.AsyncTaskContainer;
import com.opencode.cui.skill.service.task.TaskLeaseManager;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 异步任务服务。
 * 管理后台异步任务的创建和定时扫描调度。
 *
 * <p>创建任务仅登记到 DB，不立即执行。定时扫描 PENDING 任务后通过
 * {@link AsyncTaskContainer} 按策略模式路由到对应 {@code TaskProcessor}，
 * 每个处理器内部自行获取分布式锁防止并发重复执行。
 */
@Slf4j
@Service
public class AsyncTaskService {

    private static final int PENDING_FETCH_LIMIT = 10;
    private static final String STALE_RECOVERY_LOCK_KEY = "ss:async-task-stale-recovery-lock";

    private final AsyncTaskRepository taskRepository;
    private final AsyncTaskContainer taskContainer;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Executor asyncTaskExecutor;
    private final RedissonClient redissonClient;
    private final TaskLeaseManager leaseManager;
    private final int staleTimeoutMinutes;
    private final int maxRetry;

    public AsyncTaskService(
            AsyncTaskRepository taskRepository,
            AsyncTaskContainer taskContainer,
            SnowflakeIdGenerator snowflakeIdGenerator,
            @Qualifier("asyncTaskExecutor") Executor asyncTaskExecutor,
            RedissonClient redissonClient,
            TaskLeaseManager leaseManager,
            @Value("${skill.session.cleanup.async-task-stale-timeout-minutes:10}") int staleTimeoutMinutes,
            @Value("${skill.session.cleanup.async-task-max-retry:3}") int maxRetry) {
        this.taskRepository = taskRepository;
        this.taskContainer = taskContainer;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.asyncTaskExecutor = asyncTaskExecutor;
        this.redissonClient = redissonClient;
        this.leaseManager = leaseManager;
        this.staleTimeoutMinutes = staleTimeoutMinutes;
        this.maxRetry = maxRetry;
    }

    /**
     * 创建异步任务（仅登记，不立即执行）。
     */
    @Transactional
    public AsyncTask createTask(AsyncTaskType taskType, String payload) {
        AsyncTask task = AsyncTask.builder()
                .id(snowflakeIdGenerator.nextId())
                .taskType(taskType)
                .payload(payload)
                .status(AsyncTaskStatus.PENDING)
                .retryCount(0)
                .build();
        taskRepository.insert(task);
        log.info("Created async task: id={}, type={}", task.getId(), taskType);
        return task;
    }

    /**
     * 高频回收超时的 PROCESSING 任务。
     * 先通过 Redis 发送取消信号中断执行中的线程，再更新 DB 状态。
     */
    @Scheduled(fixedDelayString = "${skill.session.cleanup.async-task-stale-recovery-interval-ms:300000}")
    public void recoverStaleProcessingTasks() {
        RLock lock = redissonClient.getLock(STALE_RECOVERY_LOCK_KEY);
        try {
            if (!lock.tryLock(0, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            List<AsyncTask> staleTasks = taskRepository.findByStatus(
                    AsyncTaskStatus.PROCESSING.name(), PENDING_FETCH_LIMIT);
            if (staleTasks.isEmpty()) {
                return;
            }
            for (AsyncTask task : staleTasks) {
                if (task.getUpdatedAt() == null) {
                    continue;
                }
                long runningMinutes = java.time.Duration.between(
                        task.getUpdatedAt(), java.time.LocalDateTime.now()).toMinutes();
                if (runningMinutes < staleTimeoutMinutes) {
                    continue;
                }
                leaseManager.signalCancel(task.getId());
                int reset = taskRepository.incrementRetryAndReset(task.getId(), maxRetry);
                if (reset > 0) {
                    log.warn("Stale task reset to PENDING: taskId={}, runningMinutes={}", task.getId(), runningMinutes);
                } else {
                    taskRepository.updateStatusAndError(task.getId(),
                            AsyncTaskStatus.FAILED.name(), "Task processing timeout");
                    log.warn("Stale task marked FAILED: taskId={}, runningMinutes={}", task.getId(), runningMinutes);
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("Failed to release stale recovery lock", e);
                }
            }
        }
    }

    /**
     * 定时扫描 PENDING 任务，多线程分发到 AsyncTaskContainer。
     */
    @Scheduled(cron = "${skill.session.cleanup.async-task-cron:0 0 2 * * ?}")
    public void processPendingTasks() {
        List<AsyncTask> pendingTasks = taskRepository.findByStatus(
                AsyncTaskStatus.PENDING.name(), PENDING_FETCH_LIMIT);
        if (pendingTasks.isEmpty()) {
            return;
        }
        log.info("Scheduled scan found {} PENDING async tasks", pendingTasks.size());
        for (AsyncTask task : pendingTasks) {
            asyncTaskExecutor.execute(() -> {
                try {
                    taskContainer.process(task);
                } catch (Exception e) {
                    log.error("Async task dispatch failed: id={}", task.getId(), e);
                }
            });
        }
    }
}
