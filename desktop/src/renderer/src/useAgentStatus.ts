import { useCallback, useEffect, useRef, useState } from 'react';
import type { AgentApi, AgentStatus } from '../../shared/agent';

export const AGENT_POLL_MS = 10_000;

const UNAVAILABLE: AgentStatus = {
  state: 'stopped',
  agentVersion: null,
  uptimeMillis: null,
  capabilities: null,
  error: null,
  autoRestartsRemaining: 0
};

/**
 * Local agent status from the main process: initial fetch, pushed changes, and a slow refresh
 * while ready (for uptime). Never overlaps requests; cleans up its subscription on unmount.
 */
export function useAgentStatus(agent: AgentApi | undefined, pollMs = AGENT_POLL_MS) {
  const [status, setStatus] = useState<AgentStatus | null>(null);
  const [restarting, setRestarting] = useState(false);
  const inFlight = useRef(false);

  const refresh = useCallback(async () => {
    if (!agent || inFlight.current) return;
    inFlight.current = true;
    try {
      setStatus(await agent.getStatus());
    } catch {
      // Keep the last known status; the main process pushes changes anyway.
    } finally {
      inFlight.current = false;
    }
  }, [agent]);

  useEffect(() => {
    if (!agent) return;
    let live = true;
    const unsubscribe = agent.onStatusChange(setStatus);
    void agent
      .getStatus()
      .then((s) => {
        if (live) setStatus(s);
      })
      .catch(() => undefined);
    return () => {
      live = false;
      unsubscribe();
    };
  }, [agent]);

  const ready = status?.state === 'ready';
  useEffect(() => {
    if (!ready) return;
    const timer = setInterval(() => void refresh(), pollMs);
    return () => clearInterval(timer);
  }, [ready, refresh, pollMs]);

  const restart = useCallback(async () => {
    if (!agent || restarting) return;
    setRestarting(true);
    try {
      setStatus(await agent.restart());
    } catch {
      await refresh();
    } finally {
      setRestarting(false);
    }
  }, [agent, restarting, refresh]);

  return { status: agent ? status : UNAVAILABLE, restarting, restart };
}
