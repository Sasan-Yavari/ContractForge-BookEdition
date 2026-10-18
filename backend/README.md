# Backend

The backend is the single Spring Boot module for ContractForge.

Its Java package structure is organized by responsibility:

- `api` — HTTP API adapters
- `application` — application use cases and orchestration
- `domain` — core business domain model and rules
- `infrastructure` — persistence and external-service adapters
- `worker` — asynchronous message consumers and job entry points

Source, test, and resource files follow the standard Maven layout under
`src/main` and `src/test`.

## Database configuration

The runtime datasource is PostgreSQL. Local development uses these values:

- `CONTRACTFORGE_DB_URL` — PostgreSQL JDBC connection URL
- `CONTRACTFORGE_DB_USERNAME` — local PostgreSQL username
- `CONTRACTFORGE_DB_PASSWORD` — local PostgreSQL password

The `aws` profile uses the AWS Advanced JDBC Wrapper and the RDS-managed
Secrets Manager secret. ECS supplies only `CONTRACTFORGE_DB_SECRET_ARN`; the
backend task role resolves credentials through Secrets Manager for JDBC
connections. Login failures force a secret refresh and retry for at most 120
seconds within the configured 150-second Hikari connection timeout. This
covers the roughly one-minute publication window observed for RDS-managed
rotation while keeping retries bounded, so the running task does not depend
on credentials injected at task startup.
Production task environment variables and task definitions contain no
database password.
See the [AWS JDBC wrapper Secrets Manager plugin documentation](https://github.com/aws/aws-advanced-jdbc-wrapper/blob/main/docs/using-the-jdbc-driver/using-plugins/UsingTheAwsSecretsManagerPlugin.md).

## PostgreSQL integration tests

The backend's schema integration tests use Testcontainers with a disposable
PostgreSQL 16 instance and run the real Flyway migrations from
`src/main/resources/db/migration/`. A Docker-compatible container runtime is
the only local prerequisite. Run them with:

```text
JAVA_HOME=/usr/lib/jvm/jdk-26.0.2-oracle-x64 ./backend/mvnw -f backend/pom.xml clean verify
```

The tests fail clearly if Docker (or another Docker-compatible runtime) is not
available; they do not fall back to H2 or a developer database.
