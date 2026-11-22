// @vitest-environment node
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { AgentManager } from './agent-manager';
import { resolveJava } from './java-discovery';
import { agentHttpGet, checkJava, isFile, randomSecret, spawnAgent } from './node-runtime';

/**
 * Real smoke test: the production AgentManager with real spawn, real Java and the real agent jar
 * (agent/target/hyperdrive-agent.jar). Skipped — and reported as skipped — when the jar has not
 * been built. This exercises real process behavior on this machine, not Windows packaging.
 */
const jar = resolve(__dirname, '../../../../agent/target/hyperdrive-agent.jar');
const dataRoot = mkdtempSync(join(tmpdir(), 'hyperdrive-agent-smoke-'));

afterAll(() => rmSync(dataRoot, { recursive: true, force: true }));

describe.skipIf(!isFile(jar))('local agent smoke test (real Java process)', () => {
  const makeManager = () =>
    new AgentManager({
      launch: {
        java: resolveJava(process.env, process.platform, isFile),
        jarPath: jar,
        dataDir: join(dataRoot, 'agent'),
        logLevel: 'INFO'
      },
      fileExists: isFile,
      checkJava,
      spawn: spawnAgent,
      httpGet: agentHttpGet,
      randomSecret,
      startupTimeoutMs: 30_000
    });

  it('starts the real agent, reports truthful status, restarts and stops it', async () => {
    const manager = makeManager();
    await manager.start();
    const ready = await manager.getStatus();
    expect(ready.state).toBe('ready');
    expect(ready.agentVersion).toBe('0.1.0-SNAPSHOT');
    expect(ready.capabilities).toEqual({ providerReads: false, rangeReads: false, chunkCache: false, virtualDrive: false });
    expect(ready.uptimeMillis).toBeGreaterThanOrEqual(0);

    // A second manager on the same data directory is refused by the agent's lock.
    const intruder = makeManager();
    await intruder.start();
    expect(intruder.snapshot()).toMatchObject({ state: 'failed', error: 'data-dir-locked' });

    const restarted = await manager.restart();
    expect(restarted.state).toBe('ready');

    await manager.stop();
    expect(manager.snapshot().state).toBe('stopped');
    expect(manager.log.snapshot().join('\n')).toContain('Agent stopped');
  }, 90_000);
});
