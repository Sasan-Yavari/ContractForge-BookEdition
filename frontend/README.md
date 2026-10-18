# Frontend

The frontend is the ContractForge web application boundary. The approved
frontend direction is React with TypeScript, Vite, React Router, MUI, and an
OpenAPI-generated client.

The application shell uses explicit React Router boundaries for Home, adding an
electricity contract, processing, review, confirmed contract overview, sign-in,
and unavailable/not-found destinations. Workflow pages remain route boundaries
for the follow-up frontend slices.

Home loads the complete owner collection through the generated
`GET /v1/contracts` client operation, renders lifecycle and confirmed states in
the order returned by the backend, and refreshes the view every 15 seconds while
visible. `GET /v1/contracts` supplies a `NEEDS_ATTENTION` state but no attention
summary; the per-contract overview returns all current recommendations without
the deferred Home selection rule. The overview API also does not provide a
cross-contract Things-to-know selection. Home therefore shows the authoritative
Needs attention badge and leaves both summary and Things-to-know presentations
deferred instead of inferring or ranking items in the frontend.

Protected routes use an in-memory authentication adapter and resolve generated
OpenAPI bearer tokens from the active session. A `401` or token expiry removes
protected UI. Development and Production use the Cognito authorization-code
flow with PKCE and the configured API access scope. OIDC user/token state is
memory-only; session storage carries only transient redirect state needed to
finish sign-in. Sign-out goes through Cognito Hosted UI, and failed navigation
does not clear the active session. The frontend image reads environment-specific
public auth values from `/runtime-config.json` generated when the container
starts. Local Compose supplies local auth only at runtime and only loopback
browser hosts can activate it.

## Reconciled journey and evidence

The approved journey, language, and interaction decisions remain owned by
Design artifacts are intentionally excluded from this reader candidate. Current frontend evidence is
mapped here so cross-cutting work can be checked without duplicating that
specification.

| Journey or requirement | Current implementation and direct evidence |
| --- | --- |
| Authenticated entry, account menu, sign out, and expired-session return | `src/App.tsx`; `src/App.test.tsx`; `src/auth/authSession.test.ts`; `src/auth/apiAuth.test.ts` |
| Empty, populated, refreshing, and failed Home collection | `src/HomePage.tsx`; `src/App.test.tsx` |
| Add PDF, local validation, processing, terminal failure, and recovery | `src/WorkflowPages.tsx`; `src/WorkflowPages.test.tsx`; `src/api/uploadApi.test.ts`; `src/api/documentApi.test.ts` |
| Loading, safe errors, retry boundaries, and auth/session consistency across protected pages | `src/App.tsx`; `src/HomePage.tsx`; `src/WorkflowPages.tsx`; `src/ContractReviewPage.tsx`; `src/ContractOverviewPage.tsx`; `src/App.test.tsx`; page tests |
| Canonical status labels and status meaning conveyed without color alone | `src/HomePage.tsx`; `src/WorkflowPages.tsx`; `src/App.test.tsx`; `src/WorkflowPages.test.tsx` |
| Review, save/confirm separation, field validation, original PDF, and dirty navigation | `src/ContractReviewPage.tsx`; `src/ContractReviewPage.test.tsx`; `src/review/ContractDetailsEditor.tsx`; `src/review/contractReviewModel.test.ts` |
| Confirmed overview, edit/conflict handling, Things to know, history, original PDF, and permanent removal | `src/ContractOverviewPage.tsx`; `src/ContractOverviewPage.test.tsx`; `src/App.test.tsx` |
| Locale-aware dates and currency, user-facing terminology, and no raw API identifiers or provider payloads | `src/ContractOverviewPage.tsx`; `src/HomePage.tsx`; `src/ContractOverviewPage.test.tsx`; `src/App.test.tsx` |
| Keyboard focus, common touch targets, primary-color contrast, reduced motion, and accessible dialogs | `src/appTheme.ts`; `src/appTheme.test.ts`; dialog name/description/focus assertions in Review and overview tests |
| Responsive Home, Add, Review, overview, and edit layouts | Responsive `sx` breakpoints in `src/App.tsx`, `src/HomePage.tsx`, `src/WorkflowPages.tsx`, `src/ContractReviewPage.tsx`, `src/ContractOverviewPage.tsx`, and `src/review/ContractDetailsEditor.tsx`; approved mobile references were inspected |
| No competing global Monitoring Results/Events/Recommendations page | The obsolete `MonitoringResults` component and its frontend-only API wrapper were removed; `/monitoring-results` is covered by `src/App.test.tsx` as an unavailable product route. Monitoring remains presented through the contract-scoped overview read model. |

The current layouts use MUI responsive breakpoints and single-column mobile
forms/cards, and the approved 390 px Home, Add, Review, overview, and edit
references were inspected during reconciliation. Component tests verify
semantic controls, state changes, and dialog focus behavior; they do not
simulate browser layout, screen readers, or physical touch. Those manual
checks remain necessary for release accessibility review. The theme test
measures the actual primary color against white at 5.12:1 and verifies the
shared focus, 44 px action targets, and `prefers-reduced-motion` rules.

The project CI-equivalent commands are listed in the repository's
[documentation guide](../docs/README.md); this README records frontend-specific
evidence locations rather than maintaining a second validation command list.
