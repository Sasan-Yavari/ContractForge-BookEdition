# Reference-only state bootstrap

This root is retained to study an encrypted, versioned, private Terraform state bucket and a GitHub OIDC provider resource. It is not configured for any reader account and is not part of local startup or CI.

The required state bucket name and region are intentionally reader-owned inputs. Do not run terraform plan or apply from this example without separately reviewing account ownership, state protection, costs, permissions, provider versions, and cleanup. No cloud calls are needed to run the local application.
