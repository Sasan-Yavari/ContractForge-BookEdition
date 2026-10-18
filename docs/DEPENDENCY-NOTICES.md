# Dependency and asset notices

The edition carries these generated/reviewed notices:

- [`../backend/THIRD-PARTY-NOTICES.txt`](../backend/THIRD-PARTY-NOTICES.txt): 183 Maven artifacts from the resolved graph, generated with Maven License Plugin 2.7.1.
- [`../frontend/THIRD-PARTY-NOTICES.md`](../frontend/THIRD-PARTY-NOTICES.md): all locked frontend package locations and declared licenses, including optional platform packages.
- [`../THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md): container image sources, observed manifest digests, and image-level terms.
- [`../ASSET-PROVENANCE.md`](../ASSET-PROVENANCE.md): included sample document provenance.

Terraform's pinned AWS provider is `hashicorp/aws` 6.46.0, licensed under MPL-2.0; see the [provider source and license](https://github.com/hashicorp/terraform-provider-aws). The lock file is `infrastructure/terraform/bootstrap/.terraform.lock.hcl`. CI workflow actions are referenced by workflow files and run on GitHub-hosted infrastructure; they are not vendored or redistributed by this repository.

The root [`LICENSE`](../LICENSE) is the existing MIT license from the public destination repository. It does not relicense third-party software, the LocalStack image, image-layer packages, or their terms. The notice tables are inventories, not complete per-layer SBOMs or a legal compatibility opinion. Re-run dependency inventories when manifests, lockfiles, Terraform providers, or base images change; preserve upstream notice files when distributing dependency binaries or source.
