// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { isTrustedRendererUrl } from './security';

const rendererFileUrl = 'file:///C:/Program%20Files/HyperDrive/resources/app/out/renderer/index.html';

describe('isTrustedRendererUrl', () => {
  describe('built app', () => {
    const origin = { rendererFileUrl };

    it('trusts the exact bundled renderer file, ignoring fragment and query', () => {
      expect(isTrustedRendererUrl(rendererFileUrl, origin)).toBe(true);
      expect(isTrustedRendererUrl(`${rendererFileUrl}#files`, origin)).toBe(true);
    });

    it.each([
      'https://example.com/',
      'http://127.0.0.1:18080/api/v1/health',
      'file:///C:/Users/someone/Downloads/renderer/index.html',
      'file:///C:/Windows/System32/',
      'not a url'
    ])('rejects %s', (url) => {
      expect(isTrustedRendererUrl(url, origin)).toBe(false);
    });
  });

  describe('dev server', () => {
    const origin = { devServerUrl: 'http://localhost:5173', rendererFileUrl };

    it('trusts only the dev server origin', () => {
      expect(isTrustedRendererUrl('http://localhost:5173/', origin)).toBe(true);
      expect(isTrustedRendererUrl('http://localhost:5174/', origin)).toBe(false);
      expect(isTrustedRendererUrl('https://example.com/', origin)).toBe(false);
      expect(isTrustedRendererUrl(rendererFileUrl, origin)).toBe(false);
    });
  });
});
