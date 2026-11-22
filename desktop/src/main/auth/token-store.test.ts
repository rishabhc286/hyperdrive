// @vitest-environment node
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createTokenStore, type SafeStorageLike } from './token-store';

const TOKEN = `hdr_${'Z'.repeat(43)}`;

/** Reversible stand-in for OS encryption; output never contains the plaintext. */
function fakeSafeStorage(available: boolean, backend?: string): SafeStorageLike {
  return {
    isEncryptionAvailable: () => available,
    encryptString: (s) => Buffer.from(Buffer.from(s, 'utf8').map((b) => b ^ 0x5a)),
    decryptString: (b) => Buffer.from(b.map((x) => x ^ 0x5a)).toString('utf8'),
    ...(backend ? { getSelectedStorageBackend: () => backend } : {})
  };
}

let dir: string;
let file: string;

beforeEach(async () => {
  dir = await mkdtemp(join(tmpdir(), 'hyperdrive-token-store-'));
  file = join(dir, 'session', 'refresh-token.json');
});

afterEach(async () => {
  await rm(dir, { recursive: true, force: true });
});

describe('token store', () => {
  it('persists only encrypted data when OS encryption is available', async () => {
    const store = createTokenStore(fakeSafeStorage(true), file, 'win32');
    expect(store.persistence).toBe('encrypted');

    await store.save(TOKEN);
    const raw = await readFile(file, 'utf8');
    expect(raw).not.toContain(TOKEN);
    expect(raw).not.toContain('Z'.repeat(10));
    expect(await store.load()).toBe(TOKEN);

    await store.clear();
    expect(await store.load()).toBeNull();
    expect(await readdir(join(dir, 'session'))).toEqual([]);
  });

  it('never falls back to plaintext: memory-only when encryption is unavailable', async () => {
    const store = createTokenStore(fakeSafeStorage(false), file, 'win32');
    expect(store.persistence).toBe('memory-only');

    await store.save(TOKEN);
    expect(await readdir(dir)).toEqual([]);
    expect(await store.load()).toBeNull();
  });

  it('treats Linux basic_text (hard-coded key) as unavailable', () => {
    expect(createTokenStore(fakeSafeStorage(true, 'basic_text'), file, 'linux').persistence).toBe('memory-only');
    expect(createTokenStore(fakeSafeStorage(true, 'gnome_libsecret'), file, 'linux').persistence).toBe('encrypted');
  });

  it('discards a corrupt or tampered file', async () => {
    const store = createTokenStore(fakeSafeStorage(true), file, 'win32');
    await store.save(TOKEN);
    await writeFile(file, JSON.stringify({ v: 1, data: Buffer.from('garbage').toString('base64') }));

    expect(await store.load()).toBeNull();
    expect(await readdir(join(dir, 'session'))).toEqual([]);
  });
});
