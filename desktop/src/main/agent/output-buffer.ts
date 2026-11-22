/**
 * Bounded capture of a child's text output. Keeps at most `maxLines` recent lines, each truncated
 * to `maxLineChars`, and redacts the given secret. Partial lines are capped too, so a child that
 * never prints a newline cannot grow memory without bound.
 */
export class BoundedLog {
  private readonly lines: string[] = [];
  private partial = '';

  constructor(
    private readonly maxLines = 50,
    private readonly maxLineChars = 500,
    private readonly redact: () => string | null = () => null
  ) {}

  push(chunk: string): void {
    const text = this.partial + chunk;
    const parts = text.split(/\r?\n/);
    this.partial = (parts.pop() ?? '').slice(0, this.maxLineChars);
    for (const line of parts) this.add(line);
  }

  private add(line: string): void {
    let clean = line.slice(0, this.maxLineChars);
    const secret = this.redact();
    if (secret) clean = clean.split(secret).join('[redacted]');
    this.lines.push(clean);
    if (this.lines.length > this.maxLines) this.lines.shift();
  }

  snapshot(): readonly string[] {
    return [...this.lines];
  }
}

/**
 * Collects stdout up to the first newline (the handshake), refusing more than `maxBytes` before
 * it. Output after the handshake is ignored by design (stdout is reserved for the handshake).
 */
export class HandshakeReader {
  private buffer = '';
  private done = false;

  constructor(
    private readonly maxBytes: number,
    private readonly onLine: (line: string) => void,
    private readonly onOverflow: () => void
  ) {}

  push(chunk: string): void {
    if (this.done) return;
    this.buffer += chunk;
    const nl = this.buffer.indexOf('\n');
    if (nl >= 0) {
      this.done = true;
      const line = this.buffer.slice(0, nl).replace(/\r$/, '');
      this.buffer = '';
      this.onLine(line);
    } else if (this.buffer.length > this.maxBytes) {
      this.done = true;
      this.buffer = '';
      this.onOverflow();
    }
  }
}
