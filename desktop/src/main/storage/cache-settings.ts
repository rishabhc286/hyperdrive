import { promises as fsPromises } from 'node:fs';
import { dirname } from 'node:path';
import { DEFAULT_CACHE_BUDGET, isValidCacheBudget } from '../../shared/cache';

type FsLike = Pick<typeof fsPromises, 'readFile' | 'writeFile' | 'rename' | 'mkdir'>;

/** The user-chosen cache budget, persisted by the desktop (not a secret). */
export class CacheSettings {
  private budget: number | null = null;

  constructor(
    private readonly file: string,
    private readonly fs: FsLike = fsPromises
  ) {}

  async budgetBytes(): Promise<number> {
    if (this.budget !== null) return this.budget;
    try {
      const parsed = JSON.parse(await this.fs.readFile(this.file, 'utf8')) as { v?: unknown; budgetBytes?: unknown };
      this.budget = parsed.v === 1 && isValidCacheBudget(parsed.budgetBytes) ? parsed.budgetBytes : DEFAULT_CACHE_BUDGET;
    } catch {
      this.budget = DEFAULT_CACHE_BUDGET;
    }
    return this.budget;
  }

  async setBudgetBytes(bytes: number): Promise<void> {
    if (!isValidCacheBudget(bytes)) throw new Error('invalid budget');
    await this.fs.mkdir(dirname(this.file), { recursive: true });
    await this.fs.writeFile(`${this.file}.tmp`, JSON.stringify({ v: 1, budgetBytes: bytes }), 'utf8');
    await this.fs.rename(`${this.file}.tmp`, this.file);
    this.budget = bytes;
  }
}
