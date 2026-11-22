import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createMockApi, NEW_USER, ONBOARDED_USER, status } from '../../../test/mock-api';
import { App } from './App';

describe('auth states', () => {
  it('shows "checking" until the main process reports a status', () => {
    const mock = createMockApi(status('checking'));
    render(<App api={mock.api} />);
    expect(screen.getByRole('heading', { name: 'Checking your session…' })).toBeInTheDocument();
  });

  it('signed out: welcome screen with only "Continue with Google" and no password fields', async () => {
    const mock = createMockApi(status('signed-out'));
    const { container } = render(<App api={mock.api} />);

    expect(await screen.findByRole('heading', { name: 'Welcome to HyperDrive' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue with Google' })).toBeEnabled();
    expect(container.querySelector('input')).toBeNull();
    expect(screen.queryByText(/password/i)).not.toBeInTheDocument();
    expect(screen.getByText(/does not give HyperDrive access to your Google Drive/)).toBeInTheDocument();
    // No invented legal pages.
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('"Continue with Google" invokes only the narrow sign-in bridge method', async () => {
    const mock = createMockApi(status('signed-out'));
    render(<App api={mock.api} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Continue with Google' }));

    expect(mock.auth.signInWithGoogle).toHaveBeenCalledTimes(1);
    expect(mock.auth.signInWithGoogle).toHaveBeenCalledWith();
  });

  it('browser login in progress can be cancelled', async () => {
    const mock = createMockApi(status('signing-in'));
    render(<App api={mock.api} />);

    expect(await screen.findByRole('heading', { name: 'Continue in your browser' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel sign-in' }));
    expect(mock.auth.cancelSignIn).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['sign-in-cancelled', 'Sign-in was cancelled.'],
    ['sign-in-failed', 'Sign-in didn’t complete. Please try again.'],
    ['sign-in-timed-out', /timed out/],
    ['signed-out-unconfirmed', /couldn’t be reached to confirm/]
  ] as const)('shows the %s notice on the welcome screen', async (notice, text) => {
    const mock = createMockApi(status('signed-out', { notice }));
    render(<App api={mock.api} />);
    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue with Google' })).toBeEnabled();
  });

  it('session expired asks to sign in again', async () => {
    const mock = createMockApi(status('session-expired'));
    render(<App api={mock.api} />);
    expect(await screen.findByText('Your session has expired. Please sign in again.')).toBeInTheDocument();
  });

  it.each([
    ['auth-disabled', 'Sign-in isn’t enabled'],
    ['backend-unreachable', 'Can’t reach HyperDrive'],
    ['backend-not-ready', 'HyperDrive isn’t ready']
  ] as const)('unavailable (%s) explains and offers retry', async (reason, title) => {
    const mock = createMockApi(status('unavailable', { unavailableReason: reason }));
    render(<App api={mock.api} />);
    expect(await screen.findByRole('heading', { name: title })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Continue with Google' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(mock.auth.retry).toHaveBeenCalledTimes(1);
  });

  it('warns when the session cannot be kept across restarts', async () => {
    const mock = createMockApi(status('signed-out', { sessionPersistence: 'memory-only' }));
    render(<App api={mock.api} />);
    expect(await screen.findByText(/sign in again each time you start HyperDrive/)).toBeInTheDocument();
  });

  it('follows pushed transitions: signing in → onboarding → signed in → signed out', async () => {
    const mock = createMockApi(status('signing-in'));
    render(<App api={mock.api} />);
    await screen.findByRole('heading', { name: 'Continue in your browser' });

    mock.push(status('onboarding', { user: NEW_USER }));
    expect(await screen.findByRole('heading', { name: 'Choose your username' })).toBeInTheDocument();

    mock.push(status('signed-in', { user: ONBOARDED_USER }));
    expect(await screen.findByRole('navigation', { name: 'Main' })).toBeInTheDocument();

    mock.push(status('signed-out', { notice: 'signed-out' }));
    expect(await screen.findByRole('heading', { name: 'Welcome to HyperDrive' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument();
    expect(screen.queryByText('@Test_User')).not.toBeInTheDocument();
  });

  it('without a preload bridge it reports unavailable instead of crashing', () => {
    render(<App api={undefined} />);
    expect(screen.getByRole('heading', { name: 'HyperDrive is misconfigured' })).toBeInTheDocument();
  });
});

describe('username onboarding', () => {
  async function renderOnboarding() {
    const mock = createMockApi(status('onboarding', { user: NEW_USER }));
    render(<App api={mock.api} />);
    await screen.findByRole('heading', { name: 'Choose your username' });
    return mock;
  }

  it.each([
    ['ab', 'Use at least 3 characters.'],
    ['has space', 'Use only letters, numbers and underscores.'],
    ['a'.repeat(25), 'Use at most 24 characters.'],
    ['Admin', 'That username is reserved. Choose another.']
  ])('validates "%s" locally without calling the backend', async (value, message) => {
    const mock = await renderOnboarding();
    await userEvent.type(screen.getByLabelText('Username'), value);
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.getByLabelText('Username')).toHaveAttribute('aria-invalid', 'true');
    expect(mock.auth.completeOnboarding).not.toHaveBeenCalled();
  });

  it('submits a valid username with progress feedback', async () => {
    const mock = await renderOnboarding();
    let finish: (v: { ok: true }) => void = () => {};
    mock.auth.completeOnboarding.mockImplementationOnce(() => new Promise((r) => (finish = r)));

    await userEvent.type(screen.getByLabelText('Username'), 'New_User_1');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));

    expect(mock.auth.completeOnboarding).toHaveBeenCalledWith({ username: 'New_User_1' });
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled();
    finish({ ok: true });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled());
  });

  it('shows a clear "username taken" error from the backend', async () => {
    const mock = await renderOnboarding();
    mock.auth.completeOnboarding.mockResolvedValueOnce({ ok: false, error: 'username_taken' });

    await userEvent.type(screen.getByLabelText('Username'), 'Popular');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('That username is already taken. Choose another.')).toBeInTheDocument();
  });

  it('offers sign out during onboarding', async () => {
    const mock = await renderOnboarding();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(mock.auth.signOut).toHaveBeenCalledTimes(1);
  });
});
