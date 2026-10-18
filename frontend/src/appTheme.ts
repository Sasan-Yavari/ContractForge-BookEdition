import { createTheme } from '@mui/material';

const primaryBlue = '#1f5eff';

export const appTheme = createTheme({
  palette: {
    primary: { main: primaryBlue },
  },
  typography: { fontFamily: 'Inter, Arial, sans-serif' },
  components: {
    MuiButtonBase: {
      styleOverrides: {
        root: {
          '&.Mui-focusVisible': {
            outline: `3px solid ${primaryBlue}`,
            outlineOffset: 2,
          },
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: {
          minHeight: 44,
          textTransform: 'none',
        },
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: { minWidth: 44, minHeight: 44 },
      },
    },
    MuiMenuItem: {
      styleOverrides: {
        root: { minHeight: 44 },
      },
    },
    MuiCssBaseline: {
      styleOverrides: {
        '@media (prefers-reduced-motion: reduce)': {
          '*, *::before, *::after': {
            animationDuration: '0.01ms !important',
            animationIterationCount: '1 !important',
            scrollBehavior: 'auto !important',
            transitionDuration: '0.01ms !important',
          },
        },
      },
    },
  },
});
