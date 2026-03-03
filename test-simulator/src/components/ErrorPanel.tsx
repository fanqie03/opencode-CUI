import type { ErrorEntry } from '../types';

interface ErrorPanelProps {
  errors: ErrorEntry[];
  onClear: () => void;
}

export function ErrorPanel({ errors, onClear }: ErrorPanelProps) {
  const getSeverityColor = (severity: ErrorEntry['severity']) => {
    switch (severity) {
      case 'error':
        return '#dc3545';
      case 'warning':
        return '#ffc107';
      case 'info':
        return '#17a2b8';
      default:
        return '#6c757d';
    }
  };

  const getSeverityLabel = (severity: ErrorEntry['severity']) => {
    switch (severity) {
      case 'error':
        return '❌';
      case 'warning':
        return '⚠️';
      case 'info':
        return 'ℹ️';
      default:
        return '●';
    }
  };

  return (
    <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '8px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
        <h2>Error Panel ({errors.length})</h2>
        <button
          onClick={onClear}
          style={{
            padding: '6px 12px',
            background: '#6c757d',
            color: 'white',
            border: 'none',
            borderRadius: '4px',
            cursor: 'pointer',
          }}
        >
          Clear All
        </button>
      </div>

      <div
        style={{
          maxHeight: '200px',
          overflow: 'auto',
          border: '1px solid #ddd',
          padding: '8px',
          background: '#f9f9f9',
        }}
      >
        {errors.length === 0 ? (
          <p style={{ color: '#666', textAlign: 'center' }}>No errors</p>
        ) : (
          <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
            {errors.map((error, idx) => (
              <li
                key={idx}
                style={{
                  padding: '8px',
                  marginBottom: '8px',
                  border: `1px solid ${getSeverityColor(error.severity)}`,
                  borderLeft: `4px solid ${getSeverityColor(error.severity)}`,
                  borderRadius: '4px',
                  background: 'white',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', marginBottom: '4px' }}>
                  <span style={{ marginRight: '8px' }}>{getSeverityLabel(error.severity)}</span>
                  <strong style={{ color: getSeverityColor(error.severity) }}>
                    {error.severity.toUpperCase()}
                  </strong>
                  <span style={{ marginLeft: 'auto', fontSize: '12px', color: '#666' }}>
                    {new Date(error.timestamp).toLocaleTimeString()}
                  </span>
                </div>
                <div style={{ fontSize: '14px' }}>{error.message}</div>
                {error.details && (
                  <div style={{ fontSize: '12px', color: '#666', marginTop: '4px', fontFamily: 'monospace' }}>
                    {error.details}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
