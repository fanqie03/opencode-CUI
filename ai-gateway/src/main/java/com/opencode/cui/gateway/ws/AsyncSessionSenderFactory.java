package com.opencode.cui.gateway.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class AsyncSessionSenderFactory {

    private static final Logger log = LoggerFactory.getLogger(AsyncSessionSenderFactory.class);
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
        return getOrCreate(session, AsyncSenderIdentity.unknown(), null);
    }

    public AsyncSessionSender getOrCreate(WebSocketSession session, Runnable onSenderFailure) {
        return getOrCreate(session, AsyncSenderIdentity.unknown(), onSenderFailure);
    }

    public AsyncSessionSender getOrCreate(WebSocketSession session, AsyncSenderIdentity identity) {
        return getOrCreate(session, identity, null);
    }

    public AsyncSessionSender getOrCreate(WebSocketSession session, AsyncSenderIdentity identity,
            Runnable onSenderFailure) {
        String sessionId = session.getId();
        AsyncSenderIdentity requestedIdentity = AsyncSenderIdentity.orUnknown(identity);
        SenderEntry entry = senders.compute(sessionId, (linkId, existing) -> {
            Runnable failureHandler = onSenderFailure != null
                    ? onSenderFailure
                    : existing == null ? null : existing.onSenderFailure();
            if (existing != null && existing.sender().isRunning() && session.isOpen()) {
                logIdentityMismatch(linkId, existing.sender().identity(), requestedIdentity);
                return new SenderEntry(existing.sender(), failureHandler);
            }
            if (existing != null) {
                existing.sender().shutdown();
            }
            AsyncSessionSender sender = new AsyncSessionSender(session, queueCapacity,
                    () -> handleSenderFailure(linkId), requestedIdentity);
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

    private void logIdentityMismatch(String linkId, AsyncSenderIdentity existingIdentity,
            AsyncSenderIdentity requestedIdentity) {
        if (existingIdentity.equals(requestedIdentity)) {
            return;
        }
        log.warn("[AsyncSender] Sender identity mismatch on existing link: linkId={}, existingChannel={}, existingPeerType={}, existingPeerId={}, requestedChannel={}, requestedPeerType={}, requestedPeerId={}",
                linkId,
                existingIdentity.channel(), existingIdentity.peerType(), existingIdentity.peerId(),
                requestedIdentity.channel(), requestedIdentity.peerType(), requestedIdentity.peerId());
    }

    private record SenderEntry(AsyncSessionSender sender, Runnable onSenderFailure) {
    }
}
