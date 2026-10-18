import { describe, expect, it } from 'vitest';
import { ContractParty, ContractReviewResponse, ContractTerms } from '../api/generated';
import {
  createReviewDraft,
  draftsEqual,
  toEditRequest,
  validateReviewDraft,
} from './contractReviewModel';

const review: ContractReviewResponse = {
  documentId: 'document-internal-id',
  type: ContractReviewResponse.type.ELECTRICITY,
  status: ContractReviewResponse.status.DRAFT,
  version: 17,
  parties: [
    { role: ContractParty.role.CUSTOMER, name: 'Northwind' },
    { role: ContractParty.role.PROVIDER, name: 'Example Energy' },
    { role: ContractParty.role.BROKER, name: 'Broker Ltd' },
  ],
  terms: {
    startDate: '2026-01-01',
    endDate: '2027-01-01',
    durationMonths: 12,
    renewalType: ContractTerms.renewalType.AUTO_RENEW,
    pricingModel: ContractTerms.pricingModel.VARIABLE,
    electricityData: {
      consumption: { annual_kwh: 3200 },
      pricing: {
        model: ContractTerms.pricingModel.DYNAMIC,
        index: 'market-index',
        rates: [{ value: 0.27, unit: 'kWh', currency: 'EUR', period: 'month', time_period: 'off-peak', index: 'rate-index' }],
      },
      fixed_charges: [{ type: 'SUPPLY', amount: 11.25, currency: 'EUR', period: 'month' }],
      return_delivery: { compensation: 0.08, fees: 'variable' },
      discounts: [{ type: 'LOYALTY', amount: 4, value: 2.5, currency: 'EUR', period: 'month' }],
    },
  },
};

describe('electricity review draft mapping', () => {
  it('round-trips all supported review values without exposing transport metadata', () => {
    const draft = createReviewDraft(review);
    const request = toEditRequest(draft, review.version);

    expect(request).toEqual({
      expectedVersion: 17,
      parties: review.parties,
      terms: {
        startDate: '2026-01-01',
        endDate: '2027-01-01',
        durationMonths: 12,
        renewalType: ContractTerms.renewalType.AUTO_RENEW,
        pricingModel: ContractTerms.pricingModel.VARIABLE,
        electricityData: {
          consumption: { annual_kwh: 3200 },
          pricing: {
            model: ContractTerms.pricingModel.DYNAMIC,
            index: 'market-index',
            rates: [{ value: 0.27, unit: 'kWh', currency: 'EUR', period: 'month', time_period: 'off-peak', index: 'rate-index' }],
          },
          fixed_charges: [{ type: 'SUPPLY', amount: 11.25, currency: 'EUR', period: 'month' }],
          return_delivery: { compensation: 0.08, fees: 'variable' },
          discounts: [{ type: 'LOYALTY', amount: 4, value: 2.5, currency: 'EUR', period: 'month' }],
        },
      },
    });
    expect(JSON.stringify(draft)).not.toContain('document-internal-id');
    expect(JSON.stringify(draft)).not.toContain('17');
    expect(JSON.stringify(request)).not.toContain('schema_version');
  });

  it('treats missing optional values as empty and omits them from saved data', () => {
    const sparse = createReviewDraft({
      ...review,
      parties: [{ role: ContractParty.role.PROVIDER, name: 'Example Energy' }],
      terms: { electricityData: {} },
    });
    const request = toEditRequest(sparse, 2);
    expect(request.parties).toEqual([{ role: ContractParty.role.PROVIDER, name: 'Example Energy' }]);
    expect(request.terms.electricityData).toEqual({});
    expect(sparse.rates).toEqual([]);
    expect(sparse.discounts).toEqual([]);
  });

  it('compares user values and reports only supported deterministic validation errors', () => {
    const draft = createReviewDraft(review);
    const clone = createReviewDraft(review);
    expect(draftsEqual(draft, clone)).toBe(true);
    const invalid = { ...draft, durationMonths: '-1', annualKwh: '-4', endDate: '2025-01-01' };
    expect(validateReviewDraft(invalid).map(({ path }) => path)).toEqual(['endDate', 'durationMonths', 'annualKwh']);
    expect(validateReviewDraft({ ...draft, parties: draft.parties.map((party) => ({ ...party, name: '' })) } )[0]?.path).toBe('parties');
  });
});
