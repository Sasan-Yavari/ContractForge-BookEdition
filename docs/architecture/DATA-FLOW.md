# ContractForge — Data Flow

> **Responsibility:** movement of data across major system and trust boundaries. This document deliberately stays at cross-boundary level. [PROCESSING-FLOW.md](PROCESSING-FLOW.md) owns processing stages and failure semantics; [DATA-MODEL.md](DATA-MODEL.md) owns entities/constraints; [reader edition implementation baseline](IMPLEMENTATION-BASELINE.md) owns current code evidence.

## Upload, trust and processing

```text
User → Frontend → authenticated API
                    ├→ PostgreSQL Upload
                    └→ private S3 quarantine
                           ↓ ObjectCreated
                       ingestion SQS → ingestion worker
                           ↓ metadata + GuardDuty tag check
                    malware/content gate
                       ├→ rejected + cleanup
                       └→ trusted promotion
                              ↓
                   Document finalization → processing SQS
                              ↓
                   processing worker/application
                              ↓
                    Contract.DRAFT in PostgreSQL
                              ↓
                    user review/edit/approval
                              ↓
                    Contract.ACTIVE (trusted)
```

An owner can later correct supported fields on the same confirmed Contract. The version-guarded update preserves `ACTIVE`, applies the review validator, and records the owner action without copying sensitive before/after values into audit. Corrected trusted terms are refreshed through the existing deterministic monitoring rules in the same transaction: superseded generated Events are cancelled, linked Recommendations expire into contract history, and still-current results are persisted again. The [reader edition decisions](DECISIONS.md) owns the delivery consequence and rationale.

The original PDF stays in private object storage. Review and confirmed-contract experiences request owner-scoped access through `GET /v1/documents/{documentId}/original`; after checking the object exists, the API returns only a five-minute inline-PDF presigned URL. Missing source objects return the same non-disclosing not-found response as inaccessible documents; storage failures return a generic temporary-unavailable response.

The API establishes ownership from authenticated server context. Original binaries remain in private object storage; database records hold controlled metadata and business state. The processing subsystem’s detailed execution is specified separately in [PROCESSING-FLOW.md](PROCESSING-FLOW.md).

After authentication and server-side application-user resolution, upload initiation passes a process-local per-user rate gate before it creates the Upload record or presigned storage capability. A per-user quota rejection returns `429 Too Many Requests`; a full bounded tracker rejects a new identity with `503 Service Unavailable`. Both responses include `Retry-After` and create neither resource. The gate does not derive a limit key from client addresses or forwarding headers. Upload initiation also records the caller-selected supported contract type alongside the source filename. That identity follows the upload into the Document and is returned through the owner-authorized processing-status read model before confirmation.

## Contract-centric owner reads

The authenticated owner collection combines upload attempts and persisted Documents into one lifecycle view. It uses the caller-selected type and filename before confirmation, and exposes provider, pricing model and end date only for an explicitly confirmed Contract. Its user-facing order is Needs review, Couldn't process, Needs attention, confirmed, then processing; items within each group are newest first. These are read-model states, not new persisted lifecycle values.

The contract overview is available only for an owned, confirmed Contract. Current, unexpired Recommendations supply **Needs attention**. Active Events without a current matching Recommendation supply **Things to know**; matching Events are suppressed there to avoid duplicate current items. Closed Events and expired Recommendations feed a newest-first, contract-specific history, with related Event and Recommendation evidence combined into one entry. The mapping is deterministic and retains source references only inside the server-side read boundary.

## Usage and accounting

```text
paid provider call → AccountingPort → usage event → Usage SQS
                                             ↓
                                      accounting worker
                                             ↓
                                      usage_records
```

Usage crosses an asynchronous boundary so accounting failure does not fail successful business processing. Each provider-reached call is separately observable and consumption is idempotent by `usage_event_id`. The data model owns the persisted accounting structure; processing flow owns the provider-call handoff.

## Monitoring, recommendations and notification intent

```text
trusted ACTIVE Contract
          ↓
monitoring application
          ↓
persisted Event
          ↓
persisted Recommendation
          ↓
Notification + NotificationDelivery intent
          ↓ (after commit)
provider-neutral notification queue
```

Only trusted `ACTIVE` Contract data enters monitoring. Monitoring produces factual Events; Recommendations are derived from persisted Events; Notifications are derived only from persisted Recommendations. No downstream stage reconstructs an earlier stage’s rules. The detailed data structures and notification lifecycle are owned by [DATA-MODEL.md](DATA-MODEL.md), with implementation evidence in [reader edition implementation baseline](IMPLEMENTATION-BASELINE.md).

## Deletion

```text
authorized delete
      ↓
PostgreSQL hard delete + dependent cascades
      ↓ commit
S3-delete message → deletion SQS → deletion worker → private S3
                                      ├→ success/ack
                                      └→ retry/redrive → deletion DLQ
```

Per-document database and object-storage deletion are intentionally not one distributed transaction. The approved deletion strategies and operational recovery boundaries are recorded in [DOCUMENT-DELETION.md](DOCUMENT-DELETION.md); that document owns deletion-specific details while this document shows their system position.

Account deletion crosses PostgreSQL, private S3, SQS, and Cognito:

```text
DELETE /v1/account → disable User + scrub email + persist job/object manifest
                                      ├→ disable Cognito
                                      └→ account-deletion SQS
                                             ↓ wait for upload capabilities
                                     recapture keys + purge relational data
                                             ↓
                              retry-delete quarantine + trusted S3 objects
                                             ↓
                                    delete Cognito + wait token fence
                                             ↓
                          system-owned completion audit + remove identity/User
```

Every provider/subject mapping stays in place without email during the old-token fence so it resolves to a disabled User instead of creating an account through JIT provisioning; Cognito disable and delete cover every mapping attached to the User. Application and database active-account checks stop late async writes; late ingestion cleans deterministic object keys after User removal. The job remains durable until object deletion and Cognito completion succeed. Detailed retries, leases, DLQ recovery, and evidence limits are owned by [DOCUMENT-DELETION.md](DOCUMENT-DELETION.md).

## Cross-boundary security and failure rules

- Authentication and resource ownership are resolved server-side; caller-supplied ownership is not trusted.
- Untrusted upload and AI/extracted data cross validation and trust boundaries before downstream business use.
- Sensitive document, OCR, AI and credential data does not cross into ordinary logs, metrics or queue payloads.
- Durable state transitions are transactional where required; asynchronous external work has explicit retry, redrive and terminal handling.
- Queue redelivery is not a new business record; idempotency belongs at the receiving persistence boundary.

For detailed invariant evidence, use [INVARIANTS.md](INVARIANTS.md). For package ownership and implementation navigation, use [CODEBASE-MAP.md](CODEBASE-MAP.md).
