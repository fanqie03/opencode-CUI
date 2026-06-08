package com.opencode.cui.gateway.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncSessionSender {

    private static final Logger log = LoggerFactory.getLogger(AsyncSessionSender.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 10000;

    private final WebSocketSession session;
    private final BlockingQueue<TextMessage> queue;
    private final Runnable failureCallback;
    private final Thread senderThread;
    private final AtomicBoolean failureNotified = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);

    AsyncSessionSender(WebSocketSession session) {
        this(session, DEFAULT_QUEUE_CAPACITY, null);
    }

    AsyncSessionSender(WebSocketSession session, Runnable failureCallback) {
        this(session, DEFAULT_QUEUE_CAPACITY, failureCallback);
    }

    AsyncSessionSender(WebSocketSession session, int queueCapacity) {
        this(session, queueCapacity, null);
    }

    AsyncSessionSender(WebSocketSession session, int queueCapacity, Runnable failureCallback) {
        this.session = session;
        this.queue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
        this.failureCallback = failureCallback;
        this.senderThread = new Thread(this::sendLoop, "ws-sender-" + session.getId());
        this.senderThread.setDaemon(true);
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            senderThread.start();
        }
    }

    public boolean enqueue(TextMessage message) {
        if (!running.get()) {
            log.error("[AsyncSender] Sender not running, rejecting message: linkId={}, pending={}",
                    session.getId(), queue.size());
            return false;
        }
        boolean offered = queue.offer(message);
        if (!offered) {
            log.error("[AsyncSender] Queue full, dropping message: linkId={}, pending={}",
                    session.getId(), queue.size());
            return false;
        }
        log.debug("[AsyncSender] Message queued: linkId={}, pending={}", session.getId(), queue.size());
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void shutdown() {
        running.set(false);
        int dropped = queue.size();
        queue.clear();
        senderThread.interrupt();
        log.info("[AsyncSender] Sender stopped: linkId={}, droppedMessages={}", session.getId(), dropped);
    }

    public int pendingCount() {
        return queue.size();
    }

    private void sendLoop() {
        try {
            while (running.get()) {
                TextMessage msg = queue.take();
                if (!session.isOpen()) {
                    running.set(false);
                    log.error("[AsyncSender] Session closed before send: linkId={}, remaining={}",
                            session.getId(), queue.size() + 1);
                    notifyFailure();
                    break;
                }
                session.sendMessage(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[AsyncSender] Send failed: linkId={}, remaining={}",
                    session.getId(), queue.size(), e);
            running.set(false);
            notifyFailure();
        } finally {
            running.set(false);
        }
    }

    private void notifyFailure() {
        if (failureCallback != null && failureNotified.compareAndSet(false, true)) {
            try {
                failureCallback.run();
            } catch (Exception e) {
                log.warn("[AsyncSender] Failure callback failed: linkId={}, error={}",
                        session.getId(), e.getMessage(), e);
            }
        }
    }
}
