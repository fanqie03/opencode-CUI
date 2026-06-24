package com.opencode.cui.skill.service.sync;

import com.opencode.cui.skill.config.MultiSyncProperties;
import com.opencode.cui.skill.model.SyncMode;
import com.opencode.cui.skill.model.SyncRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Primary
@Component
public class CompositeMultiDeviceSyncService implements MultiDeviceSyncService {

    private final Map<SyncMode, MultiDeviceSyncService> registry;
    private final SyncMode defaultMode;

    public CompositeMultiDeviceSyncService(List<MultiDeviceSyncService> services,
            MultiSyncProperties properties) {
        this.registry = services.stream()
                .filter(s -> !(s instanceof CompositeMultiDeviceSyncService))
                .collect(Collectors.toMap(
                        MultiDeviceSyncService::getSyncMode,
                        Function.identity()));
        this.defaultMode = SyncMode.valueOf(properties.getMode().toUpperCase());
        log.info("CompositeMultiDeviceSyncService registered {} implementations: {}, defaultMode={}",
                registry.size(), registry.keySet(), defaultMode);
    }

    @Override
    public SyncMode getSyncMode() {
        throw new UnsupportedOperationException("Composite does not have a single sync mode");
    }

    @Override
    public void push(SyncRequest request) {
        MultiDeviceSyncService svc = registry.get(request.syncMode());
        if (svc == null) {
            log.warn("No sync service registered for mode: {}, falling back to default: {}",
                    request.syncMode(), defaultMode);
            svc = registry.get(defaultMode);
        }
        if (svc == null) {
            log.warn("No sync service registered for default mode: {}", defaultMode);
            return;
        }
        svc.push(request);
    }
}
