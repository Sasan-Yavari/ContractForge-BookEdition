# Reader edition [reader edition implementation baseline](IMPLEMENTATION-BASELINE.md)

This baseline describes the selected source snapshot as curated for the reader edition. It is a current implementation map, not a historical project timeline or production certification.

## Included implementation

- Spring Boot backend, Flyway-managed PostgreSQL schema migrations under `backend/src/main/resources/db/migration/`, and the versioned OpenAPI contract.
- React frontend for local contract upload, review/edit, approval, and original-document access.
- Docker Compose services for PostgreSQL, LocalStack object storage and queues, backend, and frontend.
- Deterministic local extraction and explicit upload completion seams for the walkthrough.
- A reusable Terraform platform module retained as reference material with reader-owned inputs; author-specific environment roots and cloud deployment automation are excluded.

## Evidence boundaries

The default path is local and does not prove cloud authorization, production identity, malware detection, OCR/model quality, email delivery, deployment, backup/restore, production availability, or compliance. Use the tests and local walkthrough instructions for the evidence they directly provide. See [local development](../LOCAL-DEVELOPMENT.md), [the codebase map](CODEBASE-MAP.md), and [reader edition decisions](DECISIONS.md).
