import { promises as fsPromises } from 'node:fs';
import { dirname } from 'node:path';
import { DEFAULT_CACHE_BUDGET, isValidCacheBudget } from '../../shared/cache';

type FsLike = Pick<typeof fsPromises, 'readFile' | 'writeFile' | 'rename' | 'mkdir'>;

const ACCOUNT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_PENDING = 256;

interface State {
  budgetBytes: number;
  /** Disconnected accounts whose cached chunks the agent hasn't confirmed deleting yet. */
  pendingAccountPurges: string[];
}

/**
 * Desktop cache settings (not secrets): the user-chosen budget, and account purges still owed
 * after a disconnect while the agent was unavailable. Persisted so a restart doesn't forget them.
 */
export class CacheSettings {
  private state: State | null = null;

  constructor(
    private readonly file: string,
    private readonly fs: FsLike = fsPromises
  ) {}

  async budgetBytes(): Promise<number> {
    return (await this.load()).budgetBytes;
  }

  async setBudgetBytes(bytes: number): Promise<void> {
    if (!isValidCacheBudget(bytes)) throw new Error('invalid budget');
    const state = await this.load();
    await this.save({ ...state, budgetBytes: bytes });
  }

  async pendingAccountPurges(): Promise<string[]> {
    return [...(await this.load()).pendingAccountPurges];
  }

  async setPendingAccountPurges(ids: readonly string[]): Promise<void> {
    const valid = [...new Set(ids.filter((id) => ACCOUNT_ID.test(id)))].slice(-MAX_PENDING);
    const state = await this.load();
    await this.save({ ...state, pendingAccountPurges: valid });
  }

  private async load(): Promise<State> {
    if (this.state) return this.state;
    let state: State = { budgetBytes: DEFAULT_CACHE_BUDGET, pendingAccountPurges: [] };
    try {
      const parsed = JSON.parse(await this.fs.readFile(this.file, 'utf8')) as {
        v?: unknown;
        budgetBytes?: unknown;
        pendingAccountPurges?: unknown;
      };
      if (parsed.v === 1) {
        const pending = Array.isArray(parsed.pendingAccountPurges)
          ? parsed.pendingAccountPurges.filter((id): id is string => typeof id === 'string' && ACCOUNT_ID.test(id))
          : [];
        state = {
          budgetBytes: isValidCacheBudget(parsed.budgetBytes) ? parsed.budgetBytes : DEFAULT_CACHE_BUDGET,
          pendingAccountPurges: pending.slice(-MAX_PENDING)
        };
      }
    } catch {
      // missing or unreadable: defaults
    }
    this.state = state;
    return state;
  }

  private async save(next: State): Promise<void> {
    await this.fs.mkdir(dirname(this.file), { recursive: true });
    await this.fs.writeFile(`${this.file}.tmp`, JSON.stringify({ v: 1, ...next }), 'utf8');
    await this.fs.rename(`${this.file}.tmp`, this.file);
    this.state = next;
  }
}
