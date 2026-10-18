import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ContractParty, ContractReviewResponse, ContractTerms, type CancelablePromise } from './api/generated';
import { ContractReviewPage } from './ContractReviewPage';

const api = vi.hoisted(() => ({
  getReview: vi.fn(), editContract: vi.fn(), approveDraft: vi.fn(),
  getProcessingStatus: vi.fn(), getOriginalDocumentAccess: vi.fn(),
}));

vi.mock('./api/contractApi', () => ({
  contractApi: { getReview: api.getReview, editContract: api.editContract, approveDraft: api.approveDraft },
  isContractConflict: (error: unknown) => typeof error === 'object' && error !== null && (error as { status?: number }).status === 409,
  isContractUnavailable: (error: unknown) => typeof error === 'object' && error !== null && [403, 404].includes((error as { status?: number }).status ?? 0),
}));

vi.mock('./api/documentApi', () => ({
  documentApi: { getProcessingStatus: api.getProcessingStatus, getOriginalDocumentAccess: api.getOriginalDocumentAccess },
}));

function cancelable<T>(result: Promise<T>): CancelablePromise<T> {
  return Object.assign(result, { cancel: vi.fn() }) as unknown as CancelablePromise<T>;
}

function draftResponse(overrides: Partial<ContractReviewResponse> = {}): ContractReviewResponse {
  return {
    documentId: 'document-1', type: ContractReviewResponse.type.ELECTRICITY,
    status: ContractReviewResponse.status.DRAFT, version: 4,
    parties: [
      { role: ContractParty.role.PROVIDER, name: 'Example Energy' },
      { role: ContractParty.role.CUSTOMER, name: 'Northwind' },
    ],
    terms: {
      startDate: '2026-01-01', endDate: '2027-01-01', durationMonths: 12,
      renewalType: ContractTerms.renewalType.AUTO_RENEW,
      pricingModel: ContractTerms.pricingModel.FIXED,
      electricityData: { pricing: { model: ContractTerms.pricingModel.FIXED, rates: [{ value: 0.4, unit: 'kWh', currency: 'EUR' }] } },
    },
    ...overrides,
  };
}

function renderReview() {
  return render(
    <MemoryRouter initialEntries={['/contracts/document-1/review']}>
      <Routes>
        <Route path="/contracts/:documentId/review" element={<><a href="/home">Your contracts</a><ContractReviewPage /></>} />
        <Route path="/contracts/:documentId" element={<h1>Confirmed contract</h1>} />
        <Route path="/home" element={<h1>Your contracts</h1>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Review your contract task', () => {
  beforeEach(() => {
    api.getReview.mockReset().mockImplementation(() => cancelable(Promise.resolve(draftResponse())));
    api.editContract.mockReset().mockResolvedValue(draftResponse({ version: 5 }));
    api.approveDraft.mockReset().mockResolvedValue(draftResponse({ status: ContractReviewResponse.status.ACTIVE, version: 5 }));
    api.getProcessingStatus.mockReset().mockImplementation(() => cancelable(Promise.resolve({ originalFilename: 'electricity-source.pdf' })));
    api.getOriginalDocumentAccess.mockReset().mockResolvedValue({ url: 'https://signed.example.test/document?secret=capability', expiresAt: 'later' });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('loads the owner-authorized DRAFT review and filename without presenting it as trusted', async () => {
    renderReview();
    expect(await screen.findByRole('heading', { name: 'Review your contract' })).toBeDefined();
    expect(api.getReview).toHaveBeenCalledWith('document-1');
    expect(api.getProcessingStatus).toHaveBeenCalledWith('document-1');
    expect(screen.getByText('electricity-source.pdf')).toBeDefined();
    expect(screen.getByText(/When you confirm, these details become the trusted information/)).toBeDefined();
    expect(screen.getByRole('button', { name: 'Save changes' })).toHaveProperty('disabled', true);
    expect(screen.queryByText(/\bversion\b|document-1|schema_version/i)).toBeNull();
  });

  it('uses a safe load error with retry and a non-disclosing state for unavailable contracts', async () => {
    api.getReview.mockImplementationOnce(() => cancelable(Promise.reject({ status: 500, message: 'private backend error' })));
    renderReview();
    expect(await screen.findByText('We couldn’t load this section.')).toBeDefined();
    expect(screen.queryByText('private backend error')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { name: 'Review your contract' })).toBeDefined();

    cleanup();
    api.getReview.mockImplementationOnce(() => cancelable(Promise.reject({ status: 404, message: 'private backend error' })));
    renderReview();
    expect(await screen.findByRole('heading', { name: 'Contract not available' })).toBeDefined();
    expect(screen.queryByText('private backend error')).toBeNull();
  });

  it('renders every supported editable electricity field in the approved groups', async () => {
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    for (const heading of ['Parties', 'Contract', 'Pricing', 'Tariff components', 'Fixed charges', 'Return and delivery', 'Discounts']) {
      expect(screen.getByRole('heading', { name: heading })).toBeDefined();
    }
    fireEvent.click(screen.getByRole('button', { name: '+ Add fixed charge' }));
    fireEvent.click(screen.getByRole('button', { name: '+ Add discount' }));
    for (const label of [
      'Provider', 'Customer', 'Broker (optional)', 'Start date', 'End date', 'Duration (months)', 'Renewal',
      'Pricing model', 'Pricing index', 'Annual electricity use (kWh)', 'Rate', 'Unit', 'Currency', 'Period', 'Time period', 'Rate index',
      'Charge type', 'Amount', 'Export compensation', 'Export fees', 'Discount type', 'Value',
    ]) {
      expect(screen.getAllByLabelText(label).length).toBeGreaterThan(0);
    }
  });

  it('associates a date validation error with the date field that needs attention', async () => {
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    const endDate = screen.getByLabelText('End date') as HTMLInputElement;
    fireEvent.change(endDate, { target: { value: '2025-01-01' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm contract' }));

    const descriptionId = endDate.getAttribute('aria-describedby');
    expect(descriptionId).toBeTruthy();
    expect(document.getElementById(descriptionId!)?.textContent).toMatch(/must be on or after/i);
  });

  it('edits optional values and adds/removes party, tariff, fixed-charge and discount rows', async () => {
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    const choose = async (label: string, option: string) => {
      fireEvent.mouseDown(screen.getByRole('combobox', { name: label }));
      fireEvent.click(await screen.findByRole('option', { name: option }));
    };
    const lastField = (label: string) => {
      const fields = screen.getAllByLabelText(label);
      return fields[fields.length - 1];
    };
    fireEvent.change(screen.getByLabelText('Broker (optional)'), { target: { value: 'Broker Group' } });
    fireEvent.change(screen.getByLabelText('Start date'), { target: { value: '2026-02-01' } });
    fireEvent.change(screen.getByLabelText('End date'), { target: { value: '2027-02-01' } });
    fireEvent.change(screen.getByLabelText('Duration (months)'), { target: { value: '18' } });
    await choose('Renewal', 'Fixed term');
    await choose('Pricing model', 'Variable price');
    fireEvent.change(screen.getByLabelText('Annual electricity use (kWh)'), { target: { value: '4500' } });
    fireEvent.change(screen.getByLabelText('Rate'), { target: { value: '0.36' } });
    fireEvent.change(screen.getByLabelText('Unit'), { target: { value: 'kWh' } });
    fireEvent.change(screen.getAllByLabelText('Currency')[0], { target: { value: 'EUR' } });
    fireEvent.change(screen.getAllByLabelText('Period')[0], { target: { value: 'month' } });
    fireEvent.change(screen.getByLabelText('Time period'), { target: { value: 'day' } });
    fireEvent.change(screen.getByLabelText('Rate index'), { target: { value: 'market-rate' } });
    fireEvent.change(screen.getByLabelText('Export compensation'), { target: { value: '0.05' } });
    fireEvent.change(screen.getByLabelText('Export fees'), { target: { value: 'variable' } });

    fireEvent.click(screen.getByRole('button', { name: '+ Add provider' }));
    fireEvent.change(screen.getByLabelText('Provider 2'), { target: { value: 'Second Provider' } });
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove provider' })[1]);

    fireEvent.click(screen.getByRole('button', { name: '+ Add tariff component' }));
    fireEvent.change(screen.getAllByLabelText('Rate')[1], { target: { value: '0.2' } });
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]);

    fireEvent.click(screen.getByRole('button', { name: '+ Add fixed charge' }));
    await choose('Charge type', 'Supply charge');
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '9.5' } });
    fireEvent.change(lastField('Currency'), { target: { value: 'EUR' } });
    fireEvent.change(lastField('Period'), { target: { value: 'month' } });
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]);

    fireEvent.click(screen.getByRole('button', { name: '+ Add discount' }));
    await choose('Discount type', 'Welcome discount');
    fireEvent.change(lastField('Amount'), { target: { value: '6' } });
    fireEvent.change(screen.getByLabelText('Value'), { target: { value: '10' } });
    fireEvent.change(lastField('Currency'), { target: { value: 'EUR' } });
    fireEvent.change(lastField('Period'), { target: { value: 'year' } });
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]);

    expect(screen.getByLabelText('Broker (optional)')).toHaveProperty('value', 'Broker Group');
    expect(api.editContract).not.toHaveBeenCalled();
  }, 20_000);

  it('saves edits with the expected version and stays in the DRAFT task', async () => {
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    fireEvent.change(screen.getByRole('textbox', { name: 'Provider' }), { target: { value: 'Updated Energy' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(api.editContract).toHaveBeenCalledOnce());
    expect(api.editContract.mock.calls[0]).toEqual(['document-1', expect.objectContaining({ expectedVersion: 4, parties: expect.arrayContaining([{ role: 'PROVIDER', name: 'Updated Energy' }]) })]);
    expect(api.approveDraft).not.toHaveBeenCalled();
    expect(await screen.findByText('Changes saved. This contract still needs review.')).toBeDefined();
    expect(screen.getByRole('heading', { name: 'Review your contract' })).toBeDefined();
    expect(screen.getByRole('button', { name: 'Save changes' })).toHaveProperty('disabled', true);
  });

  it('keeps edits after safe save and optimistic conflict failures without exposing backend details', async () => {
    api.editContract.mockRejectedValueOnce({ status: 409, body: { error: 'CONTRACT_VERSION_CONFLICT', version: 999 } });
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    const provider = screen.getByRole('textbox', { name: 'Provider' }) as HTMLInputElement;
    fireEvent.change(provider, { target: { value: 'My corrected provider' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText(/This contract changed while you were editing/)).toBeDefined();
    expect(provider.value).toBe('My corrected provider');
    expect(screen.queryByText(/CONTRACT_VERSION_CONFLICT|999/)).toBeNull();
  });

  it('keeps edits after generic save failure and hides raw error content', async () => {
    api.editContract.mockRejectedValueOnce({ status: 400, body: { error: 'private validator internals' } });
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    const provider = screen.getByRole('textbox', { name: 'Provider' }) as HTMLInputElement;
    fireEvent.change(provider, { target: { value: 'Corrected provider' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText(/couldn’t save these details/)).toBeDefined();
    expect(provider.value).toBe('Corrected provider');
    expect(screen.queryByText('private validator internals')).toBeNull();
  });

  it('confirms separately, transitions only on ACTIVE, then opens the living overview', async () => {
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm contract' }));
    expect(await screen.findByRole('heading', { name: 'Confirmed contract' })).toBeDefined();
    expect(api.editContract).not.toHaveBeenCalled();
    expect(api.approveDraft).toHaveBeenCalledWith('document-1', 4);
  });

  it('locks structured edits while the trust transition is in flight', async () => {
    let finishApproval!: (response: ContractReviewResponse) => void;
    api.approveDraft.mockImplementationOnce(() => new Promise((resolve) => { finishApproval = resolve; }));
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm contract' }));
    expect(screen.getByRole('button', { name: '+ Add tariff component' })).toHaveProperty('disabled', true);
    expect(screen.getByRole('textbox', { name: 'Provider' })).toHaveProperty('disabled', true);
    await act(async () => finishApproval(draftResponse({ status: ContractReviewResponse.status.ACTIVE, version: 5 })));
    expect(await screen.findByRole('heading', { name: 'Confirmed contract' })).toBeDefined();
  });

  it('saves changed values before confirming and keeps them when confirmation conflicts', async () => {
    api.editContract.mockResolvedValueOnce(draftResponse({ version: 5 }));
    api.approveDraft.mockRejectedValueOnce({ status: 409, body: { error: 'CONTRACT_VERSION_CONFLICT' } });
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    const provider = screen.getByRole('textbox', { name: 'Provider' }) as HTMLInputElement;
    fireEvent.change(provider, { target: { value: 'Corrected provider' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm contract' }));

    expect(await screen.findByText(/This contract changed while you were editing/)).toBeDefined();
    expect(api.editContract).toHaveBeenCalledOnce();
    expect(api.approveDraft).toHaveBeenCalledWith('document-1', 5);
    expect(provider.value).toBe('Corrected provider');
    expect(screen.getByRole('heading', { name: 'Review your contract' })).toBeDefined();
  });

  it('keeps form values and stays in Review after a generic confirmation failure', async () => {
    api.approveDraft.mockRejectedValueOnce({ status: 503, message: 'provider or storage details' });
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    const provider = screen.getByRole('textbox', { name: 'Provider' }) as HTMLInputElement;
    fireEvent.change(provider, { target: { value: 'User correction' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm contract' }));
    expect(await screen.findByText(/couldn’t confirm this contract/)).toBeDefined();
    expect(provider.value).toBe('User correction');
    expect(screen.getByRole('heading', { name: 'Review your contract' })).toBeDefined();
    expect(screen.queryByText('provider or storage details')).toBeNull();
  });

  it('does not leave Review unless the approval response authoritatively reports ACTIVE', async () => {
    api.approveDraft.mockResolvedValueOnce(draftResponse({ status: ContractReviewResponse.status.DRAFT }));
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm contract' }));
    expect(await screen.findByText(/couldn’t confirm this contract/)).toBeDefined();
    expect(screen.getByRole('heading', { name: 'Review your contract' })).toBeDefined();
    expect(screen.queryByRole('heading', { name: 'Confirmed contract' })).toBeNull();
  });

  it('protects unsaved in-app navigation with Keep editing and Discard changes', async () => {
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    fireEvent.change(screen.getByRole('textbox', { name: 'Provider' }), { target: { value: 'Unsaved provider' } });
    fireEvent.click(screen.getByRole('link', { name: 'Your contracts' }));
    const dialog = await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
    expect(dialog.getAttribute('aria-describedby')).toBeTruthy();
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review your contract' })).toBeDefined());
    fireEvent.click(screen.getByRole('link', { name: 'Your contracts' }));
    fireEvent.click(screen.getByRole('button', { name: 'Discard changes' }));
    expect(await screen.findByRole('heading', { name: 'Your contracts' })).toBeDefined();
  });

  it('warns before browser back or refresh while the form is dirty', async () => {
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    fireEvent.change(screen.getByRole('textbox', { name: 'Provider' }), { target: { value: 'Unsaved provider' } });
    fireEvent(window, new PopStateEvent('popstate'));
    expect(await screen.findByRole('dialog', { name: 'Discard unsaved changes?' })).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    const unload = new Event('beforeunload', { cancelable: true });
    fireEvent(window, unload);
    expect(unload.defaultPrevented).toBe(true);
  });

  it('opens original content only through the short-lived access API in a detached new tab', async () => {
    const tab = { opener: window, location: { replace: vi.fn() }, close: vi.fn() };
    const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    renderReview();
    await screen.findByRole('heading', { name: 'Review your contract' });
    fireEvent.click(screen.getByRole('button', { name: 'View original document' }));

    await waitFor(() => expect(tab.location.replace).toHaveBeenCalledWith('https://signed.example.test/document?secret=capability'));
    expect(open).toHaveBeenCalledWith('about:blank', '_blank');
    expect(tab.opener).toBeNull();
    expect(api.getOriginalDocumentAccess).toHaveBeenCalledWith('document-1');
    expect(screen.queryByText(/secret=capability/)).toBeNull();
  });

  it('redirects an already ACTIVE contract to the overview', async () => {
    api.getReview.mockImplementationOnce(() => cancelable(Promise.resolve(draftResponse({ status: ContractReviewResponse.status.ACTIVE }))));
    renderReview();
    expect(await screen.findByRole('heading', { name: 'Confirmed contract' })).toBeDefined();
    expect(screen.queryByRole('heading', { name: 'Review your contract' })).toBeNull();
  });
});
