import { execFile, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync, statSync } from 'node:fs';
import type { AgentChild, AgentPath } from './agent-manager';
import { parseJavaMajor } from './java-discovery';

/** Real implementations of the AgentManager's process and network dependencies. */

export function spawnAgent(command: string, args: readonly string[]): AgentChild {
  // shell:false (the default) and an argv array: paths are never parsed by a shell.
  return spawn(command, [...args], { shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
}

export function checkJava(command: string): Promise<{ major: number } | { error: 'not-found' | 'unknown' }> {
  return new Promise((resolve) => {
    execFile(command, ['-version'], { shell: false, windowsHide: true, timeout: 15_000, maxBuffer: 64 * 1024 }, (error, stdout, stderr) => {
      if (error && (error as NodeJS.ErrnoException).code === 'ENOENT') {
        resolve({ error: 'not-found' });
        return;
      }
      // `java -version` prints to stderr; this parses only the version token.
      const major = parseJavaMajor(`${stderr}\n${stdout}`);
      resolve(major === null ? { error: 'unknown' } : { major });
    });
  });
}

export function isFile(path: string): boolean {
  try {
    return existsSync(path) && statSync(path).isFile();
  } catch {
    return false;
  }
}

/** 256 random bits, base64url (43 characters). */
export function randomSecret(): string {
  return randomBytes(32).toString('base64url');
}

/**
 * Authenticated GET to the agent. The URL is always built here from 127.0.0.1 and a validated
 * port; the child never supplies a URL. No redirects, bounded time and size.
 */
export async function agentHttpGet(
  port: number,
  path: AgentPath,
  secret: string,
  timeoutMs: number
): Promise<{ status: number; body: unknown }> {
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid agent port');
  const response = await fetch(`http://127.0.0.1:${port}${path}`, {
    method: 'GET',
    headers: { Authorization: `Bearer ${secret}`, Accept: 'application/json' },
    redirect: 'error',
    cache: 'no-store',
    signal: AbortSignal.timeout(timeoutMs)
  });
  const text = await response.text();
  if (text.length > 16 * 1024) throw new Error('Agent response too large');
  return { status: response.status, body: parseJson(text) };
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}
