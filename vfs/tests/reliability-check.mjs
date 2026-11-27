// SPDX-License-Identifier: GPL-3.0-only
// Opt-in Windows reliability check (Batch 10) of the mounted drive through the REAL agent and VFS,
// with the TEST-ONLY fixture provider (FixtureAgentMain) and a local stand-in backend. Measures and
// checks: first-byte/seek latency, sequential throughput, read amplification, memory during a 5 GiB
// read, renewal of a short-lived credential during a long read, backend outage, network drop,
// Explorer-style retry storms, and the desktop ("Electron") process being killed.
// Fixture evidence only; NOT live Google/Dropbox verification.
//
//   node vfs/tests/reliability-check.mjs agent vfs/build/hyperdrive-vfs.exe
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createReadStream, existsSync, rmSync } from 'node:fs';
import { open, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import {
  BOX, DRIVE, GiB, MiB, checker, expected, freeLetter, mountAndPump, sleep, startAgent, startBackend, startVfs, workingSet
} from './lib/fixture-harness.mjs';

const agentDir = resolve(process.argv[2] ?? 'agent');
const exe = resolve(process.argv[3] ?? 'vfs/build/hyperdrive-vfs.exe');
const childMode = process.argv[4] === '--child';
const tokens = { google: `fixture-google-${randomBytes(16).toString('hex')}`, dropbox: `fixture-dropbox-${randomBytes(16).toString('hex')}` };
const FILES = [
  { account: BOX, id: 'id:relbig', name: 'big.bin', provider: 'DROPBOX', size: 5 * GiB + 777, seed: 3, rev: '2a1b2c3d4e5' },
  { account: BOX, id: 'id:relsmall', name: 'small.bin', provider: 'DROPBOX', size: 3 * MiB, seed: 1, rev: '0a1b2c3d4e5' },
  { account: BOX, id: 'id:relgone', name: 'deleted-remotely.bin', provider: 'DROPBOX', size: 1000, seed: 9, rev: '9a1b2c3d4e5' },
  { account: DRIVE, id: 'relgoogle', name: 'google-512MiB.bin', provider: 'GOOGLE_DRIVE', size: 512 * MiB, seed: 6, rev: null },
  { account: DRIVE, id: 'relgnet', name: 'google-net.bin', provider: 'GOOGLE_DRIVE', size: 256 * MiB, seed: 8, rev: null },
  { account: DRIVE, id: 'relgsmall', name: 'google-small.bin', provider: 'GOOGLE_DRIVE', size: 2 * MiB, seed: 7, rev: null }
];
const byName = Object.fromEntries(FILES.map((f) => [f.name, f]));

// --- Child mode: plays a desktop process that owns an agent and a mounted VFS, then gets killed ---
if (childMode) {
  const backend = await startBackend(FILES);
  const agent = await startAgent(agentDir, FILES, tokens);
  const state = await mountAndPump(agent, backend, tokens);
  const letter = freeLetter();
  const vfs = startVfs(exe, agent.port, state.capability, letter);
  await vfs.mounted;
  console.log(JSON.stringify({ agentPid: agent.proc.pid, vfsPid: vfs.proc.pid, letter, work: agent.work }));
  await new Promise(() => {}); // until killed
}

const { check, failed } = checker();
const evidence = [];
const backend = await startBackend(FILES, { missing: ['id:relgone'] });
const agent = await startAgent(agentDir, FILES, tokens);
// The first Dropbox credential has only 100 s left (< the 2-min renew-ahead margin); later ones 1 h.
const state = await mountAndPump(agent, backend, tokens, { expiresIn: (account, n) => (account === BOX && n === 0 ? 100 : 3600) });
await agent.admin('/local/v1/cache/session', { userId: '22222222-3333-4444-8555-666666666666', key: randomBytes(32).toString('base64'), fresh: true, budgetBytes: 2 * GiB });
const letter = freeLetter();
const vfs = startVfs(exe, agent.port, state.capability, letter);
await vfs.mounted;
const { readdir } = await import('node:fs/promises');
const root = (await readdir(`${letter}:\\`)).sort();
const path = (f) => `${letter}:\\${root[f.account === BOX ? 0 : 1]}\\${f.name}`;
check('mounted through the agent with the fixture provider', root.length === 2);

// 1. Latency and amplification (cold).
const timedRead = async (h, offset, length) => {
  const b = Buffer.alloc(length);
  const t0 = process.hrtime.bigint();
  const { bytesRead } = await h.read(b, 0, length, offset);
  return { ms: Number(process.hrtime.bigint() - t0) / 1e6, ok: b.subarray(0, bytesRead) };
};
const median = (xs) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)];
for (const f of [byName['big.bin'], byName['google-512MiB.bin']]) {
  const before = await agent.stats();
  const t0 = process.hrtime.bigint();
  const h = await open(path(f), 'r');
  const first = await timedRead(h, 0, 4096);
  const firstMs = Number(process.hrtime.bigint() - t0) / 1e6;
  const seeks = [];
  let exact = first.ok.equals(expected(f.seed, 0, 4096));
  for (let i = 1; i <= 8; i++) {
    const at = Math.floor((f.size / 9) * i);
    const r = await timedRead(h, at, 4096);
    seeks.push(r.ms);
    exact &&= r.ok.equals(expected(f.seed, at, 4096));
  }
  await h.close();
  const after = await agent.stats();
  const requested = 9 * 4096;
  check(`${f.provider} cold open + first 4 KiB and 8 cold seeks are exact`, exact);
  evidence.push(`${f.provider} first byte (open + 4 KiB read): ${firstMs.toFixed(1)} ms; cold seek 4 KiB: median ${median(seeks).toFixed(1)} ms, max ${Math.max(...seeks).toFixed(1)} ms; app requested ${requested} B, agent returned ${after.bytesReturned - before.bytesReturned} B, provider fetched ${after.providerBytes - before.providerBytes} B (amplification ${((after.providerBytes - before.providerBytes) / requested).toFixed(1)}x), agent reads ${after.reads - before.reads}`);
}

// 2. Sequential throughput + memory over a 5 GiB Dropbox file (cache budget 2 GiB: eviction runs).
const streamFile = (f, onChunk) => new Promise((res, rej) => {
  let pos = 0;
  let sampleOk = true;
  const s = createReadStream(path(f), { highWaterMark: 1 * MiB });
  s.on('data', (c) => {
    // Sample 32 bytes at the start of every 64 MiB.
    const mark = Math.ceil(pos / (64 * MiB)) * 64 * MiB;
    if (mark >= pos && mark + 32 <= pos + c.length && !c.subarray(mark - pos, mark - pos + 32).equals(expected(f.seed, mark, 32))) sampleOk = false;
    pos += c.length;
    onChunk?.(pos);
  });
  s.on('end', () => res({ bytes: pos, sampleOk }));
  s.on('error', (e) => rej(e));
});
const mem = { agent: [], vfs: [] };
const sampler = setInterval(async () => {
  mem.agent.push(await workingSet(agent.proc.pid));
  mem.vfs.push(await workingSet(vfs.proc.pid));
}, 5000);
let before = await agent.stats();
let t0 = Date.now();
const big = await streamFile(byName['big.bin']);
let secs = (Date.now() - t0) / 1000;
clearInterval(sampler);
let after = await agent.stats();
check('5 GiB sequential read completes with correct sampled bytes', big.bytes === byName['big.bin'].size && big.sampleOk, `${big.bytes} B`);
evidence.push(`Dropbox sequential 5 GiB (buffered, 1 MiB app reads): ${(big.bytes / MiB / secs).toFixed(1)} MiB/s over ${secs.toFixed(1)} s; agent reads ${after.reads - before.reads}, provider ${after.providerBytes - before.providerBytes} B, cache ${after.cacheBytes - before.cacheBytes} B`);
const mb = (xs) => xs.filter(Boolean).map((x) => Math.round(x / MiB));
evidence.push(`working set during the 5 GiB read (MiB, every 5 s): agent ${JSON.stringify(mb(mem.agent))}; vfs ${JSON.stringify(mb(mem.vfs))}`);
const vfsMem = mb(mem.vfs);
check('VFS memory stays bounded during a 5 GiB read (no growth with bytes read)', vfsMem.length > 0 && Math.max(...vfsMem) < 200, `max ${Math.max(...vfsMem)} MiB`);
const cacheStatus = (await agent.call('GET', '/local/v1/cache/status')).body;
check('encrypted cache stayed within its 2 GiB budget during the read', cacheStatus.encryptedBytesOnDisk <= 2 * GiB + 64 * MiB, `${Math.round(cacheStatus.encryptedBytesOnDisk / MiB)} MiB on disk, evictions ${cacheStatus.counters?.evictions}`);
const boxInstalls = state.installs.filter((i) => i.accountId === BOX);
check('a short-lived credential was renewed exactly once during the long read', boxInstalls.length === 2 && boxInstalls.every((i) => i.status === 201), `${boxInstalls.length} Dropbox installs`);

// 3. Google sequential throughput, with a backend outage in the middle.
before = await agent.stats();
t0 = Date.now();
const googleRead = streamFile(byName['google-512MiB.bin']);
await sleep(1000);
await backend.stop();
const outageAt = Date.now();
let openDuringOutage;
try {
  await readFile(path(byName['google-small.bin']));
  openDuringOutage = 'succeeded';
} catch (e) {
  openDuringOutage = `failed:${e.code}`;
}
const outageOpenMs = Date.now() - outageAt;
const g = await googleRead.then((r) => r, (e) => ({ error: e.code }));
secs = (Date.now() - t0) / 1000;
after = await agent.stats();
check('backend outage: reads on an already open file keep working (no backend needed)', g.bytes === 512 * MiB && g.sampleOk, JSON.stringify(g));
check('backend outage: opening a new file fails in bounded time', openDuringOutage.startsWith('failed') && outageOpenMs < 30000, `${openDuringOutage} after ${outageOpenMs} ms`);
evidence.push(`Google sequential 512 MiB (uncached): ${(512 / secs).toFixed(1)} MiB/s; provider ${after.providerBytes - before.providerBytes} B for 536870912 B read (amplification ${((after.providerBytes - before.providerBytes) / (512 * MiB)).toFixed(2)}x), cache ${after.cacheBytes - before.cacheBytes} B`);
await backend.restart();
await sleep(1500); // the transient open failure backs off 1 s per open
const reopened = await readFile(path(byName['google-small.bin'])).then((b) => b.equals(expected(7, 0, 2 * MiB)), () => false);
check('backend restored: the same file opens and reads correctly', reopened);

// 4. Network drop mid-read (fixture provider cuts connections), then restored.
const netFile = byName['google-net.bin'];
let netPos = 0;
const netRead = streamFile(netFile, (p) => (netPos = p)).then(() => 'completed', (e) => `failed:${e.code}`);
await sleep(300);
await agent.network('down');
const dropAt = Date.now();
const netOutcome = await Promise.race([netRead, sleep(60000).then(() => 'still-running')]);
const dropMs = Date.now() - dropAt;
check('network drop: the read fails (not hangs, not zero-filled) within bounded time', netOutcome.startsWith('failed') && dropMs < 50000, `${netOutcome} after ${dropMs} ms at ${netPos} B`);
await agent.network('up');
const h = await open(path(netFile), 'r');
const r = await timedRead(h, 100 * MiB, 64 * 1024).catch(() => ({ ok: Buffer.alloc(0) }));
await h.close();
check('network restored: a new open reads the right bytes', r.ok.equals(expected(netFile.seed, 100 * MiB, 64 * 1024)));

// 5. Explorer-style retry storm on a file that was deleted remotely.
const gone = byName['deleted-remotely.bin'];
const metaRequests = () => backend.requests.filter((x) => x.includes(encodeURIComponent(gone.id)) || x.includes(gone.id)).length;
const beforeMeta = metaRequests();
const stormStart = Date.now();
let errors = 0;
for (let i = 0; i < 30; i++) {
  try {
    await readFile(path(gone));
  } catch {
    errors++;
  }
}
const stormMs = Date.now() - stormStart;
const metaCalls = metaRequests() - beforeMeta;
check('30 repeated opens of a remotely deleted file all fail quickly', errors === 30 && stormMs < 10000, `${stormMs} ms`);
check('...and cause at most a few backend lookups (negative cache), not 30', metaCalls <= 3, `${metaCalls} metadata requests`);

// Teardown of the main mount.
vfs.proc.stdin.write('unmount\n');
await Promise.race([vfs.exited, sleep(20000)]);
check('unmounted cleanly', vfs.events.some((e) => e.event === 'unmounted') && !existsSync(`${letter}:\\`));
state.pumping = false;
await agent.admin('/local/v1/vfs/mount/revoke', {}).catch(() => undefined);
agent.proc.stdin.end();
await Promise.race([agent.exited, sleep(15000)]);
check('agent exited after its parent channel closed', agent.proc.exitCode !== null);
const secrets = [tokens.google, tokens.dropbox, state.capability, backend.grant, 'hdf_', 'hdh_'];
check('no secrets in agent or VFS output', !secrets.some((s) => agent.err.includes(s) || vfs.err.includes(s) || JSON.stringify(vfs.events).includes(s)));

// 6. The desktop process is killed (TerminateProcess): its agent and VFS must clean up by themselves.
const child = spawn(process.execPath, [fileURLToPath(import.meta.url), agentDir, exe, '--child'], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
const info = await new Promise((res, rej) => {
  let out = '';
  child.stdout.on('data', (c) => {
    out += c;
    const nl = out.indexOf('\n');
    if (nl >= 0) res(JSON.parse(out.slice(0, nl)));
  });
  setTimeout(() => rej(new Error('child did not mount')), 60000);
});
check('child "desktop" mounted a drive', existsSync(`${info.letter}:\\`), info.letter);
const killedAt = Date.now();
child.kill(); // TerminateProcess of that one process only; its children are not targeted
const alive = (pid) => new Promise((res) => {
  const p = spawn('tasklist', ['/FI', `PID eq ${pid}`, '/NH'], { windowsHide: true });
  let out = '';
  p.stdout.on('data', (c) => (out += c));
  p.on('exit', () => res(out.includes(String(pid))));
});
let vfsGone = false;
let agentGone = false;
for (let i = 0; i < 60 && !(vfsGone && agentGone); i++) {
  await sleep(500);
  vfsGone ||= !(await alive(info.vfsPid));
  agentGone ||= !(await alive(info.agentPid));
}
const cleanupMs = Date.now() - killedAt;
check('desktop killed: the VFS unmounted and exited by itself (parent channel closed)', vfsGone && !existsSync(`${info.letter}:\\`), `${cleanupMs} ms`);
check('desktop killed: the agent shut down by itself (no orphan)', agentGone, `${cleanupMs} ms`);
rmSync(info.work, { recursive: true, force: true });
rmSync(agent.work, { recursive: true, force: true });

await backend.stop();
for (const e of evidence) console.log(`INFO ${e}`);
console.log(`${failed() === 0 ? 'OK' : 'FAILED'}: ${failed()} failed -- FIXTURE provider on this PC, not live Google/Dropbox`);
process.exit(failed() === 0 ? 0 : 1);
