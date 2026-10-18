import { describe, expect, it } from 'vitest';
import { appTheme } from './appTheme';

function contrastRatio(foreground: string, background: string): number {
  const luminance = (color: string) => {
    const [red, green, blue] = color.match(/[\da-f]{2}/gi)!.map((channel) => parseInt(channel, 16) / 255);
    const linear = [red, green, blue].map((channel) => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4);
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
  };
  const foregroundLuminance = luminance(foreground);
  const backgroundLuminance = luminance(background);
  return (Math.max(foregroundLuminance, backgroundLuminance) + 0.05)
    / (Math.min(foregroundLuminance, backgroundLuminance) + 0.05);
}

describe('application accessibility theme', () => {
  it('provides AA contrast for the actual primary action and a shared visible focus ring', () => {
    expect(contrastRatio(appTheme.palette.primary.main, '#ffffff')).toBeGreaterThanOrEqual(4.5);
    expect(appTheme.components?.MuiButtonBase?.styleOverrides).toMatchObject({
      root: {
        '&.Mui-focusVisible': { outline: '3px solid #1f5eff', outlineOffset: 2 },
      },
    });
  });

  it('keeps common actions touch-friendly and reduces motion for users who request it', () => {
    expect(appTheme.components?.MuiButton?.styleOverrides).toMatchObject({ root: { minHeight: 44 } });
    expect(appTheme.components?.MuiIconButton?.styleOverrides).toMatchObject({ root: { minWidth: 44, minHeight: 44 } });
    expect(appTheme.components?.MuiMenuItem?.styleOverrides).toMatchObject({ root: { minHeight: 44 } });
    expect(appTheme.components?.MuiCssBaseline?.styleOverrides).toMatchObject({
      '@media (prefers-reduced-motion: reduce)': {
        '*, *::before, *::after': {
          animationDuration: '0.01ms !important',
          animationIterationCount: '1 !important',
          scrollBehavior: 'auto !important',
          transitionDuration: '0.01ms !important',
        },
      },
    });
  });
});
