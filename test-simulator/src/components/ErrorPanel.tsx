import type { ErrorEntry } from '../types';

interface ErrorPanelProps {
  errors: ErrorEntry[];
  onClear: () => void;
}

export function ErrorPanel({ errors, onClear }: ErrorPanelProps) {
  return (
    <div className="panel">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2>🔴 Error Log ({errors.length})</h2>
        <button onClick={onClear} className="btn btn-secondary btn-sm">
          Clear
        </button>
      </div>

      {errors.length === 0 ? (
        <p className="placeholder-text">No errors</p>
      ) : (
        <div className="error-list">
          {[...errors].reverse().map((err, idx) => (
            <div key={idx} className={`error-entry error-severity-${err.severity}`}>
              <span className="error-timestamp">
                {new Date(err.timestamp).toLocaleTimeString()}
              </span>
              <span>{err.message}</span>
              {err.details && <span className="log-content">{err.details}</span>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
