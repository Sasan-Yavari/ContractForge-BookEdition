import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ContractHistoryPage, ContractOverviewPage } from './ContractOverviewPage';

const api = vi.hoisted(() => ({ getReview: vi.fn(), getOverview: vi.fn(), editContract: vi.fn(), getProcessingStatus: vi.fn(), getOriginalDocumentAccess: vi.fn(), deleteDocument: vi.fn() }));
vi.mock('./api/contractApi', () => ({ contractApi: { getReview: api.getReview, getOverview: api.getOverview, editContract: api.editContract }, isContractConflict: (e: unknown) => (e as { status?: number })?.status === 409, isContractUnavailable: (e: unknown) => [403, 404].includes((e as { status?: number })?.status || 0) }));
vi.mock('./api/documentApi', () => ({ documentApi: { getProcessingStatus: api.getProcessingStatus, getOriginalDocumentAccess: api.getOriginalDocumentAccess, deleteDocument: api.deleteDocument } }));

const contract = { documentId: 'private-id', type: 'ELECTRICITY', status: 'ACTIVE', version: 7, parties: [{ role: 'PROVIDER', name: 'Example Energy' }], terms: { endDate: '2027-05-02', pricingModel: 'FIXED', electricityData: { consumption: { annual_kwh: 2500 }, pricing: { index: 'CPI', rates: [{ value: 0.4, unit: 'kWh', currency: 'EUR', period: 'year' }] } } } };
const overview = { documentId: 'private-id', needsAttention: [], thingsToKnow: [{ id: 'event-id', documentId: 'private-id', type: 'PAYMENT_DUE', description: 'internal raw description', eventDate: '2026-11-01', status: 'ACTIVE', createdAt: '2026-01-01' }], history: [{ id: 'history-id', occurredAt: '2026-09-01T00:00:00Z', event: { type: 'CONTRACT_EXPIRING' }, recommendation: null }] };

async function openRemoval() { fireEvent.click(await screen.findByRole('button', { name: 'More' })); fireEvent.click(await screen.findByRole('menuitem', { name: 'Remove contract' })); }
function renderPage(path = '/contracts/private-id') { return render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/contracts/:documentId" element={<ContractOverviewPage/>}/><Route path="/contracts/:documentId/history" element={<ContractHistoryPage/>}/><Route path="/" element={<h1>Your contracts</h1>}/></Routes></MemoryRouter>); }
describe('confirmed contract overview', () => {
  beforeEach(() => { api.getReview.mockReset().mockResolvedValue(contract); api.getOverview.mockReset().mockResolvedValue(overview); api.getProcessingStatus.mockReset().mockResolvedValue({ originalFilename: 'contract.pdf' }); api.editContract.mockReset().mockResolvedValue({ ...contract, version: 8 }); api.getOriginalDocumentAccess.mockReset().mockResolvedValue({ url: 'https://example.test/signed' }); api.deleteDocument.mockReset().mockResolvedValue(undefined); });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
  it('shows reassuring healthy state, localized contract details and informational events without raw payloads', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Example Energy' })).toBeTruthy();
    expect(screen.getByText('Everything looks good')).toBeTruthy();
    expect(screen.getByText('CPI')).toBeTruthy();
    expect(screen.getByText('Payment due')).toBeTruthy();
    expect(screen.queryByText(/internal raw description|private-id|event-id|ACTIVE/)).toBeNull();
  });
  it('preserves confirmed status through expected-version editing and cancel discards edits', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit details' }));
    const endDate = screen.getByLabelText('End date');
    fireEvent.change(endDate, { target: { value: '2028-05-02' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit details' }));
    expect((screen.getByLabelText('End date') as HTMLInputElement).value).toBe('2027-05-02');
    fireEvent.change(screen.getByLabelText('End date'), { target: { value: '2028-05-02' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.editContract).toHaveBeenCalledWith('private-id', expect.objectContaining({ expectedVersion: 7 })));
    expect(api.getOverview).toHaveBeenCalledTimes(2);
  }, 20000);
  it('shows the contract history in the backend order', async () => { renderPage('/contracts/private-id/history'); expect(await screen.findByRole('heading', { name: 'History' })).toBeTruthy(); expect(screen.getByText('Contract expiry')).toBeTruthy(); });
  it('keeps an unavailable history destination non-disclosing and provides a route back home', async () => {
    api.getOverview.mockRejectedValueOnce({ status: 404 });
    renderPage('/contracts/private-id/history');
    expect(await screen.findByRole('heading', { name: 'Contract not available' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Back to your contracts' })).toBeTruthy();
    expect(screen.queryByText('private-id')).toBeNull();
  });
  it('renders every attention item in server order and retains edits after a conflict', async () => {
    api.getOverview.mockResolvedValueOnce({ ...overview, needsAttention: [
      { id: 'first', type: 'CONTRACT_EXPIRY', data: { evidence: { event_date: '2027-05-02' } } },
      { id: 'second', type: 'RENEWAL', data: { evidence: { event_date: '2027-06-02' } } },
    ] });
    renderPage();
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Contract expiry · May 2, 2027')).toBeTruthy();
    expect(screen.getByText('Contract renewal · Jun 2, 2027')).toBeTruthy();
    api.editContract.mockRejectedValueOnce({ status: 409 });
    fireEvent.click(screen.getByRole('button', { name: 'Edit details' }));
    fireEvent.change(screen.getByLabelText('End date'), { target: { value: '2028-05-02' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText(/changed elsewhere/)).toBeTruthy();
    expect((screen.getByLabelText('End date') as HTMLInputElement).value).toBe('2028-05-02');
  });
  it('uses short-lived authorized access to open the original document in a detached tab', async () => {
    const tab = { opener: window, location: { replace: vi.fn() }, close: vi.fn() };
    vi.stubGlobal('open', vi.fn(() => tab));
    renderPage();
    expect(await screen.findByText('contract.pdf')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'View original document' }));
    await waitFor(() => expect(api.getOriginalDocumentAccess).toHaveBeenCalledWith('private-id'));
    expect(tab.opener).toBeNull();
    expect(tab.location.replace).toHaveBeenCalledWith('https://example.test/signed');
  });
  it('keeps edits after a failed save and warns before discarding dirty navigation', async () => {
    api.editContract.mockRejectedValueOnce({ status: 500, message: 'internal response' });
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit details' }));
    fireEvent.change(screen.getByLabelText('End date'), { target: { value: '2028-05-02' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText(/couldn’t save your changes/)).toBeTruthy();
    expect((screen.getByLabelText('End date') as HTMLInputElement).value).toBe('2028-05-02');
    fireEvent.click(screen.getByRole('link', { name: /Your contracts/ }));
    const discardDialog = await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
    expect(discardDialog.getAttribute('aria-describedby')).toBeTruthy();
    await waitFor(() => expect(discardDialog.contains(document.activeElement)).toBe(true));
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
    expect(screen.getByLabelText('End date')).toBeTruthy();
  });
  it('keeps the overview available when the original document is unavailable', async () => {
    api.getOriginalDocumentAccess.mockRejectedValueOnce({ status: 500 });
    vi.stubGlobal('open', vi.fn(() => ({ opener: null, location: { replace: vi.fn() }, close: vi.fn() })));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'View original document' }));
    expect(await screen.findByText(/couldn’t open the original document/)).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Example Energy' })).toBeTruthy();
  });
  it('keeps the destructive confirmation open when permanent removal fails', async () => {
    api.deleteDocument.mockRejectedValueOnce({ status: 500 });
    renderPage();
    await openRemoval();
    fireEvent.click(screen.getByRole('button', { name: 'Remove contract' }));
    expect(await screen.findByText(/couldn’t remove this contract/)).toBeTruthy();
    const removeDialog = screen.getByRole('dialog', { name: 'Remove this contract permanently?' });
    expect(removeDialog.getAttribute('aria-describedby')).toBeTruthy();
    await waitFor(() => expect(removeDialog.contains(document.activeElement)).toBe(true));
  });
  it('uses a non-disclosing unavailable state for a non-owned contract', async () => {
    api.getReview.mockRejectedValueOnce({ status: 404 });
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Contract not available' })).toBeTruthy();
    expect(screen.queryByText('private-id')).toBeNull();
  });
  it('requires explicit confirmation before permanent removal and navigates home after success', async () => {
    renderPage(); await openRemoval();
    expect(screen.getByText(/can’t be undone/)).toBeTruthy(); fireEvent.click(screen.getByRole('button', { name: 'Cancel' })); expect(api.deleteDocument).not.toHaveBeenCalled();
    await openRemoval();
    fireEvent.click(screen.getByRole('button', { name: 'Remove contract' }));
    expect(await screen.findByRole('heading', { name: 'Your contracts' })).toBeTruthy();
  });
});
