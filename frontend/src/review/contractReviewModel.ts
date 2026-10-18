import { ContractParty, ContractTerms, type ContractReviewResponse, type EditContractRequest } from '../api/generated';

export type PartyRole = ContractParty.role;
export type PricingModel = ContractTerms.pricingModel;
export type RenewalType = ContractTerms.renewalType;
export const fixedChargeOptions = [{ value: 'SUPPLY', label: 'Supply charge' }] as const;
export const discountOptions = [
  { value: 'WELCOME', label: 'Welcome discount' },
  { value: 'LOYALTY', label: 'Loyalty discount' },
  { value: 'PROMOTIONAL', label: 'Promotional discount' },
  { value: 'OTHER', label: 'Other discount' },
] as const;
export type FixedChargeType = (typeof fixedChargeOptions)[number]['value'];
export type DiscountType = (typeof discountOptions)[number]['value'];

type Row = { key: number };

export type PartyDraft = Row & { role: PartyRole; name: string };
export type RateDraft = Row & {
  value: string;
  unit: string;
  currency: string;
  period: string;
  timePeriod: string;
  index: string;
};
export type FixedChargeDraft = Row & {
  type: FixedChargeType | '';
  amount: string;
  currency: string;
  period: string;
};
export type DiscountDraft = Row & {
  type: DiscountType | '';
  amount: string;
  value: string;
  currency: string;
  period: string;
};

export type ContractReviewDraft = {
  parties: PartyDraft[];
  startDate: string;
  endDate: string;
  durationMonths: string;
  renewalType: RenewalType | '';
  pricingModel: PricingModel | '';
  electricityPricingModel: PricingModel | '';
  pricingIndex: string;
  annualKwh: string;
  rates: RateDraft[];
  fixedCharges: FixedChargeDraft[];
  returnCompensation: string;
  returnFees: string;
  discounts: DiscountDraft[];
};

export type DraftValidationIssue = { path: string; message: string };

const partyRoles = Object.values(ContractParty.role);
const pricingModels = Object.values(ContractTerms.pricingModel);
const renewalTypes = Object.values(ContractTerms.renewalType);
const fixedChargeTypes = fixedChargeOptions.map(({ value }) => value);
const discountTypes = discountOptions.map(({ value }) => value);

let nextRowKey = 0;
const rowKey = () => nextRowKey++;

export function createReviewDraft(review: ContractReviewResponse): ContractReviewDraft {
  const terms = review.terms;
  const electricity = asRecord(terms?.electricityData);
  const pricing = asRecord(electricity.pricing);
  const parties = (review.parties ?? []).map((party) => ({
    key: rowKey(),
    role: party.role,
    name: party.name ?? '',
  }));

  for (const role of partyRoles) {
    if (!parties.some((party) => party.role === role)) parties.push({ key: rowKey(), role, name: '' });
  }

  return {
    parties,
    startDate: terms?.startDate ?? '',
    endDate: terms?.endDate ?? '',
    durationMonths: terms?.durationMonths == null ? '' : String(terms.durationMonths),
    renewalType: enumValueOrEmpty(terms?.renewalType, renewalTypes),
    pricingModel: enumValueOrEmpty(terms?.pricingModel, pricingModels),
    electricityPricingModel: enumValueOrEmpty(pricing.model, pricingModels),
    pricingIndex: stringValue(pricing.index),
    annualKwh: scalarString(asRecord(electricity.consumption).annual_kwh),
    rates: arrayRecords(pricing.rates).map((rate) => ({
      key: rowKey(),
      value: scalarString(rate.value),
      unit: stringValue(rate.unit),
      currency: stringValue(rate.currency),
      period: stringValue(rate.period),
      timePeriod: stringValue(rate.time_period),
      index: stringValue(rate.index),
    })),
    fixedCharges: arrayRecords(electricity.fixed_charges).map((charge) => ({
      key: rowKey(),
      type: enumValueOrEmpty(charge.type, fixedChargeTypes),
      amount: scalarString(charge.amount),
      currency: stringValue(charge.currency),
      period: stringValue(charge.period),
    })),
    returnCompensation: scalarString(asRecord(electricity.return_delivery).compensation),
    returnFees: scalarString(asRecord(electricity.return_delivery).fees),
    discounts: arrayRecords(electricity.discounts).map((discount) => ({
      key: rowKey(),
      type: enumValueOrEmpty(discount.type, discountTypes),
      amount: scalarString(discount.amount),
      value: scalarString(discount.value),
      currency: stringValue(discount.currency),
      period: stringValue(discount.period),
    })),
  };
}

export function addParty(draft: ContractReviewDraft, role: PartyRole): ContractReviewDraft {
  return { ...draft, parties: [...draft.parties, { key: rowKey(), role, name: '' }] };
}

export function removeParty(draft: ContractReviewDraft, key: number): ContractReviewDraft {
  const party = draft.parties.find((item) => item.key === key);
  if (!party) return draft;
  const remaining = draft.parties.filter((item) => item.key !== key);
  if (!remaining.some((item) => item.role === party.role)) remaining.push({ key: rowKey(), role: party.role, name: '' });
  return { ...draft, parties: remaining };
}

export function addRate(draft: ContractReviewDraft): ContractReviewDraft {
  return { ...draft, rates: [...draft.rates, emptyRate()] };
}

export function addFixedCharge(draft: ContractReviewDraft): ContractReviewDraft {
  return { ...draft, fixedCharges: [...draft.fixedCharges, emptyFixedCharge()] };
}

export function addDiscount(draft: ContractReviewDraft): ContractReviewDraft {
  return { ...draft, discounts: [...draft.discounts, emptyDiscount()] };
}

export function draftsEqual(first: ContractReviewDraft, second: ContractReviewDraft): boolean {
  return JSON.stringify(comparableDraft(first)) === JSON.stringify(comparableDraft(second));
}

export function validateReviewDraft(draft: ContractReviewDraft): DraftValidationIssue[] {
  const issues: DraftValidationIssue[] = [];
  if (!draft.parties.some((party) => party.name.trim().length > 0)) {
    issues.push({ path: 'parties', message: 'Add at least one contract party.' });
  }
  const endDate = draft.startDate && draft.endDate ? Date.parse(draft.endDate) - Date.parse(draft.startDate) : 0;
  if (endDate < 0) issues.push({ path: 'endDate', message: 'End date must be on or after the start date.' });

  if (draft.durationMonths !== '') {
    const duration = Number(draft.durationMonths);
    if (!Number.isInteger(duration) || duration <= 0) {
      issues.push({ path: 'durationMonths', message: 'Enter a whole number of months greater than zero.' });
    }
  }

  addNumberIssue(issues, 'annualKwh', draft.annualKwh, 'Enter a valid amount of 0 or more.');
  draft.rates.forEach((rate) => addNumberIssue(issues, `rates.${rate.key}.value`, rate.value, 'Enter a valid rate of 0 or more.'));
  draft.fixedCharges.forEach((charge) => addNumberIssue(issues, `fixedCharges.${charge.key}.amount`, charge.amount, 'Enter a valid amount of 0 or more.'));
  draft.discounts.forEach((discount) => {
    addNumberIssue(issues, `discounts.${discount.key}.amount`, discount.amount, 'Enter a valid amount of 0 or more.');
    addNumberIssue(issues, `discounts.${discount.key}.value`, discount.value, 'Enter a valid value of 0 or more.');
  });
  return issues;
}

export function toEditRequest(draft: ContractReviewDraft, expectedVersion: number): EditContractRequest {
  const parties = draft.parties
    .filter((party) => party.name.trim().length > 0)
    .map(({ role, name }) => ({ role, name }));
  const terms: ContractTerms = {
    electricityData: buildElectricityData(draft),
  };
  if (draft.startDate) terms.startDate = draft.startDate;
  if (draft.endDate) terms.endDate = draft.endDate;
  if (draft.durationMonths) terms.durationMonths = Number(draft.durationMonths);
  if (draft.renewalType) terms.renewalType = draft.renewalType;
  if (draft.pricingModel) terms.pricingModel = draft.pricingModel;
  return { expectedVersion, parties, terms };
}

function buildElectricityData(draft: ContractReviewDraft): Record<string, unknown> {
  const data: Record<string, unknown> = {};
  const annualKwh = optionalNumber(draft.annualKwh);
  if (annualKwh !== undefined) data.consumption = { annual_kwh: annualKwh };

  const rates = draft.rates.map(buildRate).filter((rate) => Object.keys(rate).length > 0);
  const pricing: Record<string, unknown> = {};
  if (draft.electricityPricingModel) pricing.model = draft.electricityPricingModel;
  addTextIfPresent(pricing, 'index', draft.pricingIndex);
  if (rates.length > 0) pricing.rates = rates;
  if (Object.keys(pricing).length > 0) data.pricing = pricing;

  const fixedCharges = draft.fixedCharges.map(buildFixedCharge).filter((charge) => Object.keys(charge).length > 0);
  if (fixedCharges.length > 0) data.fixed_charges = fixedCharges;

  const returnDelivery: Record<string, number | string> = {};
  const compensation = flexibleScalar(draft.returnCompensation);
  const fees = flexibleScalar(draft.returnFees);
  if (compensation !== undefined) returnDelivery.compensation = compensation;
  if (fees !== undefined) returnDelivery.fees = fees;
  if (Object.keys(returnDelivery).length > 0) data.return_delivery = returnDelivery;

  const discounts = draft.discounts.map(buildDiscount).filter((discount) => Object.keys(discount).length > 0);
  if (discounts.length > 0) data.discounts = discounts;
  return data;
}

function buildRate(rate: RateDraft): Record<string, unknown> {
  const value: Record<string, unknown> = {};
  addIfPresent(value, 'value', optionalNumber(rate.value));
  addTextIfPresent(value, 'unit', rate.unit);
  addTextIfPresent(value, 'currency', rate.currency);
  addTextIfPresent(value, 'period', rate.period);
  addTextIfPresent(value, 'time_period', rate.timePeriod);
  addTextIfPresent(value, 'index', rate.index);
  return value;
}

function buildFixedCharge(charge: FixedChargeDraft): Record<string, unknown> {
  const value: Record<string, unknown> = {};
  if (charge.type) value.type = charge.type;
  addIfPresent(value, 'amount', optionalNumber(charge.amount));
  addTextIfPresent(value, 'currency', charge.currency);
  addTextIfPresent(value, 'period', charge.period);
  return value;
}

function buildDiscount(discount: DiscountDraft): Record<string, unknown> {
  const value: Record<string, unknown> = {};
  if (discount.type) value.type = discount.type;
  addIfPresent(value, 'amount', optionalNumber(discount.amount));
  addIfPresent(value, 'value', optionalNumber(discount.value));
  addTextIfPresent(value, 'currency', discount.currency);
  addTextIfPresent(value, 'period', discount.period);
  return value;
}

function comparableDraft(draft: ContractReviewDraft) {
  return {
    parties: draft.parties.map(({ role, name }) => ({ role, name })),
    startDate: draft.startDate,
    endDate: draft.endDate,
    durationMonths: draft.durationMonths,
    renewalType: draft.renewalType,
    pricingModel: draft.pricingModel,
    electricityPricingModel: draft.electricityPricingModel,
    pricingIndex: draft.pricingIndex,
    annualKwh: draft.annualKwh,
    rates: draft.rates.map(({ value, unit, currency, period, timePeriod, index }) => ({ value, unit, currency, period, timePeriod, index })),
    fixedCharges: draft.fixedCharges.map(({ type, amount, currency, period }) => ({ type, amount, currency, period })),
    returnCompensation: draft.returnCompensation,
    returnFees: draft.returnFees,
    discounts: draft.discounts.map(({ type, amount, value, currency, period }) => ({ type, amount, value, currency, period })),
  };
}

function addNumberIssue(issues: DraftValidationIssue[], path: string, value: string, message: string) {
  if (!value.trim()) return;
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) issues.push({ path, message });
}

function optionalNumber(value: string): number | undefined {
  if (!value.trim()) return undefined;
  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
}

function flexibleScalar(value: string): number | string | undefined {
  if (!value.trim()) return undefined;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : value;
}

function addIfPresent(target: Record<string, unknown>, key: string, value: unknown) {
  if (value !== undefined) target[key] = value;
}

function addTextIfPresent(target: Record<string, unknown>, key: string, value: string) {
  if (value.trim()) target[key] = value;
}

function scalarString(value: unknown): string {
  if (typeof value === 'number' || typeof value === 'string') return String(value);
  return '';
}

function stringValue(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function arrayRecords(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.map(asRecord) : [];
}

function enumValueOrEmpty<T extends string>(value: unknown, options: readonly T[]): T | '' {
  return options.includes(value as T) ? value as T : '';
}

function emptyRate(): RateDraft {
  return { key: rowKey(), value: '', unit: '', currency: '', period: '', timePeriod: '', index: '' };
}

function emptyFixedCharge(): FixedChargeDraft {
  return { key: rowKey(), type: '', amount: '', currency: '', period: '' };
}

function emptyDiscount(): DiscountDraft {
  return { key: rowKey(), type: '', amount: '', value: '', currency: '', period: '' };
}
