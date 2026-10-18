import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/main.tsx', 'src/api/generated/**', '**/*.d.ts', '**/*.test.*'],
      thresholds: {
        lines: 80,
        functions: 80,
        statements: 80,
        branches: 50,
      },
    },
  },
});
