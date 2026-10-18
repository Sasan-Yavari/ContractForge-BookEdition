# ContractForge — Data Model

> **Responsibility:** persistent/domain entities, relationships, constraints, trust lifecycle and retention rules. This document owns what data exists; flows describe movement and the implementation baseline describes current code evidence.

> Canonical data-model overview. The committed SQL migrations and persistence code define the implemented schema. This document explains the main relationships and data-handling boundaries.

## Persistence decisions

- PostgreSQL is the primary application database for the MVP.
- Original uploaded document binaries live in private S3, not PostgreSQL.
- PostgreSQL JSONB is used where extracted structures genuinely need flexibility.
- Database infrastructure is provisioned with Terraform/OpenTofu; schema evolution uses Flyway and versioned SQL migrations.
- User-approved trusted contract facts are the authoritative business data. A `DRAFT` Contract may be edited before approval; no second raw/candidate copy is persisted for the MVP.
- Do not persist full LLM prompts/responses or document text in ordinary operational tables unless explicitly required later.
- Sensitive document content must not be written to logs.
- Subscription and customer entitlement state are not current MVP entities or persisted data; this deliberate deferral and its revisit conditions are recorded in [DECISIONS.md](DECISIONS.md) .

## `users` — APPROVED

```text
users
-----
id
status
created_at
updated_at
```

- `id` = ContractForge internal User PK, PostgreSQL `UUID`.
- `status` = `ACTIVE` / `DISABLED`, PostgreSQL `VARCHAR(20)` with a CHECK constraint.
- `created_at` and `updated_at` use PostgreSQL `TIMESTAMPTZ` and are NOT NULL.
- Authentication is handled by the external identity provider; provider identity is mapped separately.
- A reserved system user row is used for system-generated audit events.
- Reserved system-user UUID: `00000000-0000-0000-0000-000000000001`.
- The first Flyway migration creates the reserved system-user row with `status = ACTIVE`.
- No status index is required.

## `user_identities` — APPROVED

```text
user_identities
---------------
id
user_id
provider
provider_subject
email
created_at
```

- `id` = ContractForge internal identity-record PK, PostgreSQL `UUID`.
- `user_id` = FK → `users.id`, NOT NULL.
- `provider` = PostgreSQL `VARCHAR(50)`, NOT NULL.
- `provider_subject` = PostgreSQL `VARCHAR(255)`, NOT NULL.
- `email` = nullable PostgreSQL `VARCHAR(320)` containing only the normalized address asserted as verified by the trusted identity provider. It is an identity/account delivery input, never a caller-supplied notification destination; an unavailable value cannot be fabricated.
- `created_at` uses PostgreSQL `TIMESTAMPTZ` and is NOT NULL.
- `UNIQUE(provider, provider_subject)`.
- Do not auto-link identities merely by email.
- A `DISABLED` application user cannot access the application even if external authentication succeeds.

## `documents` — APPROVED

```text
documents
---------
id
user_id
storage_key
filename
mime_type
size
status
contract_type
created_at
updated_at
```

- `id` = PostgreSQL `UUID` PK.
- `user_id` = FK → `users.id`, NOT NULL.
- `storage_key` and `filename` use PostgreSQL `TEXT`, NOT NULL.
- `mime_type` = `VARCHAR(100)`, NOT NULL.
- `size` = `BIGINT`, NOT NULL, with `size >= 0`.
- `status` = `PROCESSING` / `SUCCESS` / `FAILED`, `VARCHAR(20)` with a CHECK constraint.
- `contract_type` = `VARCHAR(50)`, NOT NULL with a CHECK constraint; the current supported value is `ELECTRICITY`. It records the type selected when upload starts and is copied from Upload during document finalization.
- `created_at` and `updated_at` use `TIMESTAMPTZ`, NOT NULL.
- `storage_key` is a private S3 object key, never a public URL.
- Original binary remains in private S3.
- `GET /v1/documents/{documentId}/original` first resolves the Document for the authenticated owner and checks the trusted object is present. It returns a five-minute inline-PDF presigned read URL, never the storage key or credentials. A missing object is non-disclosing `404`; unavailable storage is a generic `503`. The URL is a bearer capability and is not logged.
- `SUCCESS` means automated processing successfully produced the reviewable `DRAFT` Contract. It does not mean that the Contract has been user-approved.
- **No `deleted_at`.** Documents are physically hard-deleted from PostgreSQL when the user deletes them.
- Dependent document data uses `ON DELETE CASCADE` so hard deletion can occur atomically in one DB transaction.

### Document deletion boundary

The data-model facts are that Documents are hard-deleted, have no `deleted_at`, and dependent business data uses the approved cascade relationships. The cross-boundary deletion strategy, asynchronous S3 cleanup, retry/redrive and DLQ behavior are owned by [DOCUMENT-DELETION.md](DOCUMENT-DELETION.md), not repeated in this data model.

## Temporary account-deletion state

Account deletion introduces `account_deletion_jobs` and `account_deletion_objects` only as temporary recovery state, not as retained customer history. A job stores a random operation UUID, the still-present internal `user_id`, typed lifecycle phase, upload-capability and identity-fence deadlines, dispatch/worker leases, and retry timing. It contains no email or provider subject. The object manifest stores each captured private S3 key, whether it is quarantine or trusted, and whether deletion succeeded. Rows are removed when the entire lifecycle completes. These temporary keys remain until cleanup succeeds because losing them would make object erasure unrecoverable.

At acceptance, the application User becomes `DISABLED` and every verified email is scrubbed from `user_identities`. All provider/subject mappings stay temporarily so an unexpired access token resolves to the disabled User rather than provisioning a replacement; every mapping is immutable during that fence. Account-owned relational data is hard-deleted after outstanding upload capabilities expire. User deletion then cascades identity removal only after object cleanup, deletion of every mapped Cognito identity, and the configured token-validity fence. The reserved system User remains active and is not customer-owned deletion data. The relational completion evidence is a random operation identifier and completion action in an audit row owned by that system User; it has no customer email, provider subject, document/contract payload, or customer User foreign key.

Managed database backups can retain recoverable copies after live deletion; retention and recovery controls are deployment-specific. The local Compose edition does not model cloud recovery points or deletion tombstones. It demonstrates application-level deletion behavior only; see [Document Deletion](DOCUMENT-DELETION.md).

## `uploads` — APPROVED

```text
uploads
-------
id
user_id
status
object_key
original_filename
contract_type
expires_at
created_at
```

Approved lifecycle model:

```text
UPLOADING
UPLOADED
SCANNED
PROMOTED
COMPLETED
REJECTED
```

- `id` = PostgreSQL `UUID` PK.
- `user_id` = FK → `users.id`, NOT NULL.
- `status` = `VARCHAR(20)` with a CHECK constraint for the six persisted states above.
- `object_key` = PostgreSQL `TEXT`, NOT NULL.
- `original_filename` = PostgreSQL `VARCHAR(255)`, NOT NULL. It is validated display metadata retained across asynchronous ingestion and never determines an object key or authorization decision.
- `contract_type` = PostgreSQL `VARCHAR(50)`, NOT NULL with a CHECK constraint. Upload initiation requires the selected supported type and persists it before bytes are accepted; the current value is `ELECTRICITY`.
- `expires_at` and `created_at` use `TIMESTAMPTZ`, NOT NULL.
- `UPLOADING` is the initial state when an upload starts.
- `COMPLETED` is the final state when the Document record has been created successfully.
- `EXPIRED` is **not persisted**. It is a derived API state when an `UPLOADING` upload has passed `expires_at`.
- `REJECTED` is terminal for definitive invalid, oversized, unsupported, mismatched or malicious uploads. It never advances to `SCANNED`, promotion, Document creation or processing.
- There is **no persisted `FAILED` Upload state**. Temporary infrastructure/provider failures remain retryable from the last durable checkpoint; definitive security/content failures are persisted as `REJECTED`.
- `Upload.id` is generated when upload starts.
- `Upload.id === Document.id` for successful ingestion.
- There is no `document_id` column on Upload.
- One successful Upload produces one Document in the MVP.
- Upload records are retained as upload-attempt history.
- `expires_at` identifies stale upload attempts; it is not a database-cleanup mechanism.
- `expires_at` derives the API's `EXPIRED` status; it does not delete the Upload row or quarantine object. Managed object-storage lifecycle policies may provide a separate cleanup safety net, but their retention settings are deployment-specific and are not reproduced by the local Compose walkthrough.
- SQS carries the upload identifier as the processing trigger.

## `contracts` — APPROVED

```text
contracts
---------
document_id
type
status
data
created_at
updated_at
archived_at
```

- `document_id` = PostgreSQL `UUID` PK + FK → `documents.id`, guaranteeing at most one Contract per Document.
- `type` = PostgreSQL `VARCHAR(50)`, NOT NULL. MVP value: `ELECTRICITY`.
- The column remains extensible for future contract domains; new type values require an explicit product/schema decision.
- `status` = PostgreSQL `VARCHAR(20)`, NOT NULL, CHECK `DRAFT` / `ACTIVE` / `ARCHIVED`.
- `data` = PostgreSQL `JSONB`, NOT NULL, containing flexible Contract data. While status is `DRAFT`, the data is reviewable AI-extracted/user-edited candidate business data and is not yet trusted for downstream business decisions. Once status becomes `ACTIVE`, the approved data is trusted Contract data.
- `version` = PostgreSQL `BIGINT`, NOT NULL, non-negative monotonic optimistic-concurrency token, initially `0`. Draft edits, confirmed edits, and approval must submit the expected version; a guarded edit requires the expected current status (`DRAFT` or `ACTIVE`) and equal version, then increments the version exactly once. This is not revision history and no before/after payloads are persisted.
- `created_at` and `updated_at` = `TIMESTAMPTZ`, NOT NULL.
- `archived_at` = `TIMESTAMPTZ`, nullable.
- `archived_at` is populated when the Contract is archived.
- Successful automated processing creates the Contract as `DRAFT` and changes Document to `SUCCESS` atomically.
- The user may edit the `DRAFT` Contract before approval.
- Explicit user approval transitions the same Contract from `DRAFT` to `ACTIVE`; approval does not create a second Contract.
- An owner may deliberately correct the same supported fields on an `ACTIVE` Contract. The guarded write preserves `ACTIVE`; it never returns the Contract to `DRAFT`. The same field validator applies, and the owner, resource, and `CONFIRMED_EDITED` action are recorded without persisting contract payloads in the audit row.
- A confirmed correction immediately refreshes generated current monitoring in the same transaction. Existing active contract-term Events are cancelled and their linked Recommendations expired into contract history; the existing deterministic monitors then persist results that remain valid for the corrected facts. Details and notification consequences belong to [DECISIONS.md](DECISIONS.md) and [DATA-FLOW.md](DATA-FLOW.md).
- Owner-only review mutations use the Contract version as the authoritative concurrency token. Stale edits, edit/approval races and duplicate approval affect neither newer data nor the trust boundary.
- Only `ACTIVE` Contracts are trusted inputs for monitoring, recommendations and deterministic financial outcomes.
- The MVP does not persist a second raw/candidate extraction copy. Meaningful edits and approval may create audit events where useful.

## `contract_parties` — APPROVED

```text
contract_parties
----------------
id
contract_id
role
name
```

- `id` = PostgreSQL `UUID` PK.
- `contract_id` = FK → `contracts.document_id`, NOT NULL.
- `role` = `VARCHAR(20)`, NOT NULL, CHECK `CUSTOMER` / `PROVIDER` / `BROKER`.
- `name` = `TEXT`, NOT NULL.
- `UNIQUE(contract_id, role, name)`.
- Relationship: Contract 1:N ContractParty.
- Contract deletion cascades to ContractParty rows.
- Party data belonging to a `DRAFT` Contract is reviewable/untrusted until the Contract becomes `ACTIVE`.

## `contract_terms` — APPROVED

```text
contract_terms
--------------
contract_id
start_date
end_date
contract_duration
renewal_type
pricing_model
electricity_data
```

- `contract_id` = PostgreSQL `UUID` PK + FK → `contracts.document_id`.
- `start_date` and `end_date` = PostgreSQL `DATE`, nullable.
- The extraction candidate emits known contract dates as ISO 8601 calendar dates (`YYYY-MM-DD`), matching the strict `LocalDate` mapping. Incomplete or ambiguous dates remain null.
- `contract_duration` = PostgreSQL `INTEGER`, nullable, representing **number of months**; if present it must be greater than zero.
- `renewal_type` = `VARCHAR(30)`, nullable; allowed values: `FIXED_TERM`, `AUTO_RENEW`, `OPEN_ENDED`.
- `pricing_model` = `VARCHAR(30)`, nullable; allowed values: `FIXED`, `VARIABLE`, `DYNAMIC`.
- `electricity_data` = PostgreSQL `JSONB`, nullable.
- `end_date` cannot be before `start_date` when both are present.
- `start_date`, `end_date`, `contract_duration`, and `renewal_type` describe the overall electricity supply contract term. Tariff schedule and fixed-price validity dates are distinct and are not mapped to these fields; the current candidate model has no tariff-validity-date fields.
- The root `contract_terms.pricing_model` and nested `electricity_data.pricing.model` are separate stored properties. When the overall pricing model is known, extraction emits the same value in both, even if no rates are stated; current local schema validation checks each property's shape independently and does not enforce cross-field equality.
- Relationship: Contract 1:1 ContractTerms.
- Contract deletion cascades to ContractTerms.
- Terms belonging to a `DRAFT` Contract are reviewable/untrusted until the Contract becomes `ACTIVE`.

### Electricity-specific data

`electricity_data` is country-neutral JSONB. The MVP structure is:

```text
electricity_data
├── consumption
│   └── annual_kwh
├── pricing
│   ├── model
│   ├── rates[]
│   │   ├── value
│   │   ├── unit
│   │   ├── currency
│   │   ├── period (optional)
│   │   ├── time_period (optional)
│   │   └── index (optional)
├── fixed_charges[]
│   ├── type
│   ├── amount
│   ├── currency
│   └── period
├── return_delivery
│   ├── compensation
│   └── fees
└── discounts[]
    ├── type
    ├── amount/value
    ├── currency (when applicable)
    └── period (when applicable)
```

Approved electricity concepts:

- `pricing.model` supports the approved pricing-model concepts `FIXED`, `VARIABLE`, and `DYNAMIC`.
- Each pricing rate contains `value`, `unit`, and `currency`, with optional `period`, `time_period`, and `index` where relevant. `rates[].period` is distinct from the tariff band in `rates[].time_period` and the billing period in `fixed_charges[].period`; populate a rate period only when the contract explicitly states it for that rate. Do not copy either of the other periods into it.
- Fixed charges contain `type`, `amount`, `currency`, and `period`.
- Initial fixed-charge type: `SUPPLY`, for an explicit supplier-side supply/standing charge. Network, distribution, transport, tax, levy, and tax-reduction amounts have no supported fixed-charge type.
- `return_delivery` separates export compensation from export fees.
- Discount types: `WELCOME`, `LOYALTY`, `PROMOTIONAL`, `OTHER`.
- No generic `market_specific` escape hatch is introduced. JSONB is already extensible; genuinely new concepts should be modeled deliberately when justified.

The complete supported field set is defined by the [electricity candidate schema](../../backend/src/main/resources/extraction/electricity-contract-candidate-v1.schema.json); this summary is navigational and must stay aligned with that schema. No additional generic electricity CHECK constraints are introduced beyond the explicitly approved relational value sets above; JSON structure validation belongs to the application/schema validation layer.

## `events` — APPROVED

```text
events
------
id
user_id
document_id
type
description
event_date
amount
currency
status
source_reference
created_at
```

- `id` = PostgreSQL `UUID` PK.
- `user_id` = FK → `users.id`, NOT NULL.
- `document_id` = FK → `documents.id`, NOT NULL, with `ON DELETE CASCADE`.
- `type` = `VARCHAR(40)`, NOT NULL, CHECK:
  `PAYMENT_DUE`, `CONTRACT_EXPIRING`, `CONTRACT_RENEWING`, `CANCELLATION_DEADLINE`, `PRICE_CHANGE`, `WARRANTY_EXPIRING`, `REQUIREMENT`, `OTHER`.
- `description` = `TEXT`, NOT NULL.
- `event_date` = `TIMESTAMPTZ`, NOT NULL.
- `amount` = `NUMERIC(15,2)`, nullable; must be non-negative when present.
- `currency` = `VARCHAR(3)`, nullable; required when `amount` is present.
- `status` = `VARCHAR(20)`, NOT NULL, CHECK `ACTIVE` / `COMPLETED` / `DISMISSED` / `CANCELLED`.
- `source_reference` = `TEXT`, nullable.
- `created_at` = `TIMESTAMPTZ`, NOT NULL.
- Every MVP actionable event has a source Document.
- Monitoring occurrence Events use a deterministic non-null `source_reference`; V18 adds a partial unique index on `(document_id, type, source_reference)` so repeated runs cannot create duplicate occurrences. Events with a null source reference retain the existing behavior.

## `monitoring_rules` — REMOVED FROM MVP

Monitoring rules are not persisted as database entities in the MVP.

Dedicated monitoring service classes implement each monitoring concern and evaluate trusted `ACTIVE` Contract data to generate actionable Events and/or Recommendations. `DRAFT` Contracts are not eligible for monitoring.

The monitoring execution foundation uses a focused read model containing the owning User context, Contract identity/type, approved parties, and approved ContractTerms. The server-side query joins `contracts` to `documents`, filters `type = ELECTRICITY` and `status = ACTIVE`, and never accepts client-supplied ownership or lifecycle state. Evaluation time is supplied by the application clock; no monitoring timestamp is obtained through hidden wall-clock calls.

The first dedicated services are `ContractExpiryMonitoringService` and `ContractRenewalMonitoringService`. `FIXED_TERM` with a resolved end date produces `CONTRACT_EXPIRING`; `AUTO_RENEW` with a resolved end date produces `CONTRACT_RENEWING`. An explicit `end_date` wins; otherwise a positive approved `contract_duration` is added to `start_date`. Missing dates, invalid duration, and `OPEN_ENDED` produce no event. Events are `ACTIVE`, contain no amount/currency, retain the owning user/document, and are deduplicated by the deterministic occurrence reference.

Example conceptual structure:

```text
MonitoringService
├── ContractExpiryMonitoringService
├── PaymentDueMonitoringService
├── PriceChangeMonitoringService
└── ContractRenewalMonitoringService
```

## `recommendations` — APPROVED

```text
recommendations
---------------
id
user_id
document_id
type
data
created_at
expires_at
```

- `id` = PostgreSQL `UUID` PK.
- `user_id` = FK → `users.id`, NOT NULL, with `ON DELETE CASCADE`.
- `document_id` = FK → `documents.id`, NOT NULL, with `ON DELETE CASCADE`.
- `type` = `VARCHAR(50)`, NOT NULL, CHECK:
  `PRICE_OPPORTUNITY`, `CONTRACT_EXPIRY`, `RENEWAL`, `TERMS_CHANGE`, `DISCOUNT_EXPIRY`, `ACTION_REQUIRED`.
- `data` = `JSONB`, NOT NULL, containing structured recommendation facts, not presentation-ready user text.
- Common recommendation data contains `reason`, `evidence`, `impact`, and `action`.
- Impact types: `ANNUAL_COST`, `ANNUAL_SAVING`, `PRICE_CHANGE`, `NONE`.
- Action types: `REVIEW`, `COMPARE`, `RENEW`, `CANCEL`, `CONTACT_PROVIDER`, `TAKE_NO_ACTION`.
- `SWITCH_PROVIDER` is not an approved action type for MVP.
- `created_at` = `TIMESTAMPTZ`, NOT NULL.
- `expires_at` = `TIMESTAMPTZ`, nullable.
- The presentation layer constructs the user-facing message.
- Recommendations are persisted because monitoring runs asynchronously and recommendations must remain available between user sessions.
- There is no uniqueness constraint on `(document_id, type)`; the same recommendation type may be generated again after an earlier one expires.

### Owner-scoped contract read model

`GET /v1/contracts` is a bounded SQL projection over the authenticated owner's Uploads and Documents, with optional Contract/party/terms data. Completed Upload attempts without a surviving Document are omitted; uploads still before Document creation remain visible. It does not persist product lifecycle labels: it maps existing upload/document/contract states into the user-level collection order defined in [DATA-FLOW.md](DATA-FLOW.md). A single collection query includes a correlated existence check for current Recommendations, avoiding per-row lookups. `V21` adds owner/time/ID indexes for collection and contract monitoring reads.

`GET /v1/contracts/{documentId}/overview` requires an owned `ACTIVE` Contract. Events and Recommendations are queried with both authenticated `user_id` and `document_id`; current Recommendation evidence is matched to its source Event using the structured type/reference in Recommendation data. Those internal source references are not returned by the API. This read boundary follows the category mapping and history rules in [DATA-FLOW.md](DATA-FLOW.md).

## `notifications` — APPROVED / Milestone 8 architecture

```text
notifications
-------------
id
recommendation_id
notification_type
data
created_at
```

- `id` = PostgreSQL `UUID` PK.
- `recommendation_id` = FK → `recommendations.id`, NOT NULL, `ON DELETE CASCADE`, and `UNIQUE`. It is the sole logical source identity for the Notification.
- `notification_type` = `VARCHAR(50)`, NOT NULL, currently CHECK `RECOMMENDATION` only. This is a semantic notification kind, not a delivery channel.
- `data` = JSONB, NOT NULL, containing only the bounded structured Recommendation facts required by the repository-owned renderer. It contains no finished user-facing copy, document/OCR/LLM content, prompts, credentials, storage keys, or provider internals.
- `created_at` = `TIMESTAMPTZ`, NOT NULL.
- No `owner_id` is stored. Ownership is inherited through `recommendation_id → recommendations.document_id → documents.user_id` and is never independently mutable.
- Notifications have no read/unread, dismissed, preference, or frontend/API state in MVP. The existing Recommendations UI remains the in-application experience.
- A Recommendation creates at most one logical Notification. Repeated generation uses the Recommendation uniqueness boundary and must converge without replacing the original semantic snapshot.

## `notification_deliveries` — APPROVED / Milestone 8 architecture

```text
notification_deliveries
-----------------------
id
notification_id
channel
status
destination
attempt_count
last_attempt_at
in_flight_at
lease_until
claim_token
delivered_at
last_failure_at
failure_code
created_at
updated_at
```

- `id` = PostgreSQL `UUID` PK.
- `notification_id` = FK → `notifications.id`, NOT NULL, `ON DELETE CASCADE`.
- `channel` = `VARCHAR(20)`, NOT NULL, currently CHECK `EMAIL` only. The logical Notification remains channel-neutral; additional channels require a later explicit decision.
- `status` = `VARCHAR(20)`, NOT NULL, CHECK `QUEUED`, `IN_FLIGHT`, `DELIVERED`, or `FAILED`.
- `destination` = the trusted normalized email address needed by the EMAIL transport, resolved from the trusted owner identity/account boundary during generation. It is not accepted from a notification API request, included in queue messages, or written to logs/metrics. If no usable trusted destination exists, the delivery becomes a non-retryable terminal failure and the worker does not call SES.
- `attempt_count` = non-negative `INTEGER`, NOT NULL, default `0`; it records worker claims, not a hard-coded retry limit.
- `last_attempt_at`, `delivered_at`, `last_failure_at`, `lease_until`, and `in_flight_at` use nullable `TIMESTAMPTZ`; `created_at` and `updated_at` use NOT NULL `TIMESTAMPTZ`.
- `claim_token` is a nullable opaque UUID replaced on each atomic claim. A worker may update a delivery only while its claim token is current; an expired `lease_until` makes `IN_FLIGHT` eligible for guarded return to `QUEUED` and reclaim.
- `failure_code` is a nullable bounded operational code, not a raw exception, provider response, email address, or message body.
- `UNIQUE(notification_id, channel)` enforces one delivery record for the MVP EMAIL channel.
- `QUEUED → IN_FLIGHT → DELIVERED` is the successful lifecycle. Transient failure returns to `QUEUED`; non-retryable failure or bounded retry exhaustion becomes `FAILED` and follows the notification DLQ/investigation path. Attempt/lease limits and queue visibility/redrive settings are deployment configuration wired through Terraform and application properties, not schema constants. The Terraform module guide describes reusable configuration examples; local Compose does not exercise cloud recovery ownership.
- Delivery lifecycle is separate from logical Notification identity. Queue redelivery may repeat delivery work but never creates another Notification or delivery record.

### Recommendation-notification rendering contract — APPROVED

The repository-owned renderer consumes a typed view, not arbitrary Notification JSON. Version 1 contains `schemaVersion = 1`, the Recommendation UUID and type, and only the existing bounded semantic fields: `reason.type` (`CONTRACT_EXPIRY` or `RENEWAL`), `evidence.event_type` (`CONTRACT_EXPIRING` or `CONTRACT_RENEWING`), `evidence.event_date` (UTC RFC 3339), `impact.type` (`NONE`), and `action.type` (`REVIEW`). Unknown versions or values are rejected. The view contains no owner, address, document/OCR/AI text, prompt, provider data, or finished copy; templates turn these facts into email wording.
- Expired recommendations are retained as history rather than deleted.
- Monitoring-derived recommendations use a deterministic ID derived from the authoritative monitoring Event ID. Repository `saveIfAbsent` uses the recommendation primary key with `ON CONFLICT DO NOTHING`, so repeated runs cannot duplicate one Event occurrence while a later Event occurrence can create a distinct record for the same document and type.
- Current deterministic recommendation mappings are `CONTRACT_EXPIRING` → `CONTRACT_EXPIRY` and `CONTRACT_RENEWING` → `RENEWAL`. They use structured `reason`, `evidence`, `impact`, and `action` facts with `impact.type = NONE` and `action.type = REVIEW`; no presentation text, savings, price, or switching claim is persisted.
- The v1 read API exposes owner-scoped persisted recommendations. Default reads include rows whose `expires_at` is absent or after the read evaluation time; `includeHistory=true` explicitly includes expired rows. Query ordering is `created_at DESC, id DESC`. Events are owner-scoped and ordered by `event_date DESC, id DESC`; the API does not expose internal `source_reference` or database ownership fields.

## `processing_attempts` — APPROVED

```text
processing_attempts
-------------------
id
document_id
attempt_number
status
started_at
completed_at
error_code
```

- `id` = PostgreSQL `UUID` PK.
- `document_id` = FK → `documents.id`, NOT NULL, with `ON DELETE CASCADE`.
- `attempt_number` = `INTEGER`, NOT NULL, greater than zero.
- `status` = `VARCHAR(20)`, NOT NULL, CHECK `RUNNING` / `SUCCEEDED` / `FAILED`.
- `started_at` = `TIMESTAMPTZ`, NOT NULL.
- `completed_at` = `TIMESTAMPTZ`, nullable.
- `error_code` = `VARCHAR(100)`, nullable.
- `UNIQUE(document_id, attempt_number)`.
- A retry creates a new processing-attempt row; there is no `RETRYING` status.
- Application processing attempts are distinct from SQS message-delivery retries.
- Do not store prompts, LLM responses, OCR text, or large error messages here merely for convenience.

## `audit_events` — APPROVED

```text
audit_events
------------
id
user_id
resource_type
resource_id
action
created_at
```

- `id` = PostgreSQL `UUID` PK.
- `user_id` = FK → `users.id`, NOT NULL.
- `resource_type` = `VARCHAR(50)`, NOT NULL, CHECK `USER` / `DOCUMENT` / `CONTRACT` / `EVENT` / `RECOMMENDATION` / `UPLOAD`.
- `resource_id` = PostgreSQL `UUID`, NOT NULL.
- `action` = `VARCHAR(100)`, NOT NULL. No database CHECK constraint is imposed on actions; typical values include `CREATED`, `UPDATED`, `APPROVED`, `DELETED`, `VIEWED`, `DISMISSED`, and `COMPLETED`.
- `created_at` = `TIMESTAMPTZ`, NOT NULL.
- System-generated events use the reserved system user in `users`.
- Audit events are append-only under normal application operation.
- Meaningful Contract draft edits and approval may be audited where useful, but the audit table does not store full before/after Contract payloads merely for convenience.
- Do not store request payloads, document contents, prompts, or other sensitive content.
- No additional index is introduced until actual audit-query patterns are defined.

## `usage_records` — APPROVED

Durable accounting source of truth for **all money-consuming external provider calls**, including OCR, LLM and future paid processing services.

```text
usage_records
-------------
id
user_id
document_id
operation
provider
service
usage_data
cost_amount
currency
cost_status
pricing_version
usage_event_id
created_at
```

- `id` = PostgreSQL `UUID` PK.
- `user_id` = FK → `users.id`, NOT NULL, with `ON DELETE CASCADE`.
- `document_id` = FK → `documents.id`, NOT NULL, with `ON DELETE CASCADE`.
- `operation` = `VARCHAR(100)`, NOT NULL.
- `provider` = `VARCHAR(50)`, NOT NULL.
- `service` = `VARCHAR(100)`, NOT NULL.
- `usage_data` = `JSONB`, NOT NULL, containing provider-specific raw usage metrics.
- `cost_amount` = `NUMERIC(15,4)`, NOT NULL, non-negative.
- `currency` = `VARCHAR(3)`, NOT NULL.
- `cost_status` = `VARCHAR(20)`, NOT NULL, one of `CALCULATED`, `UNKNOWN`, or legacy `LEGACY` for records written before pricing calculation was introduced. `UNKNOWN` uses `0.0000` only as a storage-compatible sentinel and must not be interpreted as a zero-price claim.
- `pricing_version` = `VARCHAR(100)`, nullable; required when `cost_status = CALCULATED` and identifies the immutable local price snapshot used for the calculation.
- `usage_event_id` = PostgreSQL `UUID`, NOT NULL, `UNIQUE`.
- `created_at` = `TIMESTAMPTZ`, NOT NULL.
- There is no `attempt_number`: each actual paid provider call creates its own usage record.
- No provider call means no provider usage charge.
- Paid retries create additional usage records when the provider is actually called.
- Money-consuming implementations use the common Accounting abstraction.
- Usage events are sent asynchronously to Usage SQS.
- Accounting Worker persists usage records idempotently using `usage_event_id`.
- **No Usage Outbox database table.**
- Accounting success/failure does not determine business-processing success.

### Cost calculation boundary

Raw `usage_data` is converted to monetary cost in the application accounting boundary before the Usage Event is published. Provider invocation adapters do not contain pricing tables. The initial catalog supports Textract `DetectDocumentText` pages and Bedrock Nova 2 Lite input/output tokens, selecting by effective timestamp plus region or inference profile/model. Pricing is local, explicitly versioned configuration; runtime web/provider-price lookups and FX conversion are not used.

The calculated amount is rounded with `HALF_UP` to four fractional digits before persistence, matching `NUMERIC(15,4)`. The selected `pricing_version`, currency, status and raw billable units are persisted together. If usage is unavailable or no effective matching price exists, the event is marked `UNKNOWN`; no monetary amount is fabricated.

Recommended indexes:

```text
INDEX usage_records_document_idx
    ON usage_records(document_id)

INDEX usage_records_user_created_idx
    ON usage_records(user_id, created_at DESC)

INDEX usage_records_provider_created_idx
    ON usage_records(provider, created_at DESC)
```

## Relationships — current approved model

```text
User
 ├──< UserIdentity
 ├──< Document
 ├──< Event
 ├──< Recommendation
 ├──< ProcessingAttempt
 ├──< UsageRecord
 └──< AuditEvent

Upload
 ── successful ingestion ──> Document

Document
 ├── 1 : 1 Contract (at most one; Contract.document_id is PK/FK)
 ├──< Event
 ├──< Recommendation
 ├──< ProcessingAttempt
 └──< UsageRecord

Recommendation
 └── 1 : 0..1 Notification (unique recommendation_id)

Notification
 └──< NotificationDelivery (unique notification_id + channel)

Contract
 ├── lifecycle: DRAFT → ACTIVE → ARCHIVED
 ├──< ContractParty
 └── 1 : 1 ContractTerms

Monitoring services
 ── evaluate ACTIVE Contract data ──> Event / Recommendation
```

## Consistency rules

1. One Document produces at most one Contract; `contract.document_id` is PK/FK.
2. Successful automated processing creates `Contract.DRAFT` and transitions Document to `SUCCESS` atomically.
3. Explicit user approval transitions the same Contract from `DRAFT` to `ACTIVE`; approval does not create another Contract.
4. Only `ACTIVE` Contracts are trusted inputs for monitoring, recommendations and deterministic financial outcomes.
5. The MVP persists no second raw/candidate extraction copy; user edits operate on the `DRAFT` Contract itself.
6. User ownership is derived from authenticated identity, never frontend-supplied ownership.
7. `provider + provider_subject` is unique for a UserIdentity.
8. Document deletion is a PostgreSQL hard delete; no `deleted_at`.
9. S3 deletion is asynchronous through dedicated SQS; failures retry and eventually reach the deletion DLQ.
10. Usage accounting is idempotent by unique `usage_event_id`.
11. Usage accounting does not determine business-processing success.
12. Original document content is stored in private S3, not ordinary database rows.
13. Sensitive document/AI content is not written to logs.
14. Only product-useful/actionable information is retained.
15. Recommendations are persisted as structured data; presentation text is generated by the presentation layer.
16. Monitoring rules are service implementations, not persisted MVP entities.
17. Dependent Document data is removed through database FK cascades during hard deletion.
18. A Notification can only be generated from a persisted Recommendation; Events never enter notification delivery directly.
19. One Recommendation produces at most one logical Notification, and one Notification has at most one MVP EMAIL delivery record.
20. Notification ownership is inherited through Recommendation → Contract/Document → User; Notification and NotificationDelivery have no independently mutable owner.
21. Notification persistence is independent of SES availability; the durable queued delivery intent survives without a Notification outbox table. The scheduled recovery dispatcher exists but should not be enabled until it supports bounded claims/batches. Cloud publication alarms and operator recovery are not exercised by local Compose.
22. Notification delivery is asynchronous and at-least-once; atomic claim tokens and leases recover worker crashes, while the delivery record and worker idempotency boundary prevent duplicate logical Notifications. An interrupted external send may remain an operational at-least-once ambiguity.
23. Hard deletion cascades Recommendation → Notification → NotificationDelivery. A stale queued message is acknowledged when its delivery relationship no longer exists.

## Retention and deletion status

No universal or general customer-data-category retention schedule has been approved. The specific reviewed 16-day quarantine-object safety net is limited to `quarantine/incoming/`; it is not a row-retention rule or legal/GDPR retention period. The repository does not implement a general expiry/anonymization job, and this document does not assert legal compliance or automatic removal for data whose policy is unresolved. An explicit user-requested account deletion is a full-erasure operation, not a general retention schedule or exception.

- **Documents:** an owner-authorized per-document deletion hard-deletes the Document and dependent rows, then queues private-object cleanup. See [DOCUMENT-DELETION.md](DOCUMENT-DELETION.md).
- **Accounts and identities:** the application implements full erasure across application rows, identity mappings, Cognito, and private objects. See [DOCUMENT-DELETION.md](DOCUMENT-DELETION.md) for the deletion lifecycle. No other category retention policy is implied.
- **Uploads:** upload records are retained as history. `expires_at` only derives the API state for an expired `UPLOADING` attempt; it does not delete the row or object. Rejected ingestion and explicit per-Document/account deletion retain their active cleanup paths. Managed object-storage lifecycle policies may provide a separate cleanup safety net, but their retention settings are deployment-specific and are not reproduced by the local Compose walkthrough.
- **Processing attempts, usage records, and audit events:** the schema and application persist these records for operational/accounting/security purposes until a user-requested account deletion hard-deletes the account-linked rows. No generic timed deletion or anonymization is implemented; category-specific periods and lawful exceptions remain an owner policy decision. Only the non-identifying system-owned account-deletion completion record survives that specific operation.
- **Events and recommendations:** `expires_at` removes expired Recommendations from default reads; expired Recommendations remain available as history. It does not delete them. Events and other related rows are removed through the implemented Document deletion cascade.
- **Notifications and delivery records:** these are removed by the Document relationship cascade when their source Document is deleted and are also purged across the account during account deletion. No independent timed-retention cleanup is implemented.

Managed object-storage versioning and lifecycle retention are deployment-specific controls. The local Compose edition does not model them; explicit application deletion remains the mechanism for user-requested removal.

## Migrations

```text
Git
 ├── Java code
 ├── OpenAPI
 ├── Terraform/OpenTofu
 └── SQL migrations
          ↓
       Flyway
          ↓
     PostgreSQL
```

Applied migrations are immutable; corrective changes use new migrations. Prefer backward-compatible expand/contract changes when application versions overlap.

The application schema is implemented by versioned plain SQL migrations under `src/main/resources/db/migration/`; Flyway applies them during backend startup. See [Implementation Baseline](IMPLEMENTATION-BASELINE.md) for the current repository setup. Schema changes must evolve forward rather than rewriting applied migrations.

## Implementation baseline status

The committed SQL migrations are the executable schema authority. This overview describes relationships and data-handling intent; consult the migrations and persistence code for exact constraints and indexes.
