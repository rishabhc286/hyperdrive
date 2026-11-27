import { ChildProcess, execFile, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync, statSync } from 'node:fs';
import type { AgentChild, AgentPath, AgentPostPath, AgentPostResponse } from './agent-manager';
import { parseJavaMajor } from './java-discovery';

/** Real implementations of the AgentManager's process and network dependencies. */

export function spawnAgent(command: string, args: readonly string[]): AgentChild {
  // shell:false (the default) and an argv array: paths are never parsed by a shell.
  const child = spawn(command, [...args], { shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
  if (process.platform !== 'win32') return child;
  // Windows: "java" from PATH is often Oracle's javapath launcher, which starts the real JVM as its
  // own child. Killing only the launcher would orphan a hung JVM (verified on this platform), so a
  // forced stop ends the process TREE of the child this manager spawned, by PID (never by name).
  const pid = child.pid;
  return Object.assign(child, {
    kill: (): boolean => (pid === undefined ? ChildProcess.prototype.kill.call(child) : killProcessTree(pid, child))
  });
}

/** taskkill from System32 by absolute path (no PATH lookup), for one owned PID and its descendants. */
export function taskkillArgs(pid: number, systemRoot: string | undefined): { command: string; args: string[] } {
  if (!Number.isSafeInteger(pid) || pid <= 0) throw new Error('invalid pid');
  const root = systemRoot && /^[A-Za-z]:\\[^"<>|?*]*$/.test(systemRoot) ? systemRoot : 'C:\\Windows';
  return { command: `${root}\\System32\\taskkill.exe`, args: ['/PID', String(pid), '/T', '/F'] };
}

function killProcessTree(pid: number, child: ChildProcess): boolean {
  try {
    const { command, args } = taskkillArgs(pid, process.env['SystemRoot']);
    execFile(command, args, { shell: false, windowsHide: true, timeout: 10_000 }, (error) => {
      if (error) ChildProcess.prototype.kill.call(child); // fall back to the child itself
    });
    return true;
  } catch {
    return ChildProcess.prototype.kill.call(child);
  }
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

/** 4 MiB of file bytes plus headroom; anything larger is cut off and rejected. */
const MAX_POST_RESPONSE = 4 * 1024 * 1024 + 64 * 1024;

/**
 * Authenticated JSON POST to the agent (127.0.0.1 + validated port only). The response body is read
 * with a hard cap; bytes stay in the main process.
 */
export async function agentHttpPost(
  port: number,
  path: AgentPostPath,
  secret: string,
  body: unknown,
  timeoutMs: number,
  signal?: AbortSignal
): Promise<AgentPostResponse> {
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid agent port');
  const timeout = AbortSignal.timeout(timeoutMs);
  const response = await fetch(`http://127.0.0.1:${port}${path}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${secret}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    redirect: 'error',
    cache: 'no-store',
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout
  });
  const headers: Record<string, string> = {};
  response.headers.forEach((value, name) => {
    const n = name.toLowerCase();
    if (n === 'content-type' || n.startsWith('x-hyperdrive-')) headers[n] = value;
  });
  const chunks: Uint8Array[] = [];
  let total = 0;
  const reader = response.body?.getReader();
  while (reader) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_POST_RESPONSE) {
      await reader.cancel();
      throw new Error('Agent response too large');
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
    chunk.fill(0);
  }
  return { status: response.status, headers, body: out };
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}
