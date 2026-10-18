import { afterEach, describe, expect, it, vi } from 'vitest';
import { InMemoryWebStorage, User, WebStorageStateStore } from 'oidc-client-ts';
import { createAuthAdapter, createCognitoUserManager, getCurrentAccessToken, setCurrentSession, isLocalAuthEnabled } from './authSession';
import type { AuthRuntimeConfiguration, RuntimeConfiguration } from './runtimeConfig';

const localRuntime: RuntimeConfiguration = { environment: 'local', auth: { mode: 'local', token: 'local-owner' } };
const cognitoConfig: Extract<AuthRuntimeConfiguration, { mode: 'cognito' }> = {
  mode: 'cognito',
  issuer: 'https://issuer.example.test/pool',
  domain: 'https://login.example.test',
  clientId: 'public-client-id',
  scopes: ['openid', 'email', 'contractforge-api/access'],
  callbackUrl: 'https://app.example.test/',
  logoutUrl: 'https://app.example.test/',
};
const cognitoRuntime: RuntimeConfiguration = { environment: 'Development', auth: cognitoConfig };

function user(overrides: Record<string, unknown> = {}) {
  return {
    access_token: 'cognito-access-token',
    expires_at: Math.floor(Date.now() / 1000) + 60,
    expired: false,
    profile: { email: 'user@example.test' },
    state: undefined,
    ...overrides,
  } as never;
}

describe('frontend authentication boundary', () => {
  afterEach(() => setCurrentSession(null));

  it('enables the local Compose session only for explicit local runtime config on loopback', async () => {
    expect(isLocalAuthEnabled(localRuntime, 'localhost')).toBe(true);
    expect(isLocalAuthEnabled(localRuntime, '127.0.0.1')).toBe(true);
    expect(isLocalAuthEnabled(localRuntime, 'contractforge.example.com')).toBe(false);
    expect(isLocalAuthEnabled({ environment: 'Production', auth: localRuntime.auth }, 'localhost')).toBe(false);

    const adapter = createAuthAdapter(localRuntime, {
      location: { hostname: 'localhost', href: 'http://localhost/', assign: vi.fn() },
      sessionStorage: window.sessionStorage,
    });
    expect(adapter.canSignIn).toBe(false);
    expect(await adapter.getSession()).toEqual({ accessToken: 'local-owner' });
    await adapter.signOut({ accessToken: 'local-owner' });
    expect(await adapter.getSession()).toBeNull();
  });

  it('does not activate local auth on a non-loopback host or from disabled runtime config', async () => {
    const nonLoopback = createAuthAdapter(localRuntime, {
      location: { hostname: 'contractforge.example.com', href: 'https://contractforge.example.com/', assign: vi.fn() },
      sessionStorage: window.sessionStorage,
    });
    const disabled = createAuthAdapter({ environment: 'local', auth: { mode: 'disabled' } }, {
      location: { hostname: 'localhost', href: 'http://localhost/', assign: vi.fn() },
      sessionStorage: window.sessionStorage,
    });
    expect(await nonLoopback.getSession()).toBeNull();
    expect(await disabled.getSession()).toBeNull();
  });

  it('uses the Cognito authorization-code callback and exposes only an unexpired access token', async () => {
    const callbackManager = {
      getUser: vi.fn(),
      signinRedirect: vi.fn(),
      signinRedirectCallback: vi.fn().mockResolvedValue(user({ state: { returnTo: '/contracts/one' } })),
      removeUser: vi.fn(),
    };
    const dependencies = { createUserManager: () => callbackManager, redirect: vi.fn() };
    const browser = { location: { hostname: 'app.example.test', href: 'https://app.example.test/?code=abc&state=xyz', assign: vi.fn() }, sessionStorage: window.sessionStorage };
    const adapter = createAuthAdapter(cognitoRuntime, browser, dependencies);
    expect(adapter.canSignIn).toBe(true);
    const sessions = await Promise.all([adapter.getSession(), adapter.getSession()]);
    expect(sessions[0]).toMatchObject({ accessToken: 'cognito-access-token', email: 'user@example.test', returnTo: '/contracts/one' });
    expect(sessions[1]).toEqual(sessions[0]);
    expect(callbackManager.signinRedirectCallback).toHaveBeenCalledOnce();
    expect(callbackManager.signinRedirectCallback).toHaveBeenCalledWith(browser.location.href);
    expect(window.localStorage.length).toBe(0);
  });

  it('starts PKCE sign-in with a same-origin return path and rejects open redirects', async () => {
    const manager = { getUser: vi.fn(), signinRedirect: vi.fn(), signinRedirectCallback: vi.fn(), removeUser: vi.fn() };
    const adapter = createAuthAdapter(cognitoRuntime, {
      location: { hostname: 'app.example.test', href: 'https://app.example.test/', assign: vi.fn() },
      sessionStorage: window.sessionStorage,
    }, { createUserManager: () => manager, redirect: vi.fn() });
    await adapter.signIn('/contracts/one?tab=overview#history');
    await adapter.signIn('//evil.example.test');
    await adapter.signIn('/\\evil.example.test');
    await adapter.signIn('/%5C%5Cevil.example.test');
    await adapter.signIn('/sign-in');
    expect(manager.signinRedirect.mock.calls.map(([options]) => options)).toEqual([
      { state: { returnTo: '/contracts/one?tab=overview#history' } },
      { state: { returnTo: undefined } },
      { state: { returnTo: undefined } },
      { state: { returnTo: undefined } },
      { state: { returnTo: undefined } },
    ]);
  });

  it('rejects an unsafe return path from the Cognito callback state', async () => {
    const manager = {
      getUser: vi.fn(),
      signinRedirect: vi.fn(),
      signinRedirectCallback: vi.fn().mockResolvedValue(user({ state: { returnTo: '/\\evil.example.test' } })),
      removeUser: vi.fn(),
    };
    const adapter = createAuthAdapter(cognitoRuntime, {
      location: { hostname: 'app.example.test', href: 'https://app.example.test/?code=abc&state=xyz', assign: vi.fn() },
      sessionStorage: window.sessionStorage,
    }, { createUserManager: () => manager, redirect: vi.fn() });

    expect(await adapter.getSession()).toMatchObject({ accessToken: 'cognito-access-token' });
    expect((await adapter.getSession())?.returnTo).toBeUndefined();
  });

  it('does not restore an expired Cognito access token', async () => {
    const manager = { getUser: vi.fn().mockResolvedValue(user({ expires_at: Math.floor(Date.now() / 1000) - 1 })), signinRedirect: vi.fn(), signinRedirectCallback: vi.fn(), removeUser: vi.fn() };
    const adapter = createAuthAdapter(cognitoRuntime, {
      location: { hostname: 'app.example.test', href: 'https://app.example.test/', assign: vi.fn() },
      sessionStorage: window.sessionStorage,
    }, { createUserManager: () => manager, redirect: vi.fn() });
    expect(await adapter.getSession()).toBeNull();
    expect(manager.removeUser).toHaveBeenCalledOnce();
  });

  it('redirects to Cognito logout and preserves its active session if navigation fails', async () => {
    const manager = { getUser: vi.fn(), signinRedirect: vi.fn(), signinRedirectCallback: vi.fn(), removeUser: vi.fn() };
    const redirect = vi.fn(() => { throw new Error('navigation failed'); });
    const adapter = createAuthAdapter(cognitoRuntime, {
      location: { hostname: 'app.example.test', href: 'https://app.example.test/', assign: vi.fn() },
      sessionStorage: window.sessionStorage,
    }, { createUserManager: () => manager, redirect });
    await expect(adapter.signOut({ accessToken: 'cognito-access-token' })).rejects.toThrow('navigation failed');
    expect(redirect).toHaveBeenCalledWith(expect.stringContaining('/logout?'));
    expect(manager.removeUser).not.toHaveBeenCalled();
  });

  it('keeps production tokens in memory instead of browser storage', async () => {
    const manager = { getUser: vi.fn().mockResolvedValue(user()), signinRedirect: vi.fn(), signinRedirectCallback: vi.fn(), removeUser: vi.fn() };
    const adapter = createAuthAdapter(cognitoRuntime, {
      location: { hostname: 'localhost', href: 'https://app.example.test/', assign: vi.fn() },
      sessionStorage: window.sessionStorage,
    }, { createUserManager: () => manager, redirect: vi.fn() });
    const session = await adapter.getSession();
    expect(session?.accessToken).toBe('cognito-access-token');
    expect((await adapter.getSession())?.accessToken).toBe('cognito-access-token');
    setCurrentSession(session);
    expect(getCurrentAccessToken()).toBe('cognito-access-token');
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it('configures the OIDC client to keep user tokens in memory and only transient flow state in session storage', async () => {
    const manager = createCognitoUserManager(cognitoConfig, {
      location: { hostname: 'app.example.test', href: 'https://app.example.test/', assign: vi.fn() },
      sessionStorage: window.sessionStorage,
    });
    const storedUser = new User({
      access_token: 'in-memory-access-token',
      expires_at: Math.floor(Date.now() / 1000) + 60,
      token_type: 'Bearer',
      profile: {
        sub: 'subject',
        iss: 'https://issuer.example.test/pool',
        aud: 'public-client-id',
        exp: Math.floor(Date.now() / 1000) + 60,
        iat: Math.floor(Date.now() / 1000),
      },
    });
    await manager.storeUser(storedUser);

    expect(await manager.getUser()).toMatchObject({ access_token: 'in-memory-access-token' });
    expect(manager.settings.userStore).toBeInstanceOf(WebStorageStateStore);
    expect((manager.settings.userStore as unknown as { _store?: Storage })._store).toBeInstanceOf(InMemoryWebStorage);
    expect(manager.settings.stateStore).toBeInstanceOf(WebStorageStateStore);
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });
});
