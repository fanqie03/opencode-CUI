import type { Metrics } from '../types';

interface MetricsPanelProps {
  metrics: Metrics;
}

export function MetricsPanel({ metrics }: MetricsPanelProps) {
  const avgLatency =
    metrics.latencies.length > 0
      ? Math.round(metrics.latencies.reduce((a, b) => a + b, 0) / metrics.latencies.length)
      : 0;

  return (
    <div className="panel">
      <h2>📊 Metrics</h2>

      <div className="metrics-grid">
        <div className="metric-card">
          <div className="metric-value">{metrics.messagesSent}</div>
          <div className="metric-label">Sent</div>
        </div>
        <div className="metric-card">
          <div className="metric-value">{metrics.messagesReceived}</div>
          <div className="metric-label">Received</div>
        </div>
        <div className="metric-card">
          <div className="metric-value">{metrics.sequenceGaps}</div>
          <div className="metric-label">Seq Gaps</div>
        </div>
        <div className="metric-card">
          <div className="metric-value">{metrics.reconnectCount}</div>
          <div className="metric-label">Reconnects</div>
        </div>
        <div className="metric-card" style={{ gridColumn: '1 / -1' }}>
          <div className="metric-value">{avgLatency}ms</div>
          <div className="metric-label">Avg Latency</div>
        </div>
      </div>
    </div>
  );
}
