import type { ParsedEvent, ToolUseInfo } from './types';

/**
 * Create a new ToolUseInfo from a `tool.start` event.
 */
export function startTool(event: ParsedEvent): ToolUseInfo {
  return {
    toolName: event.toolName ?? 'unknown',
    args: event.toolArgs,
    status: 'running',
  };
}

/**
 * Update an existing ToolUseInfo with the outcome of a
 * `tool.result` or `tool.error` event.
 */
export function completeTool(
  event: ParsedEvent,
  existing: ToolUseInfo,
): ToolUseInfo {
  if (event.eventType === 'tool.error') {
    return {
      ...existing,
      error: event.error,
      status: 'error',
    };
  }

  return {
    ...existing,
    result: event.toolResult,
    status: 'completed',
  };
}

/**
 * Produce a renderable representation of a ToolUseInfo.
 *
 * The returned object contains a human-readable `title`, the primary
 * `content` to display, and an optional `language` hint for syntax
 * highlighting.
 */
export function renderToolUse(info: ToolUseInfo): {
  title: string;
  content: string;
  language?: string;
} {
  const statusLabel =
    info.status === 'running'
      ? '运行中...'
      : info.status === 'error'
        ? '错误'
        : '完成';

  const title = `Tool: ${info.toolName} [${statusLabel}]`;

  if (info.status === 'error' && info.error) {
    return { title, content: info.error };
  }

  if (info.result) {
    // Attempt to pretty-print JSON results; fall back to raw string.
    try {
      const parsed = JSON.parse(info.result);
      return {
        title,
        content: JSON.stringify(parsed, null, 2),
        language: 'json',
      };
    } catch {
      return { title, content: info.result };
    }
  }

  if (info.args) {
    return {
      title,
      content: JSON.stringify(info.args, null, 2),
      language: 'json',
    };
  }

  return { title, content: '' };
}
