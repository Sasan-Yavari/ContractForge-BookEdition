# OpenAPI v1

This directory contains the versioned v1 ContractForge API contract.

`openapi.yaml` is the source of truth for the public API contract. Monitoring
events and recommendations are read-only, owner-scoped collections. Events are
ordered by event date descending and ID descending. Recommendations default to
non-expired records and accept `includeHistory=true` for explicit history reads;
both are ordered by their primary timestamp descending and ID descending.

The frontend client is generated from this document with:

```text
cd frontend
npm run generate:api
```

Generated output is written to `frontend/src/api/generated/` and is ignored by
Git. Hand-written frontend code must not be placed in that directory.
