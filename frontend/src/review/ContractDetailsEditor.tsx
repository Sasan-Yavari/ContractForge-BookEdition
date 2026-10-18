import { useId } from 'react';
import type { ChangeEvent, ReactNode } from 'react';
import {
  Box,
  Button,
  Divider,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { ContractParty, ContractTerms } from '../api/generated';
import {
  addParty,
  addRate,
  addFixedCharge,
  addDiscount,
  removeParty,
  fixedChargeOptions,
  discountOptions,
  type ContractReviewDraft,
  type DraftValidationIssue,
  type PartyRole,
} from './contractReviewModel';

const colors = { ink: '#18202a', muted: '#667085', border: '#e3e6ec', blue: '#1f5eff' };

const partyGroups: Array<{ role: PartyRole; title: string; optional?: boolean }> = [
  { role: ContractParty.role.PROVIDER, title: 'Provider' },
  { role: ContractParty.role.CUSTOMER, title: 'Customer' },
  { role: ContractParty.role.BROKER, title: 'Broker', optional: true },
];

const pricingOptions = [
  { value: ContractTerms.pricingModel.FIXED, label: 'Fixed price' },
  { value: ContractTerms.pricingModel.VARIABLE, label: 'Variable price' },
  { value: ContractTerms.pricingModel.DYNAMIC, label: 'Dynamic price' },
];

const renewalOptions = [
  { value: ContractTerms.renewalType.FIXED_TERM, label: 'Fixed term' },
  { value: ContractTerms.renewalType.AUTO_RENEW, label: 'Automatically renews' },
  { value: ContractTerms.renewalType.OPEN_ENDED, label: 'Open-ended' },
];

export function ContractDetailsEditor({
  draft,
  onChange,
  validationIssues = [],
  disabled = false,
  order = 'review',
}: {
  draft: ContractReviewDraft;
  onChange: (update: (current: ContractReviewDraft) => ContractReviewDraft) => void;
  validationIssues?: DraftValidationIssue[];
  disabled?: boolean;
  order?: 'review' | 'overview';
}) {
  const issuesByPath = new Map(validationIssues.map((issue) => [issue.path, issue.message]));
  const sections = {
    Contract: <ContractFields draft={draft} onChange={onChange} disabled={disabled} issues={issuesByPath} />,
    Pricing: <PricingFields draft={draft} onChange={onChange} disabled={disabled} issues={issuesByPath} />,
    Parties: <PartyFields draft={draft} onChange={onChange} disabled={disabled} issues={issuesByPath} />,
  };
  const titles = order === 'overview' ? ['Contract', 'Pricing', 'Parties'] as const : ['Parties', 'Contract', 'Pricing'] as const;

  return (
    <Stack spacing={2.5}>
      {titles.map((title, index) => (
        <Box key={title}>
          {index > 0 && <Divider sx={{ mb: 2.5, borderColor: colors.border }} />}
          {sections[title]}
        </Box>
      ))}
    </Stack>
  );
}

function ContractFields({ draft, onChange, disabled, issues }: EditorSectionProps) {
  return (
    <ReviewSection title="Contract">
      <FieldGrid>
        <ReviewField label="Start date" value={draft.startDate} type="date" disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, startDate: event.target.value }))} />
        <ReviewField label="End date" value={draft.endDate} type="date" disabled={disabled} errorText={issues.get('endDate')} onChange={(event) => onChange((current) => ({ ...current, endDate: event.target.value }))} />
        <ReviewField label="Duration (months)" value={draft.durationMonths} type="number" min={1} step={1} disabled={disabled} errorText={issues.get('durationMonths')} onChange={(event) => onChange((current) => ({ ...current, durationMonths: event.target.value }))} />
        <ReviewSelect label="Renewal" value={draft.renewalType} options={renewalOptions} disabled={disabled} onChange={(value) => onChange((current) => ({ ...current, renewalType: value as ContractReviewDraft['renewalType'] }))} />
      </FieldGrid>
    </ReviewSection>
  );
}

function PricingFields({ draft, onChange, disabled, issues }: EditorSectionProps) {
  return (
    <ReviewSection title="Pricing">
      <FieldGrid>
        <ReviewSelect label="Pricing model" value={draft.pricingModel} options={pricingOptions} disabled={disabled} onChange={(value) => onChange((current) => ({ ...current, pricingModel: value as ContractReviewDraft['pricingModel'], electricityPricingModel: value as ContractReviewDraft['electricityPricingModel'] }))} />
        <ReviewField label="Annual electricity use (kWh)" value={draft.annualKwh} type="number" min={0} step="any" disabled={disabled} errorText={issues.get('annualKwh')} onChange={(event) => onChange((current) => ({ ...current, annualKwh: event.target.value }))} />
        <ReviewField label="Pricing index" value={draft.pricingIndex} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, pricingIndex: event.target.value }))} />
      </FieldGrid>

      <SchemaListSection title="Tariff components" addLabel="+ Add tariff component" onAdd={() => onChange(addRate)} emptyText="No tariff components have been provided." disabled={disabled}>
        {draft.rates.map((rate, index) => (
          <SchemaRow key={rate.key} title={`Tariff component ${index + 1}`} onRemove={() => onChange((current) => ({ ...current, rates: current.rates.filter((item) => item.key !== rate.key) }))} disabled={disabled}>
            <FieldGrid columns={3}>
              <ReviewField label="Rate" value={rate.value} type="number" min={0} step="any" disabled={disabled} errorText={issues.get(`rates.${rate.key}.value`)} onChange={(event) => onChange((current) => ({ ...current, rates: current.rates.map((item) => item.key === rate.key ? { ...item, value: event.target.value } : item) }))} />
              <ReviewField label="Unit" value={rate.unit} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, rates: current.rates.map((item) => item.key === rate.key ? { ...item, unit: event.target.value } : item) }))} />
              <ReviewField label="Currency" value={rate.currency} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, rates: current.rates.map((item) => item.key === rate.key ? { ...item, currency: event.target.value } : item) }))} />
              <ReviewField label="Period" value={rate.period} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, rates: current.rates.map((item) => item.key === rate.key ? { ...item, period: event.target.value } : item) }))} />
              <ReviewField label="Time period" value={rate.timePeriod} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, rates: current.rates.map((item) => item.key === rate.key ? { ...item, timePeriod: event.target.value } : item) }))} />
              <ReviewField label="Rate index" value={rate.index} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, rates: current.rates.map((item) => item.key === rate.key ? { ...item, index: event.target.value } : item) }))} />
            </FieldGrid>
          </SchemaRow>
        ))}
      </SchemaListSection>

      <SchemaListSection title="Fixed charges" addLabel="+ Add fixed charge" onAdd={() => onChange(addFixedCharge)} emptyText="No fixed charges have been provided." disabled={disabled}>
        {draft.fixedCharges.map((charge, index) => (
          <SchemaRow key={charge.key} title={`Fixed charge ${index + 1}`} onRemove={() => onChange((current) => ({ ...current, fixedCharges: current.fixedCharges.filter((item) => item.key !== charge.key) }))} disabled={disabled}>
            <FieldGrid columns={3}>
              <ReviewSelect label="Charge type" value={charge.type} options={fixedChargeOptions} disabled={disabled} onChange={(value) => onChange((current) => ({ ...current, fixedCharges: current.fixedCharges.map((item) => item.key === charge.key ? { ...item, type: value as ContractReviewDraft['fixedCharges'][number]['type'] } : item) }))} />
              <ReviewField label="Amount" value={charge.amount} type="number" min={0} step="any" disabled={disabled} errorText={issues.get(`fixedCharges.${charge.key}.amount`)} onChange={(event) => onChange((current) => ({ ...current, fixedCharges: current.fixedCharges.map((item) => item.key === charge.key ? { ...item, amount: event.target.value } : item) }))} />
              <ReviewField label="Currency" value={charge.currency} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, fixedCharges: current.fixedCharges.map((item) => item.key === charge.key ? { ...item, currency: event.target.value } : item) }))} />
              <ReviewField label="Period" value={charge.period} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, fixedCharges: current.fixedCharges.map((item) => item.key === charge.key ? { ...item, period: event.target.value } : item) }))} />
            </FieldGrid>
          </SchemaRow>
        ))}
      </SchemaListSection>

      <ReviewSection title="Return and delivery" level={3}>
        <FieldGrid>
          <ReviewField label="Export compensation" value={draft.returnCompensation} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, returnCompensation: event.target.value }))} />
          <ReviewField label="Export fees" value={draft.returnFees} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, returnFees: event.target.value }))} />
        </FieldGrid>
      </ReviewSection>

      <SchemaListSection title="Discounts" addLabel="+ Add discount" onAdd={() => onChange(addDiscount)} emptyText="No discounts have been provided." disabled={disabled}>
        {draft.discounts.map((discount, index) => (
          <SchemaRow key={discount.key} title={`Discount ${index + 1}`} onRemove={() => onChange((current) => ({ ...current, discounts: current.discounts.filter((item) => item.key !== discount.key) }))} disabled={disabled}>
            <FieldGrid columns={3}>
              <ReviewSelect label="Discount type" value={discount.type} options={discountOptions} disabled={disabled} onChange={(value) => onChange((current) => ({ ...current, discounts: current.discounts.map((item) => item.key === discount.key ? { ...item, type: value as ContractReviewDraft['discounts'][number]['type'] } : item) }))} />
              <ReviewField label="Amount" value={discount.amount} type="number" min={0} step="any" disabled={disabled} errorText={issues.get(`discounts.${discount.key}.amount`)} onChange={(event) => onChange((current) => ({ ...current, discounts: current.discounts.map((item) => item.key === discount.key ? { ...item, amount: event.target.value } : item) }))} />
              <ReviewField label="Value" value={discount.value} type="number" min={0} step="any" disabled={disabled} errorText={issues.get(`discounts.${discount.key}.value`)} onChange={(event) => onChange((current) => ({ ...current, discounts: current.discounts.map((item) => item.key === discount.key ? { ...item, value: event.target.value } : item) }))} />
              <ReviewField label="Currency" value={discount.currency} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, discounts: current.discounts.map((item) => item.key === discount.key ? { ...item, currency: event.target.value } : item) }))} />
              <ReviewField label="Period" value={discount.period} disabled={disabled} onChange={(event) => onChange((current) => ({ ...current, discounts: current.discounts.map((item) => item.key === discount.key ? { ...item, period: event.target.value } : item) }))} />
            </FieldGrid>
          </SchemaRow>
        ))}
      </SchemaListSection>
    </ReviewSection>
  );
}

function PartyFields({ draft, onChange, disabled, issues }: EditorSectionProps) {
  return (
    <ReviewSection title="Parties">
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', md: 'repeat(3, minmax(0, 1fr))' }, gap: 2 }}>
        {partyGroups.map((group) => {
          const entries = draft.parties.filter((party) => party.role === group.role);
          return (
            <Stack key={group.role} spacing={1.5}>
              {entries.map((party, index) => {
                const label = `${group.title}${entries.length > 1 ? ` ${index + 1}` : ''}${group.optional ? ' (optional)' : ''}`;
                return (
                  <Stack key={party.key} spacing={0.75}>
                    <ReviewField label={label} value={party.name} disabled={disabled} maxLength={500} onChange={(event) => onChange((current) => ({ ...current, parties: current.parties.map((item) => item.key === party.key ? { ...item, name: event.target.value } : item) }))} />
                    {entries.length > 1 && <Button size="small" onClick={() => onChange((current) => removeParty(current, party.key))} disabled={disabled} sx={removeButtonStyle}>Remove {group.title.toLowerCase()}</Button>}
                  </Stack>
                );
              })}
              <Button size="small" onClick={() => onChange((current) => addParty(current, group.role))} disabled={disabled} sx={addButtonStyle}>+ Add {group.title.toLowerCase()}</Button>
            </Stack>
          );
        })}
      </Box>
      {issues.has('parties') && <Typography role="alert" sx={validationTextStyle}>{issues.get('parties')}</Typography>}
    </ReviewSection>
  );
}

type EditorSectionProps = {
  draft: ContractReviewDraft;
  onChange: (update: (current: ContractReviewDraft) => ContractReviewDraft) => void;
  disabled: boolean;
  issues: Map<string, string>;
};

function ReviewSection({ title, children, level = 2 }: { title: string; children: ReactNode; level?: 2 | 3 }) {
  const Heading = `h${level}` as const;
  return <Box><Typography component={Heading} sx={{ mb: 1.5, color: colors.ink, fontSize: 18, fontWeight: 600 }}>{title}</Typography>{children}</Box>;
}

function FieldGrid({ children, columns = 2 }: { children: ReactNode; columns?: 2 | 3 }) {
  return <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: `repeat(${columns}, minmax(0, 1fr))` }, gap: 1.75 }}>{children}</Box>;
}

function ReviewField({ label, value, onChange, type = 'text', min, step, disabled = false, maxLength, errorText }: {
  label: string; value: string; onChange: (event: ChangeEvent<HTMLInputElement>) => void; type?: 'text' | 'date' | 'number'; min?: number; step?: number | 'any'; disabled?: boolean; maxLength?: number; errorText?: string;
}) {
  return <TextField fullWidth size="small" label={label} type={type} value={value} onChange={onChange} disabled={disabled} error={Boolean(errorText)} helperText={errorText || undefined} inputProps={{ min, step, maxLength }} InputLabelProps={type === 'date' ? { shrink: true } : undefined} sx={{ '& .MuiInputBase-root': { minHeight: 46, borderRadius: '9px', bgcolor: '#fff', fontSize: 14 }, '& .MuiInputLabel-root': { color: colors.muted, fontSize: 13 }, '& .MuiOutlinedInput-notchedOutline': { borderColor: '#d9dde5' } }} />;
}

function ReviewSelect<T extends string>({ label, value, options, onChange, disabled = false }: {
  label: string; value: T | ''; options: readonly { value: T; label: string }[]; onChange: (value: T | '') => void; disabled?: boolean;
}) {
  const id = useId();
  return (
    <FormControl fullWidth size="small" disabled={disabled}>
      <InputLabel id={`${id}-label`}>{label}</InputLabel>
      <Select labelId={`${id}-label`} id={id} value={value} label={label} onChange={(event) => onChange(event.target.value as T | '')} sx={{ minHeight: 46, borderRadius: '9px', bgcolor: '#fff', fontSize: 14, '& .MuiOutlinedInput-notchedOutline': { borderColor: '#d9dde5' } }}>
        <MenuItem value=""><em>Not provided</em></MenuItem>
        {options.map((option) => <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>)}
      </Select>
    </FormControl>
  );
}

function SchemaListSection({ title, addLabel, onAdd, emptyText, disabled, children }: { title: string; addLabel: string; onAdd: () => void; emptyText: string; disabled: boolean; children: ReactNode }) {
  const isEmpty = !Array.isArray(children) ? !children : children.length === 0;
  return (
    <Box sx={{ mt: 2.5 }}>
      <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1}>
        <Typography component="h3" sx={{ color: colors.ink, fontSize: 14, fontWeight: 600 }}>{title}</Typography>
        <Button size="small" onClick={onAdd} disabled={disabled} sx={addButtonStyle}>{addLabel}</Button>
      </Stack>
      {isEmpty ? <Typography sx={{ mt: 1, color: colors.muted, fontSize: 13 }}>{emptyText}</Typography> : <Stack spacing={1.5} sx={{ mt: 1.5 }}>{children}</Stack>}
    </Box>
  );
}

function SchemaRow({ title, onRemove, disabled, children }: { title: string; onRemove: () => void; disabled: boolean; children: ReactNode }) {
  return <Box sx={{ p: 1.5, border: `1px solid ${colors.border}`, borderRadius: '10px', bgcolor: '#fbfcfe' }}><Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1.5 }}><Typography sx={{ color: colors.ink, fontSize: 13, fontWeight: 600 }}>{title}</Typography><Button size="small" onClick={onRemove} disabled={disabled} sx={removeButtonStyle}>Remove</Button></Stack>{children}</Box>;
}

const addButtonStyle = { minHeight: 44, color: colors.blue, fontSize: 12, textTransform: 'none', fontWeight: 600, '&:focus-visible': { outline: `3px solid ${colors.blue}`, outlineOffset: 2 } };
const removeButtonStyle = { minHeight: 44, color: '#a92727', fontSize: 12, textTransform: 'none', '&:focus-visible': { outline: '3px solid #bd2c2c', outlineOffset: 2 } };
const validationTextStyle = { mt: 1, color: '#a92727', fontSize: 12 };
