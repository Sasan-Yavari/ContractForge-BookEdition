import { useEffect, useState } from 'react';
import {
  Alert,
  AppBar,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  Menu,
  MenuItem,
  Paper,
  Skeleton,
  Snackbar,
  Stack,
  Toolbar,
  Typography,
} from '@mui/material';
import { Link as RouterLink, Navigate, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { AuthProvider, useAuth, useRequireAuthentication } from './auth/AuthProvider';
import type { AuthAdapter } from './auth/authSession';
import { APP_ROUTES } from './routes';
import { accountApi } from './api/accountApi';
import { HomePage } from './HomePage';
import { AddContractPage, ProcessingPage } from './WorkflowPages';
import { ContractReviewPage } from './ContractReviewPage';
import { ContractHistoryPage, ContractOverviewPage } from './ContractOverviewPage';

const colors = {
  page: '#f7f8fa',
  ink: '#18202a',
  muted: '#667085',
  border: '#e3e6ec',
  blue: '#1f5eff',
};

const focusStyle = {
  '&:focus-visible': {
    outline: `3px solid ${colors.blue}`,
    outlineOffset: 2,
  },
};

export function App({ authAdapter }: { authAdapter?: AuthAdapter }) {
  return (
    <AuthProvider adapter={authAdapter}>
      <Routes>
        <Route path={APP_ROUTES.signIn} element={<SignInBoundary />} />
        <Route element={<ProtectedApplication />}>
          <Route element={<AppShell />}>
            <Route path={APP_ROUTES.home} element={<HomePage />} />
            <Route path={APP_ROUTES.addContract} element={<AddContractPage />} />
            <Route path={APP_ROUTES.processing} element={<ProcessingPage />} />
            <Route path={APP_ROUTES.processingFailed} element={<ProcessingPage />} />
            <Route path={APP_ROUTES.review} element={<ContractReviewPage />} />
            <Route path={APP_ROUTES.contractOverview} element={<ContractOverviewPage />} />
            <Route path={APP_ROUTES.contractHistory} element={<ContractHistoryPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Route>
      </Routes>
    </AuthProvider>
  );
}

function ProtectedApplication() {
  const { session, loading } = useRequireAuthentication();
  const location = useLocation();
  if (loading) return <PageLoading label="Loading your account" />;
  if (!session) return <Navigate to={APP_ROUTES.signIn} replace />;
  if (location.pathname === APP_ROUTES.home && session.returnTo && session.returnTo !== APP_ROUTES.home) {
    return <Navigate to={session.returnTo} replace />;
  }
  return <Outlet />;
}

function SignInBoundary() {
  const { session, loading, canSignIn, signIn } = useAuth();
  const location = useLocation();
  const [signInError, setSignInError] = useState(false);
  if (loading) return <PageLoading label="Checking your account" />;
  if (session) return <Navigate to={session.returnTo ?? APP_ROUTES.home} replace />;

  const returnTo = typeof location.state === 'object' && location.state !== null
    ? (location.state as { from?: unknown }).from
    : undefined;

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: colors.page, display: 'grid', placeItems: 'center', p: 3 }}>
      <Paper variant="outlined" sx={{ width: 'min(100%, 440px)', p: { xs: 3, sm: 4 }, borderColor: colors.border, borderRadius: 3 }}>
        <Brand />
        <Typography component="h1" variant="h5" sx={{ mt: 4, color: colors.ink, fontWeight: 700 }}>
          Sign in to ContractForge
        </Typography>
        <Typography color="text.secondary" sx={{ mt: 1 }}>
          {canSignIn ? 'Continue securely with your ContractForge account.' : 'Sign-in is not configured for this environment.'}
        </Typography>
        {canSignIn && <Button
          variant="contained"
          onClick={() => void signIn(typeof returnTo === 'string' ? returnTo : undefined).catch(() => setSignInError(true))}
          sx={{ mt: 3, minHeight: 44, bgcolor: colors.blue, textTransform: 'none', ...focusStyle }}
        >
          Sign in
        </Button>}
        {signInError && <Alert severity="error" sx={{ mt: 2 }}>Sign-in could not be started. Please try again.</Alert>}
      </Paper>
    </Box>
  );
}

function AppShell() {
  const { session, signOut } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const [signingOut, setSigningOut] = useState(false);
  const [signOutError, setSignOutError] = useState(false);
  const [contractRemoved, setContractRemoved] = useState(false);
  const [deleteAccountOpen, setDeleteAccountOpen] = useState(false);
  const [deletingAccount, setDeletingAccount] = useState(false);
  const [deletionStarted, setDeletionStarted] = useState(false);
  const [deletionSignOutFailed, setDeletionSignOutFailed] = useState(false);
  const [deleteAccountError, setDeleteAccountError] = useState(false);
  const menuOpen = Boolean(menuAnchor);

  useEffect(() => {
    if (location.pathname === APP_ROUTES.home && location.state?.contractRemoved) {
      setContractRemoved(true);
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location.pathname, location.state, navigate]);

  const handleSignOut = async () => {
    if (signingOut) return;
    setSigningOut(true);
    const signedOut = await signOut();
    setSigningOut(false);
    if (signedOut) {
      setMenuAnchor(null);
      setSignOutError(false);
      navigate(APP_ROUTES.signIn, { replace: true });
    } else {
      setMenuAnchor(null);
      setSignOutError(true);
    }
  };

  const handleDeleteAccount = async () => {
    if (deletingAccount) return;
    setDeletingAccount(true);
    setDeleteAccountError(false);
    try {
      await accountApi.deleteAccount();
      setDeletionStarted(true);
      window.setTimeout(() => {
        void signOutFromDeletedAccount();
      }, 400);
    } catch {
      setDeletingAccount(false);
      setDeleteAccountError(true);
    }
  };

  const signOutFromDeletedAccount = async () => {
    setSigningOut(true);
    const signedOut = await signOut();
    setSigningOut(false);
    if (signedOut) {
      setDeletionSignOutFailed(false);
      setDeleteAccountOpen(false);
      navigate(APP_ROUTES.signIn, { replace: true });
    } else {
      setDeletionSignOutFailed(true);
    }
  };

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: colors.page }}>
      <AppBar position="static" elevation={0} sx={{ bgcolor: '#fff', color: colors.ink, borderBottom: `1px solid ${colors.border}` }}>
        <Toolbar disableGutters sx={{ minHeight: { xs: '64px !important', md: '72px !important' }, px: { xs: 2, md: 'max(24px, calc((100vw - 1296px) / 2))' }, justifyContent: 'space-between' }}>
          <Brand />
          <Stack direction="row" alignItems="center" spacing={1.5}>
            <Button
              component={RouterLink}
              to={APP_ROUTES.addContract}
              variant="contained"
              sx={{ minHeight: 44, px: { xs: 1.5, sm: 2.25 }, py: { xs: 1.125, sm: 1.375 }, borderRadius: { xs: '9px', sm: '10px' }, bgcolor: colors.blue, fontSize: { xs: 13, sm: 14 }, textTransform: 'none', fontWeight: 600, ...focusStyle }}
            >
              +&nbsp; Add<Box component="span" sx={{ display: { xs: 'none', sm: 'inline' } }}>&nbsp;contract</Box>
            </Button>
            <Button
              aria-label="Open account menu"
              aria-haspopup="menu"
              aria-expanded={menuOpen ? 'true' : undefined}
              onClick={(event) => setMenuAnchor(event.currentTarget)}
              variant="outlined"
              sx={{ minWidth: { xs: 44, sm: session?.email ? 96 : 68 }, width: { xs: 44, sm: 'auto' }, minHeight: 44, px: { xs: 0, sm: 1 }, borderColor: '#d9dde5', borderRadius: '10px', color: '#3556a8', textTransform: 'none', ...focusStyle }}
            >
              <Box component="span" aria-hidden="true" sx={{ display: { xs: 'none', sm: 'inline-grid' }, placeItems: 'center', width: 26, height: 26, borderRadius: '50%', bgcolor: '#eef1fb', color: '#3556a8', fontSize: 12, mr: 0.75 }}>
                {session?.email?.[0]?.toUpperCase() ?? '•'}
              </Box>
              <Box component="span" aria-hidden="true" sx={{ display: { xs: 'inline-grid', sm: 'none' }, placeItems: 'center', width: 26, height: 26, borderRadius: '50%', bgcolor: '#eef1fb', color: '#3556a8', fontSize: 9, fontWeight: 700 }}>
                {session?.email?.slice(0, 2).toUpperCase() ?? '•'}
              </Box>
              {session?.email && <Box component="span" aria-hidden="true" sx={{ display: { xs: 'none', sm: 'inline' }, color: '#3556a8', fontSize: 10, fontWeight: 700, mr: 0.75 }}>
                {session.email.split('@')[0].slice(0, 2).toUpperCase()}
              </Box>}
              <Box component="span" aria-hidden="true" sx={{ display: { xs: 'none', sm: 'inline' }, color: colors.muted, fontSize: 14 }}>⌄</Box>
            </Button>
            <Menu
              anchorEl={menuAnchor}
              open={menuOpen}
              onClose={() => setMenuAnchor(null)}
              MenuListProps={{ 'aria-label': 'Account actions' }}
              PaperProps={{ sx: { mt: 0.75, minWidth: 240, border: `1px solid #d9dde5`, borderRadius: '12px' } }}
            >
              {session?.email && <Box sx={{ px: 1.75, py: 1.25 }}>
                <Typography variant="caption" color="text.secondary" display="block">Signed in as</Typography>
                <Typography variant="body2" sx={{ color: colors.ink, fontWeight: 600, overflowWrap: 'anywhere' }}>{session.email}</Typography>
              </Box>}
              {session?.email && <Box sx={{ borderTop: `1px solid ${colors.border}` }} />}
              <MenuItem onClick={() => void handleSignOut()} disabled={signingOut} sx={{ minHeight: 44, ...focusStyle }}>
                {signingOut ? 'Signing out…' : 'Sign out'}
              </MenuItem>
              <Divider />
              <MenuItem
                onClick={() => { setMenuAnchor(null); setDeleteAccountError(false); setDeletionStarted(false); setDeletionSignOutFailed(false); setDeleteAccountOpen(true); }}
                disabled={deletingAccount}
                sx={{ minHeight: 44, color: '#b42318', ...focusStyle }}
              >
                Delete account
              </MenuItem>
            </Menu>
          </Stack>
        </Toolbar>
      </AppBar>
      <Outlet />
      <Dialog
        open={deleteAccountOpen}
        onClose={() => { if (!deletingAccount && !deletionStarted) setDeleteAccountOpen(false); }}
        aria-labelledby="delete-account-title"
        aria-describedby="delete-account-description"
      >
        <DialogTitle id="delete-account-title">
          {deletionStarted ? 'Deletion started' : 'Delete your account?'}
        </DialogTitle>
        <DialogContent>
          {deletionStarted ? (
            <Stack spacing={1.5}>
              <Typography id="delete-account-description">
                Account access has stopped. Your account data and private files are being removed. We’re signing you out now.
              </Typography>
              {deletionSignOutFailed && <Alert severity="error">Deletion has started, but sign-out did not finish. Try again to leave this session.</Alert>}
            </Stack>
          ) : (
            <Stack spacing={1.5}>
              <Typography id="delete-account-description">
                This permanently deletes the account and its data. Your access stops immediately while data and private files are removed.
              </Typography>
              <Typography variant="body2" sx={{ color: colors.muted, overflowWrap: 'anywhere' }}>
                Signed in as <strong>{session?.email ?? 'your account'}</strong>
              </Typography>
              {deleteAccountError && <Alert severity="error">We couldn’t confirm whether the request was saved. If it was, your access is blocked while deletion retries. Keep this dialog open and try again; we’ll sign you out when the request is confirmed.</Alert>}
            </Stack>
          )}
        </DialogContent>
        {!deletionStarted && <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={() => setDeleteAccountOpen(false)} disabled={deletingAccount}>Cancel</Button>
          <Button color="error" variant="contained" onClick={() => void handleDeleteAccount()} disabled={deletingAccount}>
            {deletingAccount ? 'Starting deletion…' : 'Delete account'}
          </Button>
        </DialogActions>}
        {deletionStarted && deletionSignOutFailed && <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={() => void signOutFromDeletedAccount()} disabled={signingOut}>
            {signingOut ? 'Signing out…' : 'Sign out'}
          </Button>
        </DialogActions>}
      </Dialog>
      <Snackbar
        open={signOutError}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
        sx={{ '& .MuiAlert-root': { alignItems: 'flex-start', bgcolor: '#fff2f2', border: '1px solid #edc2c2', color: '#b62a2a', boxShadow: '0 6px 18px rgba(0,0,0,.16)' } }}
      >
        <Alert
          severity="error"
          icon={false}
          onClose={() => setSignOutError(false)}
          action={<IconButton aria-label="Dismiss sign-out error" size="small" onClick={() => setSignOutError(false)} sx={{ color: '#8c2929', ...focusStyle }}>×</IconButton>}
          sx={{ width: 'min(330px, calc(100vw - 32px))' }}
        >
          <Typography variant="body2" sx={{ fontWeight: 600 }}>Couldn't sign you out</Typography>
          <Typography variant="caption">You're still signed in. Please try again.</Typography>
        </Alert>
      </Snackbar>
      <Snackbar open={contractRemoved} autoHideDuration={5000} onClose={() => setContractRemoved(false)} message="Contract removed" anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }} />
    </Box>
  );
}

function Brand() {
  return (
    <Stack direction="row" alignItems="center" spacing={{ xs: 1, sm: 1.25 }} aria-label="ContractForge">
      <Box aria-hidden="true" sx={{ width: { xs: 26, sm: 28 }, height: { xs: 26, sm: 28 }, display: 'grid', placeItems: 'center', bgcolor: colors.blue, borderRadius: { xs: '7px', sm: '8px' }, color: '#fff', fontSize: { xs: 13, sm: 14 }, fontWeight: 700 }}>C</Box>
      <Typography component="span" sx={{ color: colors.ink, fontSize: { xs: 16, sm: 20 }, fontWeight: 650, letterSpacing: '-0.02em' }}>ContractForge</Typography>
    </Stack>
  );
}

function NotFoundPage() {
  return (
    <Box component="main" sx={{ maxWidth: 620, width: 'calc(100% - 48px)', mx: 'auto', pt: { xs: 6, md: 11 } }}>
      <Stack spacing={2} alignItems="flex-start">
        <Typography component="h1" variant="h5" sx={{ color: colors.ink, fontWeight: 700 }}>Page not found</Typography>
        <Button component={RouterLink} to={APP_ROUTES.home} variant="contained" sx={{ minHeight: 44, px: 2, borderRadius: '10px', bgcolor: colors.blue, textTransform: 'none', ...focusStyle }}>
          Back to your contracts
        </Button>
      </Stack>
    </Box>
  );
}

function PageLoading({ label }: { label: string }) {
  return <Box role="status" aria-label={label} sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: colors.page }}><CircularProgress size={28} /></Box>;
}
