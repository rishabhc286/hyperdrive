import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { safeEqual } from './pkce';

export const CALLBACK_PATH = '/callback';
const HANDOFF_CODE = /^hdc_[A-Za-z0-9_-]{43}$/;
const ALLOWED_ERRORS = new Set(['access_denied', 'login_failed']);

export type LoopbackResult =
  | { readonly type: 'code'; readonly code: string }
  | { readonly type: 'error'; readonly error: 'access_denied' | 'login_failed' };

export class LoopbackTimeoutError extends Error {
  constructor() {
    super('Timed out waiting for the browser sign-in to finish');
    this.name = 'LoopbackTimeoutError';
  }
}

export class LoopbackAbortedError extends Error {
  constructor() {
    super('Sign-in was cancelled');
    this.name = 'LoopbackAbortedError';
  }
}

export interface LoopbackHandle {
  readonly port: number;
  /** Settles once: with the callback result, or rejects on timeout/abort. */
  readonly result: Promise<LoopbackResult>;
  /** Idempotent. Stops listening and drops open connections. */
  close(): void;
  isListening(): boolean;
}

export interface LoopbackOptions {
  readonly expectedState: string;
  readonly timeoutMs: number;
  readonly signal?: AbortSignal;
}

const PAGE_HEADERS = {
  'Content-Type': 'text/html; charset=utf-8',
  'Cache-Control': 'no-store',
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
  'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'",
  Connection: 'close'
};

function page(title: string, message: string): string {
  return (
    '<!doctype html><html lang="en"><head><meta charset="utf-8"><title>HyperDrive</title></head>' +
    '<body style="font-family:system-ui,sans-serif;background:#0f1217;color:#e6e9ee;max-width:36rem;margin:15vh auto;padding:0 1rem">' +
    `<h1 style="font-size:1.4rem">${title}</h1><p>${message}</p></body></html>`
  );
}

/**
 * Temporary HTTP listener on 127.0.0.1 with an OS-assigned port, accepting exactly one valid
 * callback: GET /callback with the expected state. Anything else is refused without ending the
 * attempt, so a stray or hostile local request cannot cancel or hijack a sign-in.
 */
export async function startLoopbackListener(options: LoopbackOptions): Promise<LoopbackHandle> {
  let settle: ((r: LoopbackResult) => void) | undefined;
  let fail: ((e: Error) => void) | undefined;
  const result = new Promise<LoopbackResult>((resolve, reject) => {
    settle = resolve;
    fail = reject;
  });
  // Avoid unhandled-rejection noise if nobody awaits after close().
  result.catch(() => undefined);

  let port = 0;
  let closed = false;
  let timer: ReturnType<typeof setTimeout> | undefined = undefined;

  const server: Server = createServer((req: IncomingMessage, res: ServerResponse) => {
    const outcome = handle(req, port, options.expectedState);
    res.writeHead(outcome.status, PAGE_HEADERS);
    res.end(page(outcome.title, outcome.message));
    if (outcome.result) {
      settle?.(outcome.result);
      close();
    }
  });

  const onAbort = (): void => {
    fail?.(new LoopbackAbortedError());
    close();
  };

  function close(): void {
    if (closed) return;
    closed = true;
    if (timer) clearTimeout(timer);
    options.signal?.removeEventListener('abort', onAbort);
    server.close();
    server.closeAllConnections();
  }

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      server.off('error', reject);
      resolve();
    });
  });
  port = (server.address() as AddressInfo).port;

  timer = setTimeout(() => {
    fail?.(new LoopbackTimeoutError());
    close();
  }, options.timeoutMs);

  if (options.signal?.aborted) {
    onAbort();
  } else {
    options.signal?.addEventListener('abort', onAbort, { once: true });
  }

  return { port, result, close, isListening: () => server.listening };
}

interface Outcome {
  status: number;
  title: string;
  message: string;
  result?: LoopbackResult;
}

function handle(req: IncomingMessage, port: number, expectedState: string): Outcome {
  const rejected: Outcome = {
    status: 400,
    title: 'Invalid sign-in response',
    message: 'This page was not opened by a HyperDrive sign-in. Return to HyperDrive.'
  };
  // DNS-rebinding guard: only accept the exact loopback host we listen on.
  if (req.method !== 'GET' || req.headers.host !== `127.0.0.1:${port}`) {
    return rejected;
  }
  const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
  if (url.pathname !== CALLBACK_PATH) {
    return { status: 404, title: 'Not found', message: 'Return to HyperDrive.' };
  }
  const state = url.searchParams.get('state');
  if (state === null || !safeEqual(state, expectedState)) {
    return rejected;
  }
  const code = url.searchParams.get('code');
  if (code !== null && HANDOFF_CODE.test(code)) {
    return {
      status: 200,
      title: 'You’re signed in',
      message: 'You can close this tab and return to HyperDrive.',
      result: { type: 'code', code }
    };
  }
  const error = url.searchParams.get('error');
  if (error !== null && ALLOWED_ERRORS.has(error)) {
    return {
      status: 200,
      title: error === 'access_denied' ? 'Sign-in cancelled' : 'Sign-in didn’t complete',
      message: 'You can close this tab and return to HyperDrive.',
      result: { type: 'error', error: error as 'access_denied' | 'login_failed' }
    };
  }
  return rejected;
}
