// MessageEnvelope types for gateway protocol
export interface MessageEnvelope {
  version: string;
  messageId: string;
  timestamp: number;
  source: {
    type: 'agent' | 'skill_server' | 'gateway';
    id: string;
  };
  destination?: {
    type: 'agent' | 'skill_server' | 'gateway';
    id: string;
  };
  payload: {
    type: string;
    data: unknown;
  };
  metadata?: {
    sessionId?: string;
    correlationId?: string;
    sequenceNumber?: number;
    [key: string]: unknown;
  };
}

// Stream message types
export interface StreamMessage {
  type: 'delta' | 'done' | 'error' | 'agent_offline' | 'agent_online';
  seq?: number;
  content?: string;
  usage?: { input_tokens: number; output_tokens: number };
  message?: string;
}

// Session types
export interface Session {
  id: string;
  agentId: string;
  status: 'created' | 'active' | 'closed';
  createdAt: number;
}

// Metrics types
export interface Metrics {
  messagesSent: number;
  messagesReceived: number;
  sequenceGaps: number;
  reconnectCount: number;
  latencies: number[];
}

// Error types
export interface ErrorEntry {
  timestamp: number;
  severity: 'error' | 'warning' | 'info';
  message: string;
  details?: string;
}

// Test scenario types
export interface TestScenario {
  id: string;
  name: string;
  description: string;
  steps: ScenarioStep[];
}

export interface ScenarioStep {
  action: string;
  params?: Record<string, unknown>;
  expectedResult?: string;
}

export interface ScenarioResult {
  scenarioId: string;
  status: 'running' | 'passed' | 'failed';
  startTime: number;
  endTime?: number;
  steps: StepResult[];
  error?: string;
}

export interface StepResult {
  step: number;
  action: string;
  status: 'pending' | 'running' | 'passed' | 'failed';
  result?: string;
  error?: string;
}
