import type { MessagePart } from './types';

/**
 * ToolUseRenderer (v2 — adapted for MessagePart)
 *
 * Renders tool use information from a MessagePart into a
 * displayable format. This is kept for compatibility but the
 * primary rendering is now done by the ToolCard component.
 */

export interface RenderedToolUse {
  title: string;
  content: string;
  language?: string;
}

const statusLabels: Record<string, string> = {
  pending: '等待中',
  running: '运行中...',
  completed: '完成',
  error: '错误',
};

/**
 * Render a tool MessagePart into a displayable format.
 */
export function renderToolPart(part: MessagePart): RenderedToolUse {
  const statusLabel = statusLabels[part.toolStatus ?? 'pending'] ?? part.toolStatus ?? '';
  const title = `Tool: ${part.toolName ?? 'unknown'} [${statusLabel}]`;

  if (part.toolStatus === 'error' && part.content) {
    return { title, content: part.content };
  }

  if (part.toolOutput) {
    try {
      const parsed = JSON.parse(part.toolOutput);
      return {
        title,
        content: JSON.stringify(parsed, null, 2),
        language: 'json',
      };
    } catch {
      return { title, content: part.toolOutput };
    }
  }

  if (part.toolInput) {
    return {
      title,
      content: JSON.stringify(part.toolInput, null, 2),
      language: 'json',
    };
  }

  return { title, content: '' };
}
