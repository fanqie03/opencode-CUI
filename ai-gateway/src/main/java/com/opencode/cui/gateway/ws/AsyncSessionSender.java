package com.opencode.cui.gateway.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncSessionSender {

    private static final Logger log = LoggerFactory.getLogger(AsyncSessionSender.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 10000;

    private final WebSocketSession session;
    private final BlockingQueue<TextMessage> queue;
    private final Thread senderThread;
    private final Runnable failureCallback;
    private final AtomicBoolean failureNotified = new AtomicBoolean(false);
    private volatile boolean running = true;

    public AsyncSessionSender(WebSocketSession session) {
        this(session, DEFAULT_QUEUE_CAPACITY, null);
    }

    public AsyncSessionSender(WebSocketSession session, Runnable failureCallback) {
        this(session, DEFAULT_QUEUE_CAPACITY, failureCallback);
    }

    public AsyncSessionSender(WebSocketSession session, int queueCapacity) {
        this(session, queueCapacity, null);
    }

    public AsyncSessionSender(WebSocketSession session, int queueCapacity, Runnable failureCallback) {
        this.session = session;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.failureCallback = failureCallback;
        this.senderThread = new Thread(this::sendLoop, "ws-sender-" + session.getId());
        this.senderThread.setDaemon(true);
    }

    /**
     * 启动发送线程。必须在对象完全构造之后调用。
     */
    public void start() {
        this.senderThread.start();
    }

    public boolean enqueue(TextMessage message) {
        if (!running) {
            log.warn("[AsyncSender] Sender not running, rejecting message: linkId={}", session.getId());
            return false;
        }
        boolean offered = queue.offer(message);
        if (!offered) {
            log.warn("[AsyncSender] Queue full, dropping message: linkId={}, queueSize={}",
                    session.getId(), queue.size());
        }
        return offered;
    }

    public boolean isRunning() {
        return running;
    }

    public void shutdown() {
        running = false;
        senderThread.interrupt();
        try {
            senderThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int pendingCount() {
        return queue.size();
    }

    private void sendLoop() {
        while (running) {
            try {
                TextMessage msg = queue.poll(1, TimeUnit.SECONDS);
                if (msg == null) {
                    continue;
                }
                session.sendMessage(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                log.error("[AsyncSender] Send failed: linkId={}, remaining={}",
                        session.getId(), queue.size(), e);
                running = false;
                notifyFailure();
                break;
            }
        }
        log.info("[AsyncSender] Sender thread stopped: linkId={}, droppedMessages={}",
                session.getId(), queue.size());
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
