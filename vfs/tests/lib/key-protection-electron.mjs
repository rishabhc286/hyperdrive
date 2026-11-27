// SPDX-License-Identifier: GPL-3.0-only
// Electron main script for key-protection-check.mjs (TEST-ONLY). One run = one app start: the REAL
// Electron safeStorage (Windows DPAPI) wraps/unwraps the cache key through the REAL CacheKeyStore
// (compiled from desktop/src), the real agent (fixture provider) gets the key, and one Dropbox read
// shows whether the encrypted cache answered. Prints one JSON line; never the key or file bytes.
import { app, safeStorage } from 'electron';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [mode, userData, compiledStore, agentDir, dataDir, specJson] = process.argv.slice(-6);
app.setPath('userData', userData);
// No top-level await on whenReady: the main module must finish loading before Electron emits ready.
app.whenReady().then(main);

async function main() {
const out = { mode, encryptionAvailable: safeStorage.isEncryptionAvailable() };
try {
  const { CacheKeyStore } = createRequire(import.meta.url)(compiledStore);
  const { BOX, startAgent } = await import(new URL('./fixture-harness.mjs', import.meta.url));
  const spec = JSON.parse(specJson);
  const keyDir = join(userData, 'cache-keys');
  const keyFiles = () => { try { return readdirSync(keyDir).filter((f) => f.endsWith('.json')); } catch { return []; } };
  if (mode === 'corrupt') for (const f of keyFiles()) writeFileSync(join(keyDir, f), JSON.stringify({ v: 1, data: Buffer.from('not a DPAPI blob').toString('base64') }));
  if (mode === 'truncated') for (const f of keyFiles()) writeFileSync(join(keyDir, f), readFileSync(join(keyDir, f), 'utf8').slice(0, 20));
  if (mode === 'missing') for (const f of keyFiles()) rmSync(join(keyDir, f));
  const store = new CacheKeyStore(safeStorage, keyDir);
  out.storeAvailable = store.available;
  const got = await store.getOrCreate(spec.user);
  out.fresh = got.fresh;
  out.fingerprint = createHash('sha256').update(got.key).digest('hex').slice(0, 12);
  const fileText = keyFiles().map((f) => readFileSync(join(keyDir, f), 'utf8')).join('');
  out.keyFileHasPlainKey = fileText.includes(got.key.toString('base64')) || fileText.includes(spec.user);
  out.dpapiMarker = Buffer.from(JSON.parse(fileText).data, 'base64').subarray(0, 3).toString('latin1'); // Electron's "v10"

  const agent = await startAgent(agentDir, spec.files, spec.tokens, { dataDir });
  const session = await agent.admin('/local/v1/cache/session', { userId: spec.user, key: got.key.toString('base64'), fresh: got.fresh, budgetBytes: 1024 ** 3 });
  got.key.fill(0);
  out.cacheEnabled = session.body.enabled;
  out.entriesAtStart = session.body.entryCount;
  const cred = await agent.admin('/local/v1/credentials', { provider: 'DROPBOX', accountId: BOX, accessToken: spec.tokens.dropbox, providerExpiresAt: new Date(Date.now() + 3600_000).toISOString(), rootNamespaceId: '777' });
  const f = spec.files[0];
  const r = await fetch(`http://127.0.0.1:${agent.port}/local/v1/reads`, {
    method: 'POST', headers: { Authorization: `Bearer ${agent.secret}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ handle: cred.body.handle, fileId: f.id, offset: 1000, length: 100000, expectedSize: f.size, expectedRevision: f.rev })
  });
  const bytes = Buffer.from(await r.arrayBuffer());
  out.readStatus = r.status;
  out.cache = r.headers.get('x-hyperdrive-cache');
  out.readSha = createHash('sha256').update(bytes).digest('hex');
  out.entriesAfter = (await agent.call('GET', '/local/v1/cache/status')).body.entryCount;
  agent.proc.stdin.end();
  await Promise.race([agent.exited, new Promise((res) => setTimeout(res, 15000))]);
  out.agentLogHasKeyPath = agent.err.includes(keyDir) || agent.err.includes('cache-keys');
  rmSync(agent.work, { recursive: true, force: true });
} catch (e) {
  out.error = String(e?.message ?? e).slice(0, 200);
}
process.stdout.write(`${JSON.stringify(out)}\n`);
app.exit(0);
}
