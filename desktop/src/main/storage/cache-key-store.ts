import { createHash, randomBytes } from 'node:crypto';
import { promises as fsPromises } from 'node:fs';
import { join } from 'node:path';
import type { SafeStorageLike } from '../auth/token-store';

type FsLike = Pick<typeof fsPromises, 'readFile' | 'writeFile' | 'rename' | 'rm' | 'mkdir'>;

/**
 * Per-user cache master keys (Batch 7). A random 32-byte key is wrapped with Electron safeStorage
 * (Windows DPAPI) and stored as `<dir>/<hash of user id>.json`. The unwrapped key goes only to the
 * agent over its authenticated channel; never to the renderer or logs.
 *
 * No plaintext fallback: without usable OS protection (including Linux "basic_text") `available` is
 * false and no key is ever created, which disables the disk cache.
 */
export class CacheKeyStore {
  readonly available: boolean;

  constructor(
    private readonly safeStorage: SafeStorageLike,
    private readonly dir: string,
    platform: NodeJS.Platform = process.platform,
    private readonly fs: FsLike = fsPromises
  ) {
    const backend = platform === 'linux' ? safeStorage.getSelectedStorageBackend?.() : undefined;
    this.available = safeStorage.isEncryptionAvailable() && backend !== 'basic_text' && backend !== 'unknown';
  }

  /** The user's key; `fresh` when it had to be created (or replaced because it was unreadable). */
  async getOrCreate(userId: string): Promise<{ key: Buffer; fresh: boolean } | null> {
    if (!this.available) return null;
    const file = this.fileFor(userId);
    try {
      const parsed = JSON.parse(await this.fs.readFile(file, 'utf8')) as { v?: unknown; data?: unknown };
      if (parsed.v !== 1 || typeof parsed.data !== 'string') throw new Error('format');
      const encoded = this.safeStorage.decryptString(Buffer.from(parsed.data, 'base64'));
      if (!/^[A-Za-z0-9+/]{43}=$/.test(encoded)) throw new Error('format');
      return { key: Buffer.from(encoded, 'base64'), fresh: false };
    } catch {
      // Missing, corrupt, or wrapped for another Windows user: start over. The agent is told the
      // key is fresh and removes this user's old (now unreadable) entries.
    }
    const key = randomBytes(32);
    const wrapped = this.safeStorage.encryptString(key.toString('base64')).toString('base64');
    await this.fs.mkdir(this.dir, { recursive: true });
    const tmp = `${file}.tmp`;
    await this.fs.writeFile(tmp, JSON.stringify({ v: 1, data: wrapped }), { encoding: 'utf8', mode: 0o600 });
    await this.fs.rename(tmp, file);
    return { key, fresh: true };
  }

  /** Logout: forget the user's key, so anything left on disk can't be decrypted any more. */
  async remove(userId: string): Promise<void> {
    await this.fs.rm(this.fileFor(userId), { force: true });
  }

  private fileFor(userId: string): string {
    if (!/^[0-9a-f-]{36}$/i.test(userId)) throw new Error('invalid user id');
    const name = createHash('sha256').update(`hyperdrive-cache-key-v1\0${userId}`).digest('hex').slice(0, 32);
    return join(this.dir, `${name}.json`);
  }
}
