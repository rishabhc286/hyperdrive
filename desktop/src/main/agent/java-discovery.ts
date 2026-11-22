import { isAbsolute, join } from 'node:path';

/**
 * Development Java discovery. Order:
 *   1. HYPERDRIVE_AGENT_JAVA: an absolute path to a java executable (trusted main-process config)
 *   2. JAVA_HOME/bin/java(.exe)
 *   3. "java", resolved by the OS from PATH (spawned without a shell)
 * Paths are passed to spawn() as argv values, never interpolated into shell commands.
 * Nothing is downloaded or installed, and no environment settings are changed.
 */
export type JavaCandidate =
  | { readonly ok: true; readonly command: string; readonly source: 'HYPERDRIVE_AGENT_JAVA' | 'JAVA_HOME' | 'PATH' }
  | { readonly ok: false; readonly reason: 'java-not-found' };

export interface JavaEnv {
  readonly HYPERDRIVE_AGENT_JAVA?: string | undefined;
  readonly JAVA_HOME?: string | undefined;
}

export function resolveJava(
  env: JavaEnv,
  platform: NodeJS.Platform,
  fileExists: (path: string) => boolean
): JavaCandidate {
  const exe = platform === 'win32' ? 'java.exe' : 'java';
  const explicit = env.HYPERDRIVE_AGENT_JAVA?.trim();
  if (explicit) {
    // An explicit setting is never silently ignored in favor of another Java.
    return isAbsolute(explicit) && fileExists(explicit)
      ? { ok: true, command: explicit, source: 'HYPERDRIVE_AGENT_JAVA' }
      : { ok: false, reason: 'java-not-found' };
  }
  const home = env.JAVA_HOME?.trim();
  if (home && isAbsolute(home)) {
    const candidate = join(home, 'bin', exe);
    if (fileExists(candidate)) return { ok: true, command: candidate, source: 'JAVA_HOME' };
  }
  return { ok: true, command: 'java', source: 'PATH' };
}

/** Required Java feature release for the agent jar (compiled with --release 21). */
export const REQUIRED_JAVA_MAJOR = 21;

/**
 * Major version from `java -version` output, e.g. `openjdk version "21.0.5" 2024-10-15` → 21,
 * `java version "1.8.0_401"` → 8. Null if no version string is found.
 */
export function parseJavaMajor(output: string): number | null {
  const match = /version "(\d+)(?:\.(\d+))?[^"]*"/.exec(output);
  if (!match) return null;
  const first = Number(match[1]);
  if (first === 1 && match[2] !== undefined) return Number(match[2]);
  return Number.isFinite(first) ? first : null;
}
