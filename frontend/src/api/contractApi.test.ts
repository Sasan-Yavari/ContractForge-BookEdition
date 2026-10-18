import { afterEach, describe, expect, it, vi } from 'vitest';
import { DefaultService, type EditContractRequest } from './generated';
import { contractApi, isContractAuthorizationError, isContractConflict, isContractUnavailable } from './contractApi';

describe('contract API integration boundary', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('delegates review, edit, and approval calls to the generated client', async () => {
    const review = { documentId: 'document-1', status: 'DRAFT', version: 2 } as never;
    const overview = { documentId: 'document-1' } as never;
    const request = {
      expectedVersion: 2,
      parties: [{ role: 'CUSTOMER', name: 'Ada' }],
      terms: { startDate: '2026-01-01' },
    } as EditContractRequest;
    const getOverview = vi.spyOn(DefaultService, 'getContractOverview').mockResolvedValue(overview);
    const getReview = vi.spyOn(DefaultService, 'getContractReview').mockResolvedValue(review);
    const edit = vi.spyOn(DefaultService, 'editContract').mockResolvedValue(review);
    const approveDraft = vi.spyOn(DefaultService, 'approveContractDraft').mockResolvedValue(review);

    await expect(contractApi.getOverview('document-1')).resolves.toBe(overview);
    await expect(contractApi.getReview('document-1')).resolves.toBe(review);
    await expect(contractApi.editContract('document-1', request)).resolves.toBe(review);
    await expect(contractApi.approveDraft('document-1', 3)).resolves.toBe(review);

    expect(getOverview).toHaveBeenCalledWith('document-1');
    expect(getReview).toHaveBeenCalledWith('document-1');
    expect(edit).toHaveBeenCalledWith('document-1', request);
    expect(approveDraft).toHaveBeenCalledWith('document-1', { expectedVersion: 3 });
  });

  it('loads the complete collection through the generated client without reshaping its order', async () => {
    const first = { id: 'first', status: 'PROCESSING' } as never;
    const second = { id: 'second', status: 'NEEDS_REVIEW' } as never;
    const listContracts = vi.spyOn(DefaultService, 'listContracts').mockResolvedValue([first, second]);

    await expect(contractApi.listContracts()).resolves.toEqual([first, second]);
    expect(listContracts).toHaveBeenCalledOnce();
  });

  it('recognizes only conflict and authorization HTTP status errors', () => {
    expect(isContractConflict({ status: 409 })).toBe(true);
    expect(isContractConflict({ status: 400 })).toBe(false);
    expect(isContractConflict(null)).toBe(false);
    expect(isContractAuthorizationError({ status: 401 })).toBe(true);
    expect(isContractAuthorizationError({ status: 403 })).toBe(true);
    expect(isContractAuthorizationError({ status: 409 })).toBe(false);
    expect(isContractAuthorizationError('forbidden')).toBe(false);
    expect(isContractUnavailable({ status: 403 })).toBe(true);
    expect(isContractUnavailable({ status: 404 })).toBe(true);
    expect(isContractUnavailable({ status: 500 })).toBe(false);
    expect(isContractUnavailable(undefined)).toBe(false);
  });
});
