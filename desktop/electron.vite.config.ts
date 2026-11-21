import react from '@vitejs/plugin-react';
import { defineConfig } from 'electron-vite';
import type { Plugin } from 'vite';

/**
 * Content Security Policy for the renderer.
 * - Build: strict, everything from the app bundle only, no network access from the renderer
 *   (backend calls go through the main process).
 * - Dev: additionally allows the inline React Refresh preamble, injected <style> tags and the
 *   Vite HMR websocket. These relaxations never reach a built app.
 */
const productionCsp = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
  "object-src 'none'",
  "frame-src 'none'"
].join('; ');

const developmentCsp = [
  "default-src 'none'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'self' ws://localhost:* ws://127.0.0.1:*",
  "base-uri 'none'",
  "form-action 'none'",
  "object-src 'none'",
  "frame-src 'none'"
].join('; ');

function contentSecurityPolicy(): Plugin {
  let isDevServer = false;
  return {
    name: 'hyperdrive-csp',
    configResolved(config) {
      isDevServer = config.command === 'serve';
    },
    transformIndexHtml() {
      return [
        {
          tag: 'meta',
          attrs: {
            'http-equiv': 'Content-Security-Policy',
            content: isDevServer ? developmentCsp : productionCsp
          },
          injectTo: 'head-prepend'
        }
      ];
    }
  };
}

export default defineConfig({
  main: {},
  preload: {},
  renderer: {
    plugins: [react(), contentSecurityPolicy()],
    build: { minify: true }
  }
});
