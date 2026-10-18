import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import type { AuthAdapter, AuthSession } from './auth/authSession';
import { AUTH_SESSION_EXPIRED_EVENT, getCurrentAccessToken, setCurrentSession } from './auth/authSession';
import { ContractCollectionItem } from './api/generated';

const contractMocks = vi.hoisted(() => ({ getOverview: vi.fn(), getReview: vi.fn(), editContract: vi.fn(), approveDraft: vi.fn(), listContracts: vi.fn() }));
const workflowMocks = vi.hoisted(() => ({ startUpload: vi.fn(), getUploadStatus: vi.fn(), uploadToStorage: vi.fn(), completeLocalUpload: vi.fn(), getProcessingStatus: vi.fn(), deleteDocument: vi.fn() }));
const accountMocks = vi.hoisted(() => ({ deleteAccount: vi.fn() }));

vi.mock('./api/accountApi', () => ({ accountApi: { deleteAccount: accountMocks.deleteAccount } }));

vi.mock('./api/contractApi', () => ({
  contractApi: { getOverview: contractMocks.getOverview, getReview: contractMocks.getReview, editContract: contractMocks.editContract, approveDraft: contractMocks.approveDraft, listContracts: contractMocks.listContracts },
  isContractUnavailable: (error: unknown) => typeof error === 'object' && error !== null
    && [403, 404].includes((error as { status?: number }).status ?? 0),
}));

vi.mock('./api/uploadApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api/uploadApi')>();
  return {
    ...actual,
    uploadApi: { ...actual.uploadApi, startUpload: workflowMocks.startUpload, getUploadStatus: workflowMocks.getUploadStatus },
    uploadToStorage: workflowMocks.uploadToStorage,
    completeLocalUpload: workflowMocks.completeLocalUpload,
  };
});

vi.mock('./api/documentApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api/documentApi')>();
  return { ...actual, documentApi: { ...actual.documentApi, getProcessingStatus: workflowMocks.getProcessingStatus, deleteDocument: workflowMocks.deleteDocument } };
});

const authenticatedSession: AuthSession = {
  accessToken: 'test-access-token',
  email: 'owner@example.test',
};

function makeAdapter(overrides: Partial<AuthAdapter> = {}): AuthAdapter {
  return {
    canSignIn: true,
    getSession: async () => authenticatedSession,
    signIn: async () => undefined,
    signOut: async () => undefined,
    ...overrides,
  };
}

function renderApp(path = '/', adapter: AuthAdapter = makeAdapter()) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App authAdapter={adapter} />
    </MemoryRouter>,
  );
}

function cancelable<T>(value: T) {
  return Object.assign(Promise.resolve(value), { cancel: vi.fn() });
}

function collectionItem(
  status: ContractCollectionItem.status,
  overrides: Partial<ContractCollectionItem> = {},
): ContractCollectionItem {
  return {
    id: `contract-${status.toLowerCase()}`,
    contractType: ContractCollectionItem.contractType.ELECTRICITY,
    status,
    sourceFilename: 'Example Energy-contract.pdf',
    createdAt: '2026-09-24T10:00:00Z',
    provider: 'Example Energy',
    pricingModel: ContractCollectionItem.pricingModel.FIXED,
    endDate: '2027-08-01',
    ...overrides,
  };
}

describe('ContractForge application shell and routes', () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  beforeEach(() => {
    setCurrentSession(null);
    contractMocks.getOverview.mockReset().mockResolvedValue({});
    contractMocks.getReview.mockReset().mockImplementation(() => cancelable({
      documentId: 'document-1', type: 'ELECTRICITY', status: 'DRAFT', version: 1, parties: [], terms: { electricityData: {} },
    }));
    contractMocks.editContract.mockReset().mockResolvedValue({});
    contractMocks.approveDraft.mockReset().mockResolvedValue({});
    contractMocks.listContracts.mockReset().mockResolvedValue([]);
    workflowMocks.getUploadStatus.mockReset().mockResolvedValue({
      uploadId: 'document-1', status: 'UPLOADING', contractType: 'ELECTRICITY',
      originalFilename: 'source.pdf', expiresAt: '2026-10-01T12:00:00Z',
    });
    workflowMocks.startUpload.mockReset().mockResolvedValue({
      uploadId: 'document-1', contractType: 'ELECTRICITY', originalFilename: 'source.pdf',
      upload: { method: 'PUT', url: 'https://storage.example.test/signed', headers: { 'Content-Type': 'application/pdf' } },
    });
    workflowMocks.uploadToStorage.mockReset().mockResolvedValue(undefined);
    accountMocks.deleteAccount.mockReset().mockResolvedValue(undefined);
    workflowMocks.completeLocalUpload.mockReset().mockResolvedValue(undefined);
    workflowMocks.getProcessingStatus.mockReset().mockResolvedValue({
      documentId: 'document-1', status: 'PROCESSING', contractType: 'ELECTRICITY',
      originalFilename: 'source.pdf', failureCategory: null,
    });
    workflowMocks.deleteDocument.mockReset().mockResolvedValue(undefined);
  });

  it('sends unauthenticated users to the sign-in boundary without exposing product UI', async () => {
    renderApp('/', makeAdapter({ getSession: async () => null }));

    expect(await screen.findByRole('heading', { name: 'Sign in to ContractForge' })).toBeDefined();
    expect(screen.queryByRole('link', { name: /add contract/i })).toBeNull();
  });

  it('starts Cognito sign-in with the protected route as its validated return path', async () => {
    const signIn = vi.fn().mockResolvedValue(undefined);
    renderApp('/contracts/document-1', makeAdapter({ getSession: async () => null, signIn }));

    fireEvent.click(await screen.findByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(signIn).toHaveBeenCalledWith('/contracts/document-1'));
  });

  it('returns from the Cognito callback to the protected route requested before sign-in', async () => {
    renderApp('/', makeAdapter({ getSession: async () => ({ ...authenticatedSession, returnTo: '/contracts/add' }) }));

    expect(await screen.findByRole('heading', { name: 'Add electricity contract' })).toBeDefined();
  });

  it('removes protected content when the in-memory access token expires', async () => {
    renderApp('/', makeAdapter({ getSession: async () => ({ ...authenticatedSession, expiresAt: Date.now() + 50 }) }));

    expect(await screen.findByRole('heading', { name: 'Your contracts' })).toBeDefined();
    expect(await screen.findByRole('heading', { name: 'Sign in to ContractForge' })).toBeDefined();
    expect(screen.queryByRole('heading', { name: 'Your contracts' })).toBeNull();
  });

  it('shows the persistent product shell and sends Add contract to the approved electricity route', async () => {
    renderApp();

    expect(await screen.findByRole('heading', { name: 'Your contracts' })).toBeDefined();
    fireEvent.click(screen.getByRole('link', { name: /add contract/i }));
    expect(await screen.findByRole('heading', { name: 'Add electricity contract' })).toBeDefined();
    expect(screen.getByRole('link', { name: /add contract/i })).toBeDefined();
    expect(screen.queryByText(/insights|settings|notifications/i)).toBeNull();
  });

  it('shows the purposeful empty collection without reassurance, sample cards, or Things to know', async () => {
    renderApp();

    expect(await screen.findByRole('heading', { name: 'Add your first contract' })).toBeDefined();
    expect(screen.getByRole('heading', { name: 'Your contracts' })).toBeDefined();
    expect(screen.getByText(/helps you understand your contracts/i)).toBeDefined();
    expect(screen.queryByText('Everything looks good')).toBeNull();
    expect(screen.queryByText('Things to know')).toBeNull();
    expect(screen.getAllByRole('link', { name: /add contract/i })).toHaveLength(1);
  });

  it('shows reassurance only when the returned collection has no user-involvement status', async () => {
    contractMocks.listContracts.mockResolvedValueOnce([
      collectionItem(ContractCollectionItem.status.CONFIRMED),
      collectionItem(ContractCollectionItem.status.PROCESSING),
    ]);
    renderApp();
    expect(await screen.findByRole('heading', { name: 'Everything looks good' })).toBeDefined();

    cleanup();
    for (const status of [
      ContractCollectionItem.status.NEEDS_REVIEW,
      ContractCollectionItem.status.COULD_NOT_PROCESS,
      ContractCollectionItem.status.NEEDS_ATTENTION,
    ]) {
      contractMocks.listContracts.mockResolvedValueOnce([collectionItem(status)]);
      renderApp();
      expect(await screen.findByRole('heading', { name: 'Your contracts' })).toBeDefined();
      expect(screen.queryByRole('heading', { name: 'Everything looks good' })).toBeNull();
      cleanup();
    }
  });

  it('renders the complete collection in backend order and routes every card by its authoritative state', async () => {
    const items = [
      collectionItem(ContractCollectionItem.status.PROCESSING, { id: 'processing-id', provider: 'Untrusted extracted provider' }),
      collectionItem(ContractCollectionItem.status.NEEDS_REVIEW, { id: 'review-id' }),
      collectionItem(ContractCollectionItem.status.COULD_NOT_PROCESS, { id: 'failed-id' }),
      collectionItem(ContractCollectionItem.status.NEEDS_ATTENTION, { id: 'attention-id' }),
      collectionItem(ContractCollectionItem.status.CONFIRMED, { id: 'confirmed-id' }),
    ];
    contractMocks.listContracts.mockResolvedValueOnce(items);
    renderApp();

    const processing = await screen.findByRole('link', { name: /Electricity, Example Energy-contract.pdf, Processing/i });
    const review = screen.getByRole('link', { name: /Electricity, Example Energy-contract.pdf, Needs review/i });
    const failed = screen.getByRole('link', { name: /Electricity, Example Energy-contract.pdf, Couldn't process/i });
    const attention = screen.getByRole('link', { name: /Electricity, Example Energy, Needs attention/i });
    const confirmed = screen.getByRole('link', { name: /Electricity, Example Energy$/i });

    expect(processing.getAttribute('href')).toBe('/processing/processing-id');
    expect(review.getAttribute('href')).toBe('/contracts/review-id/review');
    expect(failed.getAttribute('href')).toBe('/processing/failed-id/failed');
    expect(attention.getAttribute('href')).toBe('/contracts/attention-id');
    expect(confirmed.getAttribute('href')).toBe('/contracts/confirmed-id');
    expect(screen.getByText("We're reading your contract")).toBeDefined();
    expect(screen.getByText("We couldn't read this contract")).toBeDefined();
    expect(screen.getByText(/Your file hasn't been removed/)).toBeDefined();
    expect(screen.queryByText(/renewal window|Things to know/i)).toBeNull();
    expect(screen.queryByText('COULD_NOT_PROCESS')).toBeNull();
    expect(screen.getAllByRole('link').indexOf(processing)).toBeLessThan(screen.getAllByRole('link').indexOf(review));
    expect(screen.queryByText('Untrusted extracted provider')).toBeNull();
    expect(screen.getAllByRole('link', { name: /Electricity/i })).toHaveLength(items.length);
  });

  it('uses only pre-confirmation identity before approval and trusted contract fields after approval', async () => {
    contractMocks.listContracts.mockResolvedValueOnce([
      collectionItem(ContractCollectionItem.status.NEEDS_REVIEW, {
        id: 'review-id', provider: 'Untrusted provider', pricingModel: ContractCollectionItem.pricingModel.DYNAMIC, endDate: '2038-01-01',
      }),
      collectionItem(ContractCollectionItem.status.CONFIRMED, { id: 'confirmed-id', sourceFilename: 'untrusted-source.pdf' }),
    ]);
    renderApp();

    await screen.findByRole('link', { name: /Electricity, Example Energy-contract.pdf, Needs review/i });
    expect(screen.queryByText('Untrusted provider')).toBeNull();
    expect(screen.queryByText('Dynamic')).toBeNull();
    expect(screen.queryByText('untrusted-source.pdf')).toBeNull();
    expect(screen.getByText('Example Energy')).toBeDefined();
    expect(screen.getByText('Fixed price')).toBeDefined();
    expect(screen.getByText(/2027/)).toBeDefined();
    expect(screen.getByText(/2026/)).toBeDefined();
    expect(screen.queryByText('Confirmed')).toBeNull();
  });

  it('refreshes the collection periodically and preserves loaded contracts after refresh failure', async () => {
    vi.useFakeTimers();
    contractMocks.listContracts
      .mockResolvedValueOnce([collectionItem(ContractCollectionItem.status.PROCESSING)])
      .mockRejectedValueOnce(new Error('temporary read failure'));
    renderApp();

    await act(async () => { await Promise.resolve(); });
    expect(screen.getByRole('link', { name: /Example Energy-contract.pdf, Processing/i })).toBeDefined();
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });

    expect(contractMocks.listContracts).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('link', { name: /Example Energy-contract.pdf, Processing/i })).toBeDefined();
    expect(screen.getByText("We couldn't load your contracts.")).toBeDefined();
    vi.useRealTimers();
  });

  it('reflects completed processing on Home without taking over navigation', async () => {
    vi.useFakeTimers();
    contractMocks.listContracts
      .mockResolvedValueOnce([
        collectionItem(ContractCollectionItem.status.PROCESSING, { id: 'moving-id' }),
        collectionItem(ContractCollectionItem.status.CONFIRMED, { id: 'settled-id' }),
      ])
      .mockResolvedValueOnce([
        collectionItem(ContractCollectionItem.status.NEEDS_REVIEW, { id: 'moving-id' }),
        collectionItem(ContractCollectionItem.status.CONFIRMED, { id: 'settled-id' }),
      ]);
    renderApp();

    await act(async () => { await Promise.resolve(); });
    expect(screen.getByRole('link', { name: /Processing/i })).toBeDefined();
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });

    expect(screen.getByRole('link', { name: /Needs review/i }).getAttribute('href')).toBe('/contracts/moving-id/review');
    expect(screen.queryByRole('link', { name: /Processing/i })).toBeNull();
    expect(screen.getByRole('heading', { name: 'Your contracts' })).toBeDefined();
    const renderedCards = screen.getAllByRole('link', { name: /Electricity/i });
    expect(renderedCards[0].getAttribute('href')).toBe('/contracts/moving-id/review');
    expect(renderedCards[1].getAttribute('href')).toBe('/contracts/settled-id');
    expect(screen.queryByRole('heading', { name: 'Review your contract' })).toBeNull();
    vi.useRealTimers();
  });

  it('uses collection-scoped skeletons while the first owner read is pending', async () => {
    let finishLoad!: (items: ContractCollectionItem[]) => void;
    contractMocks.listContracts.mockReturnValueOnce(new Promise((resolve) => { finishLoad = resolve; }));
    renderApp();

    expect(await screen.findByRole('status', { name: 'Loading your contracts' })).toBeDefined();
    expect(screen.queryByRole('heading', { name: 'Add your first contract' })).toBeNull();
    await act(async () => { finishLoad([]); });
    expect(await screen.findByRole('heading', { name: 'Add your first contract' })).toBeDefined();
  });

  it('retries an initial collection load failure without rendering an empty state', async () => {
    contractMocks.listContracts.mockRejectedValueOnce(new Error('temporary read failure'))
      .mockResolvedValueOnce([collectionItem(ContractCollectionItem.status.CONFIRMED)]);
    renderApp();

    expect(await screen.findByText("We couldn't load your contracts.")).toBeDefined();
    expect(screen.queryByRole('heading', { name: 'Add your first contract' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('link', { name: /Electricity, Example Energy$/i })).toBeDefined();
  });

  it.each([
    ['/processing/document-1', 'We’re reading your contract'],
    ['/processing/document-1/failed', 'We’re reading your contract'],
    ['/contracts/document-1/review', 'Review your contract'],
  ])('keeps %s as an explicit protected route boundary', async (path, title) => {
    if (path.includes('/review')) {
      workflowMocks.getProcessingStatus.mockImplementationOnce(() => cancelable({
        documentId: 'document-1', status: 'SUCCESS', contractType: 'ELECTRICITY',
        originalFilename: 'source.pdf', failureCategory: null,
      }));
    }
    renderApp(path);
    expect(await screen.findByRole('heading', { name: title })).toBeDefined();
    expect(screen.getByRole('link', { name: /add contract/i })).toBeDefined();
  });

  it('shows only safely available identity and signs out by clearing session and protected content', async () => {
    renderApp();
    await screen.findByRole('heading', { name: 'Your contracts' });
    fireEvent.click(screen.getByRole('button', { name: 'Open account menu' }));
    expect(await screen.findByText('owner@example.test')).toBeDefined();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }));

    expect(await screen.findByRole('heading', { name: 'Sign in to ContractForge' })).toBeDefined();
    expect(screen.queryByRole('heading', { name: 'Your contracts' })).toBeNull();
    expect(getCurrentAccessToken()).toBeUndefined();
  });

  it('keeps the session active and shows a dismissible toast when sign-out fails', async () => {
    renderApp('/', makeAdapter({ signOut: async () => { throw new Error('provider failure'); } }));
    await screen.findByRole('heading', { name: 'Your contracts' });
    fireEvent.click(screen.getByRole('button', { name: 'Open account menu' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sign out' }));

    expect((await screen.findByRole('alert')).textContent).toContain("Couldn't sign you out");
    expect(screen.getByText("You're still signed in. Please try again.")).toBeDefined();
    expect(screen.getByRole('heading', { name: 'Your contracts' })).toBeDefined();
    expect(getCurrentAccessToken()).toBe(authenticatedSession.accessToken);
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss sign-out error' }));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });

  it('requires explicit confirmation, names the signed-in account, then starts deletion and signs out', async () => {
    const signOut = vi.fn().mockResolvedValue(undefined);
    renderApp('/', makeAdapter({ signOut }));
    await screen.findByRole('heading', { name: 'Your contracts' });
    fireEvent.click(screen.getByRole('button', { name: 'Open account menu' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete account' }));

    expect(await screen.findByRole('heading', { name: 'Delete your account?' })).toBeDefined();
    expect(screen.getByText('owner@example.test')).toBeDefined();
    expect(screen.getByText(/permanently deletes the account and its data/i)).toBeDefined();
    expect(screen.queryByLabelText(/type.*delete/i)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Delete your account?' })).toBeNull());
    expect(accountMocks.deleteAccount).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Open account menu' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete account' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete account' }));

    expect(await screen.findByRole('heading', { name: 'Deletion started' })).toBeDefined();
    expect(screen.getByText(/private files are being removed/i)).toBeDefined();
    expect(accountMocks.deleteAccount).toHaveBeenCalledOnce();
    expect(await screen.findByRole('heading', { name: 'Sign in to ContractForge' }, { timeout: 2_000 })).toBeDefined();
    expect(signOut).toHaveBeenCalledOnce();
  });

  it('keeps the confirmation open and does not sign out when deletion was not accepted', async () => {
    const signOut = vi.fn();
    accountMocks.deleteAccount.mockRejectedValueOnce(new Error('unavailable'));
    renderApp('/', makeAdapter({ signOut }));
    await screen.findByRole('heading', { name: 'Your contracts' });
    fireEvent.click(screen.getByRole('button', { name: 'Open account menu' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete account' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete account' }));

    expect(await screen.findByText(/couldn’t confirm whether the request was saved/i)).toBeDefined();
    expect(screen.getByRole('heading', { name: 'Delete your account?' })).toBeDefined();
    expect(signOut).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Delete account' }).hasAttribute('disabled')).toBe(false);
  });

  it('routes expired authentication to sign-in and removes protected content immediately', async () => {
    renderApp();
    await screen.findByRole('heading', { name: 'Your contracts' });
    expect(getCurrentAccessToken()).toBe(authenticatedSession.accessToken);

    window.dispatchEvent(new Event(AUTH_SESSION_EXPIRED_EVENT));

    expect(await screen.findByRole('heading', { name: 'Sign in to ContractForge' })).toBeDefined();
    expect(screen.queryByRole('heading', { name: 'Your contracts' })).toBeNull();
    expect(getCurrentAccessToken()).toBeUndefined();
  });

  it('uses the non-disclosing unavailable state for inaccessible contract overview routes', async () => {
    contractMocks.getOverview.mockRejectedValue({ status: 404 });
    renderApp('/contracts/private-document-id');

    expect(await screen.findByRole('heading', { name: 'Contract not available' })).toBeDefined();
    expect(screen.getByText(/may have been removed or it may no longer be available to your account/i)).toBeDefined();
    expect(screen.getByRole('link', { name: 'Back to your contracts' }).getAttribute('href')).toBe('/');
    expect(screen.queryByText(/private-document-id|404|not authorized/i)).toBeNull();
  });

  it('returns Home and refreshes the collection with a removal confirmation after permanent deletion', async () => {
    contractMocks.getReview.mockImplementation(() => cancelable({
      documentId: 'document-1', type: 'ELECTRICITY', status: 'ACTIVE', version: 2, parties: [], terms: { electricityData: {} },
    }));
    contractMocks.getOverview.mockResolvedValue({ documentId: 'document-1', needsAttention: [], thingsToKnow: [], history: [] });
    renderApp('/contracts/document-1');
    fireEvent.click(await screen.findByRole('button', { name: 'More' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Remove contract' }));
    fireEvent.click(screen.getByRole('button', { name: 'Remove contract' }));
    expect(await screen.findByRole('heading', { name: 'Your contracts' })).toBeDefined();
    expect(await screen.findByText('Contract removed')).toBeDefined();
    expect(workflowMocks.deleteDocument).toHaveBeenCalledWith('document-1');
    expect(contractMocks.listContracts).toHaveBeenCalled();
  });

  it('completes the authenticated add, processing, review, confirm, overview, and sign-out journey', async () => {
    const draft = {
      documentId: 'document-1', type: 'ELECTRICITY', status: 'DRAFT', version: 4,
      parties: [{ role: 'PROVIDER', name: 'Example Energy' }, { role: 'CUSTOMER', name: 'Northstar Bakery' }],
      terms: { startDate: '2026-01-01', endDate: '2027-01-01', durationMonths: 12, electricityData: {} },
    };
    const active = { ...draft, status: 'ACTIVE' };
    contractMocks.getReview
      .mockImplementationOnce(() => cancelable(draft))
      .mockImplementationOnce(() => cancelable(active));
    contractMocks.approveDraft.mockResolvedValueOnce(active);
    contractMocks.getOverview.mockResolvedValueOnce({ documentId: 'document-1', needsAttention: [], thingsToKnow: [], history: [] });
    workflowMocks.getUploadStatus.mockResolvedValue({
      uploadId: 'document-1', status: 'COMPLETED', contractType: 'ELECTRICITY',
      originalFilename: 'source.pdf', expiresAt: '2026-10-01T12:00:00Z',
    });
    workflowMocks.getProcessingStatus.mockImplementation(() => cancelable({
      documentId: 'document-1', status: 'SUCCESS', contractType: 'ELECTRICITY',
      originalFilename: 'source.pdf', failureCategory: null,
    }));

    renderApp();
    await screen.findByRole('heading', { name: 'Your contracts' });
    fireEvent.click(screen.getByRole('link', { name: /Add contract/i }));
    const pdf = new File(['pdf bytes'], 'source.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByLabelText('Choose a PDF file'), { target: { files: [pdf] } });
    fireEvent.click(screen.getByRole('button', { name: 'Add contract' }));

    expect(await screen.findByRole('heading', { name: 'Review your contract' })).toBeDefined();
    expect(workflowMocks.startUpload).toHaveBeenCalledWith(expect.objectContaining({ filename: 'source.pdf' }));
    expect(workflowMocks.uploadToStorage).toHaveBeenCalledOnce();
    expect(workflowMocks.getUploadStatus).toHaveBeenCalledWith('document-1');
    fireEvent.click(screen.getByRole('button', { name: 'Confirm contract' }));

    expect(await screen.findByRole('heading', { name: 'Example Energy' })).toBeDefined();
    expect(contractMocks.approveDraft).toHaveBeenCalledWith('document-1', 4);
    expect(screen.getByRole('button', { name: 'View original document' })).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Open account menu' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sign out' }));
    expect(await screen.findByRole('heading', { name: 'Sign in to ContractForge' })).toBeDefined();
  }, 20_000);

  it('keeps unknown routes inside the shell and offers a route back to the collection', async () => {
    renderApp('/monitoring-results');
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeDefined();
    expect(screen.getByRole('link', { name: 'Back to your contracts' })).toBeDefined();
    expect(screen.queryByText(/Monitoring events|Recommendations|Monitoring results/i)).toBeNull();
  });

  it('does not offer identity claims when the configured session has no safe display identity', async () => {
    renderApp('/', makeAdapter({ getSession: async () => ({ accessToken: 'local-token' }) }));
    await screen.findByRole('heading', { name: 'Your contracts' });
    fireEvent.click(screen.getByRole('button', { name: 'Open account menu' }));
    expect(await screen.findByRole('menuitem', { name: 'Sign out' })).toBeDefined();
    expect(screen.queryByText('Signed in as')).toBeNull();
  });
});
