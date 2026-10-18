import { afterEach, describe, expect, it, vi } from 'vitest';
import { DefaultService, StartUploadRequest, UploadStatusResponse, UploadTarget } from './generated';
import { completeLocalUpload, isTerminalUploadStatus, uploadApi, uploadToStorage, validateSelectedFile } from './uploadApi';
import { setCurrentSession } from '../auth/authSession';
import { DISABLED_RUNTIME_CONFIGURATION, setRuntimeConfiguration } from '../auth/runtimeConfig';

describe('upload API integration boundary', () => {
  afterEach(() => {
    setCurrentSession(null);
    setRuntimeConfiguration(DISABLED_RUNTIME_CONFIGURATION);
  });

  it('sends the binary with only presigned target instructions and omits credentials', async () => {
    const fetcher = vi.fn().mockResolvedValue({ ok: true });
    const file = new File(['pdf'], 'contract.pdf', { type: 'application/pdf' });
    const target = {
      method: UploadTarget.method.PUT,
      url: 'https://storage.example.test/signed',
      headers: { 'Content-Type': 'application/pdf' },
      expiresAt: '2026-09-10T12:00:00Z',
    };

    await uploadToStorage(target, file, fetcher);

    expect(fetcher).toHaveBeenCalledWith(target.url, {
      method: 'PUT',
      headers: target.headers,
      body: file,
      credentials: 'omit',
    });
    expect(fetcher.mock.calls[0][1]).not.toHaveProperty('Authorization');
  });

  it('rejects non-PDF and oversized files at the UX boundary', () => {
    expect(validateSelectedFile(new File(['x'], 'notes.txt', { type: 'text/plain' }))).toContain('PDF');
    const large = new File([new Uint8Array(25 * 1024 * 1024 + 1)], 'large.pdf', { type: 'application/pdf' });
    expect(validateSelectedFile(large)).toContain('25 MB');
  });

  it('rejects unsupported storage instructions and unsuccessful storage responses', async () => {
    const file = new File(['pdf'], 'contract.pdf', { type: 'application/pdf' });
    const target = {
      method: 'POST' as UploadTarget.method,
      url: 'https://storage.example.test/signed',
      headers: {},
      expiresAt: '2026-09-10T12:00:00Z',
    };
    await expect(uploadToStorage(target, file, vi.fn())).rejects.toThrow('Unsupported upload method');
    await expect(uploadToStorage({ ...target, method: UploadTarget.method.PUT }, file,
      vi.fn().mockResolvedValue({ ok: false }))).rejects.toThrow('Storage upload failed');
  });

  it('preserves protected local completion status for the application error boundary', async () => {
    setRuntimeConfiguration({ environment: 'local', auth: { mode: 'local', token: 'local-owner' } });
    setCurrentSession({ accessToken: 'local-owner' });
    await expect(completeLocalUpload('upload-1', vi.fn().mockResolvedValue({ ok: false, status: 403 })))
      .rejects.toMatchObject({ message: 'Local upload processing failed', status: 403 });
  });

  it('delegates application status calls to the generated client and recognizes only API terminals', async () => {
    const start = vi.spyOn(DefaultService, 'startUpload').mockResolvedValue({} as never);
    const status = vi.spyOn(DefaultService, 'getUploadStatus').mockResolvedValue({} as never);
    const request = {
      filename: 'contract.pdf', contentType: 'application/pdf', size: 3,
      contractType: StartUploadRequest.contractType.ELECTRICITY,
    };
    await uploadApi.startUpload(request);
    await uploadApi.getUploadStatus('upload-1');
    expect(start).toHaveBeenCalledWith(request);
    expect(status).toHaveBeenCalledWith('upload-1');
    expect(isTerminalUploadStatus(UploadStatusResponse.status.COMPLETED)).toBe(true);
    expect(isTerminalUploadStatus(UploadStatusResponse.status.REJECTED)).toBe(true);
    expect(isTerminalUploadStatus(UploadStatusResponse.status.EXPIRED)).toBe(true);
    expect(isTerminalUploadStatus(UploadStatusResponse.status.PROMOTED)).toBe(false);
    start.mockRestore();
    status.mockRestore();
  });
});
