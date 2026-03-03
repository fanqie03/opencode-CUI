import type { Metrics } from '../types';

interface MetricsPanelProps {
  metrics: Metrics;
}

export function MetricsPanel({ metrics }: MetricsPanelProps) {
  const calculatePercentile = (values: number[], percentile: number): number => {
    if (values.length === 0) return 0;
    const sorted = [...values].sort((a, b) => a - b);
    const index = Math.ceil((percentile / 100) * sorted.length) - 1;
    return sorted[Math.max(0, index)];
  };

  const p50 = calculatePercentile(metrics.latencies, 50);
  const p95 = calculatePercentile(metrics.latencies, 95);
  const p99 = calculatePercentile(metrics.latencies, 99);

  return (
    <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '8px' }}>
      <h2>Metrics Panel</h2>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
        <div style={{ padding: '12px', border: '1px solid #ddd', borderRadius: '4px', background: '#f0f8ff' }}>
          <div style={{ fontSize: '24px', fontWeight: 'bold', color: '#007bff' }}>
            {metrics.messagesSent}
          </div>
          <div style={{ fontSize: '14px', color: '#666' }}>Messages Sent</div>
        </div>

        <div style={{ padding: '12px', border: '1px solid #ddd', borderRadius: '4px', background: '#f0fff0' }}>
          <div style={{ fontSize: '24px', fontWeight: 'bold', color: '#28a745' }}>
            {metrics.messagesReceived}
          </div>
          <div style={{ fontSize: '14px', color: '#666' }}>Messages Received</div>
        </div>

        <div style={{ padding: '12px', border: '1px solid #ddd', borderRadius: '4px', background: '#fff3cd' }}>
          <div style={{ fontSize: '24px', fontWeight: 'bold', color: '#ffc107' }}>
            {metrics.sequenceGaps}
          </div>
          <div style={{ fontSize: '14px', color: '#666' }}>Sequence Gaps</div>
        </div>

        <div style={{ padding: '12px', border: '1px solid #ddd', borderRadius: '4px', background: '#f8d7da' }}>
          <div style={{ fontSize: '24px', fontWeight: 'bold', color: '#dc3545' }}>
            {metrics.reconnectCount}
          </div>
          <div style={{ fontSize: '14px', color: '#666' }}>Reconnects</div>
        </div>
      </div>

      <div style={{ marginTop: '16px', padding: '12px', border: '1px solid #ddd', borderRadius: '4px' }}>
        <h3 style={{ marginTop: 0 }}>Latency Histogram</h3>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '12px' }}>
          <div>
            <div style={{ fontSize: '12px', color: '#666' }}>P50</div>
            <div style={{ fontSize: '20px', fontWeight: 'bold' }}>{p50.toFixed(2)}ms</div>
          </div>
          <div>
            <div style={{ fontSize: '12px', color: '#666' }}>P95</div>
            <div style={{ fontSize: '20px', fontWeight: 'bold' }}>{p95.toFixed(2)}ms</div>
          </div>
          <div>
            <div style={{ fontSize: '12px', color: '#666' }}>P99</div>
            <div style={{ fontSize: '20px', fontWeight: 'bold' }}>{p99.toFixed(2)}ms</div>
          </div>
        </div>
        <div style={{ marginTop: '8px', fontSize: '12px', color: '#666' }}>
          Total samples: {metrics.latencies.length}
        </div>
      </div>
    </div>
  );
}
