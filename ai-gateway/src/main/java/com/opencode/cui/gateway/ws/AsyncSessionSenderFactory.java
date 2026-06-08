package com.opencode.cui.gateway.ws;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class AsyncSessionSenderFactory {

    private static final int DEFAULT_QUEUE_CAPACITY = 10000;

    private final int queueCapacity;
    private final ConcurrentHashMap<String, SenderEntry> senders = new ConcurrentHashMap<>();

    @Autowired
    public AsyncSessionSenderFactory(
            @Value("${gateway.async-sender.queue-capacity:10000}") int queueCapacity) {
        this.queueCapacity = Math.max(1, queueCapacity);
    }

    public static AsyncSessionSenderFactory defaultFactory() {
        return new AsyncSessionSenderFactory(DEFAULT_QUEUE_CAPACITY);
    }

    public AsyncSessionSender getOrCreate(WebSocketSession session) {
        return getOrCreate(session, null);
    }

    public AsyncSessionSender getOrCreate(WebSocketSession session, Runnable onSenderFailure) {
        String sessionId = session.getId();
        SenderEntry entry = senders.compute(sessionId, (linkId, existing) -> {
            Runnable failureHandler = onSenderFailure != null
                    ? onSenderFailure
                    : existing == null ? null : existing.onSenderFailure();
            if (existing != null && existing.sender().isRunning() && session.isOpen()) {
                return new SenderEntry(existing.sender(), failureHandler);
            }
            if (existing != null) {
                existing.sender().shutdown();
            }
            AsyncSessionSender sender = new AsyncSessionSender(session, queueCapacity,
                    () -> handleSenderFailure(linkId));
            sender.start();
            return new SenderEntry(sender, failureHandler);
        });
        return entry.sender();
    }

    public AsyncSessionSender get(String sessionId) {
        SenderEntry entry = senders.get(sessionId);
        return entry == null ? null : entry.sender();
    }

    public void remove(String sessionId) {
        remove(sessionId, true);
    }

    public void remove(String sessionId, boolean shutdown) {
        SenderEntry entry = senders.remove(sessionId);
        if (entry != null && shutdown) {
            entry.sender().shutdown();
        }
    }

    private void handleSenderFailure(String sessionId) {
        SenderEntry entry = senders.remove(sessionId);
        if (entry != null && entry.onSenderFailure() != null) {
            entry.onSenderFailure().run();
        }
    }

    private record SenderEntry(AsyncSessionSender sender, Runnable onSenderFailure) {
    }
}
