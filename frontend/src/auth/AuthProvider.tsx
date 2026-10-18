import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import type { AuthAdapter, AuthSession } from './authSession';
import { AUTH_SESSION_EXPIRED_EVENT, createAuthAdapter, expireCurrentSession, setCurrentSession } from './authSession';
import { APP_ROUTES } from '../routes';

type AuthContextValue = {
  session: AuthSession | null;
  loading: boolean;
  canSignIn: boolean;
  signIn: (returnTo?: string) => Promise<void>;
  signOut: () => Promise<boolean>;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children, adapter }: { children: ReactNode; adapter?: AuthAdapter }) {
  const [authAdapter] = useState(() => adapter ?? createAuthAdapter());
  const [session, setSession] = useState<AuthSession | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    void authAdapter.getSession().then((loadedSession) => {
      if (cancelled) return;
      setCurrentSession(loadedSession);
      setSession(loadedSession);
      setLoading(false);
    }).catch(() => {
      if (cancelled) return;
      setCurrentSession(null);
      setSession(null);
      setLoading(false);
    });

    return () => { cancelled = true; };
  }, [authAdapter]);

  useEffect(() => {
    if (!session?.expiresAt) return;
    const remaining = session.expiresAt - Date.now();
    if (remaining <= 0) {
      setCurrentSession(null);
      setSession(null);
      return;
    }
    const timeout = window.setTimeout(() => expireCurrentSession(), remaining);
    return () => window.clearTimeout(timeout);
  }, [session]);

  useEffect(() => {
    const expireSession = () => {
      setCurrentSession(null);
      setSession(null);
      setLoading(false);
    };
    window.addEventListener(AUTH_SESSION_EXPIRED_EVENT, expireSession);
    return () => window.removeEventListener(AUTH_SESSION_EXPIRED_EVENT, expireSession);
  }, []);

  const signOut = useCallback(async () => {
    if (!session) return false;
    try {
      await authAdapter.signOut(session);
      setCurrentSession(null);
      setSession(null);
      return true;
    } catch {
      return false;
    }
  }, [authAdapter, session]);

  const signIn = useCallback((returnTo?: string) => authAdapter.signIn(returnTo), [authAdapter]);

  const value = useMemo(() => ({ session, loading, canSignIn: authAdapter.canSignIn, signIn, signOut }), [authAdapter.canSignIn, session, loading, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider');
  return context;
}

export function useRequireAuthentication() {
  const { session, loading } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  useEffect(() => {
    if (!loading && !session) {
      navigate(APP_ROUTES.signIn, {
        replace: true,
        state: { from: `${location.pathname}${location.search}${location.hash}` },
      });
    }
  }, [loading, session, navigate, location.pathname, location.search, location.hash]);
  return { session, loading };
}
