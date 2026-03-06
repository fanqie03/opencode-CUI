/**
 * ProtocolAdapter Unit Tests
 *
 * Tests envelope wrapping, sequence counting, and output format.
 */

import { describe, test, expect } from 'bun:test';
import { ProtocolAdapter } from '../ProtocolAdapter';
import type { OpenCodeEvent } from '../types/PluginTypes';

describe('ProtocolAdapter', () => {
    // -----------------------------------------------------------------------
    // wrapToolEvent
    // -----------------------------------------------------------------------

    describe('wrapToolEvent', () => {
        test('wraps event with correct type and fields', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');
            const event: OpenCodeEvent = {
                type: 'message.part.updated',
                properties: { sessionID: 'sess_abc' },
            };

            const result = adapter.wrapToolEvent(event, '42');

            expect(result.type).toBe('tool_event');
            expect(result.sessionId).toBe('42');
            expect(result.event).toBe(event);
            expect(result.envelope).toBeDefined();
        });

        test('envelope contains required metadata', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');
            const event: OpenCodeEvent = { type: 'message.created' };

            const result = adapter.wrapToolEvent(event, '42');
            const envelope = result.envelope as Record<string, unknown>;

            expect(envelope.version).toBe('1.0.0');
            expect(typeof envelope.messageId).toBe('string');
            expect(typeof envelope.timestamp).toBe('string');
            expect(envelope.source).toBe('OPENCODE');
            expect(envelope.agentId).toBe('agent-123');
            expect(envelope.sessionId).toBe('42');
            expect(envelope.sequenceNumber).toBe(1);
            expect(envelope.sequenceScope).toBe('session');
        });

        test('without sessionId uses agent scope', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');
            const event: OpenCodeEvent = { type: 'session.created' };

            const result = adapter.wrapToolEvent(event);
            const envelope = result.envelope as Record<string, unknown>;

            expect(result.sessionId).toBeUndefined();
            expect(envelope.sequenceScope).toBe('agent');
        });

        test('event is passed through without modification', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');
            const event: OpenCodeEvent = {
                type: 'message.part.updated',
                properties: {
                    sessionID: 'sess_abc',
                    messageID: 'msg_def',
                    part: { id: 'part_001', type: 'text', text: 'hello' },
                },
            };

            const result = adapter.wrapToolEvent(event, '42');
            // Event should be the exact same object (no cloning)
            expect(result.event).toBe(event);
        });
    });

    // -----------------------------------------------------------------------
    // wrapToolDone
    // -----------------------------------------------------------------------

    describe('wrapToolDone', () => {
        test('wraps done message correctly', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');
            const usage = { inputTokens: 100, outputTokens: 50 };

            const result = adapter.wrapToolDone(usage, '42');

            expect(result.type).toBe('tool_done');
            expect(result.sessionId).toBe('42');
            expect(result.usage).toBe(usage);
            expect(result.envelope).toBeDefined();
        });
    });

    // -----------------------------------------------------------------------
    // wrapToolError
    // -----------------------------------------------------------------------

    describe('wrapToolError', () => {
        test('wraps error message correctly', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');

            const result = adapter.wrapToolError('Something went wrong', '42');

            expect(result.type).toBe('tool_error');
            expect(result.sessionId).toBe('42');
            expect(result.error).toBe('Something went wrong');
            expect(result.envelope).toBeDefined();
        });
    });

    // -----------------------------------------------------------------------
    // wrapSessionCreated
    // -----------------------------------------------------------------------

    describe('wrapSessionCreated', () => {
        test('wraps session created message correctly', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');
            const session = { id: 'sess_abc', model: 'gpt-4' };

            const result = adapter.wrapSessionCreated('sess_abc', session);

            expect(result.type).toBe('session_created');
            expect(result.toolSessionId).toBe('sess_abc');
            expect(result.session).toBe(session);
            expect(result.envelope).toBeDefined();
        });

        test('includes sessionId when provided', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');
            const session = { id: 'sess_abc', model: 'gpt-4' };

            const result = adapter.wrapSessionCreated('sess_abc', session, '10');

            expect(result.type).toBe('session_created');
            expect(result.sessionId).toBe('10');
            expect(result.toolSessionId).toBe('sess_abc');
        });
    });

    // -----------------------------------------------------------------------
    // Sequence counting
    // -----------------------------------------------------------------------

    describe('sequence counting', () => {
        test('session sequences increment independently', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');

            expect(adapter.getNextSequence('session-1')).toBe(1);
            expect(adapter.getNextSequence('session-1')).toBe(2);
            expect(adapter.getNextSequence('session-2')).toBe(1);
            expect(adapter.getNextSequence('session-1')).toBe(3);
            expect(adapter.getNextSequence('session-2')).toBe(2);
        });

        test('agent-level sequence works when no sessionId', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');

            expect(adapter.getNextSequence()).toBe(1);
            expect(adapter.getNextSequence()).toBe(2);
            expect(adapter.getNextSequence()).toBe(3);
        });

        test('resetSessionSequence clears counter', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');

            adapter.getNextSequence('session-1'); // 1
            adapter.getNextSequence('session-1'); // 2
            expect(adapter.getCurrentSequence('session-1')).toBe(2);

            adapter.resetSessionSequence('session-1');
            expect(adapter.getCurrentSequence('session-1')).toBe(0);
            expect(adapter.getNextSequence('session-1')).toBe(1);
        });

        test('getCurrentSequence returns 0 for unknown session', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');
            expect(adapter.getCurrentSequence('nonexistent')).toBe(0);
        });
    });

    // -----------------------------------------------------------------------
    // Unique messageId
    // -----------------------------------------------------------------------

    describe('unique messageId', () => {
        test('each wrapped message has a unique messageId', () => {
            const adapter = new ProtocolAdapter('OPENCODE', 'agent-123');
            const event: OpenCodeEvent = { type: 'message.created' };

            const result1 = adapter.wrapToolEvent(event, '42');
            const result2 = adapter.wrapToolEvent(event, '42');

            const id1 = (result1.envelope as Record<string, unknown>).messageId;
            const id2 = (result2.envelope as Record<string, unknown>).messageId;

            expect(id1).not.toBe(id2);
        });
    });
});
