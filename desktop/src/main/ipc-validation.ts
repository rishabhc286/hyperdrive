/** Validation of renderer-supplied IPC payloads. Anything unexpected is rejected. */

export class InvalidIpcPayloadError extends Error {
  constructor(channel: string) {
    super(`Invalid payload for ${channel}`);
    this.name = 'InvalidIpcPayloadError';
  }
}

export function expectNoArgs(channel: string, args: unknown[]): void {
  if (args.length !== 0) throw new InvalidIpcPayloadError(channel);
}

/** Exactly `[{ username: string }]` with no other keys and a sane length. */
export function parseOnboardingPayload(channel: string, args: unknown[]): { username: string } {
  if (args.length !== 1) throw new InvalidIpcPayloadError(channel);
  const [payload] = args;
  if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) {
    throw new InvalidIpcPayloadError(channel);
  }
  const keys = Object.keys(payload);
  const username = (payload as Record<string, unknown>)['username'];
  if (keys.length !== 1 || keys[0] !== 'username' || typeof username !== 'string' || username.length > 64) {
    throw new InvalidIpcPayloadError(channel);
  }
  return { username };
}
