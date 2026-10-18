import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { App } from './App';
import { appTheme } from './appTheme';
import { configureApiAuthentication } from './auth/apiAuth';
import { loadRuntimeConfiguration, setRuntimeConfiguration } from './auth/runtimeConfig';

async function startApplication() {
  setRuntimeConfiguration(await loadRuntimeConfiguration());
  configureApiAuthentication();
  const root = document.getElementById('root');
  if (!root) throw new Error('Application root element is missing.');

  createRoot(root).render(
    <StrictMode>
      <ThemeProvider theme={appTheme}>
        <CssBaseline />
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </ThemeProvider>
    </StrictMode>,
  );
}

void startApplication();
