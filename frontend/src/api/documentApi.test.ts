import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DefaultService } from './generated';
import { documentApi, isDocumentAuthorizationError, isDocumentUnavailableError } from './documentApi';

describe('documentApi', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('uses the generated authoritative processing-status operation', async () => {
    const status = { documentId: 'document-1', status: 'PROCESSING' } as never;
    const getStatus = vi.spyOn(DefaultService, 'getDocumentProcessingStatus').mockResolvedValue(status);

    await expect(documentApi.getProcessingStatus('document-1')).resolves.toBe(status);
    expect(getStatus).toHaveBeenCalledWith('document-1');
  });

  it('uses the generated owner-authorized original-document access operation', async () => {
    const access = { url: 'https://storage.example.test/signed', expiresAt: '2026-09-24T12:05:00Z' } as never;
    const getAccess = vi.spyOn(DefaultService, 'getOriginalDocumentAccess').mockResolvedValue(access);

    await expect(documentApi.getOriginalDocumentAccess('document-1')).resolves.toBe(access);
    expect(getAccess).toHaveBeenCalledWith('document-1');
  });

  it('uses the existing owner-authorized permanent document deletion operation', async () => {
    const remove = vi.spyOn(DefaultService, 'deleteDocument').mockResolvedValue(undefined);

    await expect(documentApi.deleteDocument('document-1')).resolves.toBeUndefined();
    expect(remove).toHaveBeenCalledWith('document-1');
  });

  it('recognizes only protected authorization failures', () => {
    expect(isDocumentAuthorizationError({ status: 401 })).toBe(true);
    expect(isDocumentAuthorizationError({ status: 403 })).toBe(true);
    expect(isDocumentAuthorizationError({ status: 404 })).toBe(false);
    expect(isDocumentUnavailableError({ status: 403 })).toBe(true);
    expect(isDocumentUnavailableError({ status: 404 })).toBe(true);
    expect(isDocumentUnavailableError({ status: 500 })).toBe(false);
  });
});
