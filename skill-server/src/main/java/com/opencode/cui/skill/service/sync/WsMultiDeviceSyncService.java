package com.opencode.cui.skill.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SyncMode;
import com.opencode.cui.skill.model.SyncRequest;
import com.opencode.cui.skill.service.RedisMessageBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WsMultiDeviceSyncService implements MultiDeviceSyncService {

    private final RedisMessageBroker broker;
    private final ObjectMapper objectMapper;

    public WsMultiDeviceSyncService(RedisMessageBroker broker, ObjectMapper objectMapper) {
        this.broker = broker;
        this.objectMapper = objectMapper;
    }

    @Override
    public SyncMode getSyncMode() {
        return SyncMode.WS;
    }

    @Override
    public void push(SyncRequest request) {
        if (request.targetAccount() == null || request.targetAccount().isBlank()) {
            log.warn("WsMultiDeviceSyncService push skipped: targetAccount is null or blank, type={}",
                    request.syncType().getType());
            return;
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", request.syncType().getType());

        Object sessionId = request.syncContent().get("welinkSessionId");
        if (sessionId != null) {
            envelope.put("sessionId", sessionId.toString());
        }

        envelope.set("content", objectMapper.valueToTree(request.syncContent()));
        String message = envelope.toString();

        log.info("WsMultiDeviceSyncService push: type={}, targetAccount={}",
                request.syncType().getType(), request.targetAccount());
        broker.publishToUser(request.targetAccount(), message);
    }
}
