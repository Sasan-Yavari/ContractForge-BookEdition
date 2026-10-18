import {
  CancelablePromise,
  DefaultService,
  type ContractCollectionItem,
  type ContractOverviewResponse,
  type ContractReviewResponse,
  type EditContractRequest,
} from './generated';

export type ContractApi = {
  listContracts: () => Promise<ContractCollectionItem[]>;
  getOverview: (documentId: string) => Promise<ContractOverviewResponse>;
  getReview: (documentId: string) => CancelablePromise<ContractReviewResponse>;
  editContract: (documentId: string, request: EditContractRequest) => Promise<ContractReviewResponse>;
  approveDraft: (documentId: string, expectedVersion: number) => Promise<ContractReviewResponse>;
};

export const contractApi: ContractApi = {
  listContracts: () => DefaultService.listContracts(),
  getOverview: (documentId) => DefaultService.getContractOverview(documentId),
  getReview: (documentId) => DefaultService.getContractReview(documentId),
  editContract: (documentId, request) => DefaultService.editContract(documentId, request),
  approveDraft: (documentId, expectedVersion) => DefaultService.approveContractDraft(documentId, { expectedVersion }),
};

export function isContractConflict(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { status?: number }).status === 409;
}

export function isContractUnavailable(error: unknown): boolean {
  return typeof error === 'object' && error !== null
    && ([403, 404] as number[]).includes((error as { status?: number }).status ?? 0);
}

export function isContractAuthorizationError(error: unknown): boolean {
  return typeof error === 'object' && error !== null
    && ([401, 403] as number[]).includes((error as { status?: number }).status ?? 0);
}
