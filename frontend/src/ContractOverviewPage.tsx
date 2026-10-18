import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Divider, Menu, MenuItem, Paper, Skeleton, Stack, TextField, Typography } from '@mui/material';
import { Link as RouterLink, useLocation, useNavigate, useParams } from 'react-router-dom';
import { contractApi, isContractConflict, isContractUnavailable } from './api/contractApi';
import { ContractReviewResponse, type ContractOverviewResponse } from './api/generated';
import { documentApi } from './api/documentApi';
import { APP_ROUTES } from './routes';
import { ContractDetailsEditor } from './review/ContractDetailsEditor';
import { createReviewDraft, draftsEqual, toEditRequest, validateReviewDraft, type ContractReviewDraft } from './review/contractReviewModel';

const panel = { borderColor: '#e3e6ec', borderRadius: '12px', p: { xs: 2, sm: 3 }, bgcolor: '#fff' };
type PendingNavigation = { kind: 'route'; to: string } | { kind: 'back' } | { kind: 'signOut'; element: HTMLElement };

export function ContractOverviewPage() {
  const { documentId = '' } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const [review, setReview] = useState<ContractReviewResponse>();
  const [overview, setOverview] = useState<ContractOverviewResponse>();
  const [filename, setFilename] = useState('Original document');
  const [draft, setDraft] = useState<ContractReviewDraft>();
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [unavailable, setUnavailable] = useState(false);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [overviewRefreshError, setOverviewRefreshError] = useState(false);
  const [moreAnchor, setMoreAnchor] = useState<HTMLElement | null>(null);
  const [removeOpen, setRemoveOpen] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [removeError, setRemoveError] = useState('');
  const [originalError, setOriginalError] = useState(false);
  const [originalLoading, setOriginalLoading] = useState(false);
  const [leaveOpen, setLeaveOpen] = useState(false);
  const dirtyRef = useRef(false);
  const currentPathRef = useRef(window.location.pathname);
  const pendingNavigationRef = useRef<PendingNavigation>();
  const allowNextNavigationRef = useRef(false);
  const restoreAfterBackRef = useRef(false);
  const dirty = editing && !!draft && !!review && !draftsEqual(draft, createReviewDraft(review));

  const load = useCallback(async () => {
    setLoading(true); setError(''); setUnavailable(false);
    try {
      const [contract, data] = await Promise.all([
        contractApi.getReview(documentId), contractApi.getOverview(documentId),
      ]);
      if (contract.status !== ContractReviewResponse.status.ACTIVE) { setUnavailable(true); return; }
      setReview(contract); setOverview(data); setDraft(createReviewDraft(contract));
      try {
        const status = await documentApi.getProcessingStatus(documentId);
        setFilename(status.originalFilename || 'Original document');
      } catch {
        setFilename('Original document');
      }
    } catch (failure) {
      if (isContractUnavailable(failure)) setUnavailable(true); else setError('We couldn’t load this contract. Please try again.');
    } finally { setLoading(false); }
  }, [documentId]);
  useEffect(() => { void load(); }, [load]);
  dirtyRef.current = dirty;
  currentPathRef.current = location.pathname;
  useEffect(() => {
    if (!dirty) return;
    const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    const guardClick = (event: MouseEvent) => {
      if (!dirtyRef.current || allowNextNavigationRef.current) { allowNextNavigationRef.current = false; return; }
      const target = event.target instanceof Element ? event.target : (event.target as Node | null)?.parentElement;
      const link = target?.closest<HTMLAnchorElement>('a[href]');
      if (link && !event.defaultPrevented && link.target !== '_blank' && !link.hasAttribute('download') && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey && event.button === 0) {
        const destination = new URL(link.href, window.location.href);
        if (destination.origin === window.location.origin && destination.pathname !== currentPathRef.current) {
          event.preventDefault(); pendingNavigationRef.current = { kind: 'route', to: `${destination.pathname}${destination.search}${destination.hash}` }; setLeaveOpen(true);
        }
        return;
      }
      const menuItem = target?.closest<HTMLElement>('[role="menuitem"]');
      if (menuItem?.textContent?.trim() === 'Sign out') { event.preventDefault(); event.stopImmediatePropagation(); pendingNavigationRef.current = { kind: 'signOut', element: menuItem }; setLeaveOpen(true); }
    };
    const guardBack = (event: PopStateEvent) => {
      if (allowNextNavigationRef.current) { allowNextNavigationRef.current = false; return; }
      if (restoreAfterBackRef.current) { restoreAfterBackRef.current = false; return; }
      if (!dirtyRef.current) return;
      event.stopImmediatePropagation(); pendingNavigationRef.current = { kind: 'back' }; setLeaveOpen(true); restoreAfterBackRef.current = true;
      window.history.forward(); window.setTimeout(() => { restoreAfterBackRef.current = false; }, 1000);
    };
    window.addEventListener('beforeunload', beforeUnload);
    document.addEventListener('click', guardClick, true);
    window.addEventListener('popstate', guardBack, true);
    return () => { window.removeEventListener('beforeunload', beforeUnload); document.removeEventListener('click', guardClick, true); window.removeEventListener('popstate', guardBack, true); };
  }, [dirty]);

  const save = async () => {
    if (!review || !draft) return;
    const issues = validateReviewDraft(draft);
    if (issues.length) { setSaveError('Check the marked details before saving.'); return; }
    setSaving(true); setSaveError('');
    try {
      const updated = await contractApi.editContract(documentId, toEditRequest(draft, review.version));
      if (updated.status !== ContractReviewResponse.status.ACTIVE) throw new Error('Contract status changed');
      setReview(updated); setDraft(createReviewDraft(updated)); setEditing(false);
      setSaveError('');
      setOverviewRefreshError(false);
      try { setOverview(await contractApi.getOverview(documentId)); }
      catch { setOverviewRefreshError(true); }
    } catch (failure) {
      setSaveError(isContractConflict(failure)
        ? 'This contract changed elsewhere. Your edits are still here. Review the current details and try again.'
        : 'We couldn’t save your changes. Your edits are still here. Please try again.');
    } finally { setSaving(false); }
  };

  const openOriginal = async () => {
    setOriginalError(false); setOriginalLoading(true);
    const tab = window.open('about:blank', '_blank');
    if (tab) tab.opener = null;
    try {
      const access = await documentApi.getOriginalDocumentAccess(documentId);
      if (!tab) throw new Error('New tab unavailable');
      tab.location.replace(access.url);
    } catch { tab?.close(); setOriginalError(true); }
    finally { setOriginalLoading(false); }
  };

  const remove = async () => {
    setRemoving(true); setRemoveError('');
    try { await documentApi.deleteDocument(documentId); navigate(APP_ROUTES.home, { replace: true, state: { contractRemoved: true } }); }
    catch { setRemoveError('We couldn’t remove this contract. Please try again.'); }
    finally { setRemoving(false); }
  };

  if (loading) return <Box component="main" role="status" aria-label="Loading contract" aria-busy="true" sx={{ maxWidth: 860, mx: 'auto', px: 3, py: 6 }}><Skeleton animation={false} height={50}/><Skeleton animation={false} height={160}/><Skeleton animation={false} height={260}/></Box>;
  if (unavailable) return <Box component="main" sx={{ maxWidth: 640, mx: 'auto', p: 3, pt: 8 }}><Alert severity="warning"><Typography component="h1" variant="h5">Contract not available</Typography><Typography>We can’t show this contract. It may have been removed or it may no longer be available to your account.</Typography></Alert><Button component={RouterLink} to={APP_ROUTES.home} sx={{ mt: 1 }}>Back to your contracts</Button></Box>;
  if (error || !review || !overview || !draft) return <Box component="main" sx={{ maxWidth: 700, mx: 'auto', p: 3, pt: 8 }}><Alert severity="error" action={<Button onClick={() => void load()}>Try again</Button>}>{error || 'We couldn’t load this contract.'}</Alert></Box>;

  const provider = review.parties.find((party) => party.role === 'PROVIDER')?.name || 'Electricity contract';
  return <Box component="main" sx={{ maxWidth: 860, width: 'calc(100% - 32px)', mx: 'auto', py: { xs: 3, md: 6 }, pb: 8 }}>
    <Button component={RouterLink} to={APP_ROUTES.home} sx={{ mb: 2 }}>← Your contracts</Button>
    <Stack direction="row" justifyContent="space-between" alignItems="flex-start" gap={2}>
      <Box><Typography variant="overline">Electricity contract</Typography><Typography component="h1" variant="h4" fontWeight={700}>{provider}</Typography><Typography color="text.secondary">{displayValue(review.terms.pricingModel || review.terms.electricityData?.pricing?.model)}{review.terms.endDate ? ` · Ends ${formatDate(review.terms.endDate)}` : ''}</Typography></Box>
      {!editing && <Stack direction="row" spacing={1}><Button variant="contained" onClick={() => { setDraft(createReviewDraft(review)); setEditing(true); }}>Edit details</Button><Button variant="outlined" onClick={(event) => setMoreAnchor(event.currentTarget)}>More</Button></Stack>}
    </Stack>
    <Menu anchorEl={moreAnchor} open={Boolean(moreAnchor)} onClose={() => setMoreAnchor(null)}><MenuItem onClick={() => { setMoreAnchor(null); setRemoveError(''); setRemoveOpen(true); }} sx={{ color: 'error.main' }}>Remove contract</MenuItem></Menu>
    {editing && <Stack direction="row" spacing={1} sx={{ mt: 2 }}><Button variant="contained" onClick={() => void save()} disabled={saving || (draft && draftsEqual(draft, createReviewDraft(review)))}>{saving ? 'Saving…' : 'Save changes'}</Button><Button onClick={() => { setDraft(createReviewDraft(review)); setEditing(false); setSaveError(''); }}>Cancel</Button></Stack>}
    {saveError && <Alert severity={isConflictMessage(saveError) ? 'warning' : 'error'} sx={{ mt: 2 }}>{saveError}</Alert>}
    {overviewRefreshError && <Alert severity="warning" sx={{ mt: 2 }}>Your changes were saved, but current contract information could not be refreshed. <Button color="inherit" onClick={() => { setOverviewRefreshError(false); void contractApi.getOverview(documentId).then(setOverview).catch(() => setOverviewRefreshError(true)); }}>Refresh</Button></Alert>}
    {editing ? <Box sx={{ mt: 2 }}><ContractDetailsEditor draft={draft} onChange={(update) => setDraft((current) => current ? update(current) : current)} disabled={saving} order="overview" /></Box> : <>
      <Paper variant="outlined" sx={{ ...panel, mt: 3 }}><Typography component="h2" variant="h6">{overview.needsAttention.length ? 'Needs attention' : 'Everything looks good'}</Typography>{overview.needsAttention.map((item) => <Alert key={item.id} severity="warning" sx={{ mt: 1 }}>{attentionText(item.type, item.data.evidence.event_date)}</Alert>)}</Paper>
      <Paper variant="outlined" sx={{ ...panel, mt: 2 }}><Typography component="h2" variant="h6">Contract</Typography><Detail label="Start date" value={formatDate(review.terms.startDate)}/><Detail label="End date" value={formatDate(review.terms.endDate)}/><Detail label="Duration" value={review.terms.durationMonths ? `${new Intl.NumberFormat().format(review.terms.durationMonths)} months` : ''}/><Detail label="Renewal" value={displayValue(review.terms.renewalType)}/></Paper>
      <Paper variant="outlined" sx={{ ...panel, mt: 2 }}><Typography component="h2" variant="h6">Pricing</Typography><Detail label="Pricing model" value={displayValue(review.terms.pricingModel || review.terms.electricityData?.pricing?.model)}/><Detail label="Annual electricity use" value={formatQuantity(review.terms.electricityData?.consumption?.annual_kwh, 'kWh')}/><Detail label="Pricing index" value={review.terms.electricityData?.pricing?.index}/>{(review.terms.electricityData?.pricing?.rates || []).map((rate: Record<string, any>, i: number) => <Detail key={i} label={`Tariff component ${i + 1}`} value={[formatMoney(rate.value, rate.currency), rate.unit, rate.period, rate.time_period, rate.index].filter(Boolean).join(' · ')}/>)}{(review.terms.electricityData?.fixed_charges || []).map((charge: Record<string, any>, i: number) => <Detail key={`charge-${i}`} label={displayChargeType(charge.type) || `Fixed charge ${i + 1}`} value={[formatMoney(charge.amount, charge.currency), charge.period].filter(Boolean).join(' · ')}/>)}<Detail label="Export compensation" value={formatAmount(review.terms.electricityData?.return_delivery?.compensation)}/><Detail label="Export fees" value={formatAmount(review.terms.electricityData?.return_delivery?.fees)}/>{(review.terms.electricityData?.discounts || []).map((discount: Record<string, any>, i: number) => <Detail key={`discount-${i}`} label={displayDiscountType(discount.type) || `Discount ${i + 1}`} value={[formatMoney(discount.amount, discount.currency), formatMaybeNumber(discount.value, discount.currency), discount.period].filter(Boolean).join(' · ')}/>)}</Paper>
      <Paper variant="outlined" sx={{ ...panel, mt: 2 }}><Typography component="h2" variant="h6">Parties</Typography>{review.parties.map((party, i) => <Detail key={`${party.role}-${i}`} label={`${displayValue(party.role)}${review.parties.filter((p) => p.role === party.role).length > 1 ? ` ${i + 1}` : ''}`} value={party.name}/>)}</Paper>
      <Paper variant="outlined" sx={{ ...panel, mt: 2 }}><Typography component="h2" variant="h6">Things to know</Typography>{overview.thingsToKnow.length ? overview.thingsToKnow.map((event) => <Detail key={event.id} label={eventText(event.type)} value={[formatDate(event.eventDate), formatMoney(event.amount ?? undefined, event.currency ?? undefined)].filter(Boolean).join(' · ')}/>) : <Typography color="text.secondary">There’s nothing else to know right now.</Typography>}</Paper>
      <Paper variant="outlined" sx={{ ...panel, mt: 2 }}><Typography component="h2" variant="h6">History</Typography>{overview.history.slice(0, 3).map((item) => <Detail key={item.id} label={historyText(item.event?.type || item.recommendation?.type)} value={[formatDate(item.occurredAt), item.event ? formatMoney(item.event.amount ?? undefined, item.event.currency ?? undefined) : undefined].filter(Boolean).join(' · ')}/>)}<Button component={RouterLink} to={`/contracts/${encodeURIComponent(documentId)}/history`}>View all history</Button></Paper>
      <Paper variant="outlined" sx={{ ...panel, mt: 2 }}>
        <Typography component="h2" variant="h6">Original document</Typography>
        <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={2} sx={{ mt: 1 }}>
          <Typography sx={{ minWidth: 0, overflowWrap: 'anywhere' }} color="text.secondary">{filename}</Typography>
          <Button aria-label="View original document" onClick={() => void openOriginal()} disabled={originalLoading} variant="outlined">
            {originalLoading ? 'Opening…' : 'View'}
          </Button>
        </Stack>
        {originalError && <Alert severity="error" sx={{ mt: 1 }}>We couldn’t open the original document. Please try again.</Alert>}
      </Paper>
    </>}
    <Dialog open={removeOpen} onClose={() => !removing && setRemoveOpen(false)} aria-labelledby="remove-contract-title" aria-describedby="remove-contract-description">
      <DialogTitle id="remove-contract-title">Remove this contract permanently?</DialogTitle>
      <DialogContent>
        <Typography id="remove-contract-description">This permanently removes the contract and its associated information. This can’t be undone.</Typography>
        {removeError && <Alert severity="error" sx={{ mt: 2 }}>{removeError}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button autoFocus onClick={() => setRemoveOpen(false)} disabled={removing}>Cancel</Button>
        <Button color="error" variant="contained" onClick={() => void remove()} disabled={removing}>{removing ? 'Removing…' : 'Remove contract'}</Button>
      </DialogActions>
    </Dialog>
    <Dialog open={leaveOpen} onClose={() => setLeaveOpen(false)} aria-labelledby="discard-overview-changes-title" aria-describedby="discard-overview-changes-description">
      <DialogTitle id="discard-overview-changes-title">Discard unsaved changes?</DialogTitle>
      <DialogContent><Typography id="discard-overview-changes-description">Your edits haven’t been saved. Leave this page and discard them?</Typography></DialogContent>
      <DialogActions>
        <Button autoFocus onClick={() => { pendingNavigationRef.current = undefined; setLeaveOpen(false); }}>Keep editing</Button>
        <Button color="error" onClick={() => { const pending = pendingNavigationRef.current; pendingNavigationRef.current = undefined; setLeaveOpen(false); if (pending?.kind === 'route') navigate(pending.to); else if (pending?.kind === 'back') { allowNextNavigationRef.current = true; navigate(-1); } else if (pending?.kind === 'signOut') { allowNextNavigationRef.current = true; pending.element.click(); } }}>Discard changes</Button>
      </DialogActions>
    </Dialog>
  </Box>;
}

export function ContractHistoryPage() {
  const { documentId = '' } = useParams();
  const [overview, setOverview] = useState<ContractOverviewResponse>();
  const [unavailable, setUnavailable] = useState(false);
  const [error, setError] = useState(false);
  const load = useCallback(() => {
    setOverview(undefined); setUnavailable(false); setError(false);
    contractApi.getOverview(documentId).then(setOverview).catch((failure) => {
      if (isContractUnavailable(failure)) setUnavailable(true); else setError(true);
    });
  }, [documentId]);
  useEffect(() => { load(); }, [load]);
  if (unavailable) return <Box component="main" sx={{ maxWidth: 640, mx: 'auto', p: 3, pt: 8 }}><Alert severity="warning"><Typography component="h1" variant="h5">Contract not available</Typography><Typography>We can’t show this contract. It may have been removed or it may no longer be available to your account.</Typography></Alert><Button component={RouterLink} to={APP_ROUTES.home} sx={{ mt: 1 }}>Back to your contracts</Button></Box>;
  if (error) return <Box component="main" sx={{ maxWidth: 640, mx: 'auto', p: 3 }}><Alert severity="error" action={<Button onClick={load}>Try again</Button>}>We couldn’t load this history. Please try again.</Alert></Box>;
  if (!overview) return <Box component="main" role="status" aria-label="Loading contract history" aria-busy="true"><Skeleton animation={false} height={80}/></Box>;
  return <Box component="main" sx={{ maxWidth: 860, mx: 'auto', p: 3 }}><Button component={RouterLink} to={`/contracts/${encodeURIComponent(documentId)}`}>← Contract overview</Button><Typography component="h1" variant="h4" sx={{ mt: 2 }}>History</Typography>{overview.history.map((item) => <Paper key={item.id} variant="outlined" sx={{ ...panel, mt: 2 }}><Typography fontWeight={600}>{historyText(item.event?.type || item.recommendation?.type)}</Typography><Typography color="text.secondary">{formatDate(item.occurredAt)}</Typography></Paper>)}</Box>;
}

function Detail({ label, value }: {label: string; value?: string}) { return value ? <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ py: 1.25 }}><Typography sx={{ minWidth: 210, color: 'text.secondary' }}>{label}</Typography><Typography>{value}</Typography></Stack> : null; }
function formatDate(value?: string | null) { if (!value) return undefined; const dateOnly = /^\d{4}-\d{2}-\d{2}$/.test(value); const date = dateOnly ? new Date(`${value}T12:00:00`) : new Date(value); return Number.isNaN(date.getTime()) ? undefined : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(date); }
function displayValue(value?: string | null) { if (!value) return undefined; return ({ FIXED: 'Fixed price', VARIABLE: 'Variable price', DYNAMIC: 'Dynamic price', FIXED_TERM: 'Fixed term', AUTO_RENEW: 'Automatically renews', OPEN_ENDED: 'Open-ended', PROVIDER: 'Provider', CUSTOMER: 'Customer', BROKER: 'Broker' } as Record<string, string>)[value] || undefined; }
function displayChargeType(value?: string) { return value === 'SUPPLY' ? 'Supply charge' : undefined; }
function displayDiscountType(value?: string) { return ({ WELCOME: 'Welcome discount', LOYALTY: 'Loyalty discount', PROMOTIONAL: 'Promotional discount', OTHER: 'Other discount' } as Record<string, string>)[value || '']; }
function formatQuantity(value?: number, unit?: string) { return value == null ? undefined : `${new Intl.NumberFormat().format(value)} ${unit}`; }
function formatMoney(value?: number, currency?: string) { if (value == null) return undefined; try { return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(value); } catch { return `${new Intl.NumberFormat().format(value)} ${currency || ''}`.trim(); } }
function formatAmount(value: unknown) {
  if (typeof value === 'number') return new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(value);
  if (typeof value === 'string' && value.trim()) {
    const numeric = Number(value);
    return Number.isFinite(numeric) ? new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(numeric) : value;
  }
  return undefined;
}
function formatMaybeNumber(value: unknown, currency?: string) {
  if (typeof value === 'number') return formatMoney(value, currency);
  if (typeof value === 'string' && value.trim()) {
    const numeric = Number(value);
    return Number.isFinite(numeric) ? formatMoney(numeric, currency) : value;
  }
  return undefined;
}
function eventText(type?: string) { return (eventLabels as Record<string, string>)[type || ''] || 'Contract update'; }
function attentionText(type?: string, date?: string) {
  const label = ({ CONTRACT_EXPIRY: 'Contract expiry', RENEWAL: 'Contract renewal', PRICE_OPPORTUNITY: 'Price opportunity', TERMS_CHANGE: 'Contract terms change', DISCOUNT_EXPIRY: 'Discount expiry', ACTION_REQUIRED: 'Action required' } as Record<string, string>)[type || ''] || 'Contract update';
  return `${label}${formatDate(date) ? ` · ${formatDate(date)}` : ''}`;
}
function historyText(type?: string) { return ({ ...recommendationLabels, ...eventLabels } as Record<string, string>)[type || ''] || 'Contract update'; }
const recommendationLabels = { CONTRACT_EXPIRY: 'Contract expiry', RENEWAL: 'Contract renewal', PRICE_OPPORTUNITY: 'Price opportunity', TERMS_CHANGE: 'Contract terms change', DISCOUNT_EXPIRY: 'Discount expiry', ACTION_REQUIRED: 'Action required' };
const eventLabels = { PAYMENT_DUE: 'Payment due', CONTRACT_EXPIRING: 'Contract expiry', CONTRACT_RENEWING: 'Contract renewal', CANCELLATION_DEADLINE: 'Cancellation deadline', PRICE_CHANGE: 'Price change', WARRANTY_EXPIRING: 'Warranty expiry', REQUIREMENT: 'Contract requirement', OTHER: 'Contract update' };
function isConflictMessage(message: string) { return message.startsWith('This contract changed'); }
