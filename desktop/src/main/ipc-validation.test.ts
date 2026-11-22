// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { deviceName } from './device-name';
import {
  expectNoArgs,
  InvalidIpcPayloadError,
  parseAccountPayload,
  parseFileMetadataPayload,
  parseListFilesPayload,
  parseOnboardingPayload,
  parseTransactionPayload
} from './ipc-validation';

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

describe('storage IPC payload validation', () => {
  const ACCOUNT = '6f1c2a8e-1b2c-4d3e-8f90-123456789abc';

  it('accepts well-formed payloads', () => {
    expect(parseTransactionPayload('c', [{ transactionId: ACCOUNT }])).toEqual({ transactionId: ACCOUNT });
    expect(parseAccountPayload('c', [{ accountId: ACCOUNT }])).toEqual({ accountId: ACCOUNT });
    expect(parseListFilesPayload('c', [{ accountId: ACCOUNT }])).toEqual({ accountId: ACCOUNT, parentId: null, pageToken: null });
    expect(parseListFilesPayload('c', [{ accountId: ACCOUNT, parentId: 'abc_-1', pageToken: 'p~2' }])).toEqual({
      accountId: ACCOUNT,
      parentId: 'abc_-1',
      pageToken: 'p~2'
    });
    expect(parseFileMetadataPayload('c', [{ accountId: ACCOUNT, fileId: 'f1' }])).toEqual({ accountId: ACCOUNT, fileId: 'f1' });
  });

  it.each([
    ['no payload', []],
    ['a non-uuid account', [{ accountId: '../etc' }]],
    ['an uppercase/invalid uuid', [{ accountId: 'not-a-uuid' }]],
    ['an extra owner field', [{ accountId: ACCOUNT, userId: 'someone-else' }]],
    ['a quote in parentId', [{ accountId: ACCOUNT, parentId: "a' or 1=1" }]],
    ['a path in parentId', [{ accountId: ACCOUNT, parentId: 'a/b' }]],
    ['a pageToken with spaces', [{ accountId: ACCOUNT, pageToken: 'a b' }]],
    ['an oversized pageToken', [{ accountId: ACCOUNT, pageToken: 'x'.repeat(2049) }]],
    ['a URL', [{ accountId: ACCOUNT, url: 'https://evil.example' }]]
  ])('rejects %s for listFiles', (_label, args) => {
    expect(() => parseListFilesPayload('c', args as unknown[])).toThrow(InvalidIpcPayloadError);
  });

  it('rejects malformed transaction, account and file payloads', () => {
    expect(() => parseTransactionPayload('c', [{ transactionId: 'x' }])).toThrow(InvalidIpcPayloadError);
    expect(() => parseTransactionPayload('c', [{ transactionId: ACCOUNT, extra: 1 }])).toThrow(InvalidIpcPayloadError);
    expect(() => parseAccountPayload('c', [ACCOUNT])).toThrow(InvalidIpcPayloadError);
    expect(() => parseFileMetadataPayload('c', [{ accountId: ACCOUNT }])).toThrow(InvalidIpcPayloadError);
    expect(() => parseFileMetadataPayload('c', [{ accountId: ACCOUNT, fileId: 'a b' }])).toThrow(InvalidIpcPayloadError);
  });
});
