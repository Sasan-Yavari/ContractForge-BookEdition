import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardActionArea,
  Divider,
  Skeleton,
  Stack,
  Typography,
} from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { contractApi } from './api/contractApi';
import { ContractCollectionItem } from './api/generated';
import { APP_ROUTES } from './routes';

const colors = {
  page: '#f7f8fa',
  ink: '#18202a',
  muted: '#667085',
  border: '#e3e6ec',
  blue: '#1f5eff',
};

const HOME_COLLECTION_REFRESH_INTERVAL_MS = 15_000;

type CollectionState = {
  items?: ContractCollectionItem[];
  loading: boolean;
  failed: boolean;
};

function useContractCollection() {
  const [state, setState] = useState<CollectionState>({ loading: true, failed: false });
  const inFlight = useRef(false);
  const mounted = useRef(false);

  const refresh = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    try {
      const items = await contractApi.listContracts();
      if (mounted.current) setState({ items, loading: false, failed: false });
    } catch {
      if (mounted.current) setState((current) => ({ ...current, loading: false, failed: true }));
    } finally {
      inFlight.current = false;
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') void refresh();
    };

    void refresh();
    const interval = window.setInterval(refreshWhenVisible, HOME_COLLECTION_REFRESH_INTERVAL_MS);
    document.addEventListener('visibilitychange', refreshWhenVisible);

    return () => {
      mounted.current = false;
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', refreshWhenVisible);
    };
  }, [refresh]);

  return { ...state, refresh };
}

export function HomePage() {
  const { items, loading, failed, refresh } = useContractCollection();

  return (
    <Box component="main" sx={{ maxWidth: 960, width: 'calc(100% - 40px)', mx: 'auto', pt: { xs: 3.5, md: 7.5 }, pb: { xs: 6, md: 11 } }}>
      {loading && !items ? <CollectionLoading /> : (
        <>
          {items && <HomeIntro items={items} />}
          {failed && (
            <Alert
              severity="error"
              action={<Button color="inherit" onClick={() => void refresh()} sx={{ minHeight: 44, whiteSpace: 'nowrap' }}>Try again</Button>}
              sx={{ mb: 2, alignItems: 'center' }}
            >
              <Typography fontWeight={600}>Something went wrong</Typography>
              We couldn't load your contracts.
            </Alert>
          )}
          {items?.length === 0 ? <EmptyCollection /> : items && (
            <Stack spacing={{ xs: 1.75, md: 2 }}>
              {items.length > 0 && !requiresUserInvolvement(items) && (
                <Typography component="h2" sx={{ color: colors.ink, fontSize: { xs: 20, md: 20 }, lineHeight: 1.3, fontWeight: 650 }}>
                  Your contracts
                </Typography>
              )}
              <Stack component="ul" spacing={{ xs: 1.75, md: 2 }} sx={{ p: 0, m: 0, listStyle: 'none' }} aria-label="Your contracts">
                {items.map((item) => <li key={item.id}><ContractCard item={item} /></li>)}
              </Stack>
            </Stack>
          )}
        </>
      )}
    </Box>
  );
}

function HomeIntro({ items }: { items: ContractCollectionItem[] }) {
  if (items.length === 0) {
    return (
      <Stack spacing={0.75} sx={{ mb: { xs: 3.5, md: 3 } }}>
        <Typography component="p" sx={{ color: colors.muted, fontSize: { xs: 11, md: 12 }, fontWeight: 600, letterSpacing: '0.02em' }}>
          YOUR CONTRACTS
        </Typography>
        <Typography component="h1" sx={{ color: colors.ink, fontSize: { xs: 28, md: 34 }, lineHeight: 1.2, fontWeight: 700 }}>
          Your contracts
        </Typography>
      </Stack>
    );
  }

  const needsInvolvement = requiresUserInvolvement(items);

  return (
    <Stack spacing={0.75} sx={{ mb: { xs: 3.5, md: 3 } }}>
      <Typography component="p" sx={{ color: colors.muted, fontSize: { xs: 11, md: 12 }, fontWeight: 600, letterSpacing: '0.02em' }}>
        YOUR CONTRACTS
      </Typography>
      {needsInvolvement ? (
        <Typography component="h1" sx={{ color: colors.ink, fontSize: { xs: 28, md: 34 }, lineHeight: 1.2, fontWeight: 700 }}>
          Your contracts
        </Typography>
      ) : (
        <>
          <Typography component="h1" sx={{ color: colors.ink, fontSize: { xs: 28, md: 34 }, lineHeight: 1.2, fontWeight: 700 }}>
            Everything looks good
          </Typography>
          <Typography sx={{ color: colors.muted, fontSize: { xs: 14, md: 16 }, lineHeight: 1.5 }}>
            None of your contracts need your attention right now.
          </Typography>
        </>
      )}
    </Stack>
  );
}

function requiresUserInvolvement(items: ContractCollectionItem[]): boolean {
  return items.some((item) => item.status !== ContractCollectionItem.status.PROCESSING
    && item.status !== ContractCollectionItem.status.CONFIRMED);
}

function EmptyCollection() {
  return (
    <Card variant="outlined" sx={{ minHeight: { xs: 300, md: 360 }, display: 'grid', placeItems: 'center', borderColor: colors.border, borderRadius: '16px', bgcolor: '#fff', boxShadow: 'none', px: { xs: 3, md: 10 }, py: { xs: 4, md: 5 } }}>
      <Stack spacing={1.5} alignItems="center" textAlign="center" maxWidth={480}>
        <Box aria-hidden="true" sx={{ width: 56, height: 56, display: 'grid', placeItems: 'center', borderRadius: '16px', bgcolor: '#e8eeff', color: '#3556a8', fontSize: 24 }}>
          ▤
        </Box>
        <Typography component="h2" sx={{ color: colors.ink, fontSize: 20, fontWeight: 650 }}>
          Add your first contract
        </Typography>
        <Typography sx={{ color: colors.muted, fontSize: 14, lineHeight: 1.5 }}>
          ContractForge helps you understand your contracts, keep track of important dates, and surface things that need your attention.
        </Typography>
      </Stack>
    </Card>
  );
}

function CollectionLoading() {
  return (
    <Box role="status" aria-label="Loading your contracts" aria-busy="true">
      <Typography component="h1" sx={{ color: colors.ink, fontSize: { xs: 20, md: 20 }, fontWeight: 650, mb: 2 }}>
        Your contracts
      </Typography>
      <Stack spacing={1.5}>
        <Skeleton animation={false} variant="rounded" height={72} sx={{ borderRadius: '12px' }} />
        <Skeleton animation={false} variant="rounded" height={72} sx={{ borderRadius: '12px' }} />
        <Skeleton animation={false} variant="rounded" height={72} sx={{ borderRadius: '12px' }} />
      </Stack>
    </Box>
  );
}

function ContractCard({ item }: { item: ContractCollectionItem }) {
  const confirmed = item.status === ContractCollectionItem.status.CONFIRMED
    || item.status === ContractCollectionItem.status.NEEDS_ATTENTION;
  const title = item.contractType === ContractCollectionItem.contractType.ELECTRICITY ? 'Electricity' : 'Contract';
  const subtitle = confirmed ? item.provider : item.sourceFilename;
  const destination = contractDestination(item);
  const statusLabel = lifecycleLabel(item.status);
  const statusTone = lifecycleTone(item.status);

  return (
    <Card variant="outlined" sx={{ overflow: 'hidden', borderColor: colors.border, borderRadius: { xs: '14px', md: '12px' }, bgcolor: '#fff', boxShadow: '0 2px 10px rgba(18,26,38,0.05)' }}>
      <CardActionArea
        component={RouterLink}
        to={destination}
        aria-label={`${title}${subtitle ? `, ${subtitle}` : ''}${statusLabel ? `, ${statusLabel}` : ''}`}
        sx={{ display: 'block', p: { xs: 2, md: 2.5 }, textAlign: 'left', '&:focus-visible': { outline: `3px solid ${colors.blue}`, outlineOffset: -3 } }}
      >
        <Stack spacing={1.5}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 1, sm: 1.5 }} alignItems={{ xs: 'flex-start', sm: 'center' }} justifyContent="space-between">
            <Stack direction="row" spacing={1.25} alignItems="center" minWidth={0}>
              <Box aria-hidden="true" sx={{ flex: '0 0 auto', width: { xs: 40, md: 44 }, height: { xs: 40, md: 44 }, display: 'grid', placeItems: 'center', borderRadius: '10px', bgcolor: statusTone.iconBg, color: '#ffd84d', fontSize: 19 }}>
                ⚡
              </Box>
              <Box minWidth={0}>
                <Typography sx={{ color: colors.ink, fontSize: 14, fontWeight: 650, lineHeight: 1.35 }}>{title}</Typography>
                {subtitle && <Typography sx={{ color: colors.muted, fontSize: 12, lineHeight: 1.45, overflowWrap: 'anywhere' }}>{subtitle}</Typography>}
              </Box>
            </Stack>
            {statusLabel && <StatusBadge label={statusLabel} tone={statusTone} />}
          </Stack>

          <Divider sx={{ borderColor: '#edf0f4' }} />

          {item.status === ContractCollectionItem.status.PROCESSING && <ProcessingSummary />}
          {item.status === ContractCollectionItem.status.COULD_NOT_PROCESS && <FailureSummary />}

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 1.25, sm: 4 }}>
            {confirmed ? (
              <>
                <CardFact label="PRICING" value={pricingLabel(item.pricingModel)} />
                <CardFact label="ENDS" value={formatDate(item.endDate)} />
              </>
            ) : (
              <CardFact label="ADDED" value={formatDate(item.createdAt)} />
            )}
          </Stack>
        </Stack>
      </CardActionArea>
    </Card>
  );
}

function StatusBadge({ label, tone }: { label: string; tone: ReturnType<typeof lifecycleTone> }) {
  return (
    <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', flex: '0 0 auto', minHeight: 24, px: 1.25, borderRadius: 99, bgcolor: tone.badgeBg, color: tone.badgeText, fontSize: 11, fontWeight: 600 }}>
      {label}
    </Box>
  );
}

function ProcessingSummary() {
  return (
    <Stack direction="row" spacing={1.25} alignItems="center" role="status">
      <Box aria-hidden="true" sx={{ width: 24, height: 24, display: 'grid', placeItems: 'center', borderRadius: '7px', bgcolor: '#e8eeff', color: '#3556a8', fontSize: 13 }}>···</Box>
      <Box>
        <Typography sx={{ color: '#344054', fontSize: 12, fontWeight: 600, lineHeight: 1.4 }}>We're reading your contract</Typography>
        <Typography sx={{ color: colors.muted, fontSize: 11, lineHeight: 1.4 }}>You can leave this page. We'll keep working in the background.</Typography>
      </Box>
    </Stack>
  );
}

function FailureSummary() {
  return (
    <Box sx={{ p: '14px', borderRadius: '10px', bgcolor: '#fff1f1', color: '#a92727' }}>
      <Typography sx={{ fontSize: 12, fontWeight: 600 }}>We couldn't read this contract</Typography>
      <Typography sx={{ mt: 0.25, color: '#825050', fontSize: 11, lineHeight: 1.4 }}>
        Open the contract to see what you can do next. Your file hasn't been removed.
      </Typography>
    </Box>
  );
}

function CardFact({ label, value }: { label: string; value: string | undefined }) {
  if (!value) return null;
  return (
    <Box>
      <Typography sx={{ color: '#8993a4', fontSize: 9, lineHeight: 1.4 }}>{label}</Typography>
      <Typography sx={{ color: '#263247', fontSize: 11, lineHeight: 1.4 }}>{value}</Typography>
    </Box>
  );
}

function contractDestination(item: ContractCollectionItem): string {
  const id = encodeURIComponent(item.id);
  switch (item.status) {
    case ContractCollectionItem.status.PROCESSING:
      return APP_ROUTES.processing.replace(':documentId', id);
    case ContractCollectionItem.status.NEEDS_REVIEW:
      return APP_ROUTES.review.replace(':documentId', id);
    case ContractCollectionItem.status.COULD_NOT_PROCESS:
      return APP_ROUTES.processingFailed.replace(':documentId', id);
    case ContractCollectionItem.status.NEEDS_ATTENTION:
    case ContractCollectionItem.status.CONFIRMED:
      return APP_ROUTES.contractOverview.replace(':documentId', id);
  }
}

function lifecycleLabel(status: ContractCollectionItem.status): string | undefined {
  switch (status) {
    case ContractCollectionItem.status.NEEDS_REVIEW: return 'Needs review';
    case ContractCollectionItem.status.COULD_NOT_PROCESS: return "Couldn't process";
    case ContractCollectionItem.status.NEEDS_ATTENTION: return 'Needs attention';
    case ContractCollectionItem.status.PROCESSING: return 'Processing';
    case ContractCollectionItem.status.CONFIRMED: return undefined;
  }
}

function lifecycleTone(status: ContractCollectionItem.status) {
  switch (status) {
    case ContractCollectionItem.status.NEEDS_REVIEW:
      return { iconBg: '#7657f6', badgeBg: '#f0eaff', badgeText: '#5933d5' };
    case ContractCollectionItem.status.COULD_NOT_PROCESS:
      return { iconBg: '#bd5d5d', badgeBg: '#fdecec', badgeText: '#a92727' };
    case ContractCollectionItem.status.NEEDS_ATTENTION:
      return { iconBg: colors.blue, badgeBg: '#fff1d8', badgeText: '#925300' };
    case ContractCollectionItem.status.PROCESSING:
      return { iconBg: '#8090a5', badgeBg: '#eef2f6', badgeText: '#506070' };
    case ContractCollectionItem.status.CONFIRMED:
      return { iconBg: '#2ca66a', badgeBg: '#edf7f1', badgeText: '#227247' };
  }
}

function pricingLabel(model: ContractCollectionItem.pricingModel | null): string | undefined {
  switch (model) {
    case ContractCollectionItem.pricingModel.FIXED: return 'Fixed price';
    case ContractCollectionItem.pricingModel.VARIABLE: return 'Variable';
    case ContractCollectionItem.pricingModel.DYNAMIC: return 'Dynamic';
    case null: return undefined;
  }
}

function formatDate(value: string | null): string | undefined {
  if (!value) return undefined;
  const dateValue = value.length === 10 ? `${value}T00:00:00.000Z` : value;
  const date = new Date(dateValue);
  if (Number.isNaN(date.getTime())) return undefined;
  return new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }).format(date);
}
