import { promises as fsPromises } from 'node:fs';
import { dirname } from 'node:path';

/** Subset of Electron's safeStorage used here (injected so it can be tested without Electron). */
export interface SafeStorageLike {
  isEncryptionAvailable(): boolean;
  encryptString(plainText: string): Buffer;
  decryptString(encrypted: Buffer): string;
  getSelectedStorageBackend?(): string;
}

type FsLike = Pick<typeof fsPromises, 'readFile' | 'writeFile' | 'rename' | 'rm' | 'mkdir'>;

/** Where the refresh token lives between app runs. */
export interface TokenStore {
  readonly persistence: 'encrypted' | 'memory-only';
  load(): Promise<string | null>;
  save(refreshToken: string): Promise<void>;
  clear(): Promise<void>;
}

/**
 * Chooses OS-encrypted persistence when available, otherwise memory-only.
 * There is deliberately no plaintext fallback: on Linux, Electron's "basic_text" backend is a
 * hard-coded key, which is treated as unavailable.
 */
export function createTokenStore(
  safeStorage: SafeStorageLike,
  filePath: string,
  platform: NodeJS.Platform = process.platform,
  fs: FsLike = fsPromises
): TokenStore {
  const backend = platform === 'linux' ? safeStorage.getSelectedStorageBackend?.() : undefined;
  const usable = safeStorage.isEncryptionAvailable() && backend !== 'basic_text' && backend !== 'unknown';
  return usable ? new EncryptedFileTokenStore(safeStorage, filePath, fs) : new MemoryOnlyTokenStore();
}

/** Keeps nothing on disk; the session ends when the app exits. */
export class MemoryOnlyTokenStore implements TokenStore {
  readonly persistence = 'memory-only' as const;
  async load(): Promise<string | null> {
    return null;
  }
  async save(): Promise<void> {
    // Intentionally does not persist.
  }
  async clear(): Promise<void> {
    // Nothing persisted.
  }
}

class EncryptedFileTokenStore implements TokenStore {
  readonly persistence = 'encrypted' as const;

  constructor(
    private readonly safeStorage: SafeStorageLike,
    private readonly filePath: string,
    private readonly fs: FsLike
  ) {}

  async load(): Promise<string | null> {
    let raw: string;
    try {
      raw = await this.fs.readFile(this.filePath, 'utf8');
    } catch {
      return null;
    }
    try {
      const parsed = JSON.parse(raw) as { v?: unknown; data?: unknown };
      if (parsed.v !== 1 || typeof parsed.data !== 'string') throw new Error('bad format');
      const token = this.safeStorage.decryptString(Buffer.from(parsed.data, 'base64'));
      if (!/^hdr_[A-Za-z0-9_-]{43}$/.test(token)) throw new Error('bad token');
      return token;
    } catch {
      // Corrupt, tampered, or encrypted for another OS user: discard it.
      await this.clear();
      return null;
    }
  }

  async save(refreshToken: string): Promise<void> {
    const data = this.safeStorage.encryptString(refreshToken).toString('base64');
    await this.fs.mkdir(dirname(this.filePath), { recursive: true });
    const temp = `${this.filePath}.tmp`;
    await this.fs.writeFile(temp, JSON.stringify({ v: 1, data }), { encoding: 'utf8', mode: 0o600 });
    await this.fs.rename(temp, this.filePath);
  }

  async clear(): Promise<void> {
    await this.fs.rm(this.filePath, { force: true });
    await this.fs.rm(`${this.filePath}.tmp`, { force: true });
  }
}
