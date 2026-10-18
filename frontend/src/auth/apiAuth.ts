import { OpenAPI } from '../api/generated';
import { expireCurrentSession, getCurrentAccessToken } from './authSession';

export function configureApiAuthentication(): () => void {
  OpenAPI.TOKEN = async () => getCurrentAccessToken() ?? '';

  const originalFetch = window.fetch;
  const authenticatedFetch: typeof fetch = async (input, init) => {
    const response = await originalFetch.call(window, input, init);
    const requestUrl = typeof input === 'string' || input instanceof URL ? input : input.url;
    const url = new URL(requestUrl, window.location.href);
    if (url.origin === window.location.origin && url.pathname.startsWith('/v1/') && response.status === 401) {
      expireCurrentSession();
    }
    return response;
  };

  window.fetch = authenticatedFetch;
  return () => {
    if (window.fetch === authenticatedFetch) window.fetch = originalFetch;
  };
}
