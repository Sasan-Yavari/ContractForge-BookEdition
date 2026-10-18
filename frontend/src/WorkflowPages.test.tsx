import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AddContractPage, ProcessingPage } from './WorkflowPages';
import { CancelablePromise, DocumentProcessingStatusResponse, StartUploadRequest, UploadStatusResponse } from './api/generated';

const mocks = vi.hoisted(() => ({
  startUpload: vi.fn(),
  getUploadStatus: vi.fn(),
  uploadToStorage: vi.fn(),
  completeLocalUpload: vi.fn(),
  getProcessingStatus: vi.fn(),
  deleteDocument: vi.fn(),
}));

vi.mock('./api/uploadApi', async () => {
  const actual = await vi.importActual<typeof import('./api/uploadApi')>('./api/uploadApi');
  return {
    ...actual,
    STATUS_POLL_INTERVAL_MS: 10,
    uploadApi: { startUpload: mocks.startUpload, getUploadStatus: mocks.getUploadStatus },
    uploadToStorage: mocks.uploadToStorage,
    completeLocalUpload: mocks.completeLocalUpload,
  };
});

vi.mock('./api/documentApi', async () => {
  const actual = await vi.importActual<typeof import('./api/documentApi')>('./api/documentApi');
  return {
    ...actual,
    documentApi: { getProcessingStatus: mocks.getProcessingStatus, deleteDocument: mocks.deleteDocument },
  };
});

function renderProcessing() {
  return render(
    <MemoryRouter initialEntries={['/processing/document-1']}>
      <Routes>
        <Route path="/processing/:documentId" element={<ProcessingPage />} />
        <Route path="/contracts/:documentId/review" element={<h1>Review route</h1>} />
        <Route path="/" element={<h1>Your contracts route</h1>} />
        <Route path="/contracts/add" element={<h1>Add route</h1>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Add contract and dedicated processing flows', () => {
  beforeEach(() => {
    vi.useRealTimers();
    mocks.startUpload.mockReset().mockResolvedValue({
      uploadId: 'document-1',
      contractType: 'ELECTRICITY',
      originalFilename: 'source.pdf',
      upload: { method: 'PUT', url: 'https://storage.example.test/signed', headers: { 'Content-Type': 'application/pdf' } },
    });
    mocks.getUploadStatus.mockReset().mockResolvedValue({
      uploadId: 'document-1', status: UploadStatusResponse.status.COMPLETED,
      contractType: UploadStatusResponse.contractType.ELECTRICITY,
      originalFilename: 'source.pdf', expiresAt: '2026-10-01T12:00:00Z',
    });
    mocks.uploadToStorage.mockReset().mockResolvedValue(undefined);
    mocks.completeLocalUpload.mockReset().mockResolvedValue(undefined);
    mocks.getProcessingStatus.mockReset();
    mocks.deleteDocument.mockReset().mockResolvedValue(undefined);
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('requires explicit submission, validates only local file constraints, and keeps storage upload unauthenticated', async () => {
    render(
      <MemoryRouter initialEntries={['/contracts/add']}>
        <Routes>
          <Route path="/contracts/add" element={<AddContractPage />} />
          <Route path="/processing/:documentId" element={<h1>We’re reading your contract</h1>} />
        </Routes>
      </MemoryRouter>,
    );
    const input = screen.getByLabelText('Choose a PDF file') as HTMLInputElement;
    const inputClick = vi.spyOn(input, 'click');
    fireEvent.click(screen.getByRole('button', { name: 'Choose file' }));
    expect(inputClick).toHaveBeenCalledOnce();
    const file = new File(['pdf bytes'], 'source.pdf', { type: 'application/pdf' });
    fireEvent.change(input, { target: { files: [file] } });

    expect(screen.getByText('source.pdf')).toBeDefined();
    expect(screen.getByText(/25 MB/)).toBeDefined();
    expect(mocks.startUpload).not.toHaveBeenCalled();
    expect(mocks.uploadToStorage).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Add contract' }));
    await waitFor(() => expect(mocks.uploadToStorage).toHaveBeenCalledOnce());
    expect(mocks.startUpload).toHaveBeenCalledWith({
      filename: 'source.pdf', contentType: 'application/pdf', size: file.size,
      contractType: StartUploadRequest.contractType.ELECTRICITY,
    });
    expect(mocks.uploadToStorage.mock.calls[0][1]).toBe(file);
    expect(await screen.findByRole('heading', { name: 'We’re reading your contract' })).toBeDefined();
  });

  it('shows a separate validation callout and does not submit invalid local selections', () => {
    render(<MemoryRouter><AddContractPage /></MemoryRouter>);
    fireEvent.change(screen.getByLabelText('Choose a PDF file'), {
      target: { files: [new File(['text'], 'notes.txt', { type: 'text/plain' })] },
    });

    const validation = screen.getByRole('alert');
    expect(screen.getByText('This file is not a PDF')).toBeDefined();
    expect(screen.getByRole('button', { name: 'Choose another file' }).getAttribute('aria-describedby')).toBe(validation.id);
    expect((screen.getByLabelText('Choose a PDF file') as HTMLInputElement).getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByRole('button', { name: 'Add contract' })).toHaveProperty('disabled', true);
    expect(mocks.startUpload).not.toHaveBeenCalled();
  });

  it('does not invite an upload retry when the submission outcome is unknown', async () => {
    mocks.startUpload.mockRejectedValueOnce(new Error('private transport detail'));
    render(
      <MemoryRouter initialEntries={['/contracts/add']}>
        <Routes>
          <Route path="/contracts/add" element={<AddContractPage />} />
          <Route path="/" element={<h1>Your contracts route</h1>} />
        </Routes>
      </MemoryRouter>,
    );
    fireEvent.change(screen.getByLabelText('Choose a PDF file'), {
      target: { files: [new File(['pdf'], 'source.pdf', { type: 'application/pdf' })] },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Add contract' }));

    expect(await screen.findByText(/Check Your contracts for the current status/)).toBeDefined();
    expect(screen.getByRole('button', { name: 'Add contract' })).toHaveProperty('disabled', true);
    expect(screen.getByRole('link', { name: 'Back to your contracts' })).toBeDefined();
    expect(screen.queryByText('private transport detail')).toBeNull();
  });

  it('polls upload then document state and automatically opens Review on authoritative success', async () => {
    let resolveSuccess!: (status: DocumentProcessingStatusResponse) => void;
    mocks.getProcessingStatus
      .mockResolvedValueOnce({
        documentId: 'document-1', status: DocumentProcessingStatusResponse.status.PROCESSING,
        contractType: DocumentProcessingStatusResponse.contractType.ELECTRICITY,
        originalFilename: 'source.pdf', failureCategory: null,
      })
      .mockImplementationOnce(() => new Promise((resolve) => { resolveSuccess = resolve; }));
    renderProcessing();

    expect(await screen.findByText('source.pdf')).toBeDefined();
    expect(await screen.findByText('Processing')).toBeDefined();
    expect(mocks.getUploadStatus).toHaveBeenCalledOnce();
    expect(mocks.getUploadStatus).toHaveBeenCalledWith('document-1');
    expect(mocks.getProcessingStatus).toHaveBeenCalledWith('document-1');
    expect(screen.queryByText(/%|OCR|queue|provider|\bAI\b/i)).toBeNull();

    await waitFor(() => expect(resolveSuccess).toBeTypeOf('function'));
    await act(async () => resolveSuccess({
      documentId: 'document-1', status: DocumentProcessingStatusResponse.status.SUCCESS,
      contractType: DocumentProcessingStatusResponse.contractType.ELECTRICITY,
      originalFilename: 'source.pdf', failureCategory: null,
    }));
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review route' })).toBeDefined(), { timeout: 4500 });
  });

  it('shows safe terminal failure with no processing retry and supports permanent removal', async () => {
    mocks.getProcessingStatus.mockResolvedValue({
      documentId: 'document-1', status: DocumentProcessingStatusResponse.status.FAILED,
      contractType: DocumentProcessingStatusResponse.contractType.ELECTRICITY,
      originalFilename: 'source.pdf',
      failureCategory: DocumentProcessingStatusResponse.failureCategory.DETERMINISTIC_DOCUMENT,
    });
    renderProcessing();

    expect(await screen.findByRole('heading', { name: 'We couldn’t read this contract' })).toBeDefined();
    expect(screen.queryByRole('button', { name: /try again/i })).toBeNull();
    expect(screen.queryByText(/provider|exception|queue|retryable|deterministic/i)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    expect(await screen.findByRole('dialog', { name: 'Remove contract?' })).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Remove contract' }));

    await waitFor(() => expect(mocks.deleteDocument).toHaveBeenCalledWith('document-1'));
    expect(await screen.findByRole('heading', { name: 'Your contracts route' })).toBeDefined();
  });

  it('keeps failed contracts recoverable by choosing another file without claiming replacement', async () => {
    mocks.getProcessingStatus.mockResolvedValue({
      documentId: 'document-1', status: DocumentProcessingStatusResponse.status.FAILED,
      contractType: DocumentProcessingStatusResponse.contractType.ELECTRICITY,
      originalFilename: 'source.pdf', failureCategory: DocumentProcessingStatusResponse.failureCategory.UNKNOWN,
    });
    renderProcessing();

    expect(await screen.findByRole('heading', { name: 'We couldn’t read this contract' })).toBeDefined();
    fireEvent.click(screen.getByRole('link', { name: 'Choose file' }));
    expect(await screen.findByRole('heading', { name: 'Add route' })).toBeDefined();
    expect(mocks.deleteDocument).not.toHaveBeenCalled();
  });

  it('presents retryable backend failure safely without offering to retry processing', async () => {
    mocks.getProcessingStatus.mockResolvedValue({
      documentId: 'document-1', status: DocumentProcessingStatusResponse.status.FAILED,
      contractType: DocumentProcessingStatusResponse.contractType.ELECTRICITY,
      originalFilename: 'source.pdf',
      failureCategory: DocumentProcessingStatusResponse.failureCategory.RETRYABLE,
    });
    renderProcessing();

    expect(await screen.findByRole('heading', { name: 'We couldn’t read this contract' })).toBeDefined();
    expect(screen.getByText(/add as a new contract/)).toBeDefined();
    expect(screen.queryByRole('button', { name: /retry|try again/i })).toBeNull();
  });

  it('stops polling after the bounded number of consecutive status-read failures', async () => {
    mocks.getUploadStatus.mockRejectedValue(new Error('private transport detail'));
    renderProcessing();

    expect(await screen.findByRole('heading', { name: 'We can’t check this contract right now' })).toBeDefined();
    expect(mocks.getUploadStatus).toHaveBeenCalledTimes(3);
    expect(screen.queryByText('private transport detail')).toBeNull();
    expect(screen.queryByRole('button', { name: /try again/i })).toBeNull();
    expect(screen.getByRole('button', { name: 'Check status' })).toBeDefined();
  });

  it('keeps terminal upload rejection separate from processing-document failure', async () => {
    mocks.getUploadStatus.mockResolvedValue({
      uploadId: 'document-1', status: UploadStatusResponse.status.REJECTED,
      contractType: UploadStatusResponse.contractType.ELECTRICITY,
      originalFilename: 'source.pdf', expiresAt: '2026-10-01T12:00:00Z',
    });
    renderProcessing();

    expect(await screen.findByRole('heading', { name: 'We couldn’t process this file' })).toBeDefined();
    expect(mocks.getProcessingStatus).not.toHaveBeenCalled();
    expect(screen.getByText(/item remains in Your contracts/i)).toBeDefined();
    expect(screen.queryByRole('button', { name: /remove/i })).toBeNull();
  });

  it('cancels an in-flight authoritative status request when the user leaves the page', async () => {
    let requestCancelled = false;
    mocks.getUploadStatus.mockReturnValue(new CancelablePromise(() => { requestCancelled = true; }));
    const view = renderProcessing();
    await waitFor(() => expect(mocks.getUploadStatus).toHaveBeenCalledOnce());

    view.unmount();
    expect(requestCancelled).toBe(true);
  });
});
