# Executed verification

Verified on 10 September 2026. This report describes local execution, not a GitHub Actions run or a production deployment.

## Build and tests

Environment: Windows 11 x64, portable Temurin Java 21.0.12.1, Maven 3.9.11, PostgreSQL 17.11 on loopback, and the embedded Kafka broker supplied by spring-kafka-test. System Java 17 was not used for the build. The test database was dedicated to this work.

Command: mvn clean verify, with TEST_DATABASE_URL, TEST_DATABASE_USER and TEST_DATABASE_PASSWORD enabling LocalPlatformIT.

| Suite | Discovered | Passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Unit tests (Surefire) | 41 | 41 | 0 | 0 |
| Native PostgreSQL + embedded Kafka integration | 18 | 18 | 0 | 0 |
| PostgreSQL/Kafka/Redis Testcontainers integration | 19 | 0 | 0 | 19 |
| Total | 78 | 59 | 0 | 19 |

**Build result: SUCCESS.** The jar was packaged. [Machine-readable test summary](test-results.json) is derived from the actual Surefire and Failsafe XML reports. Full generated reports and JaCoCo output remain in target and are gitignored.

Executed integration cases include real Flyway migrations/JPA validation, JWT login and claim checks, ownership on all asset routes, malicious uploads, six simultaneous idempotent submissions, deterministic replay after processing, concurrent owner-scoped content deduplication, interrupted starts, duplicate delivery, broker-send failure/recovery, invalid content, retry exhaustion, a retry budget persisted across interrupted deliveries, DLT quarantine, deletion and object cleanup, SSE cursor replay, and streamed HTTP multipart size limits.

Redis is mocked only in LocalPlatformIT. The live Redis atomic-counter/expiry test is in the skipped container suite. No H2 database was used. A broker-start timeout occurred on an earlier resource-constrained run; test JVM memory/CPU resources are now bounded and the broker admin startup timeout is explicit. The final clean run passed. The integration suite found and fixed a real timestamp-precision mismatch in replay receipts.

## Security and static checks

- OSV runtime audit: **124 artifacts checked, 0 advisory matches** after remediation. [Raw audit result](dependency-audit.json). The initial audit reported 13 matches; [dependency notes](DEPENDENCIES.md) list the changes. No advisory was suppressed.
- Gitleaks 8.30.1 source scan: **no leaks found**, with redaction enabled. Generated target artifacts are excluded; real local secrets reside outside the repository or in ignored .env files.
- Java source formatted with google-java-format 1.28.0. JavaScript convenience, smoke, audit and load scripts passed node --check.
- Compiler, Maven version and Java version gates passed. OWASP Dependency-Check's optional NVD-based profile was not executed. Container OS/JDK image vulnerability scans and penetration testing were not performed.

## Docker and deployment

Standalone Docker Compose 5.5.1 validated docker-compose.yml using .env.example, exit code 0. The referenced public container tags were checked against Docker Hub metadata and were available.

Compose build and startup were attempted but could not connect to the Docker engine named pipe. There is no available Docker daemon or installed WSL distribution on this host. Therefore image build, Compose startup, the full-stack smoke driver, and live Redis container behavior remain **unverified locally**. CI is configured to run those gates with Docker, but GitHub Actions has not been executed.

The benchmark driver is supplied and syntax-checked, but was not run against the complete stack. There are no throughput or production-performance claims. No AWS deployment exists.

## Git and publication

The source is initialized on main and staged. Git status, the staged file manifest, staged whitespace checks and ignored-secret/build-artifact checks were inspected. The storage ignore rule is anchored to the repository root so it does not exclude Java storage packages. Git author name/email are not configured and were requested from the user; the initial commit is pending that information. GitHub CLI is not installed in this environment, so its authentication could not be checked; no public repository was created and nothing was pushed. No existing remote history was overwritten.

After the author identity is supplied, create the requested initial commit, authenticate GitHub CLI, check whether distributed-digital-asset-platform already exists, and create/push only if that name is unused. Never force-push or overwrite an existing repository.
