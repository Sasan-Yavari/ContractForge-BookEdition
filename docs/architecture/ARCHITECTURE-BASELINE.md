# ContractForge — Architecture Baseline

> **Responsibility:** the approved system architecture: major components, trust boundaries, deployment shape and architectural principles. The [Reader Edition Decisions](DECISIONS.md) owns why choices were made; [DATA-MODEL.md](DATA-MODEL.md) owns persistent structures; [DATA-FLOW.md](DATA-FLOW.md) and [PROCESSING-FLOW.md](PROCESSING-FLOW.md) own flow detail; [IMPLEMENTATION-BASELINE.md](IMPLEMENTATION-BASELINE.md) owns current implementation state.

## 1. System intent

ContractForge is an AI-assisted document and contract processing platform. It accepts user documents, protects and validates them, processes them asynchronously through OCR/AI services, persists a reviewable Contract draft, and lets the user establish trusted business state by explicit approval. The product informs and recommends; the user makes the final decision.

## 2. High-level architecture

```text
Browser / Frontend
        |
        v
Authentication → API
                  |
       +----------+----------+----------------+
       v          v          v                v
   PostgreSQL  private S3   SQS       monitoring/results
                              |
                              v
                       Processing workers
                         |          |
                         v          v
                        OCR        LLM
                              |
                              v
                       Accounting SQS
                              |
                              v
                       Accounting worker
```

The API is synchronous for authenticated state changes and upload initiation. Slow or paid work is asynchronous. The application layer owns use-case orchestration and transaction boundaries; infrastructure owns provider, storage, queue and persistence adapters. The frontend consumes the versioned OpenAPI contract and does not make authorization or business-outcome decisions.

## 3. Architectural boundaries

- **Transport:** HTTP controllers and API models translate requests/results; they are not the domain model.
- **Application:** use cases own authorization context, orchestration, transactions and provider-neutral ports.
- **Domain:** domain models and closed vocabulary types express business state without JDBC, SDK or transport dependencies.
- **Infrastructure:** JDBC/Flyway, private object storage, SQS, OCR/LLM, SES and scheduling adapters implement ports.
- **Trust:** uploaded objects, extracted text and AI output are untrusted until the applicable validation and user-approval boundary. Only an approved `ACTIVE` Contract is trusted for downstream monitoring, recommendations and deterministic financial outcomes.
- **External operations:** each actual paid provider call crosses the accounting boundary; provider-specific behavior remains behind deliberate adapters.

The repository layout and entry points are maintained in [CODEBASE-MAP.md](CODEBASE-MAP.md). The approved data structures and trust/retention constraints are maintained in [DATA-MODEL.md](DATA-MODEL.md). Do not copy those specifications here.

## 4. System-wide principles

- Synchronous operations establish durable state; expensive processing is asynchronous and failure-isolated.
- PostgreSQL is authoritative application state; private object storage holds original binaries.
- SQS provides durable buffering, retry/redrive and back-pressure where asynchronous work is approved.
- Database constraints and application transactions protect atomic state transitions; schema evolution is forward-only through immutable Flyway migrations.
- Security, privacy, minimization, ownership and observability are architecture concerns. Sensitive document/AI content is not logged.
- Deployable artifacts are immutable once promoted; infrastructure is managed as code.
- Closed domain/protocol vocabularies have one authoritative typed representation or constant and are mapped explicitly at boundaries.

Detailed persistence, flow, failure, notification and accounting behavior belongs in the documents named above and must not be duplicated here.

## 5. Deployment shape

The reader edition retains reusable infrastructure examples for study. Its local walkthrough uses local substitutes and excludes author-specific deployment roots, account identities, endpoints, and operational evidence. Local execution does not prove managed identity, deployment protection, availability, or latency controls.

The current scope uses two environments, Development and Production, with one immutable application image promoted after validation. Managed infrastructure, private data services, stateless containers and Terraform/OpenTofu are the approved direction. Kubernetes is excluded from the current architecture because the chosen runtime does not require its operational complexity. The Production backend currently runs as one ECS task because the monitoring trigger is process-local; deployments stop the old task before starting the replacement. ECS/Fargate can host more tasks, but horizontal backend scaling is not a current MVP capability. The local walkthrough does not measure or establish production availability or latency targets.

Backend and frontend Fargate containers use explicit non-root numeric users and read-only root filesystems. Each has one task-scoped ephemeral bind mount at `/tmp`; the image declares that `VOLUME` path and its permissions, with no `tmpfs`, privileged mode, or extra capabilities. Nginx listens on internal port 8080 behind the unchanged public HTTPS listener on port 443. The implementation and deployment-preservation evidence are maintained in the [Implementation Baseline](IMPLEMENTATION-BASELINE.md) and [Terraform runtime guide](../../infrastructure/terraform/README.md).

## 6. Security and operational boundaries

The principal boundaries are browser→API, API→database, API/worker→private storage, producer/consumer→SQS, worker→external provider, uploaded file→parser/OCR/LLM, and draft Contract→explicit approval→trusted Contract. Authentication, authorization, least privilege, malware/content gates, resource limits, structured diagnostics and automated security gates apply at the relevant boundary.

### Current STRIDE reconciliation

This table is the current architecture-level threat model for the implemented trust boundaries. It records controls and residual gaps; it is not evidence of a deployed penetration test or a claim that every approved control is complete.

| STRIDE class | Asset / boundary | Implemented controls and evidence | Residual scope |
| --- | --- | --- | --- |
| Spoofing | Browser identity → API | Cognito access-token issuer/client/expiry validation, trusted UserInfo subject matching, protected-route authentication, immediate disabled-User fence, and temporary provider/subject mapping through token expiry; `SecurityIntegrationTests`, `CognitoSecurityConfigurationTests`, `CognitoAuthenticationAdapterTests`, `AccountDeletionLifecycleIntegrationTests` | Local execution does not prove Cognito token validation or deployed DLQ routing. |
| Tampering | Browser/API → database, S3, and SQS | Server-generated upload keys, owner-derived authorization, quarantine and content checks, validated message shapes, persistence constraints, and idempotent consumers; `UploadServiceTests`, `UploadIngestionServiceTests`, `S3ObjectCreatedEventParserTests`, `IdentityResolutionIntegrationTests` | Managed object-storage lifecycle behavior is deployment-specific and is not exercised by the local walkthrough. |
| Repudiation | User actions and operational changes | Selected identity, contract, original-document, and non-identifying account-deletion completion actions have system-owned audit records; CI/release artifacts identify source and image digests, and deployment workflows use environment-scoped OIDC roles | Local CI does not exercise GitHub Environment protection or AWS deployment roles. |
| Information disclosure | Private documents, identity data, provider input/output, and API responses | Private encrypted S3 and PostgreSQL, least-privilege task roles, short-lived owner-authorized PDF access, generic external failures, privacy-safe logging, and Nginx browser security headers on frontend, runtime-config, proxied API, and error responses; [Implementation Baseline](IMPLEMENTATION-BASELINE.md) and `.github/scripts/tests/frontend-security-headers.sh` | Deployed DAST and penetration testing are not included in the local edition. |
| Denial of service | Public API and paid processing paths | Authentication, upload byte/page/model-input limits, a five-per-rolling-minute upload-initiation limit keyed by authenticated application user (`429` on per-user overage), a 10,000-user bounded tracker that rejects new identities with `503` when full, bounded provider attempts/timeouts, queue back-pressure, and retry/redrive limits; `UploadInitiationRateLimiterTests`, `UploadRateLimitHttpIntegrationTests`, upload/extraction/processing regression suites | The volatile limiter resets on backend restart; multiple backend instances multiply the per-user allowance. The local edition does not include production traffic measurements. |
| Elevation of privilege | User ownership and deployment roles | Authenticated server-side ownership checks and non-disclosing cross-owner reads; least-privilege task roles and OIDC environment roles; `IdentityResolutionIntegrationTests`, `UploadHttpIntegrationTests`, and `OriginalDocumentHttpIntegrationTests` | The local edition does not prove deployed-role or environment protection. |

Source-level security analysis runs as a blocking Java and TypeScript SAST gate, separate from dependency, secret, IaC, and container scanning; the [Implementation Baseline](IMPLEMENTATION-BASELINE.md) records the exact tool, ruleset, and exception evidence. Existing automated tests cover API authentication/ownership, malformed and malicious upload paths, and prompt-injection handling. Deployed-environment DAST and penetration testing are not included in this local edition.

Operational evidence covers API health, processing and queue behavior, DLQs, database/infrastructure health, paid usage and cost drivers, and AWS spend. Exact queue names, retry values, provider choices, Terraform layout and other deployment parameters remain implementation/configuration decisions documented where they become concrete.

## 7. Scope and unresolved decisions

The MVP excludes automatic switching, broad document catalogs, bank/email integrations, multiple external AI providers, advanced ML, real-time processing, multi-country optimization, and unrestricted document download. The current API contract, schema, queue/runtime values, provider/model selection, pricing snapshots, and CI/CD path are specified by their canonical owners and implemented boundaries; this system-level baseline does not duplicate those details. Future changes to them require an explicit decision recorded in the [Reader Edition Decisions](DECISIONS.md).
