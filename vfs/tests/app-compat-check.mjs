// SPDX-License-Identifier: GPL-3.0-only
// Opt-in application checks (Batch 10) on a REAL mount through the real agent and VFS. The files are
// synthetic, non-sensitive test files (lib/make-test-media.mjs) served by the TEST-ONLY fixture
// provider, so this tests the drive with real applications, NOT live Google/Dropbox.
// Applications are driven headlessly where they allow it (Explorer shell COM, Windows Imaging
// Component, Edge headless, VLC dummy interface). Anything needing eyes (scrolling, preview pane,
// drag-out) is left to the manual checklist in docs/verification.md.
//
//   node vfs/tests/app-compat-check.mjs agent vfs/build/hyperdrive-vfs.exe
import { execFile, spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs';
import { copyFile, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { BOX, DRIVE, checker, freeLetter, mountAndPump, sleep, startAgent, startBackend, startVfs } from './lib/fixture-harness.mjs';
import { appPath, makeTestMedia } from './lib/make-test-media.mjs';

const agentDir = resolve(process.argv[2] ?? 'agent');
const exe = resolve(process.argv[3] ?? 'vfs/build/hyperdrive-vfs.exe');
const { check, failed } = checker();
const rows = [];
const work = mkdtempSync(join(tmpdir(), 'hd-apps-'));
const media = await makeTestMedia(join(work, 'media'));
const sha = (p) => createHash('sha256').update(readFileSync(p)).digest('hex');
const tokens = { google: `fixture-google-${randomBytes(16).toString('hex')}`, dropbox: `fixture-dropbox-${randomBytes(16).toString('hex')}` };
const FILES = media.map((m, i) => ({ account: BOX, id: `id:app${i}`, name: m.name, provider: 'DROPBOX', size: m.size, rev: `${(0xa00000000 + i).toString(16)}`, source: m.path }));
for (const name of ['doc.pdf', 'photo.jpg', 'video.mp4']) {
  const m = media.find((x) => x.name === name);
  if (m) FILES.push({ account: DRIVE, id: `gapp${name.replace(/\W/g, '')}`, name, provider: 'GOOGLE_DRIVE', size: m.size, rev: null, source: m.path });
}
const backend = await startBackend(FILES);
const agent = await startAgent(agentDir, FILES, tokens);
const state = await mountAndPump(agent, backend, tokens);
await agent.admin('/local/v1/cache/session', { userId: '22222222-3333-4444-8555-666666666666', key: randomBytes(32).toString('base64'), fresh: true, budgetBytes: 1024 ** 3 });
const letter = freeLetter();
const vfs = startVfs(exe, agent.port, state.capability, letter);
await vfs.mounted;
const root = (await readdir(`${letter}:\\`)).sort();
const dir = (account) => `${letter}:\\${root[account === BOX ? 0 : 1]}`;
const onDrive = (name, account = BOX) => `${dir(account)}\\${name}`;
const original = (name) => media.find((m) => m.name === name);

const run = (cmd, args, timeout = 120_000) => new Promise((r) => {
  const t0 = Date.now();
  execFile(cmd, args, { windowsHide: true, timeout }, (error, stdout, stderr) => r({ code: error ? (error.code ?? 'error') : 0, stdout, stderr, ms: Date.now() - t0 }));
});
const ps = (script, timeout) => run('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', script], timeout);

/** Runs one application step and records the agent's counters around it. */
async function step(app, what, fn) {
  const before = (await agent.call('GET', '/local/v1/vfs/status')).body.reads;
  const t0 = Date.now();
  let result;
  try {
    result = await fn();
  } catch (e) {
    result = { ok: false, detail: String(e?.message ?? e).slice(0, 120) };
  }
  const after = (await agent.call('GET', '/local/v1/vfs/status')).body.reads;
  const failures = Object.entries(after.failures).filter(([k, v]) => v !== (before.failures[k] ?? 0)).map(([k, v]) => `${k}+${v - (before.failures[k] ?? 0)}`);
  rows.push({ app, what, ok: result.ok, ms: Date.now() - t0, agentReads: after.reads - before.reads, providerBytes: after.providerBytes - before.providerBytes,
    cacheBytes: after.cacheBytes - before.cacheBytes, returnedBytes: after.bytesReturned - before.bytesReturned, errors: failures.join(' ') || '-', detail: result.detail ?? '' });
  check(`${app}: ${what}`, result.ok, result.detail);
}

// --- Explorer (shell COM: the same property system and copy engine Explorer uses) ---
const pdf = original('doc.pdf');
await step('Explorer shell', 'properties show the real size', async () => {
  const r = await ps(`$f=(New-Object -ComObject Shell.Application).NameSpace('${dir(BOX)}').ParseName('doc.pdf'); $f.ExtendedProperty('System.Size')`);
  return { ok: Number(r.stdout.trim()) === pdf.size, detail: `System.Size ${r.stdout.trim()}` };
});
const copyDir = join(work, 'explorer-copy');
await step('Explorer shell', 'copy out (Shell CopyHere, no UI) matches SHA-256', async () => {
  const wav = original('tone.wav');
  await ps(`New-Item -ItemType Directory -Force '${copyDir}' | Out-Null; (New-Object -ComObject Shell.Application).NameSpace('${copyDir}').CopyHere('${onDrive('tone.wav')}', 20)`);
  const target = join(copyDir, 'tone.wav');
  for (let i = 0; i < 120 && !(existsSync(target) && statSync(target).size === wav.size); i++) await sleep(250);
  await sleep(500);
  return { ok: existsSync(target) && sha(target) === sha(wav.path), detail: existsSync(target) ? `${statSync(target).size} B` : 'not copied' };
});
await step('Explorer zip folder', 'lists the entries of a ZIP on the drive', async () => {
  const r = await ps(`(New-Object -ComObject Shell.Application).NameSpace('${onDrive('archive.zip')}').Items() | ForEach-Object { $_.Name }`);
  // Shell display names follow Explorer's "hide extensions" setting, so compare without extensions.
  const names = r.stdout.trim().split(/\r?\n/).filter(Boolean).map((n) => n.replace(/\.(png|txt)$/, '')).sort();
  return { ok: names.join('|') === 'image|notes', detail: names.join('|') };
});
await step('Explorer shell', 'thumbnail request returns in bounded time (no hang)', async () => {
  const r = await ps(`Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public static class Thumb {
  [ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IShellItemImageFactory { [PreserveSig] int GetImage(SIZE size, int flags, out IntPtr bmp); }
  [StructLayout(LayoutKind.Sequential)] struct SIZE { public int cx; public int cy; }
  [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
  static extern void SHCreateItemFromParsingName(string path, IntPtr bc, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out object item);
  [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr o);
  public static string Get(string path, int flags) {
    Guid iid = new Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"); object o;
    SHCreateItemFromParsingName(path, IntPtr.Zero, ref iid, out o);
    IntPtr h; int hr = ((IShellItemImageFactory)o).GetImage(new SIZE { cx = 256, cy = 256 }, flags, out h);
    if (h != IntPtr.Zero) DeleteObject(h);
    return "0x" + hr.ToString("x8");
  }
}
'@
[Thumb]::Get('${onDrive('photo.jpg')}', 0)`, 60_000);
  return { ok: r.code === 0 && r.ms < 30_000, detail: `HRESULT ${r.stdout.trim() || r.code}, ${r.ms} ms` };
});

// --- Images: Windows Imaging Component (the decoder Photos and Explorer use) ---
for (const [name, account] of [['image.png', BOX], ['photo.jpg', BOX], ['photo.jpg', DRIVE]]) {
  await step('Windows Imaging Component', `decodes ${name} (${account === BOX ? 'Dropbox' : 'Google'})`, async () => {
    const r = await ps(`Add-Type -AssemblyName PresentationCore; $s=[IO.File]::OpenRead('${onDrive(name, account)}'); try { $d=[Windows.Media.Imaging.BitmapDecoder]::Create($s,'None','OnLoad'); "$($d.Frames[0].PixelWidth)x$($d.Frames[0].PixelHeight)" } finally { $s.Dispose() }`);
    return { ok: r.stdout.trim() === '2400x1600', detail: r.stdout.trim() || r.stderr.slice(0, 80) };
  });
}

// --- PDF: Edge (headless) opens the PDF from the drive and prints it ---
const edge = await appPath('msedge.exe');
for (const account of [BOX, DRIVE]) {
  if (!edge) break;
  await step('Microsoft Edge (headless)', `renders doc.pdf from ${account === BOX ? 'Dropbox' : 'Google'}`, async () => {
    const out = join(work, `edge-${account === BOX ? 'box' : 'drive'}.pdf`);
    const r = await run(edge, ['--headless', '--disable-gpu', `--user-data-dir=${join(work, 'edge-profile')}`, `--print-to-pdf=${out}`, `file:///${onDrive('doc.pdf', account).replace(/\\/g, '/')}`], 120_000);
    const size = existsSync(out) ? statSync(out).size : 0;
    return { ok: size > 10_000, detail: `printed ${size} B, ${r.ms} ms` };
  });
}

// --- Media: VLC (dummy interface, no audio/video output) opens, plays and seeks ---
const vlc = await appPath('vlc.exe');
const vlcPlay = (path, start) => run(vlc, ['-I', 'dummy', '--aout=dummy', '--vout=dummy', '--no-video-title-show', '--play-and-exit', `--start-time=${start}`, '--run-time=3', path, 'vlc://quit'], 60_000);
if (vlc) {
  for (const [name, account] of [['video.mp4', BOX], ['video.mp4', DRIVE], ['tone.wav', BOX]]) {
    await step('VLC (headless)', `plays ${name} (${account === BOX ? 'Dropbox' : 'Google'}) from the start`, async () => {
      const r = await vlcPlay(onDrive(name, account), 0);
      return { ok: r.code === 0 && !/main input error|cannot open|access error/i.test(r.stderr), detail: `${r.ms} ms` };
    });
    await step('VLC (headless)', `seeks forward to 30 s in ${name}`, async () => {
      const r = await vlcPlay(onDrive(name, account), 30);
      return { ok: r.code === 0 && !/main input error|cannot open|access error/i.test(r.stderr), detail: `${r.ms} ms` };
    });
    await step('VLC (headless)', `seeks back to 5 s in ${name}`, async () => {
      const r = await vlcPlay(onDrive(name, account), 5);
      return { ok: r.code === 0 && !/main input error|cannot open|access error/i.test(r.stderr), detail: `${r.ms} ms` };
    });
  }
  // Two applications reading the same file at once.
  await step('VLC + copy', 'two applications read tone.wav concurrently', async () => {
    const target = join(work, 'concurrent.wav');
    const [v] = await Promise.all([vlcPlay(onDrive('tone.wav'), 10), copyFile(onDrive('tone.wav'), target)]);
    return { ok: v.code === 0 && sha(target) === sha(original('tone.wav').path), detail: `vlc ${v.ms} ms` };
  });
}

// --- Text: read by .NET (the API editors use); Notepad itself is in the manual checklist ---
await step('.NET File.ReadAllText', 'reads notes.txt (UTF-8) exactly', async () => {
  const r = await ps(`[IO.File]::ReadAllText('${onDrive('notes.txt')}').Length`);
  const expectedLength = readFileSync(original('notes.txt').path, 'utf8').length;
  return { ok: Number(r.stdout.trim()) === expectedLength, detail: `${r.stdout.trim()} chars` };
});

// Teardown.
vfs.proc.stdin.write('unmount\n');
await Promise.race([vfs.exited, sleep(20000)]);
check('unmounted cleanly after the application runs', !existsSync(`${letter}:\\`));
state.pumping = false;
await agent.admin('/local/v1/vfs/mount/revoke', {}).catch(() => undefined);
agent.proc.stdin.end();
await Promise.race([agent.exited, sleep(15000)]);
await backend.stop();
const leftovers = await ps(`@(Get-Process vlc -ErrorAction SilentlyContinue | Where-Object { $_.StartTime -gt (Get-Date).AddMinutes(-30) }).Count`);
check('no VLC process left running by the check', leftovers.stdout.trim() === '0', leftovers.stdout.trim());
console.log('\nTABLE app | step | ok | ms | agent reads | provider B | cache B | returned B | errors');
for (const r of rows) console.log(`ROW ${r.app} | ${r.what} | ${r.ok ? 'pass' : 'FAIL'} | ${r.ms} | ${r.agentReads} | ${r.providerBytes} | ${r.cacheBytes} | ${r.returnedBytes} | ${r.errors}`);
rmSync(work, { recursive: true, force: true });
rmSync(agent.work, { recursive: true, force: true });
console.log(`${failed() === 0 ? 'OK' : 'FAILED'}: ${failed()} failed -- synthetic files via the FIXTURE provider, not live Google/Dropbox`);
process.exit(failed() === 0 ? 0 : 1);
