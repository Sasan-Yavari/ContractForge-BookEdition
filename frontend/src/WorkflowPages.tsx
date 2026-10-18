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
  Stack,
  Typography,
} from '@mui/material';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { documentApi, isDocumentAuthorizationError, isDocumentUnavailableError } from './api/documentApi';
import { DocumentProcessingStatusResponse, StartUploadRequest, UploadStatusResponse } from './api/generated';
import { isAuthorizationError, MAX_STATUS_FAILURES, STATUS_POLL_INTERVAL_MS, SUPPORTED_UPLOAD_TYPE, uploadApi, uploadToStorage, completeLocalUpload, validateSelectedFile } from './api/uploadApi';
import { APP_ROUTES } from './routes';

const colors = {
  page: '#f7f8fa',
  ink: '#18202a',
  muted: '#667085',
  border: '#e3e6ec',
  blue: '#1f5eff',
};

const focusStyle = {
  '&:focus-visible': { outline: `3px solid ${colors.blue}`, outlineOffset: 2 },
};

type SelectedFile = { file: File; error?: string };

export function AddContractPage() {
  const navigate = useNavigate();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selected, setSelected] = useState<SelectedFile>();
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState(false);

  const selectFile = (file?: File) => {
    if (!file) return;
    setSelected({ file, error: validateSelectedFile(file) });
  };

  const submit = async () => {
    if (!selected || selected.error || submitting) return;
    setSubmitting(true);
    setSubmitError(false);
    try {
      const started = await uploadApi.startUpload({
        filename: selected.file.name,
        contentType: SUPPORTED_UPLOAD_TYPE,
        size: selected.file.size,
        contractType: StartUploadRequest.contractType.ELECTRICITY,
      });
      await uploadToStorage(started.upload, selected.file);
      await completeLocalUpload(started.uploadId);
      navigate(APP_ROUTES.processing.replace(':documentId', encodeURIComponent(started.uploadId)), { replace: true });
    } catch {
      setSubmitError(true);
      setSubmitting(false);
    }
  };

  return (
    <Box component="main" sx={{ maxWidth: 960, width: 'calc(100% - 40px)', mx: 'auto', pt: { xs: 4, md: 5.5 }, pb: 10 }}>
      <Box sx={{ maxWidth: 512, mx: 'auto' }}>
        <Typography sx={{ color: '#52627a', fontSize: 10, fontWeight: 600, letterSpacing: '0.03em' }}>ADD CONTRACT</Typography>
        <Typography component="h1" sx={{ mt: 0.5, color: colors.ink, fontSize: { xs: 28, sm: 32 }, lineHeight: 1.2, fontWeight: 700 }}>
          Add electricity contract
        </Typography>
        <Typography sx={{ mt: 0.75, color: colors.muted, fontSize: 14, lineHeight: 1.5 }}>
          Upload your electricity contract as a PDF. ContractForge will read it, then you’ll review the important details before they become trusted.
        </Typography>

        <Paper variant="outlined" sx={{ mt: 2.25, p: { xs: 2, sm: 2.5 }, borderColor: colors.border, borderRadius: { xs: '14px', sm: '12px' }, boxShadow: '0 2px 10px rgba(18,26,38,0.05)' }}>
          <Box
            component="div"
            onDragOver={(event) => event.preventDefault()}
            onDrop={(event) => {
              event.preventDefault();
              selectFile(event.dataTransfer.files[0]);
            }}
            sx={{ minHeight: { xs: 200, sm: 157 }, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', p: 2, textAlign: 'center', border: '1px dashed #cbd5e8', borderRadius: '12px', bgcolor: '#fbfcfe' }}
          >
            <Box aria-hidden="true" sx={{ display: 'grid', placeItems: 'center', width: 40, height: 40, mb: 1, borderRadius: '11px', bgcolor: '#e8eeff', color: '#3556a8', fontSize: 10, fontWeight: 700 }}>PDF</Box>
            {selected ? (
              <>
                <Typography sx={{ maxWidth: '100%', color: colors.ink, fontSize: 13, fontWeight: 600, overflowWrap: 'anywhere' }}>{selected.file.name}</Typography>
                <Typography sx={{ mt: 0.5, color: colors.muted, fontSize: 11 }}>{formatFileSize(selected.file.size)} · PDF</Typography>
                <Button aria-describedby={selected.error ? 'selected-file-error' : undefined} onClick={() => fileInputRef.current?.click()} variant="outlined" sx={{ mt: 1, minHeight: 44, px: 1.5, borderColor: '#d9dde5', borderRadius: '8px', color: '#344054', fontSize: 12, textTransform: 'none', ...focusStyle }}>
                  Choose another file
                </Button>
              </>
            ) : (
              <>
                <Typography sx={{ color: colors.ink, fontSize: 14, fontWeight: 600 }}>Drop your PDF here</Typography>
                <Typography sx={{ mt: 0.5, color: colors.muted, fontSize: 12 }}>or choose a file from your computer</Typography>
                <Button onClick={() => fileInputRef.current?.click()} variant="outlined" sx={{ mt: 1, minHeight: 44, px: 1.5, borderColor: '#d9dde5', borderRadius: '8px', color: '#344054', fontSize: 12, textTransform: 'none', ...focusStyle }}>
                  Choose file
                </Button>
              </>
            )}
            <input
              aria-label="Choose a PDF file"
              aria-invalid={Boolean(selected?.error)}
              aria-describedby={selected?.error ? 'selected-file-error' : undefined}
              ref={fileInputRef}
              type="file"
              accept=".pdf,application/pdf"
              hidden
              onChange={(event) => selectFile(event.currentTarget.files?.[0])}
            />
          </Box>

          <Typography sx={{ mt: 1.5, color: '#52627a', fontSize: 10, lineHeight: 1.5 }}>PDF only<br />Maximum file size: 25 MB</Typography>
          {selected?.error && <Alert id="selected-file-error" severity="error" icon={false} sx={{ mt: 1.25, p: '14px', borderRadius: '9px', bgcolor: '#fff1f1', color: '#a92727', '& .MuiAlert-message': { p: 0 } }}>
            <Typography sx={{ fontSize: 11, fontWeight: 600 }}>{selected.error.includes('25 MB') ? 'This file is too large' : 'This file is not a PDF'}</Typography>
            <Typography sx={{ mt: 0.25, color: '#825050', fontSize: 11 }}>{selected.error}</Typography>
          </Alert>}
          {submitError && <Alert severity="error" icon={false} sx={{ mt: 1.25, p: '14px', borderRadius: '9px', bgcolor: '#fff1f1', color: '#a92727', '& .MuiAlert-message': { p: 0 } }}>
            <Typography sx={{ fontSize: 12, fontWeight: 600 }}>We couldn’t add this contract</Typography>
          <Typography sx={{ mt: 0.25, color: '#825050', fontSize: 12 }}>Check Your contracts for the current status before adding another file.</Typography>
        </Alert>}

          <Button
            fullWidth
            variant="contained"
            disabled={!selected || Boolean(selected.error) || submitting || submitError}
            onClick={() => void submit()}
            sx={{ mt: 1.5, minHeight: 44, px: 2, borderRadius: '9px', bgcolor: colors.blue, fontWeight: 600, textTransform: 'none', ...focusStyle }}
          >
            {submitting ? 'Adding contract…' : 'Add contract'}
          </Button>
          {submitError && <Button component={RouterLink} to={APP_ROUTES.home} fullWidth variant="text" sx={{ mt: 0.5, minHeight: 44, textTransform: 'none', ...focusStyle }}>Back to your contracts</Button>}
        </Paper>
      </Box>
    </Box>
  );
}

type ProcessingState = 'loading' | 'processing' | 'failed' | 'uploadFailed' | 'unavailable' | 'error';

export function ProcessingPage() {
  const { documentId = '' } = useParams();
  const navigate = useNavigate();
  const [state, setState] = useState<ProcessingState>('loading');
  const [filename, setFilename] = useState('');
  const [contractType, setContractType] = useState('Electricity');
  const [failureCategory, setFailureCategory] = useState<DocumentProcessingStatusResponse.failureCategory | null>(null);
  const [pollGeneration, setPollGeneration] = useState(0);
  const [removeOpen, setRemoveOpen] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [removeError, setRemoveError] = useState(false);

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let cancelCurrentRequest: (() => void) | undefined;
    let consecutiveFailures = 0;
    let uploadCompleted = false;
    setState('loading');
    setFailureCategory(null);
    setRemoveError(false);

    const scheduleNext = () => {
      if (active) timer = setTimeout(() => void checkStatus(), STATUS_POLL_INTERVAL_MS);
    };

    const checkStatus = async () => {
      let readingDocument = false;
      try {
        if (!uploadCompleted) {
          const uploadRequest = uploadApi.getUploadStatus(documentId);
          cancelCurrentRequest = getRequestCancellation(uploadRequest);
          const upload = await uploadRequest;
          cancelCurrentRequest = undefined;
          if (!active) return;
          consecutiveFailures = 0;
          setFilename(upload.originalFilename);
          setContractType(upload.contractType === UploadStatusResponse.contractType.ELECTRICITY ? 'Electricity' : 'Contract');

          if (upload.status === UploadStatusResponse.status.REJECTED || upload.status === UploadStatusResponse.status.EXPIRED) {
            setState('uploadFailed');
            return;
          }
          if (upload.status !== UploadStatusResponse.status.COMPLETED) {
            setState('processing');
            scheduleNext();
            return;
          }
          uploadCompleted = true;
        }

        readingDocument = true;
        const processingRequest = documentApi.getProcessingStatus(documentId);
        cancelCurrentRequest = getRequestCancellation(processingRequest);
        const processing = await processingRequest;
        cancelCurrentRequest = undefined;
        if (!active) return;
        consecutiveFailures = 0;
        setFilename(processing.originalFilename);
        setContractType(processing.contractType === DocumentProcessingStatusResponse.contractType.ELECTRICITY ? 'Electricity' : 'Contract');
        if (processing.status === DocumentProcessingStatusResponse.status.SUCCESS) {
          navigate(APP_ROUTES.review.replace(':documentId', encodeURIComponent(documentId)), { replace: true });
          return;
        }
        if (processing.status === DocumentProcessingStatusResponse.status.FAILED) {
          setFailureCategory(processing.failureCategory);
          setState('failed');
          return;
        }
        setState('processing');
        scheduleNext();
      } catch (failure) {
        if (!active) return;
        if (isAuthorizationError(failure) || isDocumentAuthorizationError(failure)) {
          setState('unavailable');
          return;
        }
        if (isDocumentUnavailableError(failure) && readingDocument) {
          setState('unavailable');
          return;
        }
        consecutiveFailures += 1;
        if (consecutiveFailures >= MAX_STATUS_FAILURES) {
          setState('error');
          return;
        }
        setState('processing');
        scheduleNext();
      }
    };

    void checkStatus();
    return () => {
      active = false;
      if (timer) clearTimeout(timer);
      cancelCurrentRequest?.();
    };
  }, [documentId, navigate, pollGeneration]);

  const removeDocument = async () => {
    if (removing) return;
    setRemoving(true);
    setRemoveError(false);
    try {
      await documentApi.deleteDocument(documentId);
      navigate(APP_ROUTES.home, { replace: true });
    } catch {
      setRemoving(false);
      setRemoveOpen(false);
      setRemoveError(true);
    }
  };

  if (state === 'loading') {
    return <LoadingState label="Checking your contract" />;
  }
  if (state === 'unavailable') return <UnavailableState />;
  if (state === 'error') {
    return <ContextualError onCheck={() => setPollGeneration((value) => value + 1)} />;
  }
  if (state === 'uploadFailed') {
    return <UploadFailurePage contractType={contractType} filename={filename} />;
  }
  if (state === 'failed') {
    const message = failureMessage(failureCategory);
    return (
      <WorkflowLayout eyebrow="ELECTRICITY CONTRACT" title="We couldn’t read this contract" description="Your file is still here. Choose what you’d like to do next.">
        <Paper variant="outlined" sx={panelStyle}>
          <IdentityRow contractType={contractType} filename={filename} />
          <Alert severity="error" icon={false} sx={calloutStyle}>
            <Typography sx={{ fontSize: 12, fontWeight: 600 }}>We couldn’t get the details from this file</Typography>
            <Typography sx={{ mt: 0.25, color: '#825050', fontSize: 12 }}>{message}</Typography>
          </Alert>
      <RecoveryRow title="Choose another file" detail="Add a different PDF as a new contract." action={<Button component={RouterLink} to={APP_ROUTES.addContract} variant="outlined" sx={secondaryButtonStyle}>Choose file</Button>} />
          {removeError && <Alert severity="error">We couldn’t remove this contract. Please try again.</Alert>}
          <RecoveryRow title="Remove this contract" detail="Permanently remove this failed contract." action={<Button color="error" variant="outlined" onClick={() => setRemoveOpen(true)} sx={{ ...secondaryButtonStyle, color: '#bd2c2c', borderColor: '#bd2c2c' }}>Remove</Button>} separated />
        </Paper>
        <Button component={RouterLink} to={APP_ROUTES.home} variant="outlined" sx={{ ...secondaryButtonStyle, mt: 2.5 }}>Back to your contracts</Button>
        <Dialog open={removeOpen} onClose={() => !removing && setRemoveOpen(false)} aria-labelledby="remove-failed-contract-title" aria-describedby="remove-failed-contract-description">
          <DialogTitle id="remove-failed-contract-title">Remove contract?</DialogTitle>
          <DialogContent>
            <Typography id="remove-failed-contract-description" sx={{ color: colors.muted, fontSize: 14 }}>This permanently removes this contract and its associated information from ContractForge.</Typography>
            <Alert severity="error" icon={false} sx={{ ...calloutStyle, mt: 1.5 }}>
              <Typography sx={{ fontSize: 12, fontWeight: 600 }}>This can’t be undone</Typography>
              <Typography sx={{ color: '#825050', fontSize: 12 }}>There is no archive, trash, restore, or undo for this action.</Typography>
            </Alert>
          </DialogContent>
          <DialogActions sx={{ p: 2.5, pt: 0 }}>
            <Button autoFocus onClick={() => setRemoveOpen(false)} disabled={removing} sx={secondaryButtonStyle}>Cancel</Button>
            <Button color="error" variant="contained" onClick={() => void removeDocument()} disabled={removing} sx={{ minHeight: 44, textTransform: 'none' }}>{removing ? 'Removing…' : 'Remove contract'}</Button>
          </DialogActions>
        </Dialog>
      </WorkflowLayout>
    );
  }

  return (
    <WorkflowLayout eyebrow="ELECTRICITY CONTRACT" title="We’re reading your contract" description="This usually takes a little while. You can leave this page — we’ll keep working in the background.">
      <Paper variant="outlined" sx={{ ...panelStyle, minHeight: 235 }}>
        <IdentityRow contractType={contractType} filename={filename} />
        <Box sx={{ mt: 1.75, p: 1.5, border: `1px solid ${colors.border}`, borderRadius: '10px', bgcolor: '#fbfcfe' }}>
          <Stack direction="row" spacing={1.25} alignItems="center" role="status" aria-live="polite">
            <Box aria-hidden="true" sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: colors.blue, flex: '0 0 auto' }} />
            <Box>
              <Typography sx={{ color: '#344054', fontSize: 12, fontWeight: 600 }}>Processing</Typography>
              <Typography sx={{ color: colors.muted, fontSize: 11, lineHeight: 1.5 }}>We’ll take you to Review automatically when the contract is ready while this page is open.</Typography>
            </Box>
          </Stack>
        </Box>
        <Alert severity="info" icon={false} sx={{ mt: 1.75, p: '14px', borderRadius: '9px', bgcolor: '#eef3ff', color: '#3556a8', '& .MuiAlert-message': { p: 0 } }}>
          <Typography sx={{ fontSize: 11, fontWeight: 600 }}>You don’t need to wait here</Typography>
          <Typography sx={{ mt: 0.25, fontSize: 11 }}>You can return to Your contracts at any time. Processing will continue in the background.</Typography>
        </Alert>
      </Paper>
      <Button component={RouterLink} to={APP_ROUTES.home} variant="outlined" sx={{ ...secondaryButtonStyle, mt: 2.5 }}>Back to your contracts</Button>
    </WorkflowLayout>
  );
}

function UploadFailurePage({ contractType, filename }: { contractType: string; filename: string }) {
  return (
    <WorkflowLayout eyebrow="ELECTRICITY CONTRACT" title="We couldn’t process this file" description="Choose another PDF to continue. This item remains in Your contracts until you choose a recovery path.">
      <Paper variant="outlined" sx={panelStyle}>
        <IdentityRow contractType={contractType} filename={filename} />
        <Button component={RouterLink} to={APP_ROUTES.addContract} variant="outlined" sx={{ ...secondaryButtonStyle, mt: 2 }}>Choose another file</Button>
      </Paper>
      <Button component={RouterLink} to={APP_ROUTES.home} variant="outlined" sx={{ ...secondaryButtonStyle, mt: 2.5 }}>Back to your contracts</Button>
    </WorkflowLayout>
  );
}

function failureMessage(category: DocumentProcessingStatusResponse.failureCategory | null): string {
  switch (category) {
    case DocumentProcessingStatusResponse.failureCategory.DETERMINISTIC_DOCUMENT:
      return 'Choose a different PDF to add as a new contract, or remove this one if you don’t want to continue.';
    case DocumentProcessingStatusResponse.failureCategory.RETRYABLE:
    case DocumentProcessingStatusResponse.failureCategory.UNKNOWN:
    case null:
      return 'Choose a different PDF to add as a new contract, or remove this one if you don’t want to continue.';
  }
}

function WorkflowLayout({ eyebrow, title, description, children }: { eyebrow: string; title: string; description: string; children: ReactNode }) {
  return (
    <Box component="main" sx={{ maxWidth: 960, width: 'calc(100% - 40px)', mx: 'auto', pt: { xs: 4, md: 5.5 }, pb: 10 }}>
      <Box sx={{ maxWidth: 512, mx: 'auto' }}>
        <Typography sx={{ color: '#52627a', fontSize: 10, fontWeight: 600, letterSpacing: '0.03em' }}>{eyebrow}</Typography>
        <Typography component="h1" sx={{ mt: 0.5, color: colors.ink, fontSize: { xs: 28, sm: 32 }, lineHeight: 1.2, fontWeight: 700 }}>{title}</Typography>
        <Typography sx={{ mt: 0.75, color: colors.muted, fontSize: 14, lineHeight: 1.5 }}>{description}</Typography>
        <Box sx={{ mt: 2.25 }}>{children}</Box>
      </Box>
    </Box>
  );
}

function IdentityRow({ contractType, filename }: { contractType: string; filename: string }) {
  return (
    <Stack direction="row" spacing={1.25} alignItems="center" sx={{ minWidth: 0 }}>
      <Box aria-hidden="true" sx={{ width: 30, height: 30, display: 'grid', placeItems: 'center', flex: '0 0 auto', borderRadius: '8px', bgcolor: colors.blue, color: '#ffd84d', fontSize: 16 }}>⚡</Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ color: colors.ink, fontSize: 13, fontWeight: 650 }}>{contractType}</Typography>
        <Typography sx={{ color: colors.muted, fontSize: 11, overflowWrap: 'anywhere' }}>{filename}</Typography>
      </Box>
    </Stack>
  );
}

function LoadingState({ label }: { label: string }) {
  return <Box component="main" role="status" aria-label={label} aria-busy="true" sx={{ minHeight: 360, display: 'grid', placeItems: 'center' }}><Typography sx={{ color: colors.muted }}>Loading…</Typography></Box>;
}

function ContextualError({ onCheck }: { onCheck: () => void }) {
  return (
    <WorkflowLayout eyebrow="ELECTRICITY CONTRACT" title="We can’t check this contract right now" description="Your file is still here. Check its status again in a moment.">
      <Stack spacing={2} alignItems="flex-start">
        <Alert severity="error" sx={{ width: '100%' }}>We couldn’t load this section.</Alert>
        <Button variant="outlined" onClick={onCheck} sx={secondaryButtonStyle}>Check status</Button>
        <Button component={RouterLink} to={APP_ROUTES.home} sx={secondaryButtonStyle}>Back to your contracts</Button>
      </Stack>
    </WorkflowLayout>
  );
}

function UnavailableState() {
  return (
    <WorkflowLayout eyebrow="ELECTRICITY CONTRACT" title="Contract not available" description="We can’t show this contract. It may have been removed or may no longer be available to your account.">
      <Button component={RouterLink} to={APP_ROUTES.home} variant="contained" sx={{ minHeight: 44, px: 2, borderRadius: '9px', bgcolor: colors.blue, textTransform: 'none', ...focusStyle }}>Back to your contracts</Button>
    </WorkflowLayout>
  );
}

function RecoveryRow({ title, detail, action, separated = false }: { title: string; detail: string; action: ReactNode; separated?: boolean }) {
  return (
    <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between" sx={{ minHeight: 72, borderTop: separated ? `1px solid ${colors.border}` : undefined, mt: separated ? 1.5 : 1.25, pt: separated ? 1.5 : 0 }}>
      <Box>
        <Typography sx={{ color: colors.ink, fontSize: 12, fontWeight: 600 }}>{title}</Typography>
        <Typography sx={{ color: colors.muted, fontSize: 11 }}>{detail}</Typography>
      </Box>
      {action}
    </Stack>
  );
}

function formatFileSize(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${Math.round(size / 1024)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function getRequestCancellation(request: Promise<unknown>): (() => void) | undefined {
  const cancel = (request as Promise<unknown> & { cancel?: () => void }).cancel;
  return typeof cancel === 'function' ? () => cancel.call(request) : undefined;
}

const panelStyle = {
  p: { xs: 2, sm: 2.5 },
  borderColor: colors.border,
  borderRadius: { xs: '14px', sm: '12px' },
  boxShadow: '0 2px 10px rgba(18,26,38,0.05)',
};

const calloutStyle = {
  mt: 1.5,
  p: '14px',
  borderRadius: '9px',
  bgcolor: '#fff1f1',
  color: '#a92727',
  '& .MuiAlert-message': { p: 0 },
};

const secondaryButtonStyle = {
  minHeight: 44,
  px: 1.5,
  borderColor: '#d9dde5',
  borderRadius: '8px',
  color: '#344054',
  fontSize: 12,
  fontWeight: 600,
  textTransform: 'none',
  whiteSpace: 'nowrap' as const,
  ...focusStyle,
};
