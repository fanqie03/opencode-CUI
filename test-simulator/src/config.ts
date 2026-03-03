// Configuration for test simulator
export interface Config {
  gatewayUrl: string;
  gatewayUrl2?: string; // For multi-instance testing
  skillServerUrl: string;
  agentId: string;
  reconnectDelay: number;
  maxReconnectAttempts: number;
  messageTimeout: number;
}

const getEnvVar = (key: string, defaultValue: string): string => {
  if (typeof import.meta.env !== 'undefined') {
    return import.meta.env[key] || defaultValue;
  }
  return defaultValue;
};

export const config: Config = {
  gatewayUrl: getEnvVar('VITE_GATEWAY_URL', 'ws://localhost:8080'),
  gatewayUrl2: getEnvVar('VITE_GATEWAY_URL_2', 'ws://localhost:8081'),
  skillServerUrl: getEnvVar('VITE_SKILL_SERVER_URL', 'ws://localhost:9090'),
  agentId: getEnvVar('VITE_AGENT_ID', 'test-agent-001'),
  reconnectDelay: parseInt(getEnvVar('VITE_RECONNECT_DELAY', '1000'), 10),
  maxReconnectAttempts: parseInt(getEnvVar('VITE_MAX_RECONNECT_ATTEMPTS', '5'), 10),
  messageTimeout: parseInt(getEnvVar('VITE_MESSAGE_TIMEOUT', '30000'), 10),
};
