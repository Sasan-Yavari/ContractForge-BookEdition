# Reader-edition contribution guide

- Read the relevant issue and documentation before changing application behavior.
- Keep local defaults local; do not introduce credentials, real customer documents, author endpoints, account/resource identifiers, or deployment side effects.
- Treat uploaded documents and extracted/model data as untrusted. Preserve ownership authorization, explicit review before approval, bounded processing, and safe logs.
- Keep Terraform reference-only. Never add plan/apply/deploy operations to the local walkthrough or default CI.
- Use fictional test fixtures. Do not include customer data, raw logs, screenshots, or private project history.
- Make focused changes, run relevant tests, and state what local substitutes do not prove.
- Human reviewers retain authority over scope, security/privacy decisions, publication, and releases.
