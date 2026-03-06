// ==================== v1 Protocol (方案5) Types ====================

/**
 * GatewayMessage — v1 flat message format between Gateway and Skill Server.
 * Replaces the old nested MessageEnvelope format.
 */
export interface GatewayMessage {
  type: string;
  agentId?: string;
  sessionId?: string;
  toolSessionId?: string;
  event?: Record<string, unknown>;
  payload?: Record<string, unknown>;
  action?: string;
  error?: string;
  usage?: { input_tokens: number; output_tokens: number };
  sequenceNumber?: number;

  // Registration fields
  deviceName?: string;
  os?: string;
  toolType?: string;
  toolVersion?: string;

  // Envelope (optional v1 extension)
  envelope?: EnvelopeMetadata;
}

export interface EnvelopeMetadata {
  version: string;
  messageId: string;
  timestamp: string;
  source: string;
  agentId?: string;
  sessionId?: string;
  sequenceNumber?: number;
  sequenceScope?: string;
}

// ==================== Stream types ====================

export interface StreamMessage {
  type: 'delta' | 'done' | 'error' | 'agent_offline' | 'agent_online' | 'permission_updated';
  seq?: number;
  content?: string;
  usage?: { input_tokens: number; output_tokens: number };
  message?: string;
  // Permission fields
  permissionId?: string;
  permissionType?: string;
  description?: string;
}

// ==================== Session types ====================

export interface Session {
  id: string;
  agentId: string;
  status: 'ACTIVE' | 'IDLE' | 'CLOSED';
  createdAt: string;
  updatedAt?: string;
}

// ==================== Message history ====================

export interface MessageHistoryItem {
  id: number;
  sessionId: string;
  seq: number;
  role: 'user' | 'assistant' | 'system';
  content: string;
  contentType: string;
  meta?: Record<string, unknown>;
  createdAt: string;
}

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

// ==================== Permission types ====================

export interface PermissionRequest {
  permissionId: string;
  sessionId: string;
  type: string;
  description: string;
  timestamp: number;
}

// ==================== Metrics ====================

export interface Metrics {
  messagesSent: number;
  messagesReceived: number;
  sequenceGaps: number;
  reconnectCount: number;
  latencies: number[];
}

// ==================== Error types ====================

export interface ErrorEntry {
  timestamp: number;
  severity: 'error' | 'warning' | 'info';
  message: string;
  details?: string;
}

// ==================== Test scenario types ====================

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
