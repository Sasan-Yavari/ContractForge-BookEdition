# ContractForge — Codebase Map

> **Responsibility:** repository navigation only. This map answers where implementation lives; it does not re-specify architecture, data structures, flows or invariants. Follow the linked canonical owners for those questions.

## Runtime entry points

```text
frontend/src
      ↓ generated OpenAPI client
backend/api controllers
      ↓
backend/application use cases and ports
      ↓
backend/infrastructure adapters/repositories/workers
      ↓
PostgreSQL · private object storage · SQS · external providers
```

The backend is a single Spring Boot application. `backend/src/main/java/io/contractforge/backend/ContractForgeApplication.java` is the application entry point. HTTP entry points are the controllers under `backend/src/main/java/io/contractforge/backend/api`; scheduled and queue entry points are under `infrastructure`.

## Repository navigation

| Area | Location | Use it to find |
| --- | --- | --- |
| API/transport | `backend/src/main/java/io/contractforge/backend/api` | HTTP controllers, including owner-scoped contract collection, overview, confirmed edits and original-document access; request/response mapping and security transport configuration |
| Operations telemetry | `backend/src/main/java/io/contractforge/backend/api/CorrelationIdFilter.java`, `backend/src/main/java/io/contractforge/backend/application/accounting`, `backend/src/main/java/io/contractforge/backend/application/notification`, `backend/src/main/java/io/contractforge/backend/application/processing`, `backend/src/main/java/io/contractforge/backend/infrastructure/monitoring` | Server-generated HTTP correlation, structured privacy-safe operational events, and provider usage signals; local container behavior is described in [LOCAL-DEVELOPMENT.md](../LOCAL-DEVELOPMENT.md) |
| Application | `backend/src/main/java/io/contractforge/backend/application` | Use cases, ports, orchestration, authorization context and transaction boundaries; upload initiation admission is under `upload`, while confirmed edits and derived-monitor refresh live under `contract` and `monitoring` |
| Domain | `backend/src/main/java/io/contractforge/backend/domain/model` | Domain records and canonical closed vocabulary types |
| Persistence | `backend/src/main/java/io/contractforge/backend/infrastructure/persistence` | JDBC repositories, row mapping and SQL boundary code |
| Processing | `backend/src/main/java/io/contractforge/backend/infrastructure/processing` | SQS consumers and processing-worker adapters |
| Monitoring | `backend/src/main/java/io/contractforge/backend/infrastructure/monitoring` and `infrastructure/scheduling`, plus application monitoring | Monitoring trigger and bounded shared Spring scheduling configuration |
| Notification | `backend/src/main/java/io/contractforge/backend/application/notification` and `.../infrastructure/notification` | Dispatch, rendering, delivery worker and provider transport |
| Document deletion | `backend/src/main/java/io/contractforge/backend/application/document` and `.../infrastructure/document` | Owner-authorized deletion boundary, post-commit cleanup protocol and SQS worker |
| Account deletion | `backend/src/main/java/io/contractforge/backend/application/accountdeletion`, `infrastructure/accountdeletion`, `infrastructure/persistence/JdbcAccountDeletionRepository.java`, `api/AccountDeletionController.java`, `V22__account_deletion_lifecycle.sql` | Authenticated no-JIT erasure request, durable phase/object manifest, Cognito lifecycle, account-deletion SQS dispatch/consumer, Terraform-backed HMAC tombstones, RDS recovery inventory and isolated restore/release runners; see [DOCUMENT-DELETION.md](DOCUMENT-DELETION.md), [DATA-MODEL.md](DATA-MODEL.md); managed restore/release behavior is outside the local reader edition |
| Storage and ingestion events | `backend/src/main/java/io/contractforge/backend/infrastructure/storage` and `.../infrastructure/upload` | Private object storage, GuardDuty result tags, S3 ObjectCreated envelope parsing and SQS ingestion consumer |
| Accounting | `backend/src/main/java/io/contractforge/backend/application/accounting` and `.../infrastructure/accounting` | Usage publication/consumption and cost boundary |
| Database schema | `backend/src/main/resources/db/migration` | Immutable Flyway migrations |
| Backend tests | `backend/src/test/java` | Unit, architecture, repository, HTTP and integration evidence |
| Frontend | `frontend/src`, `frontend/Dockerfile`, `frontend/docker-entrypoint.sh`, `frontend/docker-content-security-policy.sh`, `frontend/nginx.conf`, `frontend/security-headers.conf` | React Router product routes and shared shell, Cognito code+PKCE adapter and session boundary under `auth`, fail-closed runtime config loading before React starts, container-time non-secret Cognito settings, same-origin API client, Nginx browser security headers at the HTTPS frontend boundary |
| Product UI/UX decisions | `frontend/src` | Approved product experience and interaction decisions; see [docs/README.md](../README.md) for documentation ownership |
| API contract | `contracts/openapi/v1/openapi.yaml` | Versioned public API source |
| Infrastructure | `infrastructure/terraform` | Development/Production AWS environments, Terraform bootstrap and reusable platform module |
| CloudWatch operations | `infrastructure/terraform/modules/platform/main.tf` | SNS-routed alarms, provider/notification metrics, RDS events and configurable AWS Budget; local container behavior is described in [LOCAL-DEVELOPMENT.md](../LOCAL-DEVELOPMENT.md), [Terraform operations](../../infrastructure/terraform/README.md) |
| Local runtime | `compose.yaml`, `backend/src/main/resources/application-local.yml`, local-profile adapters/configuration | Browser-oriented local topology and explicit substitute boundaries; see [LOCAL-DEVELOPMENT.md](../LOCAL-DEVELOPMENT.md) |
| Canonical docs | `docs/architecture` | Current engineering authority, organized by [docs/README.md](../README.md) |

## Boundary navigation rules

- Controllers translate transport models and delegate to application services; they do not own domain decisions.
- Application code depends on ports and domain models; provider SDKs and JDBC types remain in infrastructure.
- Persistence SQL and migrations are authoritative for database implementation evidence, while [DATA-MODEL.md](DATA-MODEL.md) owns the approved model and constraints.
- Generated frontend files under `frontend/src/api/generated/` are build output and are not hand-edited or committed.

For system shape, use [ARCHITECTURE-BASELINE.md](ARCHITECTURE-BASELINE.md). For data, flows, processing detail, implementation state and invariants use [DATA-MODEL.md](DATA-MODEL.md), [DATA-FLOW.md](DATA-FLOW.md), [PROCESSING-FLOW.md](PROCESSING-FLOW.md), [architecture baseline](ARCHITECTURE-BASELINE.md), and [INVARIANTS.md](INVARIANTS.md), respectively.

## Reconciliation check

At milestone/release review, compare this map with the package tree, controllers, application ports/services, migrations, workers, frontend routes and tests. Record discrepancies as a follow-up issue rather than turning this navigation document into an architecture or flow specification.
