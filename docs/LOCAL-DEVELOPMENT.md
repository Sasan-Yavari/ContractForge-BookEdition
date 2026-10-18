# Local development and reader walkthrough

## Requirements

- Git.
- Docker Engine with Docker Compose plugin.
- Enough free disk for application builds, PostgreSQL, and LocalStack images.

The supported application path runs in containers. It does not require host Java or Node, AWS credentials, author accounts, or paid services. The LocalStack 4.8 and nginx runtime images are pinned by digest; other images use versioned tags that can move. The pinned LocalStack image is the legacy Community Edition, not the later Hobby offering. Its v4.8 EULA and Apache-2.0 license, official commercial-use statement, telemetry terms, and maintenance status are documented in [third-party notices](../THIRD-PARTY-NOTICES.md). Review and accept the applicable EULA before use. The legacy image has no ongoing update/backport guarantee. First startup requires access to the configured container registries.

## Start

From the repository root, run:

    docker compose up --build -d

Wait until all services report healthy:

    docker compose ps

Open http://localhost:8080. The local runtime supplies the documented local-owner identity only when the explicit local profile is active and the browser host is loopback. Do not reuse this local token or profile outside the demo.

## Upload and process a fictional contract

1. In the application, choose Add contract and select samples/contracts/northstar-bakery-electricity.pdf.
2. Wait for processing to finish. The app should navigate to the review page for a DRAFT contract.
3. The browser uploads the PDF to the local object store. In the local profile, the frontend then calls a local-only completion endpoint to stand in for the S3/GuardDuty notification path.
4. The normal queue worker processes the upload. The local extractor reads the labelled fictional sample fields; it does not call a model or paid service.
5. Review the customer, provider, and pricing model. Change the provider to Example Energy Cooperative and save. The page should report that the draft is saved and still needs review.
6. Choose Confirm contract. The contract becomes active only after this explicit human action.
7. Open View original document. The application returns a short-lived local object-store URL and the browser displays the original fictional PDF.

The script scripts/local-walkthrough-smoke.py exercises the same upload, processing, review edit, approval, and original retrieval API path without relying on manual browser timing. It prints only step names and pass/fail status.

## Shut down and reset

Stop containers and preserve the named data volumes:

    docker compose down

Erase the local database, objects, queues, and scratch volumes:

    docker compose down -v

Reset is destructive to this local demo's data. It does not affect a remote environment.

## Local substitutes and limits

| Boundary | Local implementation | What it does not prove |
|---|---|---|
| Database | PostgreSQL container and Flyway migrations | Managed database availability, backup/restore, or production operations |
| Object storage and queues | LocalStack using the application's S3/SQS adapters | AWS IAM, quotas, cross-account policy, delivery guarantees, or deployed event wiring |
| Authentication | Fixed local identities behind an explicit local-profile guard | Cognito/OIDC lifecycle or production token validation |
| Malware scan | Deterministic local clean result | Managed malware detection or behavior against malicious files |
| Extraction | Direct text extraction and labelled fictional sample adapter | Bedrock accuracy, Textract OCR, scanned PDFs, latency, or paid usage |
| Upload completion event | Local authenticated completion endpoint | S3 event notification or GuardDuty scan-tag flow |
| Email and alerts | Delivery disabled | SES delivery or operational SNS alarm behavior |
| Infrastructure | Terraform examples retained for study, never used by Compose/CI | Provisioning, deployment, cost, backup/recovery, or teardown correctness |

For Production use, these local substitutions are not safe replacements. Optional reader-owned provider experiments require separately authorized resources, credentials, costs, and cleanup; they are not part of this walkthrough or default CI.


## Troubleshooting

- **Docker is unavailable:** confirm Docker Engine is running and `docker info` succeeds, then check that your account can access its daemon socket. Compose configuration parsing alone does not start services.
- **Docker Desktop reports a bind mount as not shared:** the local stack bind-mounts `scripts/localstack-init.sh`, so Docker Desktop must be able to read the repository directory. Move the clone into a directory already shared with Docker Desktop or add the repository directory under Docker Desktop's file-sharing settings, then retry `docker compose up --build -d`.
- **A port is already in use:** inspect the documented loopback ports before startup; stop the conflicting local process or change the local-only port mapping, then rerun `docker compose config --quiet`.
- **A service stays unhealthy:** run `docker compose ps` and `docker compose logs --tail=100 <service>` for PostgreSQL, LocalStack, backend, or frontend. Redact environment values, tokens, document contents, and object keys before sharing output.
- **The sample contract is not listed:** check backend and worker logs for a failed upload or queue handoff, verify the LocalStack initialization completed, then refresh the contract list. Retry only after confirming there is no pending processing attempt.
- **Reset did not clear demo state:** stop the stack with `docker compose down -v`, confirm the named volumes were removed, and start again. This intentionally deletes all local demo data.
- **Images cannot be pulled:** check network access to the container registry and retry the build. No cloud account or paid provider credentials are needed for the local flow.
