package com.opencode.cui.skill.service.sync;

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

    public CompositeMultiDeviceSyncService(List<MultiDeviceSyncService> services) {
        this.registry = services.stream()
                .filter(s -> !(s instanceof CompositeMultiDeviceSyncService))
                .collect(Collectors.toMap(
                        MultiDeviceSyncService::getSyncMode,
                        Function.identity()));
        log.info("CompositeMultiDeviceSyncService registered {} implementations: {}",
                registry.size(), registry.keySet());
    }

    @Override
    public SyncMode getSyncMode() {
        throw new UnsupportedOperationException("Composite does not have a single sync mode");
    }

    @Override
    public void push(SyncRequest request) {
        MultiDeviceSyncService svc = registry.get(request.syncMode());
        if (svc == null) {
            log.warn("No sync service registered for mode: {}", request.syncMode());
            return;
        }
        svc.push(request);
    }
}
