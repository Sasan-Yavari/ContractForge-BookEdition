# ContractForge — Document and Account Deletion Strategy

> **FINAL APPROVED STRATEGY**

This document owns recovery across PostgreSQL, private object storage, and Cognito for the two approved deletion operations. Per-document deletion remains a hard delete followed by asynchronous S3 cleanup. Account deletion uses a separate durable, staged lifecycle because it must coordinate the entire account, outstanding upload capabilities, every private object, old Cognito credentials, and JIT identity provisioning.

## Per-document deletion

## Flow

```text
DELETE /documents/{id}
        |
        v
Authorize ownership
        |
        v
Hard-delete Document + dependent business data
        |
        v
Commit DB transaction
        |
        v
Send S3-delete message to dedicated SQS queue
        |
        v
Deletion Worker
        |
        v
Delete private S3 object
        |
     +--+----------------+
     |                   |
  success              failure
     |                   |
     v                   v
 message done        SQS retry
                         |
                    max receives
                         |
                         v
                        DLQ
```

## Approved rules

- `Document` is physically deleted from PostgreSQL.
- There is **no `deleted_at` soft-delete column**.
- S3 cleanup is asynchronous.
- The S3-delete message is sent to a dedicated SQS queue.
- The deletion worker reads the message and deletes the S3 object.
- SQS handles retry/redrive when deletion fails.
- After the configured maximum receive attempts, SQS moves the message to the deletion DLQ.
- The database deletion and S3 deletion are intentionally not one distributed transaction.
- There is **no database Outbox table for Document deletion**.
- The deletion DLQ is monitored and forms the recovery/investigation path for repeatedly failing cleanup.

## Why this design

The user-facing business operation should not remain blocked by an external S3 cleanup failure. PostgreSQL can complete the hard delete, while SQS provides a durable asynchronous retry boundary for physical object deletion.

The design also avoids introducing a database Outbox solely to bridge a database transaction to an SQS deletion message. The small consistency window is accepted for the MVP in favor of a simpler architecture; the SQS retry/DLQ path provides operational recovery for failed cleanup.

## Related decisions

This final strategy supersedes earlier DB-backed `OutboxEvent` proposals for S3 deletion. The database Outbox remains explicitly excluded from the approved Document deletion implementation.

## Account deletion

`DELETE /v1/account` is authorized from the validated provider and subject already present in the request principal. It does not invoke the normal identity resolver or JIT provisioner. Acceptance atomically disables the application User, scrubs the stored email, captures known object keys into a durable manifest, and creates one unique deletion job. A repeated request for that still-fenced account returns the same operation. The handler confirms Cognito disable before returning `202`: it either disables every mapped identity under the initial-phase lease or observes that the durable job has already advanced beyond `DISABLE_COGNITO`. If the initial lease is owned by another worker, the persisted phase remains `DISABLE_COGNITO`, or any mapped disable fails, it returns `503 ACCOUNT_DELETION_DISABLE_PENDING` with `Retry-After`; the application User remains fenced and repeating the request is idempotent. Application authorization remains denied from the moment the PostgreSQL fence commits.

At acceptance, the environment-separated external ledger records the operation/User UUIDs, source RDS resource ID, lifecycle timestamp/state and KMS HMAC fingerprints of every provider/subject mapping. The write is atomic across the deletion item and all subject-fence index rows; rollback of a newly accepted PostgreSQL deletion removes only its still-in-progress external fence. No raw provider subject or other customer data is written to this ledger. The external subject fence blocks JIT provisioning if restored PostgreSQL no longer has the old identity mapping.

```text
Accept → disable app account + scrub email → disable Cognito
  → wait until outstanding upload capabilities expire
  → recapture object keys + purge all account-owned relational rows
  → retry-delete each quarantine/trusted S3 object
  → delete Cognito user
  → wait for old access-token validity fence
  → write system-owned non-identifying completion audit
  → commit removal of all identity mappings, job, and disabled User
  → mark the external tombstone complete
```

The job and object manifest are transactionally persisted in PostgreSQL and dispatched through a dedicated SQS queue. A one-minute dispatch reservation prevents the scheduled publisher from racing the request thread's bounded Cognito-disable call; an atomic phase-specific claim ensures a duplicate HTTP request cannot lease a later stage and strand its message. If immediate disable fails, releasing the worker lease makes the durable job dispatchable for retry. If the process crashes before the call, the reservation expires and the dispatcher resumes. The worker advances one stage per delivery using a database lease and phase guard. S3 object keys are marked deleted only after the idempotent delete call succeeds; failed worker deliveries remain unacknowledged and redrive to the DLQ. A failed queue publish releases the dispatcher lease while leaving the durable job eligible for another publish attempt. The dispatcher and worker leases recover crashes without turning queue delivery into a second workflow store. The external tombstone is marked complete only after PostgreSQL commits the final removal, so its recovery timestamp cannot precede live erasure. If that AWS update fails, the SQS delivery stays retryable; a later delivery completes the external state only after it verifies the deletion job, User and identity mappings are absent. Queue visibility, redrive, retention, and CloudWatch alarm routing are deployment-specific; local Compose does not exercise those managed controls or operator recovery. These are retry bounds, not a data-retention policy.

Use [Reader Local Operations](../LOCAL-OPERATIONS.md) for the local reset path.

Subscription confirmation and a deployed recovery exercise are outside this local edition.

The application User lock serializes upload initiation against the deletion fence. Cleanup waits through the latest issued upload capability plus a five-minute write grace; after that, a final database manifest capture runs immediately before deleting relational data. An S3 ingestion event arriving later resolves its owner from application state. A missing or disabled owner causes late ingestion to delete the quarantine key and deterministic trusted key without recreating database rows. Processing, notification, monitoring, and accounting paths check active account state, while database triggers reject account-owned inserts/updates after disable. Thus work already in flight may finish an external provider call, but cannot restore account-owned rows.

Every provider/subject mapping is retained without email until the old Cognito access token validity window has passed. Each still-valid token therefore finds the disabled User, so first-use provisioning cannot create a replacement. Cognito disable and deletion are idempotently applied to every mapping attached to that User; deleting an already-absent provider user is treated as complete. A later intentional signup with a genuinely new Cognito subject can create a new ContractForge account. Email is not used as a block key. After all objects and all mapped Cognito identities are deleted, the worker waits through the mapping fence, writes one `ACCOUNT_DELETION` completion audit row under the reserved system User, then deletes the job, every identity mapping, and the User in one transaction. The random operation identifier in that audit row is non-identifying; no customer ID, email, subject, or account payload survives.

Per-document deletion removes the Document and its document-dependent relational state before publishing trusted-object cleanup. If that post-commit queue publication fails, the Upload row remains. Account deletion enumerates the Upload row and derives its deterministic trusted-object key in addition to capturing any Document storage keys, so a previously removed Document row cannot make its private object undiscoverable during full erasure.

`COMPLETE` is represented by absence of the customer, identity, job, manifest, and account-owned objects, with the single system-owned completion record as evidence. A failed Cognito call, object delete, DB transaction, or queue attempt leaves a durable phase/manifest for retry or a visible DLQ message; the UI never reports completion based only on relational purge. The queue/DLQ alarm exists in IaC, but no live AWS deployment or alert delivery is claimed here.

## RDS recovery-data boundary

Live account deletion continues to hard-delete all application-owned PostgreSQL rows and private S3 objects, delete every mapped Cognito identity, and preserve only the reserved-system-user completion audit plus the external tombstone described in [DATA-MODEL.md](DATA-MODEL.md). RDS automated backups are a separate, temporary, encrypted disaster-recovery exception: Development retains 3 days and Production 14 days. An eligible historical point may therefore contain pre-deletion rows after live deletion has completed; `COMPLETE` does not mean every historical RDS point has been rewritten or expired. Production has no native final snapshot on destroy and RDS is configured not to retain automated backups when the whole DB instance is destroyed.

Fail-closed restore and deletion reconciliation for managed databases is outside this local reader edition; the local reset procedure does not apply to cloud recovery. The external DynamoDB tombstone binds deletion ID, internal User UUID, KMS HMAC provider/subject fingerprints, source RDS resource identity, lifecycle timestamps/state and recovery release metadata. It stores no raw provider subject, email, object key, token, or business data. Restore bootstrap captures the restored account's trusted Document keys plus each Upload's quarantine key and deterministic trusted key through the same manifest builder used by the live deletion path, before resuming any worker stage. The normal pre-purge capture remains in place to include keys from uploads that were still within their write-capability grace period. The reconciler uses restored identity mappings and that durable object manifest to replay existing idempotent Cognito/S3 cleanup, purges restored relational state through the existing deletion worker, and verifies old subjects remain blocked from JIT provisioning before cutover. Tombstones are released only after RDS inventory no longer contains a point that could restore pre-deletion state; stopped retention, snapshots and unknown inventory block release. This policy does not change live deletion semantics or claim automatic legal/GDPR erasure of encrypted historical backups.
