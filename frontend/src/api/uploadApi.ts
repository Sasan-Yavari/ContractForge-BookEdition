import {
  DefaultService,
  CancelablePromise,
  type StartUploadRequest,
  type StartUploadResponse,
  UploadStatusResponse,
  UploadTarget,
} from './generated';
import { getCurrentAccessToken, isLocalAuthEnabled } from '../auth/authSession';

export const MAX_UPLOAD_SIZE_BYTES = 25 * 1024 * 1024;
export const SUPPORTED_UPLOAD_TYPE = 'application/pdf';
export const STATUS_POLL_INTERVAL_MS = 3000;
export const MAX_STATUS_FAILURES = 3;

export type UploadApi = {
  startUpload: (request: StartUploadRequest) => Promise<StartUploadResponse>;
  getUploadStatus: (id: string) => CancelablePromise<UploadStatusResponse>;
};

export const uploadApi: UploadApi = {
  startUpload: (request) => DefaultService.startUpload(request),
  getUploadStatus: (id) => DefaultService.getUploadStatus(id),
};

export async function uploadToStorage(
  target: UploadTarget,
  file: File,
  fetcher: typeof fetch = fetch,
): Promise<void> {
  if (target.method !== UploadTarget.method.PUT) {
    throw new Error('Unsupported upload method');
  }
  const response = await fetcher(target.url, {
    method: target.method,
    headers: target.headers,
    body: file,
    credentials: 'omit',
  });
  if (!response.ok) throw new Error('Storage upload failed');
}

export async function completeLocalUpload(uploadId: string, fetcher: typeof fetch = fetch): Promise<void> {
  if (!isLocalAuthEnabled()) return;
  const token = getCurrentAccessToken();
  if (!token) return;
  const response = await fetcher(`/v1/local/uploads/${encodeURIComponent(uploadId)}/complete`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) {
    const failure = new Error('Local upload processing failed') as Error & { status: number };
    failure.status = response.status;
    throw failure;
  }
}

export function validateSelectedFile(file: File): string | undefined {
  const looksLikePdf = file.type === SUPPORTED_UPLOAD_TYPE || file.name.toLowerCase().endsWith('.pdf');
  if (!looksLikePdf) return 'Choose a PDF document.';
  if (file.size > MAX_UPLOAD_SIZE_BYTES) {
    return 'Choose a PDF document no larger than 25 MB.';
  }
  return undefined;
}

export function isTerminalUploadStatus(status: UploadStatusResponse.status): boolean {
  return status === UploadStatusResponse.status.COMPLETED
    || status === UploadStatusResponse.status.REJECTED
    || status === UploadStatusResponse.status.EXPIRED;
}

export function isAuthorizationError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false;
  const status = (error as { status?: unknown }).status;
  return status === 401 || status === 403;
}
