import {
  CancelablePromise,
  DefaultService,
  type DocumentProcessingStatusResponse,
  type OriginalDocumentAccessResponse,
} from './generated';

export type DocumentApi = {
  getProcessingStatus: (documentId: string) => CancelablePromise<DocumentProcessingStatusResponse>;
  deleteDocument: (documentId: string) => Promise<void>;
  getOriginalDocumentAccess: (documentId: string) => Promise<OriginalDocumentAccessResponse>;
};

export const documentApi: DocumentApi = {
  getProcessingStatus: (documentId) => DefaultService.getDocumentProcessingStatus(documentId),
  deleteDocument: (documentId) => DefaultService.deleteDocument(documentId),
  getOriginalDocumentAccess: (documentId) => DefaultService.getOriginalDocumentAccess(documentId),
};

export function isDocumentUnavailableError(error: unknown): boolean {
  return typeof error === 'object' && error !== null
    && ([403, 404] as number[]).includes((error as { status?: number }).status ?? 0);
}

export function isDocumentAuthorizationError(error: unknown): boolean {
  return typeof error === 'object' && error !== null
    && ([401, 403] as number[]).includes((error as { status?: number }).status ?? 0);
}
