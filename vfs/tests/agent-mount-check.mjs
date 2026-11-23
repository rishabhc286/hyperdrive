// SPDX-License-Identifier: GPL-3.0-only
// Opt-in Windows integration check of the REAL chain in normal (non-fixture) mode:
//   real agent jar  <-capability-  real hyperdrive-vfs.exe (WinFsp mount)
//   real agent      -metadata grant->  LOCAL STAND-IN backend on 127.0.0.1 (not the real backend,
//                                       not a cloud provider)
// Verifies: stdin bootstrap, capability auth, agent->backend grant use, real Explorer-visible
// listing, and that revoking the capability in the agent makes the drive unmount itself.
//
//   node vfs/tests/agent-mount-check.mjs <path\to\hyperdrive-agent.jar> <path\to\hyperdrive-vfs.exe>
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
// Async fs on purpose: the stand-in backend runs in THIS process, so blocking calls would stall it.
import { readdir, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const jar = resolve(process.argv[2] ?? 'agent/target/hyperdrive-agent.jar');
const exe = resolve(process.argv[3] ?? 'vfs/build/hyperdrive-vfs.exe');
const results = [];
const check = (name, ok, detail = '') => {
  results.push(ok);
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` (${detail})` : ''}`);
};

// --- Stand-in backend: only the two grant-scoped metadata routes. ---
const GRANT = `hdm_${randomBytes(32).toString('base64url')}`;
const ACCOUNT = '11111111-2222-4333-8444-555555555555';
const backendRequests = [];
const backend = createServer((req, res) => {
  backendRequests.push(`${req.method} ${req.url} ${req.headers.authorization === `Bearer ${GRANT}` ? 'grant-ok' : 'bad-auth'}`);
  if (req.headers.authorization !== `Bearer ${GRANT}`) {
    res.writeHead(401, { 'content-type': 'application/json' }).end('{"code":"unauthenticated"}');
    return;
  }
  const url = new URL(req.url, 'http://127.0.0.1');
  if (url.pathname === '/api/v1/vfs/accounts') {
    res.writeHead(200, { 'content-type': 'application/json' }).end(
      JSON.stringify({ items: [{ id: ACCOUNT, provider: 'DROPBOX', rootType: 'USER', status: 'CONNECTED' }] })
    );
  } else if (url.pathname === `/api/v1/vfs/accounts/${ACCOUNT}/files`) {
    const dir = url.searchParams.get('directory');
    const items = dir
      ? [{ id: 'id:n1', name: 'inner.txt', kind: 'FILE', sizeBytes: 77, modifiedAt: '2026-02-03T04:05:06Z', directoryLocator: null }]
      : [
          { id: 'id:f1', name: 'Projects', kind: 'FOLDER', sizeBytes: null, modifiedAt: null, directoryLocator: '/projects' },
          { id: 'id:a1', name: 'Plan.pdf', kind: 'FILE', sizeBytes: 4096, modifiedAt: '2026-01-02T03:04:05Z', directoryLocator: null }
        ];
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ items, continuation: null }));
  } else {
    res.writeHead(404, { 'content-type': 'application/json' }).end('{"code":"not_found"}');
  }
});
await new Promise((r) => backend.listen(0, '127.0.0.1', r));
const backendOrigin = `http://127.0.0.1:${backend.address().port}`;

// --- Real agent ---
const secret = randomBytes(32).toString('base64url');
const dataDir = mkdtempSync(join(tmpdir(), 'hd-agent-mount-'));
const agent = spawn('java', ['-jar', jar], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
agent.stdin.write(`${JSON.stringify({ protocolVersion: 1, secret, dataDir, logLevel: 'INFO', requestTimeoutMs: 30000 })}\n`);
let agentErr = '';
let vfsErr = '';
agent.stderr.on('data', (c) => (agentErr += c));
const port = await new Promise((r, j) => {
  let out = '';
  agent.stdout.on('data', (c) => {
    out += c;
    const nl = out.indexOf('\n');
    if (nl >= 0) r(JSON.parse(out.slice(0, nl)).port);
  });
  setTimeout(() => j(new Error('agent handshake timeout')), 30000);
});
const admin = (path, body) =>
  fetch(`http://127.0.0.1:${port}${path}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${secret}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }).then(async (r) => ({ status: r.status, body: await r.json() }));

const mount = await admin('/local/v1/vfs/mount', {
  userId: '22222222-3333-4444-8555-666666666666',
  backendOrigin,
  grant: GRANT,
  grantExpiresAt: new Date(Date.now() + 600_000).toISOString()
});
check('agent issued a mount capability', mount.status === 201 && /^hdv_/.test(mount.body.capability));

// --- Real VFS, normal mode ---
const letter = [...'ZYXWVUTSRQPONMLKJI'].find((l) => !existsSync(`${l}:\\`));
const vfs = spawn(exe, [], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
const events = [];
vfs.stderr.on('data', (c) => (vfsErr += c));
let vbuf = '';
const exited = new Promise((r) => vfs.on('exit', r));
const mounted = new Promise((r, j) => {
  vfs.stdout.on('data', (c) => {
    vbuf += c;
    let nl;
    while ((nl = vbuf.indexOf('\n')) >= 0) {
      const e = JSON.parse(vbuf.slice(0, nl));
      vbuf = vbuf.slice(nl + 1);
      events.push(e);
      if (e.event === 'started') {
        vfs.stdin.write(`${JSON.stringify({ protocolVersion: 1, agentPort: port, capability: mount.body.capability, mount: { driveLetter: letter } })}\n`);
      }
      if (e.event === 'mounted') r();
      if (e.event === 'failed') j(new Error(e.code));
    }
  });
  setTimeout(() => j(new Error('mount timeout')), 30000);
});
await mounted;
check('real mount reached "mounted" through the agent', events.map((e) => e.event).join(',') === 'started,agent-authenticated,volume-created,mounted');
check('no event carries the capability', !JSON.stringify(events).includes(mount.body.capability));

const root = await readdir(`${letter}:\\`);
check('root shows the account directory without email', root.length === 1 && /^Dropbox - Personal \[[0-9a-f]{8}\]$/.test(root[0]), root.join('|'));
const acct = `${letter}:\\${root[0]}`;
check('account listing from the backend via the agent', (await readdir(acct)).sort().join('|') === 'Plan.pdf|Projects');
check('file size from metadata', (await stat(`${acct}\\Plan.pdf`)).size === 4096);
check('nested listing uses the folder locator', (await readdir(`${acct}\\Projects`)).join('|') === 'inner.txt');
check('backend saw only grant-authenticated metadata routes',
  backendRequests.length > 0 && backendRequests.every((r) => r.endsWith('grant-ok') && r.includes('/api/v1/vfs/')),
  `${backendRequests.length} requests`);
check('no request reached any other backend path', !backendRequests.some((r) => /read-credentials|\/api\/v1\/accounts/.test(r)));

// --- Revoke the capability in the agent: the drive must unmount itself (heartbeat <= ~10 s) ---
const revoked = await admin('/local/v1/vfs/mount/revoke', {});
check('capability revoked in the agent', revoked.body.revoked === true);
const code = await Promise.race([exited, new Promise((r) => setTimeout(() => r('timeout'), 25000))]);
check('VFS unmounted itself after revocation', events.some((e) => e.event === 'unmounted' && e.reason === 'authorization-lost'), String(code));
check('drive letter released', !existsSync(`${letter}:\\`));
check('agent logs contain neither grant nor capability', !agentErr.includes(GRANT) && !agentErr.includes(mount.body.capability));

agent.stdin.end();
await new Promise((r) => agent.on('exit', r));
backend.close();
rmSync(dataDir, { recursive: true, force: true });
const failed = results.filter((ok) => !ok).length;
console.log(`${results.length} checks, ${failed} failed (drive ${letter}:)`);
process.exit(failed === 0 ? 0 : 1);
