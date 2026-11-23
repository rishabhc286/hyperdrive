// SPDX-License-Identifier: GPL-3.0-only
// Opt-in Windows integration check of the REAL WinFsp mount in FIXTURE mode (fixed test data; no
// agent, no cloud). Mounts on a free drive letter, verifies listing/metadata/read-only/honest read
// failures through the real Windows filesystem APIs, then unmounts via the stdin channel and
// verifies the owned process exited and the drive disappeared.
//
//   node vfs/tests/fixture-mount-check.mjs [path\to\hyperdrive-vfs.exe]
//
// Never run automatically by unit tests.
import { spawn } from 'node:child_process';
import { existsSync, readdirSync, statSync, readFileSync, writeFileSync, mkdirSync, rmSync, renameSync } from 'node:fs';
import { resolve } from 'node:path';

const exe = resolve(process.argv[2] ?? 'vfs/build/hyperdrive-vfs.exe');
const letter = [...'ZYXWVUTSRQPONMLKJI'].find((l) => !existsSync(`${l}:\\`));
if (!letter) throw new Error('no free drive letter');
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` (${detail})` : ''}`);
};
const expectError = (name, fn, codes) => {
  try {
    fn();
    check(name, false, 'no error');
  } catch (e) {
    check(name, codes.includes(e.code), e.code);
  }
};

const child = spawn(exe, ['--fixture-mount', letter], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
const events = [];
let buffer = '';
const mounted = new Promise((resolveMounted, reject) => {
  child.stdout.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    let nl;
    while ((nl = buffer.indexOf('\n')) >= 0) {
      const event = JSON.parse(buffer.slice(0, nl));
      buffer = buffer.slice(nl + 1);
      events.push(event);
      if (event.event === 'mounted') resolveMounted(event);
      if (event.event === 'failed') reject(new Error(event.code));
    }
  });
  child.on('exit', (code) => reject(new Error(`exited early: ${code}`)));
  setTimeout(() => reject(new Error('mount timeout')), 20000);
});

const exited = new Promise((r) => child.on('exit', (code) => r(code)));
const mountedEvent = await mounted;
const root = `${letter}:\\`;
check('mounted event after a real mount', mountedEvent.mountPoint === `${letter}:` && mountedEvent.fixture === true);
check('event order', events.map((e) => e.event).join(',') === 'started,fixture-mode,volume-created,mounted',
  events.map((e) => e.event).join(','));

const top = readdirSync(root);
check('root lists one labeled fixture account directory', top.length === 1 && top[0].startsWith('FIXTURE (test data) - Personal ['), top.join('|'));
const account = `${root}${top[0]}`;
const entries = readdirSync(account).sort();
check('account directory lists mapped names', ['CON_', 'Documents', 'Many files', 'a_b_c_.txt', 'empty.bin', 'trailing_'].every((n) => entries.includes(n)), entries.join('|'));
check('case-insensitive collision kept both entries with suffixes', entries.filter((n) => /^report \[[0-9a-f]{8}\]\.txt$/i.test(n)).length === 2);
check('unicode name preserved', entries.includes('Résumé 日本.txt'));
check('shortcut and native document are not shown', !entries.some((n) => /shortcut|native document/i.test(n)));
const report = entries.find((n) => /^Report \[/.test(n));
const st = statSync(`${account}\\${report}`);
check('file size is the real metadata size', st.size === 1234, String(st.size));
check('file mtime from metadata', st.mtime.toISOString() === '2026-01-01T00:00:00.000Z', st.mtime.toISOString());
check('directory stat', statSync(`${account}\\Documents`).isDirectory());
check('nested folder listing', readdirSync(`${account}\\Documents\\Nested`).join('|') === 'deep.txt');
check('case-insensitive path lookup', statSync(`${account}\\documents\\NESTED\\DEEP.TXT`).size === 3);
check('300-entry directory enumerates completely', readdirSync(`${account}\\Many files`).length === 300);
expectError('missing file is not found', () => statSync(`${account}\\nope.txt`), ['ENOENT']);
expectError('nonempty content read fails honestly', () => readFileSync(`${account}\\${report}`), ['ENOTSUP', 'EIO', 'UNKNOWN', 'EINVAL']);
check('zero-byte file reads as empty (true EOF)', readFileSync(`${account}\\empty.bin`).length === 0);
expectError('create file is rejected', () => writeFileSync(`${account}\\new.txt`, 'x'), ['EROFS', 'EPERM', 'EACCES']);
expectError('overwrite is rejected', () => writeFileSync(`${account}\\${report}`, 'x'), ['EROFS', 'EPERM', 'EACCES']);
expectError('mkdir is rejected', () => mkdirSync(`${account}\\newdir`), ['EROFS', 'EPERM', 'EACCES']);
expectError('delete is rejected', () => rmSync(`${account}\\empty.bin`), ['EROFS', 'EPERM', 'EACCES']);
expectError('rename is rejected', () => renameSync(`${account}\\empty.bin`, `${account}\\x.bin`), ['EROFS', 'EPERM', 'EACCES']);

child.stdin.write('unmount\n');
const code = await exited;
check('owned process exited cleanly after unmount', code === 0, String(code));
check('unmounted event', events.some((e) => e.event === 'unmounted' && e.reason === 'requested'));
check('drive letter released', !existsSync(root));

const failed = results.filter((r) => !r.ok).length;
console.log(`${results.length} checks, ${failed} failed (drive ${letter}:)`);
process.exit(failed === 0 ? 0 : 1);
