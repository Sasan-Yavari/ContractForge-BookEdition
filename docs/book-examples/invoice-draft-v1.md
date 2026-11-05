Sample version: `invoice-draft-v1` (5 November 2026).

## Teaching example: update an invoice draft and reopen the saved values

This is the book's worked example of an implementation issue, **not an instruction to add invoicing to ContractForge**. Copy/adapt it into your own product's tracker. The invoice product is fictional; ContractForge is a contract-management example. No implementation, deployment or provider call is requested in this repository by this issue.

## Outcome
A signed-in user edits an existing draft they own, saves it, leaves the screen, and reopens the same draft with the saved values. One change covers the screen, API, application logic and database together.

## Authority and inputs
In the reader's product repository, AGENTS.md points to the maintained records. Before assigning this issue, replace each placeholder with an accessible accepted revision/section:
- Product scope and requirements: [product brief; R-02 update/reopen and R-03 own-draft-only access].
- Architecture and selected stack/tooling: [accepted architecture/decision records; build/runtime/test versions].
- Journey, approved screen/frame states, data model and API contract: [accepted design references].
- Shared behavior: [validation, error messages, retry/time limits, duplicate-request handling and concurrent-edit policy].
- Security/privacy, diagnostics and data lifecycle: [accepted rules].
- Quality: [required tests, coverage metric/scope/threshold and required CI checks].
- Working procedure: [AGENTS.md plus verified development and review Skills].

These are input slots, not claims that the fictional product already has approved decisions. For a concrete source-navigation example, the published ContractForge map is https://github.com/Sasan-Yavari/ContractForge-BookEdition/blob/book-example-v1/docs/architecture/CODEBASE-MAP.md; its content belongs to ContractForge, not this imaginary invoice product. The tag pins source examples; it is not the implementation base of a copied invoice task.

## Readiness and dependencies
**Not ready for implementation until the reader supplies the accepted inputs above.** The book leaves the sign-in mechanism and retention decision unresolved. Work depending on them stays blocked. Allowed unfinished fields, retry limits and conflict behavior must also be resolved where this feature needs them.

When a missing behavior is shared across the application, return options and affected work to the owner. After the owner decides, update the appropriate canonical record through the normal reviewed change process, then reference that accepted decision here. Do not choose a different retry/error policy for each button. Permanent validation/permission failures are not retry candidates; temporary failures follow the project's accepted bounded retry policy.

## Scope
Update the existing owned draft and support its complete save/reopen journey. Reuse accepted UI components, API conventions, storage and migration mechanisms. Preserve own-draft-only access for both reading and modification. Sending invoices and taking payments remain outside the pilot. Add dependencies/services only after a specific need and owner decision.

## Acceptance and planned evidence
These are checks to execute, not reported passes. Link each result to the actual candidate, command/test, assertions and outcome; name unavailable checks.

| Case | Expected result / decision input | Evidence |
| --- | --- | --- |
| Owner saves agreed editable fields. | Existing draft identity is retained; reopening shows the saved values. | UI-to-API-to-database-to-reopened-screen check with fictional data; stored values and returned response agree. |
| Values fail accepted validation. | Request is rejected without changing saved values; the screen explains the error using the shared rule. | Invalid-input test plus UI state check. Missing allowed-field rules block this case. |
| Storage fails before commit. | Related writes do not leave a partial update. | Actual database transaction/rollback check; inspect saved contents. |
| Save commits but reply is lost. | A repeat/readback follows the accepted duplicate-request/recovery policy and does not create an unintended extra draft. | Simulated lost reply; stored state, repeat identity and displayed result inspected. Missing policy blocks this case. |
| Two updates overlap. | The accepted conflict policy is applied; do not invent which edit wins. | Concurrent requests against the real database boundary; observable outcome compared with the rule. |
| Another user reads the draft. | No draft data is returned. | Separate forbidden-read test. |
| Another user changes the draft. | Saved draft remains unchanged. | Separate forbidden-modification test, then read back saved contents. |
| Screen matches accepted design. | Agreed labels, values, loading/success/error states, keyboard behavior and responsive layouts are preserved. | Actual preview inspection and relevant automated UI checks; record inaccessible/unexecuted views. |
| Existing data/API clients encounter the change. | Follow accepted compatibility/migration design. | Migration and compatibility checks where changed; explain recovery plan. |
| Diagnosis is needed. | Logs identify request/stage/failure category under privacy rules. | Inspect safe diagnostics without invoice bodies, credentials or sensitive provider payloads. |

For any required external integration, test the actual adapter at an appropriate boundary. Mocks prove only the modeled responses. A real-provider probe needs its own approved environment, fictional/sanitized data, spending bound and question; this issue grants none. Schema-valid output and a correct business result are different checks.

## Execution and limits
Use an isolated task branch/workspace in [actual product repository] from [current accepted main revision]. Preserve unrelated work. Run [approved build/test commands] with [fictional fixtures/local services]. Apply the recorded coverage and quality requirements; do not invent, reduce or bypass them.

A simple save may use only the existing database. If the accepted design really includes queued background work, add its save-to-publish loss window, duplicate handling, bounded retries, stale-worker/lease behavior and failed-message destination to this issue before implementing that path. Specify accepted limits; do not promise exactly-once behavior across unrelated services.

## Stop and return a decision
Stop affected work for missing/conflicting authority, undecided product behavior, a new shared rule, scope expansion, new dependency/service, credential/data/spending change, or release/deployment. Return the gap, options and recommendation. Fix routine in-scope defects without repeated human prompts.

## Return and delivery
1. Small scoped implementation; acceptance-to-evidence table with actual results and gaps.
2. Relevant migration/compatibility notes, explicit remaining loss/repeat windows and required follow-up.
3. Automatic Draft PR and exact head; main agent self-checks and uses a fresh non-authoring reviewer worker where supported, fixes findings, reruns relevant checks and obtains re-review.
4. Separate final ChatGPT review of that exact PR against this issue using the established short review request (full request until reviewer instructions/Skill loading is verified). Findings return to correction/re-review.
5. Human inspects reviewed result and required checks, then decides merge. No automatic merge/deploy.
6. After human merge, reconcile issue, shared records, implementation references and remaining plan/blockers. Keep proposals and unimplemented work open.

The book's testing chapter develops test selection and UI testing. This example records the required evidence in advance so the implementation agent can work from one clear job.
