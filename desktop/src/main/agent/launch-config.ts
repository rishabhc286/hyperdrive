import { isAbsolute, join, resolve } from 'node:path';
import type { LaunchConfig } from './agent-manager';
import { resolveJava, type JavaEnv } from './java-discovery';

export interface LaunchEnv extends JavaEnv {
  /** Absolute path of hyperdrive-agent.jar (overrides the development default). */
  readonly HYPERDRIVE_AGENT_JAR?: string | undefined;
  readonly HYPERDRIVE_AGENT_LOG_LEVEL?: string | undefined;
}

const LEVELS = ['ERROR', 'WARN', 'INFO', 'DEBUG'] as const;

/**
 * Where the agent jar, Java and the agent's data directory come from. All inputs are trusted
 * main-process configuration (environment of the Electron process, Electron's own paths); the
 * renderer has no say. A packaged app has no bundled agent/JRE yet, so it reports not-packaged
 * unless HYPERDRIVE_AGENT_JAR is set explicitly.
 */
export function resolveLaunchConfig(options: {
  readonly env: LaunchEnv;
  readonly platform: NodeJS.Platform;
  readonly isPackaged: boolean;
  /** Electron app.getAppPath(): the desktop/ folder during development. */
  readonly appPath: string;
  /** Electron app.getPath('userData'). */
  readonly userData: string;
  readonly fileExists: (path: string) => boolean;
}): LaunchConfig {
  const { env } = options;
  const explicitJar = env.HYPERDRIVE_AGENT_JAR?.trim();
  let jarPath: string;
  if (explicitJar) {
    if (!isAbsolute(explicitJar)) return { error: 'artifact-missing' };
    jarPath = explicitJar;
  } else if (options.isPackaged) {
    return { error: 'not-packaged' };
  } else {
    jarPath = resolve(options.appPath, '..', 'agent', 'target', 'hyperdrive-agent.jar');
  }
  const level = (env.HYPERDRIVE_AGENT_LOG_LEVEL ?? 'INFO').toUpperCase();
  return {
    java: resolveJava(env, options.platform, options.fileExists),
    jarPath,
    dataDir: join(options.userData, 'agent'),
    logLevel: (LEVELS as readonly string[]).includes(level) ? (level as (typeof LEVELS)[number]) : 'INFO'
  };
}
