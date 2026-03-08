import { describe, expect, test } from 'bun:test';
import { EventRelay } from '../EventRelay';

function createRelay() {
  const sent: unknown[] = [];
  const gateway = {
    send(message: unknown) {
      sent.push(message);
    },
    onMessage() {
      // Not needed for upstream relay tests.
    },
  };

  const relay = new EventRelay(gateway as any, {} as any);
  return { relay, sent };
}

describe('EventRelay.relayUpstream', () => {
  test('includes toolSessionId when event uses sessionID', () => {
    const { relay, sent } = createRelay();

    relay.relayUpstream({
      type: 'message.updated',
      sessionID: 'ses_root_123',
    } as any);

    expect(sent).toHaveLength(1);
    expect(sent[0]).toEqual({
      type: 'tool_event',
      toolSessionId: 'ses_root_123',
      event: {
        type: 'message.updated',
        sessionID: 'ses_root_123',
      },
    });
  });

  test('includes toolSessionId when event uses info.sessionID', () => {
    const { relay, sent } = createRelay();

    relay.relayUpstream({
      type: 'message.completed',
      info: {
        sessionID: 'ses_info_456',
      },
    } as any);

    expect(sent).toHaveLength(1);
    expect((sent[0] as { toolSessionId?: string }).toolSessionId).toBe('ses_info_456');
  });

  test('includes toolSessionId when event uses properties.part.sessionID', () => {
    const { relay, sent } = createRelay();

    relay.relayUpstream({
      type: 'message.part.updated',
      properties: {
        part: {
          sessionID: 'ses_part_123',
        },
      },
    } as any);

    expect(sent).toHaveLength(1);
    expect((sent[0] as { toolSessionId?: string }).toolSessionId).toBe('ses_part_123');
  });

  test('falls back to the single pending prompt session for question events without session ids', () => {
    const { relay, sent } = createRelay();

    (relay as any).pendingPromptSessions.add('ses_pending_123');
    relay.relayUpstream({
      type: 'question.asked',
      properties: {
        id: 'question-1',
        questions: [
          {
            header: 'Choose one',
            question: 'Which option?',
            options: [
              { label: 'A', description: 'Alpha' },
              { label: 'B', description: 'Beta' },
            ],
          },
        ],
      },
    } as any);

    expect(sent).toHaveLength(1);
    expect(sent[0]).toEqual({
      type: 'tool_event',
      toolSessionId: 'ses_pending_123',
      event: {
        type: 'question.asked',
        properties: {
          id: 'question-1',
          questions: [
            {
              header: 'Choose one',
              question: 'Which option?',
              options: [
                { label: 'A', description: 'Alpha' },
                { label: 'B', description: 'Beta' },
              ],
            },
          ],
        },
      },
    });
  });

  test('sends tool_done for session.idle when session id is available', () => {
    const { relay, sent } = createRelay();

    relay.relayUpstream({
      type: 'session.idle',
      properties: {
        sessionID: 'ses_idle_789',
      },
    } as any);

    expect(sent).toHaveLength(2);
    expect(sent[0]).toEqual({
      type: 'tool_event',
      toolSessionId: 'ses_idle_789',
      event: {
        type: 'session.idle',
        properties: {
          sessionID: 'ses_idle_789',
        },
      },
    });
    expect(sent[1]).toEqual({
      type: 'tool_done',
      toolSessionId: 'ses_idle_789',
    });
  });

  test('suppresses session.idle while prompt result is still pending', () => {
    const { relay, sent } = createRelay();

    (relay as any).pendingPromptSessions.add('ses_idle_789');
    relay.relayUpstream({
      type: 'session.idle',
      properties: {
        sessionID: 'ses_idle_789',
      },
    } as any);

    expect(sent).toHaveLength(0);
  });

  test('skips high-volume session.diff events', () => {
    const { relay, sent } = createRelay();

    relay.relayUpstream({
      type: 'session.diff',
      properties: {
        sessionID: 'ses_diff_123',
        diff: 'very large patch payload',
      },
    } as any);

    expect(sent).toHaveLength(0);
  });
});

describe('EventRelay downstream invoke fallback', () => {
  test('relays final prompt parts upstream after chat completes', async () => {
    const sent: unknown[] = [];
    const gateway = {
      send(message: unknown) {
        sent.push(message);
      },
      onMessage() {
        // Not needed for direct private-method invocation.
      },
    };

    const client = {
      session: {
        prompt: async () => ({
          data: {
            parts: [
              {
                type: 'reasoning',
                id: 'part-r',
                sessionID: 'ses_chat_123',
                messageID: 'msg_chat_123',
                text: 'thinking...',
              },
              {
                type: 'text',
                id: 'part-t',
                sessionID: 'ses_chat_123',
                messageID: 'msg_chat_123',
                text: 'final answer',
              },
            ],
          },
        }),
      },
    };

    const relay = new EventRelay(gateway as any, client as any);
    await (relay as any).handleDownstreamMessage({
      type: 'invoke',
      action: 'chat',
      payload: {
        toolSessionId: 'ses_chat_123',
        text: 'hello',
      },
    });

    expect(sent).toHaveLength(3);
    expect(sent[0]).toEqual({
      type: 'tool_event',
      toolSessionId: 'ses_chat_123',
      event: {
        type: 'message.part.updated',
        properties: {
          sessionID: 'ses_chat_123',
          messageID: 'msg_chat_123',
          part: {
            type: 'reasoning',
            id: 'part-r',
            sessionID: 'ses_chat_123',
            messageID: 'msg_chat_123',
            text: 'thinking...',
          },
        },
      },
    });
    expect(sent[1]).toEqual({
      type: 'tool_event',
      toolSessionId: 'ses_chat_123',
      event: {
        type: 'message.part.updated',
        properties: {
          sessionID: 'ses_chat_123',
          messageID: 'msg_chat_123',
          part: {
            type: 'text',
            id: 'part-t',
            sessionID: 'ses_chat_123',
            messageID: 'msg_chat_123',
            text: 'final answer',
          },
        },
      },
    });
    expect(sent[2]).toEqual({
      type: 'tool_done',
      toolSessionId: 'ses_chat_123',
    });
  });

  test('question_reply uses pending question request instead of session.prompt chat', async () => {
    const sent: unknown[] = [];
    const gateway = {
      send(message: unknown) {
        sent.push(message);
      },
      onMessage() {
        // Not needed for direct private-method invocation.
      },
    };

    const getCalls: unknown[] = [];
    const postCalls: unknown[] = [];
    const client = {
      _client: {
        get: async (args: unknown) => {
          getCalls.push(args);
          return {
            data: [
              {
                id: 'question-request-1',
                sessionID: 'ses_question_123',
                tool: {
                  callID: 'call_question_1',
                },
                questions: [
                  {
                    header: 'Choose',
                    question: 'Which option?',
                    options: [
                      { label: 'A', description: 'Alpha' },
                      { label: 'B', description: 'Beta' },
                    ],
                  },
                ],
              },
            ],
          };
        },
        post: async (args: unknown) => {
          postCalls.push(args);
          return { data: undefined };
        },
      },
      session: {
        prompt: async () => {
          throw new Error('session.prompt should not be called for question_reply');
        },
      },
    };

    const relay = new EventRelay(gateway as any, client as any);
    await (relay as any).handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: '24',
      action: 'question_reply',
      payload: {
        toolSessionId: 'ses_question_123',
        toolCallId: 'call_question_1',
        answer: '停止',
      },
    });

    expect(getCalls).toEqual([
      {
        url: '/question',
      },
    ]);
    expect(postCalls).toEqual([
      {
        url: '/question/{requestID}/reply',
        path: {
          requestID: 'question-request-1',
        },
        body: {
          answers: [['停止']],
        },
        headers: {
          'Content-Type': 'application/json',
        },
      },
    ]);
    expect(sent).toHaveLength(0);
  });
});
