/**
 * StreamAssembler accumulates streaming delta text fragments into a
 * complete message.  It tracks message boundaries so the UI can
 * differentiate between an in-progress stream and a finished one.
 */
export class StreamAssembler {
  private chunks: string[] = [];
  private completed = false;

  /** Append a delta text fragment. */
  push(delta: string): void {
    if (this.completed) {
      return;
    }
    this.chunks.push(delta);
  }

  /** Return the assembled text so far. */
  getText(): string {
    return this.chunks.join('');
  }

  /** Mark the stream as done (no more deltas expected). */
  complete(): void {
    this.completed = true;
  }

  /** Whether `complete()` has been called. */
  isCompleted(): boolean {
    return this.completed;
  }

  /** Reset internal state for a new message. */
  reset(): void {
    this.chunks = [];
    this.completed = false;
  }
}
