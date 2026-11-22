import type { AgentCapabilities, AgentErrorCode } from '../../shared/agent';

/** Must equal the agent's Bootstrap.PROTOCOL_VERSION. */
export const AGENT_PROTOCOL_VERSION = 1;
/** Maximum bytes accepted on the agent's stdout before the handshake line. */
export const MAX_HANDSHAKE_BYTES = 4096;

export type Handshake =
  | { readonly type: 'ready'; readonly port: number; readonly instanceId: string; readonly agentVersion: string }
  | { readonly type: 'error'; readonly code: AgentErrorCode };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const VERSION = /^[0-9A-Za-z.+-]{1,64}$/;

/**
 * Validates the agent's one-line handshake. Anything unexpected is rejected; the child never
 * supplies a URL or host, only a port that is checked here.
 */
export function parseHandshake(line: string): Handshake | { readonly invalid: AgentErrorCode } {
  let value: unknown;
  try {
    value = JSON.parse(line);
  } catch {
    return { invalid: 'handshake-invalid' };
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return { invalid: 'handshake-invalid' };
  const v = value as Record<string, unknown>;
  if (v['type'] === 'error') {
    switch (v['code']) {
      case 'data_dir_locked':
        return { type: 'error', code: 'data-dir-locked' };
      case 'protocol_unsupported':
        return { type: 'error', code: 'protocol-mismatch' };
      default:
        return { type: 'error', code: 'bootstrap-rejected' };
    }
  }
  if (v['type'] !== 'ready') return { invalid: 'handshake-invalid' };
  const keys = Object.keys(v).sort().join(',');
  if (keys !== 'agentVersion,instanceId,port,protocolVersion,type') return { invalid: 'handshake-invalid' };
  if (v['protocolVersion'] !== AGENT_PROTOCOL_VERSION) return { invalid: 'protocol-mismatch' };
  const port = v['port'];
  if (typeof port !== 'number' || !Number.isInteger(port) || port < 1024 || port > 65535) {
    return { invalid: 'handshake-invalid' };
  }
  const instanceId = v['instanceId'];
  const agentVersion = v['agentVersion'];
  if (typeof instanceId !== 'string' || !UUID.test(instanceId) || typeof agentVersion !== 'string' || !VERSION.test(agentVersion)) {
    return { invalid: 'handshake-invalid' };
  }
  return { type: 'ready', port, instanceId, agentVersion };
}

export interface AgentStatusBody {
  readonly agentVersion: string;
  readonly instanceId: string;
  readonly uptimeMillis: number;
  readonly capabilities: AgentCapabilities;
}

/** Strict parse of GET /local/v1/status; copies only known fields. */
export function parseStatus(value: unknown): AgentStatusBody | null {
  if (typeof value !== 'object' || value === null) return null;
  const v = value as Record<string, unknown>;
  const caps = v['capabilities'];
  if (
    v['protocolVersion'] !== AGENT_PROTOCOL_VERSION ||
    v['readiness'] !== 'READY' ||
    typeof v['agentVersion'] !== 'string' ||
    !VERSION.test(v['agentVersion']) ||
    typeof v['instanceId'] !== 'string' ||
    typeof v['uptimeMillis'] !== 'number' ||
    v['uptimeMillis'] < 0 ||
    typeof caps !== 'object' ||
    caps === null
  ) {
    return null;
  }
  const c = caps as Record<string, unknown>;
  const flag = (name: string) => c[name] === true;
  return {
    agentVersion: v['agentVersion'],
    instanceId: v['instanceId'],
    uptimeMillis: Math.floor(v['uptimeMillis']),
    capabilities: {
      providerReads: flag('providerReads'),
      rangeReads: flag('rangeReads'),
      chunkCache: flag('chunkCache'),
      virtualDrive: flag('virtualDrive')
    }
  };
}
