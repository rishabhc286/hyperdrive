import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { AGENT_READY, createMockApi, ONBOARDED_USER, status } from '../../../test/mock-api';
import type { AgentStatus } from '../../shared/agent';
import type { ConnectedAccount } from '../../shared/storage';
import { App } from './App';

const FAILED: AgentStatus = { ...AGENT_READY, state: 'failed', agentVersion: null, uptimeMillis: null, capabilities: null, error: 'java-not-found' };

async function renderApp(agent: AgentStatus) {
  const mock = createMockApi(status('signed-in', { user: ONBOARDED_USER }));
  mock.agent.set(agent);
  const view = render(<App api={mock.api} />);
  await screen.findByRole('navigation', { name: 'Main' });
  return { mock, view };
}

const card = () => screen.getByRole('article', { name: 'Local agent' });

describe('Local agent status', () => {
  it('shows ready with version and uptime, and says what ready does not mean', async () => {
    await renderApp(AGENT_READY);
    await waitFor(() => expect(within(card()).getByTestId('agent-state')).toHaveTextContent('Local agent: Ready'));
    expect(card()).toHaveTextContent('0.1.0-SNAPSHOT');
    expect(card()).toHaveTextContent('2 min');
    expect(card()).toHaveTextContent(/read bounded byte ranges \(at most 4 MiB at a time\)/);
    expect(card()).not.toHaveTextContent(/doesn’t cache anything or create a drive yet/);
    // The other surfaces keep their own, separate status.
    expect(await screen.findByRole('article', { name: 'Virtual drive' })).toHaveTextContent(/Folders and file details are available/);
    expect(screen.getByRole('article', { name: 'Backend' })).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/mounted|cached \d|MB\/s|Drive connected/i);
  });

  it('shows an actionable error when the agent failed', async () => {
    await renderApp(FAILED);
    await waitFor(() => expect(within(card()).getByTestId('agent-state')).toHaveTextContent('Failed'));
    expect(within(card()).getByRole('alert')).toHaveTextContent(/Java 21 or newer wasn’t found/);
    expect(card()).not.toHaveTextContent(/[A-Z]:\\|AppData|hda_|Bearer/);
  });

  it('restart shows a pending state and calls the bridge once', async () => {
    const { mock } = await renderApp(FAILED);
    let release: (s: AgentStatus) => void = () => undefined;
    mock.agent.api.restart.mockImplementation(
      () =>
        new Promise((resolve) => {
          release = resolve;
        })
    );
    const button = await within(card()).findByRole('button', { name: 'Restart agent' });
    await userEvent.click(button);
    expect(within(card()).getByRole('button', { name: 'Restarting…' })).toBeDisabled();
    await userEvent.click(within(card()).getByRole('button', { name: 'Restarting…' }));
    expect(mock.agent.api.restart).toHaveBeenCalledTimes(1);

    release(AGENT_READY);
    await waitFor(() => expect(within(card()).getByTestId('agent-state')).toHaveTextContent('Ready'));
    expect(within(card()).getByRole('button', { name: 'Restart agent' })).toBeEnabled();
  });

  it('follows pushed status changes and disables restart while starting', async () => {
    const { mock } = await renderApp(AGENT_READY);
    await waitFor(() => expect(within(card()).getByTestId('agent-state')).toHaveTextContent('Ready'));
    mock.agent.push({ ...AGENT_READY, state: 'restarting', error: 'unexpected-exit', uptimeMillis: null });
    expect(within(card()).getByTestId('agent-state')).toHaveTextContent('Restarting');
    expect(card()).toHaveTextContent('The agent stopped unexpectedly.');
    expect(within(card()).getByRole('button', { name: 'Restarting…' })).toBeDisabled();
    mock.agent.push({ ...AGENT_READY, state: 'starting' });
    expect(within(card()).getByRole('button', { name: 'Restart agent' })).toBeDisabled();
  });

  it('unsubscribes from status pushes when the shell unmounts', async () => {
    const { mock, view } = await renderApp(AGENT_READY);
    await waitFor(() => expect(mock.agent.listeners.size).toBe(1));
    view.unmount();
    expect(mock.agent.listeners.size).toBe(0);
  });

  it('settings lists the agent separately from the backend and the virtual drive', async () => {
    await renderApp(AGENT_READY);
    await userEvent.click(screen.getByRole('button', { name: 'Settings' }));
    expect(await screen.findByText('Ready (version 0.1.0-SNAPSHOT)')).toBeInTheDocument();
    expect(screen.getByText('Read-only, metadata only (manage it on Overview)')).toBeInTheDocument();
  });

  it('cloud browsing keeps working while the agent is down', async () => {
    const account: ConnectedAccount = {
      id: '6f1c2a8e-1b2c-4d3e-8f90-123456789abc',
      provider: 'DROPBOX',
      displayName: null,
      email: 'box@example.com',
      status: 'CONNECTED',
      rootType: 'USER',
      connectedAt: '2026-11-22T10:00:00Z',
      lastSuccessAt: null,
      lastErrorCode: null,
      contentReadGranted: true
    };
    const mock = createMockApi(status('signed-in', { user: ONBOARDED_USER }));
    mock.agent.set(FAILED);
    mock.storage.listAccounts.mockResolvedValue({ ok: true, value: [account] });
    mock.storage.listFiles.mockResolvedValue({
      ok: true,
      value: {
        items: [
          { providerFileId: 'id:a', name: 'still-works.txt', kind: 'FILE', mimeType: null, sizeBytes: 3, modifiedAt: null, canDownload: true,
            directoryLocator: null, providerRevision: null, contentHash: null, contentHashAlgorithm: null, shortcutTargetId: null,
            shortcutTargetMimeType: null }
        ],
        continuation: null
      }
    });
    render(<App api={mock.api} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Files' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Open Dropbox — box@example.com' }));
    expect(await screen.findByText('still-works.txt')).toBeInTheDocument();
  });
});
