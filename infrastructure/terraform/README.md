# Infrastructure reference examples

The Terraform here illustrates selected infrastructure boundaries used by the worked application. These files are reference material, not a ready-to-deploy environment. No environment roots, author remote-state values, deployment workflows, or account-specific settings are included.

- bootstrap/ illustrates encrypted/versioned/private state storage and a GitHub OIDC provider boundary.
- modules/platform/ illustrates the reusable platform resource composition and its security/lifecycle choices.

The Compose walkthrough and CI do not run Terraform plans or applies. Do not apply these examples to an account without first reviewing account ownership, state protection, costs, permissions, provider versions, and cleanup. No cloud calls are needed to run the local application.
