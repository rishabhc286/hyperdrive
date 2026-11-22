// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { deviceName } from './device-name';
import { expectNoArgs, InvalidIpcPayloadError, parseOnboardingPayload } from './ipc-validation';

describe('IPC payload validation', () => {
  it('accepts exactly { username: string }', () => {
    expect(parseOnboardingPayload('c', [{ username: 'Test_User' }])).toEqual({ username: 'Test_User' });
  });

  it.each([
    ['no args', []],
    ['two args', [{ username: 'a' }, {}]],
    ['a string', ['Test_User']],
    ['null', [null]],
    ['an array', [['Test_User']]],
    ['extra keys', [{ username: 'a', userId: 'someone-else' }]],
    ['a non-string username', [{ username: 42 }]],
    ['an oversized username', [{ username: 'x'.repeat(65) }]]
  ])('rejects %s', (_label, args) => {
    expect(() => parseOnboardingPayload('c', args as unknown[])).toThrow(InvalidIpcPayloadError);
  });

  it('rejects arguments on no-argument channels', () => {
    expect(() => expectNoArgs('c', [])).not.toThrow();
    expect(() => expectNoArgs('c', ['https://evil.example'])).toThrow(InvalidIpcPayloadError);
  });

  it('sanitizes the device name', () => {
    expect(deviceName('DESKTOP-AB12')).toBe('DESKTOP-AB12');
    expect(deviceName('<script>')).toBe('script');
    expect(deviceName('***')).toBe('Windows PC');
    expect(deviceName('x'.repeat(100))).toHaveLength(64);
  });
});
