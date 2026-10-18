import { DefaultService } from './generated';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { accountApi } from './accountApi';

describe('accountApi', () => {
  afterEach(() => vi.restoreAllMocks());

  it('requests account deletion through the generated API client', async () => {
    const request = vi.spyOn(DefaultService, 'deleteAccount').mockResolvedValue(undefined);

    await accountApi.deleteAccount();

    expect(request).toHaveBeenCalledOnce();
  });
});
