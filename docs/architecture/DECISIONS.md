# Reader edition decisions

This file records only the decisions needed to understand and run this reader edition. It is not a reconstruction of private project history.

## Authority

- The selected application snapshot is `78ac27aeb579f47d6b2abd177a80f3ff08f08ddd`, recorded in the extraction manifest. This repository starts with a new root commit and contains no source history or tags.
- Application behavior is described by the code and its tests. This file records edition-level choices; it does not override executable behavior or the API contract.
- The default reader path is local Docker Compose. Terraform is an optional, reference-only example and is not run by startup or CI.

## Local substitutions

- A loopback-only local identity and deterministic extraction/sample fixtures make the walkthrough independent of author accounts and paid services.
- PostgreSQL and LocalStack provide local persistence, object storage, and queues. They do not establish production cloud behavior or provider quality.
- Malware scanning and external notifications are not represented as production-equivalent services in the local walkthrough.

## Distribution boundary

The edition retains the existing MIT license from the public destination repository for material the copyright holder is authorized to license. Third-party dependency and image terms remain separate; LocalStack 4.8 version-specific terms and maintenance limits are documented in the distribution notices. This license does not certify production cloud behavior. See [the distribution notice](../../DISTRIBUTION-NOTICE.md) and [dependency notices](../DEPENDENCY-NOTICES.md).
