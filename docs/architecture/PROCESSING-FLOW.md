# ContractForge — Processing Flow

> **Responsibility:** the document-processing subsystem only. This document owns the ordered processing stages, processing-specific failure/retry semantics and the accounting handoff. System-wide movement belongs in [DATA-FLOW.md](DATA-FLOW.md); persistent structures belong in [DATA-MODEL.md](DATA-MODEL.md); current implementation evidence belongs in [IMPLEMENTATION-BASELINE.md](IMPLEMENTATION-BASELINE.md).

## 1. Accepted document to processing input

The authenticated upload path validates the request and writes directly to private S3 quarantine storage. In Development and Production, the S3 `ObjectCreated` notification for `quarantine/incoming/` is sent to the ingestion SQS queue. `SqsUploadIngestionConsumer` decodes the S3 envelope, verifies the configured bucket and exact server-generated UUID key, and translates only those facts into `UploadIngestionService.ObjectCreatedCommand`. The application reloads the Upload from PostgreSQL and verifies that the event key exactly matches the persisted object key; event fields never supply user identity or ownership authority.

The worker verifies actual object metadata and bounded PDF signature bytes, then reads GuardDuty's structured `GuardDutyMalwareScanStatus` tag. GuardDuty `NO_THREATS_FOUND` permits the existing application checkpoint/finalization path. A missing or pending tag and S3/provider failures leave the message unacknowledged so SQS retries from the durable Upload checkpoint. Malicious or otherwise invalid uploads use the existing terminal rejection and quarantine cleanup path; successful and idempotently repeated completions are acknowledged. Malformed/unprocessable event envelopes remain unacknowledged for queue redrive to the ingestion DLQ. Duplicate ObjectCreated delivery is expected and handled by the existing guarded checkpoint transitions.

Queue visibility and redrive limits are deployment configuration, not application state. Terminal messages are retained in the ingestion DLQ; cloud alarm routing, operator recovery, and environment-specific timing are outside the local Compose walkthrough.

Definitive invalid or malicious content becomes terminal `Upload.REJECTED`; transient storage/provider failures remain retryable from the durable checkpoint. Only a trusted promoted Document is published to document-processing SQS.

Upload initiation requires the supported `ContractType` and persists it with the validated original filename before the direct-upload capability is issued. Finalization copies both values to the Document. The owner-authorized processing-status read model returns that identity while the Document is processing, failed or awaiting confirmation; it does not derive the intended type from extracted content.

## 2. Bounded extraction and model-input preparation

Local direct extraction and OCR have a document-resource boundary separate from the model-input boundary. The extraction path remains bounded by the trusted object byte limit, page limit and `max-output-characters` safety limit; these limits protect parsing and memory use and are not a proxy for the LLM context budget. The configured normal LLM-input bound is 200,000 characters, independently configurable through `CONTRACTFORGE_EXTRACTION_MAX_LLM_INPUT_CHARACTERS`. Text at or below that bound reaches the provider unchanged. Only oversized text passes through the provider-neutral `ModelInputPreparationService`.

The preparation step removes exact repeated long lines or three-line blocks while preserving the first occurrence and every distinct span in source order. For direct PDF extraction, the adapter preserves form-feed page boundaries. When the prepared text remains oversized, every page receives an equal share of the model budget; structurally dense lines containing digits are retained first, and the remaining page budget is distributed across beginning, middle and end context windows with an omission marker. This uses document structure only: it does not use natural-language or contract-type vocabulary, sample only early pages, or discard unique prose by business relevance guess. All retained structured lines must fit within the bound; if page boundaries are unavailable or that required material cannot fit, processing fails permanently with the privacy-safe `RESOURCE_LIMIT_LLM_INPUT_CHARACTERS` classification before any LLM invocation. Prepared text is never persisted or logged. Preparation is local and free, so it creates no accounting event.

This is a conservative model-input and cost boundary, without an additional extraction or summarization provider call. It preserves source order and keeps document text untrusted; Bedrock remains behind `ContractExtractionPort` and its trusted system instructions remain separate from the prepared document content.

## 3. Processing worker

The worker consumes the server-created Document identifier and loads the trusted Document. It performs the required local extraction/OCR and AI extraction through provider-neutral application ports, validates the bounded candidate deterministically, and maps only approved electricity fields into one `Contract.DRAFT`, parties and terms.

The Bedrock adapter supplies the versioned candidate schema in its trusted system instructions and validates the returned JSON against that schema locally before mapping it. The trusted instructions direct the provider to omit optional properties without a supported value and use JSON null only when the schema allows null. Nova 2 Lite does not support Bedrock structured outputs, so the adapter does not send the schema through `outputConfig`. It accepts raw JSON as before or unwraps exactly one whole-response Markdown code fence, optionally labelled `json`, before parsing normally; prose, multiple fences and invalid JSON fail closed. Before the unchanged strict schema validation, the adapter treats explicit JSON null only at optional `electricity_data.consumption.annual_kwh` as omission, Every other value remains subject to its existing schema and business validation. Strict business validation still fails closed before persistence.

On extraction failures, the worker emits an internal `processing_failure_diagnostic` event with a fixed stage and exception category. Stages distinguish Bedrock request/Converse/response extraction, JSON parsing, local schema validation, candidate mapping, business validation, aggregate construction and other runtime failures. Local schema-validation failures also carry a fixed violation classification (`required_missing`, `additional_property`, `type_mismatch`, `enum_mismatch` or `const_mismatch`) and a bounded location from the loaded extraction schema. Type mismatches also carry bounded expected and actual JSON type classifications. Locations never use provider-supplied property names, and type fields never include provider values. The event excludes exception messages, document or provider data, IDs, credentials and token values. It does not change processing error codes, retry behavior, schema validation or provider accounting.

The `DRAFT` Contract is the reviewable representation of extracted data. It is not trusted for monitoring, recommendations or deterministic financial outcomes. The user later edits or explicitly approves that same Contract; this trust transition is owned by the Contract review/application boundary and described in [DATA-MODEL.md](DATA-MODEL.md).

## 4. Atomic completion and attempts

Successful processing creates the `DRAFT` Contract and transitions the Document to `SUCCESS` atomically. The one-document/one-contract rule is enforced at the application/database boundary. Processing attempts are separate from SQS delivery attempts: duplicate delivery while work is running does not create another application attempt.

Application failures finalize the current attempt as `FAILED` and remain visible to the queue retry/DLQ policy. A processing DLQ transition is guarded and idempotent; deleted or already-terminal Documents are safely acknowledged. A malformed DLQ message without a trustworthy Document ID remains visible for operator investigation. A duplicate delivery while a recent attempt is still running remains unacknowledged so SQS can redeliver it after the stale-attempt threshold; the next delivery can then recover the abandoned attempt. The processing queue uses a 900-second visibility timeout, an application stale-attempt threshold of `PT1H`, and a redrive threshold of eight receives. The timing check conservatively reserves one entire visibility interval for the first `RUNNING` attempt to be recorded and leaves a 30-minute margin beyond the stale threshold before redrive, so recovery does not depend on a receive landing exactly at the one-hour boundary.

The processing-status API returns a nullable safe `failureCategory` for the latest terminal failure: `RETRYABLE` for explicitly retryable application failures, `DETERMINISTIC_DOCUMENT` for known malformed-PDF and resource-limit failures, and `UNKNOWN` when evidence does not justify either conclusion. Internal error codes remain private and are mapped through an allowlist. The current extraction result does not reliably establish document/type mismatch, so such outcomes remain `UNKNOWN`; the API never infers mismatch from missing fields or an invalid model candidate.

## 5. Paid operations and accounting handoff

Each actual paid provider call is an independently observable operation:

```text
provider call → AccountingPort → usage event → Usage SQS → Accounting Worker
```

A call that never reaches the provider creates no provider usage charge. A paid retry creates another usage event. The application accounting boundary calculates cost from provider-reported usage and the versioned local price catalog before publication; unavailable usage/pricing is explicit `UNKNOWN`, never a guessed zero price. The worker persists events idempotently by `usage_event_id`, and accounting failure does not change successful business-processing state.

## 6. Processing-specific security and reliability rules

- Uploaded files, extracted text and AI output are untrusted until their applicable validation/trust boundary.
- Document text, prompts, provider responses and sensitive payloads are not written to ordinary logs or processing-attempt records.
- Provider-specific SDKs and failures remain behind infrastructure adapters and application ports.
- Retryable and permanent processing failures are distinct; bounded retry and explicit terminal handling prevent a Document from remaining indefinitely in `PROCESSING`.
- Deterministic extraction resource-limit violations are permanent failures. Their attempt evidence uses only stable classifications (`RESOURCE_LIMIT_INPUT_BYTES`, `RESOURCE_LIMIT_EMPTY_CONTENT`, `RESOURCE_LIMIT_PAGE_COUNT`, `RESOURCE_LIMIT_OUTPUT_CHARACTERS`, or `RESOURCE_LIMIT_LLM_INPUT_CHARACTERS`); document content and provider payloads are never included.
- Processing does not generate monitoring Events, Recommendations or Notifications directly. Those downstream flows are owned by the monitoring/recommendation and data-flow architecture.

## Why this remains a separate flow

`DATA-FLOW.md` answers how information crosses major system boundaries, including monitoring, notification and deletion. This document answers the deeper subsystem question of how an uploaded Document is accepted, processed, accounted for and committed, including failure classes that would be lost in a cross-boundary overview. It is therefore a distinct responsibility rather than a second copy of the system data flow.
