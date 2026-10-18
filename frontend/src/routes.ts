export const APP_ROUTES = {
  signIn: '/sign-in',
  home: '/',
  addContract: '/contracts/add',
  processing: '/processing/:documentId',
  processingFailed: '/processing/:documentId/failed',
  review: '/contracts/:documentId/review',
  contractOverview: '/contracts/:documentId',
  contractHistory: '/contracts/:documentId/history',
} as const;
