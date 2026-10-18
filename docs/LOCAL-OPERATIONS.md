# Local operations

This guide covers only the Docker Compose reader walkthrough. It provides no production deployment, cloud recovery, or account operations instructions.

## Start and inspect

From the repository root, follow [Local development](LOCAL-DEVELOPMENT.md). Check service state with `docker compose ps`; inspect a failing service with `docker compose logs --tail=100 <service>`. Redact environment values, tokens, uploaded content, and object keys before sharing diagnostics.

## Stop and reset

Use `docker compose down` to stop the local stack. Use `docker compose down -v` to remove local database, object, and queue volumes and reset the demo. This permanently deletes local demo data.

## Troubleshooting

See the troubleshooting section in [Local development](LOCAL-DEVELOPMENT.md) for Docker daemon, port, health, image-pull, upload, and reset guidance. A successful Compose configuration check does not prove the services can start; complete the documented walkthrough on a machine with a working Docker daemon.
