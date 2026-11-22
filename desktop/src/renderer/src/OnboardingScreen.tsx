import { useState, type FormEvent } from 'react';
import type { AuthApi, OnboardingError, SafeUser } from '../../shared/auth';
import { checkUsername, USERNAME_MAX, USERNAME_MIN, type UsernameProblem } from '../../shared/username';

const PROBLEM_TEXT: Record<UsernameProblem, string> = {
  'too-short': `Use at least ${USERNAME_MIN} characters.`,
  'too-long': `Use at most ${USERNAME_MAX} characters.`,
  'invalid-characters': 'Use only letters, numbers and underscores.',
  reserved: 'That username is reserved. Choose another.'
};

const SERVER_ERROR_TEXT: Record<OnboardingError, string> = {
  invalid_username: 'That username isn’t allowed. Use 3–24 letters, numbers or underscores.',
  username_reserved: 'That username is reserved. Choose another.',
  username_taken: 'That username is already taken. Choose another.',
  onboarding_already_complete: 'Your username has already been set.',
  'session-expired': 'Your session expired. Please sign in again.',
  'backend-unreachable': 'Couldn’t reach HyperDrive. Check that the backend is running and try again.',
  'rate-limited': 'Too many attempts. Wait a minute and try again.',
  unknown: 'Something went wrong. Please try again.'
};

export function OnboardingScreen({ api, user }: { api: AuthApi; user: SafeUser }) {
  const [username, setUsername] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState<OnboardingError | null>(null);

  const problem = checkUsername(username);
  const showProblem = touched && problem !== null;
  const message = serverError ? SERVER_ERROR_TEXT[serverError] : showProblem ? PROBLEM_TEXT[problem] : null;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setTouched(true);
    setServerError(null);
    if (problem !== null || submitting) return;
    setSubmitting(true);
    try {
      const result = await api.completeOnboarding({ username });
      if (!result.ok) setServerError(result.error);
      // On success the main process pushes the signed-in status and this screen unmounts.
    } catch {
      setServerError('unknown');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section aria-labelledby="onboarding-title">
      <h1 id="onboarding-title">Choose your username</h1>
      <p>
        {user.displayName ? `Welcome, ${user.displayName}. ` : 'Welcome. '}
        Pick a username for your HyperDrive account. It’s your public handle, not a password.
      </p>
      <form onSubmit={(e) => void submit(e)} noValidate>
        <label htmlFor="username" className="field-label">
          Username
        </label>
        <input
          id="username"
          className="input"
          value={username}
          onChange={(e) => {
            setUsername(e.target.value);
            setServerError(null);
          }}
          onBlur={() => setTouched(true)}
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          maxLength={64}
          aria-invalid={message !== null}
          aria-describedby="username-hint username-error"
        />
        <p id="username-hint" className="muted small">
          {USERNAME_MIN}–{USERNAME_MAX} characters: letters, numbers and underscores.
        </p>
        <p id="username-error" className="field-error" role={message ? 'alert' : undefined}>
          {message}
        </p>
        <div className="actions">
          <button type="submit" className="button" disabled={submitting}>
            {submitting ? 'Saving…' : 'Continue'}
          </button>
          <button type="button" className="button button-secondary" onClick={() => void api.signOut()} disabled={submitting}>
            Sign out
          </button>
        </div>
      </form>
    </section>
  );
}
