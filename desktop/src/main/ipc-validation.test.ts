// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { deviceName } from './device-name';
import {
  expectNoArgs,
  InvalidIpcPayloadError,
  parseAccountPayload,
  parseBeginConnectionPayload,
  parseFileMetadataPayload,
  parseListFilesPayload,
  parseOnboardingPayload,
  parseTransactionPayload,
  parseVerifyReadPayload
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
    expect(parseListFilesPayload('c', [{ accountId: ACCOUNT }])).toEqual({ accountId: ACCOUNT, directory: null, continuation: null });
    expect(parseListFilesPayload('c', [{ accountId: ACCOUNT, directory: 'abc_-1', continuation: 'hdk_p2' }])).toEqual({
      accountId: ACCOUNT,
      directory: 'abc_-1',
      continuation: 'hdk_p2'
    });
    // Dropbox directories are paths; the backend validates per provider.
    expect(parseListFilesPayload('c', [{ accountId: ACCOUNT, directory: '/photos/2024 trip' }]).directory).toBe(
      '/photos/2024 trip'
    );
    expect(parseBeginConnectionPayload('c', [{ provider: 'DROPBOX' }])).toEqual({ provider: 'DROPBOX' });
    expect(parseBeginConnectionPayload('c', [{ provider: 'GOOGLE_DRIVE' }])).toEqual({ provider: 'GOOGLE_DRIVE' });
    expect(parseFileMetadataPayload('c', [{ accountId: ACCOUNT, fileId: 'f1' }])).toEqual({ accountId: ACCOUNT, fileId: 'f1' });
  });

  it.each([
    ['no payload', []],
    ['a non-uuid account', [{ accountId: '../etc' }]],
    ['an uppercase/invalid uuid', [{ accountId: 'not-a-uuid' }]],
    ['an extra owner field', [{ accountId: ACCOUNT, userId: 'someone-else' }]],
    ['a control character in directory', [{ accountId: ACCOUNT, directory: '/a\nb' }]],
    ['an empty directory', [{ accountId: ACCOUNT, directory: '' }]],
    ['an oversized directory', [{ accountId: ACCOUNT, directory: '/' + 'x'.repeat(4096) }]],
    ['a non-string directory', [{ accountId: ACCOUNT, directory: 42 }]],
    ['a continuation with spaces', [{ accountId: ACCOUNT, continuation: 'a b' }]],
    ['an oversized continuation', [{ accountId: ACCOUNT, continuation: 'x'.repeat(8193) }]],
    ['the old parentId key', [{ accountId: ACCOUNT, parentId: 'abc' }]],
    ['a URL', [{ accountId: ACCOUNT, url: 'https://evil.example' }]]
  ])('rejects %s for listFiles', (_label, args) => {
    expect(() => parseListFilesPayload('c', args as unknown[])).toThrow(InvalidIpcPayloadError);
  });

  it('rejects unknown providers', () => {
    for (const bad of [[{ provider: 'ONEDRIVE' }], [{ provider: 'dropbox' }], [{}], [{ provider: 'DROPBOX', url: 'x' }], []]) {
      expect(() => parseBeginConnectionPayload('c', bad as unknown[])).toThrow(InvalidIpcPayloadError);
    }
  });

  it('accepts a bounded verify-read request and rejects everything else', () => {
    const ok = { accountId: ACCOUNT, fileId: 'id:abc', offset: 0, length: 4 * 1024 * 1024 };
    expect(parseVerifyReadPayload('c', [ok])).toEqual(ok);
    for (const bad of [
      { ...ok, length: 0 },
      { ...ok, length: 4 * 1024 * 1024 + 1 },
      { ...ok, offset: -1 },
      { ...ok, offset: 0.5 },
      { ...ok, offset: Number.MAX_SAFE_INTEGER + 2 },
      { ...ok, offset: '0' },
      { ...ok, fileId: 'https://evil.example/file' },
      { ...ok, accountId: 'x' },
      { ...ok, url: 'https://evil.example' },
      { ...ok, accessToken: 'ya29.x' }
    ]) {
      expect(() => parseVerifyReadPayload('c', [bad])).toThrow(InvalidIpcPayloadError);
    }
    expect(() => parseVerifyReadPayload('c', [ok, ok])).toThrow(InvalidIpcPayloadError);
  });

  it('rejects malformed transaction, account and file payloads', () => {
    expect(() => parseTransactionPayload('c', [{ transactionId: 'x' }])).toThrow(InvalidIpcPayloadError);
    expect(() => parseTransactionPayload('c', [{ transactionId: ACCOUNT, extra: 1 }])).toThrow(InvalidIpcPayloadError);
    expect(() => parseAccountPayload('c', [ACCOUNT])).toThrow(InvalidIpcPayloadError);
    expect(() => parseFileMetadataPayload('c', [{ accountId: ACCOUNT }])).toThrow(InvalidIpcPayloadError);
    expect(() => parseFileMetadataPayload('c', [{ accountId: ACCOUNT, fileId: 'a b' }])).toThrow(InvalidIpcPayloadError);
  });
});
