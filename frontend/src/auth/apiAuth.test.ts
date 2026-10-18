import { afterEach, describe, expect, it, vi } from 'vitest';
import { OpenAPI } from '../api/generated';
import { AUTH_SESSION_EXPIRED_EVENT, getCurrentAccessToken, setCurrentSession } from './authSession';
import { configureApiAuthentication } from './apiAuth';

describe('generated API authentication integration', () => {
  afterEach(() => {
    setCurrentSession(null);
    vi.restoreAllMocks();
  });

  it('resolves bearer tokens from in-memory auth state for each protected API call', async () => {
    const restoreFetch = configureApiAuthentication();
    setCurrentSession({ accessToken: 'first-token' });
    expect(typeof OpenAPI.TOKEN).toBe('function');
    if (typeof OpenAPI.TOKEN !== 'function') throw new Error('Expected a token resolver');
    expect(await OpenAPI.TOKEN({} as never)).toBe('first-token');
    setCurrentSession({ accessToken: 'replacement-token' });
    expect(await OpenAPI.TOKEN({} as never)).toBe('replacement-token');
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    restoreFetch();
  });

  it('expires the session after a same-origin API 401 but not for external responses', async () => {
    const fetchMock = vi.spyOn(window, 'fetch').mockResolvedValue(new Response('', { status: 401 }));
    const restoreFetch = configureApiAuthentication();
    const listener = vi.fn();
    window.addEventListener(AUTH_SESSION_EXPIRED_EVENT, listener);
    setCurrentSession({ accessToken: 'memory-only-token' });

    await window.fetch('/v1/contracts');
    expect(listener).toHaveBeenCalledTimes(1);
    expect(getCurrentAccessToken()).toBeUndefined();

    setCurrentSession({ accessToken: 'memory-only-token' });
    await window.fetch('https://storage.example.test/upload');
    expect(listener).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    window.removeEventListener(AUTH_SESSION_EXPIRED_EVENT, listener);
    restoreFetch();
  });
});
