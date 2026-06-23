package com.opencode.cui.gateway.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified WebSocket connection metrics for all WS endpoints in ai-gateway.
 *
 * <p>Exposes two Prometheus metrics with a unified name, differentiated by tags:
 * <ul>
 *   <li>{@code websocket_connections_current} (Gauge) — current active connection count</li>
 *   <li>{@code websocket_connections_total} (Counter) — cumulative connection count since startup</li>
 * </ul>
 *
 * <p>Tags:
 * <ul>
 *   <li>{@code url} — WS endpoint path (e.g. {@code /ws/agent})</li>
 *   <li>{@code business} — business purpose (e.g. {@code pcagent})</li>
 *   <li>{@code direction} — {@code inbound} (accepts) or {@code outbound} (initiates)</li>
 * </ul>
 *
 * <p>Usage: inject this component and call {@link #connectionOpened} / {@link #connectionClosed}
 * at each WebSocket connect/disconnect lifecycle callback.
 */
@Component
public class WsConnectionMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AtomicInteger> currentConnections = new ConcurrentHashMap<>();

    public WsConnectionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records a new WebSocket connection: increments the cumulative counter and the current gauge.
     *
     * <p>Thread-safe. The gauge is registered lazily on the first connection for a given
     * (url, business, direction) combination.
     *
     * @param url       WS endpoint URL or path
     * @param business  business purpose identifier
     * @param direction "inbound" or "outbound"
     */
    public void connectionOpened(String url, String business, String direction) {
        Tags tags = Tags.of("url", url, "business", business, "direction", direction);
        String key = meterKey(url, business, direction);

        AtomicInteger count = currentConnections.computeIfAbsent(key, k -> {
            AtomicInteger n = new AtomicInteger(0);
            Gauge.builder("websocket_connections_current", n, AtomicInteger::doubleValue)
                    .tags(tags)
                    .register(meterRegistry);
            return n;
        });
        count.incrementAndGet();

        Counter.builder("websocket_connections_total")
                .tags(tags)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Records a WebSocket disconnection: decrements the current gauge.
     *
     * <p>Thread-safe. No-ops if no matching gauge was ever registered (e.g. double-close).
     *
     * @param url       WS endpoint URL or path
     * @param business  business purpose identifier
     * @param direction "inbound" or "outbound"
     */
    public void connectionClosed(String url, String business, String direction) {
        String key = meterKey(url, business, direction);
        AtomicInteger count = currentConnections.get(key);
        if (count != null) {
            int newVal = count.decrementAndGet();
            if (newVal < 0) {
                count.compareAndSet(newVal, 0);
            }
        }
    }

    private static String meterKey(String url, String business, String direction) {
        return url + "|" + business + "|" + direction;
    }
}
