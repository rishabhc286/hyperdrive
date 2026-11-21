// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { BACKEND_URL_ENV, DEFAULT_BACKEND_URL, resolveBackendUrl } from './config';

describe('resolveBackendUrl', () => {
  it('defaults to the loopback development backend', () => {
    const result = resolveBackendUrl({});
    expect(result).toEqual({ ok: true, url: new URL(DEFAULT_BACKEND_URL) });
  });

  it('accepts an http(s) origin, with or without a trailing slash', () => {
    expect(resolveBackendUrl({ [BACKEND_URL_ENV]: 'http://localhost:9090/' })).toEqual({
      ok: true,
      url: new URL('http://localhost:9090')
    });
    expect(resolveBackendUrl({ [BACKEND_URL_ENV]: 'https://hyperdrive.example' }).ok).toBe(true);
  });

  it.each([
    ['not a URL', 'not a url'],
    ['a non-http scheme', 'file:///C:/Windows'],
    ['embedded credentials', 'http://user:pass@127.0.0.1:18080'],
    ['a path', 'http://127.0.0.1:18080/api'],
    ['a query string', 'http://127.0.0.1:18080/?x=1'],
    ['a fragment', 'http://127.0.0.1:18080/#x']
  ])('rejects %s', (_label, value) => {
    expect(resolveBackendUrl({ [BACKEND_URL_ENV]: value }).ok).toBe(false);
  });
});
