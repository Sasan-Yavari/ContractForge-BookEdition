# ContractForge Book Edition

ContractForge Book Edition is a reader edition prepared for the AI-enabled SDLC book. The root MIT license is the existing license from the public destination repository. See [Distribution notice](DISTRIBUTION-NOTICE.md) and [third-party notices](THIRD-PARTY-NOTICES.md) for scope and upstream terms.

The application source was curated from revision 78ac27aeb579f47d6b2abd177a80f3ff08f08ddd. The edition is based on the pinned source revision above; the private staging Git history and ContractForge/AI-Books history, tags, and refs are not part of the edition.

## Run the local example

Requirements: Git, Docker Engine, and the Docker Compose plugin. The walkthrough needs no host Java/Node installation, cloud account, credentials, or paid provider.

1. Read docs/LOCAL-DEVELOPMENT.md and docs/SAMPLE-DATA.md.
2. Start services with docker compose up --build -d.
3. Open http://localhost:8080 and follow the upload, review, approval, and original-document steps in docs/LOCAL-DEVELOPMENT.md.
4. Shut down with docker compose down; erase all demo data with docker compose down -v.

The local path uses PostgreSQL, LocalStack, explicit local authentication, a deterministic fictional-sample extractor, and a local-only upload completion bridge. It does not certify cloud deployment or managed provider behavior.

## Project map

- backend/: Java API, domain/application code, infrastructure adapters, migrations, and tests.
- frontend/: browser application and frontend tests.
- contracts/openapi/v1/: API contract.
- infrastructure/terraform/: opt-in reference examples only. Nothing in the Compose walkthrough or CI applies Terraform.
- docs/: local runtime and architecture references.
- samples/: newly authored fictional contract sample.

See [distribution notice](DISTRIBUTION-NOTICE.md), [third-party notices](THIRD-PARTY-NOTICES.md), and [asset provenance](ASSET-PROVENANCE.md). The private extraction manifest is staging-only and is not part of this edition.
