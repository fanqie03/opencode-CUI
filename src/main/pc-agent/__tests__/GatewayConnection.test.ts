import { describe, expect, test } from 'bun:test';
import { GatewayConnection } from '../GatewayConnection';

describe('GatewayConnection', () => {
  test('reconnect uses fresh auth params from provider', async () => {
    const gateway = new GatewayConnection('ws://localhost:8081/ws/agent', 30_000, 1, 1);

    const seenTimestamps: string[] = [];
    let counter = 0;
    const authProvider = () => ({
      ak: 'test-ak',
      timestamp: `ts-${++counter}`,
      nonce: `nonce-${counter}`,
      signature: `sig-${counter}`,
    });

    (gateway as any).doConnect = async (auth: { timestamp: string }) => {
      seenTimestamps.push(auth.timestamp);
    };

    await gateway.connect(authProvider);
    (gateway as any).scheduleReconnect();
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(seenTimestamps).toEqual(['ts-1', 'ts-2']);
  });
});
