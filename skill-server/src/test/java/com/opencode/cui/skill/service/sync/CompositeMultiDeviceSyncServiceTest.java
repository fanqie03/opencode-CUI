package com.opencode.cui.skill.service.sync;

import com.opencode.cui.skill.config.MultiSyncProperties;
import com.opencode.cui.skill.model.SyncMode;
import com.opencode.cui.skill.model.SyncRequest;
import com.opencode.cui.skill.model.SyncType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeMultiDeviceSyncServiceTest {

    private CompositeMultiDeviceSyncService composite;
    private StubSyncService wsService;
    private StubSyncService imService;

    @BeforeEach
    void setUp() {
        wsService = new StubSyncService(SyncMode.WS);
        imService = new StubSyncService(SyncMode.IM);
        MultiSyncProperties props = new MultiSyncProperties();
        props.setMode("ws");
        composite = new CompositeMultiDeviceSyncService(List.of(wsService, imService), props);
    }

    @Test
    @DisplayName("routes WS mode to WS implementation")
    void routesWsMode() {
        SyncRequest request = new SyncRequest(SyncMode.WS, SyncType.SESSION_UNREAD,
                Map.of("welinkSessionId", "123"), "user-1");
        composite.push(request);

        assertTrue(wsService.wasCalled(), "WS implementation should have been called");
        assertEquals(1, wsService.callCount());
        assertEquals(0, imService.callCount(), "IM implementation should NOT have been called");
    }

    @Test
    @DisplayName("routes IM mode to IM implementation")
    void routesImMode() {
        SyncRequest request = new SyncRequest(SyncMode.IM, SyncType.SESSION_UNREAD,
                Map.of("welinkSessionId", "123"), "user-1");
        composite.push(request);

        assertTrue(imService.wasCalled(), "IM implementation should have been called");
        assertEquals(1, imService.callCount());
        assertEquals(0, wsService.callCount(), "WS implementation should NOT have been called");
    }

    @Test
    @DisplayName("unknown mode does not throw")
    void unknownModeDoesNotThrow() {
        // Register only WS; IM request falls back to default WS mode
        MultiSyncProperties props = new MultiSyncProperties();
        props.setMode("ws");
        CompositeMultiDeviceSyncService c = new CompositeMultiDeviceSyncService(
                List.of(wsService), props);
        SyncRequest request = new SyncRequest(SyncMode.IM, SyncType.SESSION_UNREAD,
                Map.of(), "user-1");
        assertDoesNotThrow(() -> c.push(request));
        assertTrue(wsService.wasCalled(), "WS should be called as fallback for unknown mode");
    }

    @Test
    @DisplayName("getSyncMode throws UnsupportedOperationException")
    void getSyncModeThrows() {
        assertThrows(UnsupportedOperationException.class, () -> composite.getSyncMode());
    }

    @Test
    @DisplayName("filters out self during construction")
    void filtersOutSelfDuringConstruction() {
        MultiSyncProperties props1 = new MultiSyncProperties();
        props1.setMode("ws");
        CompositeMultiDeviceSyncService c = new CompositeMultiDeviceSyncService(
                List.of(wsService, imService,
                        new CompositeMultiDeviceSyncService(List.of(), new MultiSyncProperties())),
                props1);
        assertDoesNotThrow(() -> c.push(
                new SyncRequest(SyncMode.WS, SyncType.SESSION_UNREAD, Map.of(), "user-1")));
        assertTrue(wsService.wasCalled(), "Self should have been filtered, WS should be called");
    }

    @Test
    @DisplayName("empty service list does not throw")
    void emptyServiceListDoesNotThrow() {
        CompositeMultiDeviceSyncService c = new CompositeMultiDeviceSyncService(
                List.of(), new MultiSyncProperties());
        assertDoesNotThrow(() -> c.push(
                new SyncRequest(SyncMode.WS, SyncType.SESSION_UNREAD, Map.of(), "user-1")));
    }

    private static class StubSyncService implements MultiDeviceSyncService {
        private final SyncMode mode;
        private int callCount = 0;

        StubSyncService(SyncMode mode) {
            this.mode = mode;
        }

        @Override
        public SyncMode getSyncMode() {
            return mode;
        }

        @Override
        public void push(SyncRequest request) {
            callCount++;
        }

        boolean wasCalled() {
            return callCount > 0;
        }

        int callCount() {
            return callCount;
        }
    }
}
