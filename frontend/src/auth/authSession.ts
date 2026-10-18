import {
  InMemoryWebStorage,
  UserManager,
  WebStorageStateStore,
  type User,
} from 'oidc-client-ts';
import { getRuntimeConfiguration, type AuthRuntimeConfiguration, type RuntimeConfiguration } from './runtimeConfig';
import { APP_ROUTES } from '../routes';

export type AuthSession = {
  accessToken: string;
  email?: string;
  expiresAt?: number;
  returnTo?: string;
};

export type AuthAdapter = {
  canSignIn: boolean;
  getSession: () => Promise<AuthSession | null>;
  signIn: (returnTo?: string) => Promise<void>;
  signOut: (session: AuthSession) => Promise<void>;
};

type OidcUserManager = Pick<UserManager, 'getUser' | 'signinRedirect' | 'signinRedirectCallback' | 'removeUser'>;

type BrowserRuntime = {
  location: Pick<Location, 'hostname' | 'href' | 'assign'>;
  sessionStorage: Storage;
};

type AuthAdapterDependencies = {
  createUserManager: (configuration: Extract<AuthRuntimeConfiguration, { mode: 'cognito' }>, browser: BrowserRuntime) => OidcUserManager;
  redirect: (url: string) => void;
};

let currentSession: AuthSession | null = null;

export function setCurrentSession(session: AuthSession | null): void {
  currentSession = session;
}

export function getCurrentSession(): AuthSession | null {
  return currentSession;
}

export function getCurrentAccessToken(): string | undefined {
  return currentSession?.accessToken;
}

export const AUTH_SESSION_EXPIRED_EVENT = 'contractforge:session-expired';

export function expireCurrentSession(): void {
  setCurrentSession(null);
  window.dispatchEvent(new Event(AUTH_SESSION_EXPIRED_EVENT));
}

export function createAuthAdapter(
  runtime: RuntimeConfiguration = getRuntimeConfiguration(),
  browser: BrowserRuntime = window,
  dependencies: AuthAdapterDependencies = {
    createUserManager: createCognitoUserManager,
    redirect: (url) => browser.location.assign(url),
  },
): AuthAdapter {
  const configuration = runtime.auth;
  if (configuration.mode === 'local' && isLocalAuthEnabled(runtime, browser.location.hostname)) {
    let activeToken: string | undefined = configuration.token;
    return {
      canSignIn: false,
      getSession: async () => activeToken ? { accessToken: activeToken } : null,
      signIn: async () => undefined,
      signOut: async () => { activeToken = undefined; },
    };
  }

  if (configuration.mode !== 'cognito') return disabledAuthAdapter();

  const manager = dependencies.createUserManager(configuration, browser);
  let callbackSession: Promise<AuthSession | null> | undefined;
  return {
    canSignIn: true,
    getSession: async () => {
      const callback = new URL(browser.location.href);
      if (callback.searchParams.has('code') || callback.searchParams.has('error')) {
        callbackSession ??= manager.signinRedirectCallback(callback.href)
          .then((user) => sessionFromUser(user, user.state, browser.location.href));
        return callbackSession;
      }
      const user = await manager.getUser();
      if (!user || !user.access_token || user.expired || !user.expires_at) return null;
      const expiresAt = user.expires_at * 1000;
      if (expiresAt <= Date.now()) {
        await manager.removeUser();
        return null;
      }
      return sessionFromUser(user, user.state, browser.location.href);
    },
    signIn: async (returnTo) => {
      await manager.signinRedirect({ state: { returnTo: safeReturnPath(returnTo, browser.location.href) } });
    },
    signOut: async () => {
      const logout = new URL('/logout', configuration.domain);
      logout.searchParams.set('client_id', configuration.clientId);
      logout.searchParams.set('logout_uri', configuration.logoutUrl);
      dependencies.redirect(logout.href);
      await manager.removeUser();
    },
  };
}

export function isLocalAuthEnabled(
  runtime: RuntimeConfiguration = getRuntimeConfiguration(),
  hostname: string = window.location.hostname,
): boolean {
  const isLoopbackHost = hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '::1';
  return runtime.environment === 'local'
    && runtime.auth.mode === 'local'
    && isLoopbackHost
    && Boolean(runtime.auth.token);
}

function disabledAuthAdapter(): AuthAdapter {
  return {
    canSignIn: false,
    getSession: async () => null,
    signIn: async () => undefined,
    signOut: async () => undefined,
  };
}

export function createCognitoUserManager(
  configuration: Extract<AuthRuntimeConfiguration, { mode: 'cognito' }>,
  browser: BrowserRuntime,
): UserManager {
  return new UserManager({
    authority: configuration.issuer,
    client_id: configuration.clientId,
    redirect_uri: configuration.callbackUrl,
    post_logout_redirect_uri: configuration.logoutUrl,
    response_type: 'code',
    scope: configuration.scopes.join(' '),
    automaticSilentRenew: false,
    monitorSession: false,
    stateStore: new WebStorageStateStore({ store: browser.sessionStorage }),
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  });
}

function sessionFromUser(user: User, state: unknown, baseUrl: string): AuthSession | null {
  if (!user.access_token || user.expired || !user.expires_at) return null;
  const expiresAt = user.expires_at * 1000;
  if (expiresAt <= Date.now()) return null;
  const email = user.profile.email;
  const returnTo = typeof state === 'object' && state !== null && 'returnTo' in state
    ? safeReturnPath((state as { returnTo?: unknown }).returnTo, baseUrl)
    : undefined;
  return {
    accessToken: user.access_token,
    ...(typeof email === 'string' ? { email } : {}),
    expiresAt,
    ...(returnTo ? { returnTo } : {}),
  };
}

function safeReturnPath(value: unknown, baseUrl: string): string | undefined {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || value.includes('\\') || /%5c/i.test(value)) {
    return undefined;
  }
  try {
    const origin = new URL(baseUrl).origin;
    const destination = new URL(value, baseUrl);
    if (destination.origin !== origin || destination.pathname === APP_ROUTES.signIn) return undefined;
    return `${destination.pathname}${destination.search}${destination.hash}`;
  } catch {
    return undefined;
  }
}
