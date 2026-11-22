/** Local chunk cache (Batch 7): what the renderer may see. Never paths, keys or tokens. */

export const CACHE_CHUNK_BYTES = 4 * 1024 * 1024;
/** Default disk budget: 5 GiB = 5,368,709,120 bytes. */
export const DEFAULT_CACHE_BUDGET = 5 * 1024 * 1024 * 1024;
export const MIN_CACHE_BUDGET = 256 * 1024 * 1024;
export const MAX_CACHE_BUDGET = 1024 * 1024 * 1024 * 1024;

/** 0 turns the disk cache off; otherwise 256 MiB – 1 TiB, whole bytes. */
export function isValidCacheBudget(bytes: unknown): bytes is number {
  return (
    typeof bytes === 'number' &&
    Number.isSafeInteger(bytes) &&
    (bytes === 0 || (bytes >= MIN_CACHE_BUDGET && bytes <= MAX_CACHE_BUDGET))
  );
}

/** Why a read did not use the cache at all. */
export const BYPASS_REASONS = [
  'provider_not_version_bound',
  'version_unknown',
  'cache_key_unavailable',
  'cache_disabled'
] as const;
export type CacheBypassReason = (typeof BYPASS_REASONS)[number];

/** Why fetched (and served) bytes were not stored. */
export const NOT_ADMITTED_REASONS = ['revision_unconfirmed', 'cache_full', 'cache_invalidated', 'cache_error', 'cache_disabled'] as const;
export type CacheNotAdmittedReason = (typeof NOT_ADMITTED_REASONS)[number];

export type CacheOutcome = 'hit' | 'miss' | 'partial' | 'bypass' | 'none';

export interface CacheCounters {
  readonly hits: number;
  readonly misses: number;
  readonly notAdmitted: number;
  readonly evictions: number;
  readonly corruptions: number;
  readonly bytesRequested: number;
  readonly providerBytesFetched: number;
  readonly bytesServedFromCache: number;
}

export type CacheDisabledReason = 'key-unavailable' | 'key-protection-unavailable' | 'disabled-by-budget' | 'agent-unavailable' | 'signed-out';

export interface CacheStatusView {
  /** Whether new eligible reads are cached right now. */
  readonly enabled: boolean;
  readonly disabledReason: CacheDisabledReason | null;
  /** Whether Windows-protected key storage exists on this PC (no protection → no disk cache). */
  readonly keyProtection: 'available' | 'unavailable';
  readonly budgetBytes: number;
  /** Actual encrypted .hdc bytes (ciphertext + 32 B per chunk), not logical file bytes. */
  readonly encryptedBytesOnDisk: number;
  readonly tempBytes: number;
  readonly entryCount: number;
  readonly overBudgetBytes: number;
  /** Agent process lifetime: reset when the agent restarts. */
  readonly counters: CacheCounters;
  readonly bypasses: Readonly<Partial<Record<CacheBypassReason, number>>>;
  readonly lastCleanup: { readonly scope: string; readonly removed: number; readonly failed: number } | null;
}

export type CacheResult<T> = { readonly ok: true; readonly value: T } | { readonly ok: false; readonly error: CacheError };
export type CacheError = 'agent-unavailable' | 'signed-out' | 'invalid-request' | 'unknown';

export interface CacheApi {
  getStatus(): Promise<CacheResult<CacheStatusView>>;
  setBudget(input: { budgetBytes: number }): Promise<CacheResult<CacheStatusView>>;
  /** Deletes this user's cached chunks. The renderer must confirm with the user first. */
  clear(): Promise<CacheResult<{ removed: number; failed: number }>>;
}

export const CacheIpcChannel = {
  getStatus: 'hyperdrive:cache:get-status',
  setBudget: 'hyperdrive:cache:set-budget',
  clear: 'hyperdrive:cache:clear'
} as const;
