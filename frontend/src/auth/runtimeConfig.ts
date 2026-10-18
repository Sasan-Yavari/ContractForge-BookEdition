export type AuthRuntimeConfiguration =
  | { mode: 'disabled' }
  | { mode: 'local'; token: string }
  | {
      mode: 'cognito';
      issuer: string;
      domain: string;
      clientId: string;
      scopes: string[];
      callbackUrl: string;
      logoutUrl: string;
    };

export type RuntimeConfiguration = {
  environment: 'local' | 'Development' | 'Production';
  auth: AuthRuntimeConfiguration;
};

export const DISABLED_RUNTIME_CONFIGURATION: RuntimeConfiguration = {
  environment: 'local',
  auth: { mode: 'disabled' },
};

let runtimeConfiguration = DISABLED_RUNTIME_CONFIGURATION;

export function setRuntimeConfiguration(configuration: RuntimeConfiguration): void {
  runtimeConfiguration = configuration;
}

export function getRuntimeConfiguration(): RuntimeConfiguration {
  return runtimeConfiguration;
}

export function parseRuntimeConfiguration(value: unknown): RuntimeConfiguration {
  if (!isRecord(value) || !isEnvironment(value.environment) || !isRecord(value.auth)) {
    return DISABLED_RUNTIME_CONFIGURATION;
  }

  const environment = value.environment;
  const auth = value.auth;
  if (auth.mode === 'local') {
    if (environment !== 'local' || typeof auth.token !== 'string' || auth.token.length === 0) {
      return DISABLED_RUNTIME_CONFIGURATION;
    }
    return { environment, auth: { mode: 'local', token: auth.token } };
  }

  if (auth.mode === 'cognito') {
    const scopes = typeof auth.scopes === 'string'
      ? auth.scopes.trim().split(/\s+/).filter(Boolean)
      : auth.scopes;
    if (environment === 'local'
      || !isHttpsUrl(auth.issuer)
      || !isHttpsUrl(auth.domain)
      || !isHttpsUrl(auth.callbackUrl)
      || !isHttpsUrl(auth.logoutUrl)
      || typeof auth.clientId !== 'string'
      || auth.clientId.trim().length === 0
      || !Array.isArray(scopes)
      || scopes.length === 0
      || !scopes.every((scope) => typeof scope === 'string' && scope.trim().length > 0)) {
      return DISABLED_RUNTIME_CONFIGURATION;
    }
    return {
      environment,
      auth: {
        mode: 'cognito',
        issuer: auth.issuer,
        domain: auth.domain,
        clientId: auth.clientId,
        scopes,
        callbackUrl: auth.callbackUrl,
        logoutUrl: auth.logoutUrl,
      },
    };
  }

  if (auth.mode === 'disabled') return { environment, auth: { mode: 'disabled' } };
  return DISABLED_RUNTIME_CONFIGURATION;
}

export async function loadRuntimeConfiguration(
  fetchConfiguration: typeof fetch = fetch,
): Promise<RuntimeConfiguration> {
  try {
    const response = await fetchConfiguration('/runtime-config.json', { cache: 'no-store' });
    if (!response.ok) return DISABLED_RUNTIME_CONFIGURATION;
    return parseRuntimeConfiguration(await response.json());
  } catch {
    return DISABLED_RUNTIME_CONFIGURATION;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isEnvironment(value: unknown): value is RuntimeConfiguration['environment'] {
  return value === 'local' || value === 'Development' || value === 'Production';
}

function isHttpsUrl(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password;
  } catch {
    return false;
  }
}
