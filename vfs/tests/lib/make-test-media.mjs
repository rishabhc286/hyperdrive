// SPDX-License-Identifier: GPL-3.0-only
// Generates NON-SENSITIVE synthetic test files for the opt-in application checks: text, PNG, JPEG,
// PDF (via Edge headless printing a generated page), ZIP, WAV (sine tone) and MP4 (VLC transcoding a
// generated still image; skipped if VLC is missing). Nothing is captured from the screen, camera,
// microphone or user files. Returns [{name, path, size}] for the files that could be created.
import { execFile } from 'node:child_process';
import { existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const run = (cmd, args, timeout = 120_000) =>
  new Promise((r) => execFile(cmd, args, { windowsHide: true, timeout }, (error, stdout, stderr) => r({ error, stdout, stderr })));

export async function appPath(exe) {
  const r = await run('powershell', ['-NoProfile', '-Command', `(Get-ItemProperty 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\${exe}' -ErrorAction SilentlyContinue).'(default)'`]);
  const p = r.stdout.trim().replace(/^"|"$/g, '');
  return p && existsSync(p) ? p : null;
}

export async function makeTestMedia(dir) {
  mkdirSync(dir, { recursive: true });
  const out = [];
  const add = (name) => {
    const path = join(dir, name);
    if (existsSync(path) && statSync(path).size > 0) out.push({ name, path, size: statSync(path).size });
  };

  // Text (UTF-8, ~1.3 MB).
  writeFileSync(join(dir, 'notes.txt'), Array.from({ length: 20000 }, (_, i) => `Line ${i}: HyperDrive synthetic test text, äöü ✓ 日本語.`).join('\r\n'));
  add('notes.txt');

  // Images via System.Drawing (GDI+).
  const ps = `Add-Type -AssemblyName System.Drawing; $b = New-Object System.Drawing.Bitmap 2400,1600; $g=[System.Drawing.Graphics]::FromImage($b);` +
    `for($i=0;$i -lt 40;$i++){ $c=[System.Drawing.Color]::FromArgb(255,($i*6)%256,(255-$i*5)%256,($i*13)%256); $g.FillRectangle((New-Object System.Drawing.SolidBrush $c), $i*60, 0, 60, 1600) };` +
    `$g.DrawString('HyperDrive test image', (New-Object System.Drawing.Font 'Arial',72), [System.Drawing.Brushes]::White, 100, 700);` +
    `$b.Save('${join(dir, 'image.png')}'); $b.Save('${join(dir, 'photo.jpg')}', [System.Drawing.Imaging.ImageFormat]::Jpeg)`;
  await run('powershell', ['-NoProfile', '-Command', ps]);
  add('image.png');
  add('photo.jpg');

  // PDF: Edge headless prints a generated 30-page HTML document (separate, temporary profile).
  const html = join(dir, 'doc.html');
  writeFileSync(html, `<!doctype html><meta charset="utf-8"><style>p{page-break-after:always;font:24px serif}</style>` +
    Array.from({ length: 30 }, (_, i) => `<p>HyperDrive synthetic test document, page ${i + 1}. ${'Lorem ipsum dolor sit amet. '.repeat(40)}</p>`).join(''));
  const edge = await appPath('msedge.exe');
  if (edge) {
    await run(edge, ['--headless', '--disable-gpu', `--user-data-dir=${join(dir, 'edge-profile')}`, `--print-to-pdf=${join(dir, 'doc.pdf')}`, `file:///${html.replace(/\\/g, '/')}`]);
    add('doc.pdf');
  }

  // ZIP of the text and PNG (Compress-Archive).
  await run('powershell', ['-NoProfile', '-Command', `Compress-Archive -Force -Path '${join(dir, 'notes.txt')}','${join(dir, 'image.png')}' -DestinationPath '${join(dir, 'archive.zip')}'`]);
  add('archive.zip');

  // WAV: 40 s, 44.1 kHz, 16-bit stereo sine sweep.
  const seconds = 40, rate = 44100, frames = seconds * rate;
  const wav = Buffer.alloc(44 + frames * 4);
  wav.write('RIFF', 0); wav.writeUInt32LE(36 + frames * 4, 4); wav.write('WAVEfmt ', 8); wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20); wav.writeUInt16LE(2, 22); wav.writeUInt32LE(rate, 24); wav.writeUInt32LE(rate * 4, 28);
  wav.writeUInt16LE(4, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36); wav.writeUInt32LE(frames * 4, 40);
  for (let i = 0; i < frames; i++) {
    const v = Math.round(Math.sin((2 * Math.PI * (220 + i / rate * 10) * i) / rate) * 8000);
    wav.writeInt16LE(v, 44 + i * 4); wav.writeInt16LE(v, 46 + i * 4);
  }
  writeFileSync(join(dir, 'tone.wav'), wav);
  add('tone.wav');

  // MP4: VLC transcodes the generated PNG into a 40 s H.264 video (no screen capture).
  const vlc = await appPath('vlc.exe');
  if (vlc) {
    await run(vlc, ['-I', 'dummy', '--no-audio', '--image-duration=40', join(dir, 'image.png'),
      `--sout=#transcode{vcodec=h264,fps=25,vb=1500,width=1280,height=720}:std{access=file,mux=mp4,dst=${join(dir, 'video.mp4')}}`, 'vlc://quit'], 240_000);
    add('video.mp4');
  }
  return out;
}

// CLI use only when run directly (never when imported by another check).
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href && process.argv[2]) {
  console.log(JSON.stringify(await makeTestMedia(process.argv[2]), null, 1));
}
