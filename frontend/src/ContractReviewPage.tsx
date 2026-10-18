import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Paper,
  Skeleton,
  Stack,
  Typography,
} from '@mui/material';
import { Link as RouterLink, useLocation, useNavigate, useParams } from 'react-router-dom';
import { contractApi, isContractConflict, isContractUnavailable } from './api/contractApi';
import { ContractReviewResponse } from './api/generated';
import { documentApi } from './api/documentApi';
import { ContractDetailsEditor } from './review/ContractDetailsEditor';
import { APP_ROUTES } from './routes';
import {
  createReviewDraft,
  draftsEqual,
  toEditRequest,
  validateReviewDraft,
  type ContractReviewDraft,
  type DraftValidationIssue,
} from './review/contractReviewModel';

const colors = {
  page: '#f8f9fb',
  ink: '#18202a',
  muted: '#667085',
  border: '#e3e6ec',
  blue: '#1f5eff',
};

const focusStyle = {
  '&:focus-visible': { outline: `3px solid ${colors.blue}`, outlineOffset: 2 },
};

type PageState = 'loading' | 'ready' | 'unavailable' | 'error';
type Feedback = { kind: 'save' | 'confirm' | 'conflict' | 'saved'; message: string };
type PendingNavigation = { kind: 'route'; to: string } | { kind: 'back' } | { kind: 'signOut'; element: HTMLElement };

export function ContractReviewPage() {
  const { documentId = '' } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [pageState, setPageState] = useState<PageState>('loading');
  const [filename, setFilename] = useState('');
  const [draft, setDraft] = useState<ContractReviewDraft>();
  const [savedDraft, setSavedDraft] = useState<ContractReviewDraft>();
  const [feedback, setFeedback] = useState<Feedback>();
  const [savePending, setSavePending] = useState(false);
  const [confirmPending, setConfirmPending] = useState(false);
  const [originalPending, setOriginalPending] = useState(false);
  const [originalError, setOriginalError] = useState(false);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [leaveDialogOpen, setLeaveDialogOpen] = useState(false);
  const [validationIssues, setValidationIssues] = useState<DraftValidationIssue[]>([]);

  const draftRef = useRef<ContractReviewDraft>();
  const savedDraftRef = useRef<ContractReviewDraft>();
  const versionRef = useRef<number>();
  const dirtyRef = useRef(false);
  const currentPathRef = useRef(location.pathname);
  const pendingNavigationRef = useRef<PendingNavigation>();
  const allowNextNavigationRef = useRef(false);
  const restoreAfterBackRef = useRef(false);
  const mountedRef = useRef(true);

  currentPathRef.current = location.pathname;
  const isDirty = Boolean(draft && savedDraft && !draftsEqual(draft, savedDraft));
  dirtyRef.current = isDirty;
  const busy = savePending || confirmPending;

  useEffect(() => {
    mountedRef.current = true;
    let active = true;
    setPageState('loading');
    setFeedback(undefined);
    const reviewRequest = contractApi.getReview(documentId);
    const documentRequest = documentApi.getProcessingStatus(documentId);

    void Promise.all([reviewRequest, documentRequest]).then(([review, document]) => {
      if (!active) return;
      if (review.status === ContractReviewResponse.status.ACTIVE) {
        navigate(APP_ROUTES.contractOverview.replace(':documentId', encodeURIComponent(documentId)), { replace: true });
        return;
      }
      const initialDraft = createReviewDraft(review);
      versionRef.current = review.version;
      draftRef.current = initialDraft;
      savedDraftRef.current = initialDraft;
      setDraft(initialDraft);
      setSavedDraft(initialDraft);
      setFilename(document.originalFilename);
      setPageState('ready');
    }).catch((failure: unknown) => {
      if (!active) return;
      setPageState(isContractUnavailable(failure) ? 'unavailable' : 'error');
    });

    return () => {
      active = false;
      mountedRef.current = false;
      reviewRequest.cancel();
      documentRequest.cancel();
    };
  }, [documentId, loadAttempt, navigate]);

  useEffect(() => {
    const onDocumentClick = (event: MouseEvent) => {
      if (!dirtyRef.current || allowNextNavigationRef.current) {
        if (allowNextNavigationRef.current) allowNextNavigationRef.current = false;
        return;
      }
      const target = event.target instanceof Element ? event.target : (event.target as Node | null)?.parentElement;
      const link = target?.closest<HTMLAnchorElement>('a[href]');
      if (link && !event.defaultPrevented && link.target !== '_blank' && !link.hasAttribute('download')
        && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey && event.button === 0) {
        const destination = new URL(link.href, window.location.href);
        if (destination.origin === window.location.origin && destination.pathname !== currentPathRef.current) {
          event.preventDefault();
          pendingNavigationRef.current = {
            kind: 'route',
            to: `${destination.pathname}${destination.search}${destination.hash}`,
          };
          setLeaveDialogOpen(true);
        }
        return;
      }

      const menuItem = target?.closest<HTMLElement>('[role="menuitem"]');
      if (menuItem?.textContent?.trim() === 'Sign out') {
        event.preventDefault();
        event.stopImmediatePropagation();
        pendingNavigationRef.current = { kind: 'signOut', element: menuItem };
        setLeaveDialogOpen(true);
      }
    };

    const onPopState = (event: PopStateEvent) => {
      if (allowNextNavigationRef.current) {
        allowNextNavigationRef.current = false;
        return;
      }
      if (restoreAfterBackRef.current) {
        restoreAfterBackRef.current = false;
        return;
      }
      if (!dirtyRef.current) return;
      event.stopImmediatePropagation();
      pendingNavigationRef.current = { kind: 'back' };
      setLeaveDialogOpen(true);
      restoreAfterBackRef.current = true;
      window.history.forward();
      window.setTimeout(() => { restoreAfterBackRef.current = false; }, 1000);
    };

    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!dirtyRef.current) return;
      event.preventDefault();
      event.returnValue = '';
    };

    document.addEventListener('click', onDocumentClick, true);
    window.addEventListener('popstate', onPopState, true);
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => {
      document.removeEventListener('click', onDocumentClick, true);
      window.removeEventListener('popstate', onPopState, true);
      window.removeEventListener('beforeunload', onBeforeUnload);
    };
  }, []);

  const changeDraft = (update: (current: ContractReviewDraft) => ContractReviewDraft) => {
    const current = draftRef.current;
    if (!current) return;
    const next = update(current);
    draftRef.current = next;
    setDraft(next);
    setFeedback(undefined);
    setValidationIssues([]);
  };

  const acceptSavedResponse = (response: ContractReviewResponse, submittedDraft: ContractReviewDraft): boolean => {
    const normalized = createReviewDraft(response);
    versionRef.current = response.version;
    savedDraftRef.current = normalized;
    setSavedDraft(normalized);
    if (draftRef.current && draftsEqual(draftRef.current, submittedDraft)) {
      draftRef.current = normalized;
      setDraft(normalized);
      return true;
    }
    return false;
  };

  const handleSave = async () => {
    const submittedDraft = draftRef.current;
    if (!submittedDraft || busy) return;
    const issues = validateReviewDraft(submittedDraft);
    if (issues.length > 0) {
      setValidationIssues(issues);
      setFeedback(undefined);
      return;
    }
    setSavePending(true);
    setFeedback(undefined);
    try {
      const response = await contractApi.editContract(documentId, toEditRequest(submittedDraft, versionRef.current ?? 0));
      if (!mountedRef.current) return;
      if (response.status !== ContractReviewResponse.status.DRAFT) {
        setFeedback({ kind: 'conflict', message: 'This contract changed while you were editing. Your changes are still here.' });
        return;
      }
      const unchangedSinceRequest = acceptSavedResponse(response, submittedDraft);
      setFeedback({
        kind: 'saved',
        message: unchangedSinceRequest
          ? 'Changes saved. This contract still needs review.'
          : 'These changes were saved. Newer edits are still unsaved.',
      });
      if (unchangedSinceRequest && pendingNavigationRef.current) continuePendingNavigation(true);
    } catch (failure) {
      if (!mountedRef.current) return;
      setFeedback(isContractConflict(failure)
        ? { kind: 'conflict', message: 'This contract changed while you were editing. Your changes are still here.' }
        : { kind: 'save', message: 'We couldn’t save these details. Your edits are still here. Please try again.' });
    } finally {
      if (mountedRef.current) setSavePending(false);
    }
  };

  const handleConfirm = async () => {
    let submittedDraft = draftRef.current;
    if (!submittedDraft || busy) return;
    const issues = validateReviewDraft(submittedDraft);
    if (issues.length > 0) {
      setValidationIssues(issues);
      setFeedback(undefined);
      return;
    }

    setConfirmPending(true);
    setFeedback(undefined);
    try {
      if (savedDraftRef.current && !draftsEqual(submittedDraft, savedDraftRef.current)) {
        const saved = await contractApi.editContract(documentId, toEditRequest(submittedDraft, versionRef.current ?? 0));
        if (!mountedRef.current) return;
        if (saved.status !== ContractReviewResponse.status.DRAFT) {
          setFeedback({ kind: 'conflict', message: 'This contract changed while you were editing. Your changes are still here.' });
          return;
        }
        const unchangedSinceRequest = acceptSavedResponse(saved, submittedDraft);
        if (!unchangedSinceRequest) {
          setFeedback({ kind: 'confirm', message: 'Your changes were saved, but newer edits remain. Confirm again to include them.' });
          return;
        }
        submittedDraft = draftRef.current;
      }

      const approved = await contractApi.approveDraft(documentId, versionRef.current ?? 0);
      if (!mountedRef.current) return;
      if (approved.status !== ContractReviewResponse.status.ACTIVE) {
        setFeedback({ kind: 'confirm', message: 'We couldn’t confirm this contract. Your details are still here.' });
        return;
      }
      navigate(APP_ROUTES.contractOverview.replace(':documentId', encodeURIComponent(documentId)), { replace: true });
    } catch (failure) {
      if (!mountedRef.current) return;
      setFeedback(isContractConflict(failure)
        ? { kind: 'conflict', message: 'This contract changed while you were editing. Your changes are still here.' }
        : { kind: 'confirm', message: 'We couldn’t confirm this contract. Your details are still here. Please try again.' });
    } finally {
      if (mountedRef.current) setConfirmPending(false);
    }
  };

  const continuePendingNavigation = (discard: boolean) => {
    const pending = pendingNavigationRef.current;
    pendingNavigationRef.current = undefined;
    setLeaveDialogOpen(false);
    if (!pending || !discard) return;

    if (savedDraftRef.current) {
      draftRef.current = savedDraftRef.current;
      setDraft(savedDraftRef.current);
    }

    if (pending.kind === 'route') {
      navigate(pending.to);
    } else if (pending.kind === 'back') {
      allowNextNavigationRef.current = true;
      navigate(-1);
    } else {
      allowNextNavigationRef.current = true;
      pending.element.click();
    }
  };

  const openOriginalDocument = async () => {
    if (originalPending) return;
    const tab = window.open('about:blank', '_blank');
    if (!tab) {
      setOriginalError(true);
      return;
    }
    tab.opener = null;
    setOriginalPending(true);
    setOriginalError(false);
    try {
      const access = await documentApi.getOriginalDocumentAccess(documentId);
      tab.location.replace(access.url);
    } catch {
      tab.close();
      setOriginalError(true);
    } finally {
      setOriginalPending(false);
    }
  };

  if (pageState === 'loading') return <ReviewLoading />;
  if (pageState === 'unavailable') return <ReviewUnavailable />;
  if (pageState === 'error' || !draft || !savedDraft) {
    return <ReviewLoadError onRetry={() => setLoadAttempt((attempt) => attempt + 1)} />;
  }

  const fieldsDisabled = confirmPending;

  return (
    <ReviewLayout>
      <Typography sx={{ color: colors.muted, fontSize: 12, fontWeight: 600, letterSpacing: '0.03em' }}>
        ELECTRICITY CONTRACT
      </Typography>
      <Typography component="h1" sx={{ mt: 0.5, color: colors.ink, fontSize: { xs: 28, sm: 34 }, lineHeight: 1.2, fontWeight: 700 }}>
        Review your contract
      </Typography>
      <Typography sx={{ mt: 0.75, color: colors.muted, fontSize: 14, lineHeight: 1.5 }}>
        Check each detail before confirming. Optional information can stay blank if it is not available in your contract.
      </Typography>

      <Paper variant="outlined" sx={{ ...surfaceStyle, mt: 2.25, px: { xs: 1.5, sm: 2.25 }, py: 1.5 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ xs: 'stretch', sm: 'center' }} justifyContent="space-between">
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ color: colors.ink, fontSize: 14, fontWeight: 600, overflowWrap: 'anywhere' }}>{filename}</Typography>
            <Typography sx={{ color: colors.muted, fontSize: 12 }}>Original PDF</Typography>
          </Box>
          <Button
            variant="outlined"
            onClick={() => void openOriginalDocument()}
            disabled={originalPending}
            sx={secondaryButtonStyle}
          >
            {originalPending ? 'Opening…' : 'View original document'}
          </Button>
        </Stack>
        {originalError && <Alert severity="error" sx={{ mt: 1.5 }}>We couldn’t open the original document. Please try again.</Alert>}
      </Paper>

      <Paper variant="outlined" sx={{ ...surfaceStyle, mt: 2.5, p: { xs: 2, sm: 3.5 } }}>
        <ContractDetailsEditor draft={draft} onChange={changeDraft} validationIssues={validationIssues} disabled={fieldsDisabled} />
      </Paper>

      <Stack direction="row" spacing={1} alignItems="flex-start" sx={{ mt: 2.5 }}>
        <Typography aria-hidden="true" sx={{ color: colors.blue, fontSize: 16, lineHeight: 1.3 }}>✓</Typography>
        <Typography sx={{ color: colors.muted, fontSize: 12, lineHeight: 1.5 }}>
          When you confirm, these details become the trusted information for this contract.
        </Typography>
      </Stack>

      {feedback && <Alert role={feedback.kind === 'saved' ? 'status' : 'alert'} severity={feedback.kind === 'saved' ? 'success' : feedback.kind === 'conflict' ? 'warning' : 'error'} sx={{ mt: 2 }}>{feedback.message}</Alert>}
      {validationIssues.length > 0 && <Alert severity="error" sx={{ mt: 2 }}>Check the marked details before continuing.</Alert>}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25} justifyContent="flex-end" sx={{ mt: 2.5 }}>
        <Button variant="outlined" onClick={() => void handleSave()} disabled={busy || !isDirty} sx={secondaryButtonStyle}>
          {savePending ? 'Saving…' : 'Save changes'}
        </Button>
        <Button variant="contained" onClick={() => void handleConfirm()} disabled={busy} sx={primaryButtonStyle}>
          {confirmPending ? 'Confirming…' : 'Confirm contract'}
        </Button>
      </Stack>

      <Dialog open={leaveDialogOpen} onClose={() => continuePendingNavigation(false)} aria-labelledby="discard-review-changes-title" aria-describedby="discard-review-changes-description">
        <DialogTitle id="discard-review-changes-title">Discard unsaved changes?</DialogTitle>
        <DialogContent>
          <Typography id="discard-review-changes-description" sx={{ color: colors.muted, fontSize: 14 }}>
            You have changes that haven’t been saved. If you leave now, those changes will be lost.
          </Typography>
        </DialogContent>
        <DialogActions sx={{ p: 2.5, pt: 1 }}>
          <Button autoFocus onClick={() => continuePendingNavigation(false)} sx={secondaryButtonStyle}>Keep editing</Button>
          <Button color="error" variant="contained" onClick={() => continuePendingNavigation(true)} sx={{ minHeight: 44, borderRadius: '9px', textTransform: 'none', ...focusStyle }}>Discard changes</Button>
        </DialogActions>
      </Dialog>
    </ReviewLayout>
  );
}

function ReviewLayout({ children }: { children: ReactNode }) {
  return <Box component="main" sx={{ maxWidth: 780, width: 'calc(100% - 40px)', mx: 'auto', pt: { xs: 3.5, md: 5.5 }, pb: { xs: 5, md: 9 } }}>{children}</Box>;
}

function ReviewSection({ title, children, level = 2 }: { title: string; children: ReactNode; level?: 2 | 3 }) {
  const Heading = `h${level}` as const;
  return (
    <Box>
      <Typography component={Heading} sx={{ mb: 1.5, color: colors.ink, fontSize: 18, fontWeight: 600 }}>{title}</Typography>
      {children}
    </Box>
  );
}

function ReviewLoading() {
  return (
    <ReviewLayout>
      <Box role="status" aria-label="Loading contract review" aria-busy="true">
        <Skeleton animation={false} width={170} height={18} />
        <Skeleton animation={false} width="65%" height={48} />
        <Skeleton animation={false} width="85%" height={24} />
        <Skeleton animation={false} height={72} sx={{ mt: 2, borderRadius: '12px' }} />
        <Skeleton animation={false} height={520} sx={{ mt: 2.5, borderRadius: '16px' }} />
      </Box>
    </ReviewLayout>
  );
}

function ReviewLoadError({ onRetry }: { onRetry: () => void }) {
  return (
    <ReviewLayout>
      <Alert severity="error" action={<Button color="inherit" onClick={onRetry} sx={{ minHeight: 44, ...focusStyle }}>Try again</Button>}>
        <Typography fontWeight={600}>We couldn’t load this section.</Typography>
        Please try again.
      </Alert>
    </ReviewLayout>
  );
}

function ReviewUnavailable() {
  return (
    <ReviewLayout>
      <Alert severity="warning" icon={false} sx={{ bgcolor: '#fff8e6', border: '1px solid #ecc963', borderRadius: '12px', color: colors.ink, p: 2.25 }}>
        <Typography component="h1" sx={{ color: colors.ink, fontSize: 24, fontWeight: 700 }}>Contract not available</Typography>
        <Typography sx={{ mt: 0.5, color: colors.muted, fontSize: 14 }}>
          We can’t show this contract. It may have been removed or may no longer be available to your account.
        </Typography>
      </Alert>
      <Button component={RouterLink} to={APP_ROUTES.home} variant="contained" sx={{ mt: 2, ...primaryButtonStyle }}>Back to your contracts</Button>
    </ReviewLayout>
  );
}

const surfaceStyle = {
  borderColor: colors.border,
  borderRadius: '12px',
  boxShadow: '0 2px 10px rgba(18,26,38,0.04)',
  bgcolor: '#fff',
};

const secondaryButtonStyle = {
  minHeight: 44,
  px: 1.75,
  borderColor: '#d9dde5',
  borderRadius: '9px',
  color: '#344054',
  fontSize: 13,
  fontWeight: 600,
  textTransform: 'none',
  whiteSpace: 'nowrap' as const,
  ...focusStyle,
};

const primaryButtonStyle = {
  minHeight: 44,
  px: 2,
  borderRadius: '9px',
  bgcolor: colors.blue,
  fontSize: 13,
  fontWeight: 600,
  textTransform: 'none',
  ...focusStyle,
};
