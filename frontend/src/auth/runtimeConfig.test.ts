import { describe, expect, it, vi } from 'vitest';
import { DISABLED_RUNTIME_CONFIGURATION, loadRuntimeConfiguration, parseRuntimeConfiguration } from './runtimeConfig';

const cognitoConfiguration = {
  environment: 'Development',
  auth: {
    mode: 'cognito',
    issuer: 'https://cognito-idp.eu-west-1.amazonaws.com/eu-example',
    domain: 'https://reader-example.auth.example.test',
    clientId: 'public-client-id',
    scopes: ['openid', 'email', 'profile', 'contractforge-api/access'],
    callbackUrl: 'https://dev.contractforge.example/',
    logoutUrl: 'https://dev.contractforge.example/',
  },
};

describe('frontend runtime configuration', () => {
  it('accepts public Cognito settings for Development and Production', () => {
    expect(parseRuntimeConfiguration(cognitoConfiguration)).toEqual(cognitoConfiguration);
    expect(parseRuntimeConfiguration({
      ...cognitoConfiguration,
      environment: 'Production',
    }).environment).toBe('Production');
  });

  it('fails closed for malformed, insecure, or cross-environment local authentication settings', () => {
    expect(parseRuntimeConfiguration({
      ...cognitoConfiguration,
      auth: { ...cognitoConfiguration.auth, issuer: 'http://issuer.example' },
    })).toEqual(DISABLED_RUNTIME_CONFIGURATION);
    expect(parseRuntimeConfiguration({
      environment: 'Production',
      auth: { mode: 'local', token: 'local-owner' },
    })).toEqual(DISABLED_RUNTIME_CONFIGURATION);
    expect(parseRuntimeConfiguration({})).toEqual(DISABLED_RUNTIME_CONFIGURATION);
  });

  it('loads the runtime document without caching and disables auth when it cannot be trusted', async () => {
    const fetchConfiguration = vi.fn().mockResolvedValue({ ok: true, json: async () => cognitoConfiguration });
    expect(await loadRuntimeConfiguration(fetchConfiguration)).toEqual(cognitoConfiguration);
    expect(fetchConfiguration).toHaveBeenCalledWith('/runtime-config.json', { cache: 'no-store' });
    expect(await loadRuntimeConfiguration(vi.fn().mockRejectedValue(new Error('network failure')))).toEqual(DISABLED_RUNTIME_CONFIGURATION);
  });
});
