package com.opencode.cui.skill.service.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AsyncTask;
import com.opencode.cui.skill.model.enums.AsyncTaskStatus;
import com.opencode.cui.skill.model.enums.AsyncTaskType;
import com.opencode.cui.skill.repository.AsyncTaskRepository;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * 删除会话关联消息/分片的异步任务处理器。
 *
 * <p>执行流程：
 * <ol>
 *   <li>分布式锁（Redis SET NX），防止多实例并发执行同一任务</li>
 *   <li>乐观锁抢占 PENDING → PROCESSING</li>
 *   <li>分批删除 skill_message_part（LIMIT 1000）</li>
 *   <li>删除 skill_message</li>
 *   <li>更新 COMPLETED + 释放锁</li>
 * </ol>
 */
@Slf4j
@Component
public class DeleteSessionMessagesTaskProcessor implements TaskProcessor {

    private static final long LOCK_WAIT_SECONDS = 0;
    private static final String LOCK_KEY_PREFIX = "ss:async-task-lock:";

    private final AsyncTaskRepository taskRepository;
    private final SkillMessagePartRepository partRepository;
    private final SkillMessageRepository messageRepository;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final TaskLeaseManager leaseManager;
    private final int batchDeleteLimit;
    private final int maxRetry;

    public DeleteSessionMessagesTaskProcessor(
            AsyncTaskRepository taskRepository,
            SkillMessagePartRepository partRepository,
            SkillMessageRepository messageRepository,
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            TaskLeaseManager leaseManager,
            @Value("${skill.session.cleanup.async-task-batch-delete-limit:1000}") int batchDeleteLimit,
            @Value("${skill.session.cleanup.async-task-max-retry:3}") int maxRetry) {
        this.taskRepository = taskRepository;
        this.partRepository = partRepository;
        this.messageRepository = messageRepository;
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.leaseManager = leaseManager;
        this.batchDeleteLimit = batchDeleteLimit;
        this.maxRetry = maxRetry;
    }

    @Override
    public AsyncTaskType getTaskType() {
        return AsyncTaskType.DELETE_SESSION_MESSAGES;
    }

    @Override
    @Transactional
    public void process(AsyncTask task) {
        String lockKey = LOCK_KEY_PREFIX + task.getId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.debug("Distributed lock not acquired, another instance is processing: taskId={}", task.getId());
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring distributed lock: taskId={}", task.getId());
            return;
        }

        try {
            // 乐观锁抢占 PENDING → PROCESSING
            int claimed = taskRepository.updateStatusCas(task.getId(),
                    AsyncTaskStatus.PROCESSING.name(), AsyncTaskStatus.PENDING.name());
            if (claimed == 0) {
                log.debug("Task already claimed: taskId={}, status={}", task.getId(), task.getStatus());
                return;
            }

            Long sessionId = parseSessionId(task.getPayload());
            if (sessionId == null) {
                taskRepository.updateStatusAndError(task.getId(),
                        AsyncTaskStatus.FAILED.name(), "Invalid payload: " + task.getPayload());
                return;
            }

            log.info("Starting DELETE_SESSION_MESSAGES: taskId={}, sessionId={}", task.getId(), sessionId);

            // 分批删除 skill_message_part
            int totalPartsDeleted = 0;
            while (true) {
                if (leaseManager.isCancelled(task.getId())) {
                    log.warn("Task cancelled during part delete: taskId={}", task.getId());
                    return;
                }
                int deleted = partRepository.deleteBySessionId(sessionId, batchDeleteLimit);
                totalPartsDeleted += deleted;
                if (deleted < batchDeleteLimit) {
                    break;
                }
            }
            log.info("Deleted {} skill_message_part rows for sessionId={}", totalPartsDeleted, sessionId);

            // 分批删除 skill_message
            int messagesDeleted = 0;
            while (true) {
                if (leaseManager.isCancelled(task.getId())) {
                    log.warn("Task cancelled during message delete: taskId={}", task.getId());
                    return;
                }
                int deleted = messageRepository.deleteBySessionId(sessionId, batchDeleteLimit);
                messagesDeleted += deleted;
                if (deleted < batchDeleteLimit) {
                    break;
                }
            }
            log.info("Deleted {} skill_message rows for sessionId={}", messagesDeleted, sessionId);

            taskRepository.updateStatusCas(task.getId(),
                    AsyncTaskStatus.COMPLETED.name(), AsyncTaskStatus.PROCESSING.name());
            log.info("Completed DELETE_SESSION_MESSAGES: taskId={}, sessionId={}, partsDeleted={}, messagesDeleted={}",
                    task.getId(), sessionId, totalPartsDeleted, messagesDeleted);
        } catch (Exception e) {
            log.error("DELETE_SESSION_MESSAGES failed: taskId={}, error={}", task.getId(), e.getMessage(), e);
            handleFailure(task, e);
        } finally {
            leaseManager.clearCancel(task.getId());
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception ex) {
                    log.warn("Failed to release distributed lock: key={}", lockKey);
                }
            }
        }
    }

    private void handleFailure(AsyncTask task, Exception e) {
        int affected = taskRepository.incrementRetryAndReset(task.getId(), maxRetry);
        if (affected > 0) {
            log.info("Async task retry scheduled: id={}, retryCount={}", task.getId(), task.getRetryCount() + 1);
        } else {
            taskRepository.updateStatusAndError(task.getId(),
                    AsyncTaskStatus.FAILED.name(),
                    truncateError(e.getMessage()));
            log.warn("Async task failed permanently: id={}, retryCount={}", task.getId(), task.getRetryCount());
        }
    }

    private Long parseSessionId(String payload) {
        try {
            return objectMapper.readTree(payload).path("sessionId").asLong();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse async task payload: {}", payload, e);
            return null;
        }
    }

    private static String truncateError(String msg) {
        if (msg == null) return null;
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
