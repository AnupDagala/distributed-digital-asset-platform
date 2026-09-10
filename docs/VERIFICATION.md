# Verification of the hardening review

Executed on 10 September 2026 against the existing repository. This report describes local checks, not a production deployment or a verified GitHub Actions run. The review baseline was a4eb32c099eb9f34eae5a5c72ffa2aa183f53862; Git history records the subsequent hardening commit.

## VERIFIED

Environment: Windows 11 x64; Temurin OpenJDK **21.0.12.1+1**; Apache Maven **3.9.11**; PostgreSQL **17.11** bound to loopback; real embedded Kafka supplied by spring-kafka-test; Node.js for driver/script checks. Portable Java/Maven were used, rather than the system Java 17 installation. A dedicated review database was created; no user/application database was used.

Final Java command: `mvn clean verify`, with `TEST_DATABASE_URL`, `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD` set for LocalPlatformIT. The clean build finished successfully at 22:36:57 IST (17:06:57 UTC). Compilation, Java/Maven version gates, Flyway V1/V2 migrations, Hibernate schema validation, packaging and JaCoCo report generation passed.

| Suite | Discovered | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| Unit tests / Surefire | 53 | 53 | 0 | 0 | 0 |
| Native PostgreSQL + embedded Kafka / LocalPlatformIT | 30 | 30 | 0 | 0 | 0 |
| PostgreSQL/Kafka/Redis Testcontainers / ContainerPlatformIT | 30 | 0 | 0 | 0 | 30 |
| Java total | **113** | **83** | **0** | **0** | **30** |

[Machine-readable test summary](test-results.json) is derived from the final Surefire/Failsafe XML. Generated reports remain in ignored target directories. Separately, `node --test benchmarks/load.test.mjs` passed **3 tests, 0 failures**. Those protocol fixtures check benchmark verdicts, not platform throughput.

New integration coverage includes atomic registration rollback and byte cleanup, competing conflicting idempotency keys, separate owner/key scope, a paused worker start/result gap with six competing deliveries, recovery contention, concurrent outbox relays, actual Kafka acknowledgement followed by DB rollback, single terminal effects after republication, direct SQL invariant violations, stale JPA versions, strict JSON/mass assignment, chunked oversized JSON tails, decoded HTTP auth paths, and management authorization. Earlier ownership, deduplication, retry/DLT, replay, deletion, SSE and multipart-boundary cases were rerun as part of the same suite.

Redis alone is mocked in LocalPlatformIT. Its live atomic-counter/expiry case belongs to the skipped container suite. No H2 database was substituted for PostgreSQL. Initial review runs caught a compile error in a newly added fixture and a test-context Prometheus-export issue; both were corrected before the final clean run. A further encoded-path regression was then added and the full suite rerun. No failed assertion was removed to make verification pass.

| Check | Actual result |
| --- | --- |
| Runtime dependency audit | OSV: **124 resolved artifacts, 0 advisory matches**; [raw result](dependency-audit.json). No new dependency or advisory suppression was introduced in this review. |
| Source secret scan | Gitleaks **8.30.1**, redacted, default rules plus the target-artifact exclusion: **no leaks found** in the intended source snapshot. The staged snapshot is rescanned before commit. |
| Java formatting | google-java-format **1.28.0** applied; final formatting/whitespace checks run before staging. |
| JavaScript | All scripts passed `node --check`; 3 driver-verdict tests passed. |
| Docker Compose model | Standalone Docker Compose **5.5.1**, `--env-file .env.example config --quiet`: **exit 0**. Placeholder values were used for validation only. |
| Git checks | Baseline, origin and branch inspected; source diff reviewed; `git diff --check` clean. Final staging checks exclude real .env files, secrets, build outputs and IDE files. |

One repeat OSV request failed with `getaddrinfo ENOTFOUND api.osv.dev`; retrying succeeded and the committed audit JSON records the fresh result. This transient lookup failure was not treated as a passing scan.

## ENVIRONMENT-LIMITED

The Docker CLI/engine is unavailable on this host. The standalone Compose binary can validate the model, but its image-build attempt failed with:

```text
failed to connect to the docker API at npipe:////./pipe/docker_engine; check if the path is correct and if the daemon is running: open //./pipe/docker_engine: The system cannot find the file specified.
```

Compose also warned that the buildx CLI plugin was missing and reported a secondary closed-tar-pipe error after losing the engine connection. These are not successful image builds. Consequently:

- Docker image build: **not verified; attempted and blocked by the absent daemon**.
- Compose startup, runtime health checks, Linux users/volume permissions and full-stack smoke: **not run**.
- Container-backed integration tests, including live Redis: **30 skipped**.
- Full-stack benchmark: **not run**. Driver syntax/verdict tests passed; no measured application latency, throughput or capacity is reported.

CI requires Docker and is configured to run Testcontainers, the dependency/secret checks, image build, smoke test and benchmark-driver tests. That configuration is not proof of a successful GitHub Actions run. Inspect the run attached to the pushed commit separately.

## NOT VERIFIED

OWASP Dependency-Check's optional NVD profile; container OS/JDK vulnerability scans; penetration testing; isolated hostile-parser execution; representative query plans/load; broker/host failover; backup restoration; retention/orphan reconciliation; real multi-instance cloud operation; AWS deployment. No cloud resources were created.

The [engineering review](REVIEW.md) and [threat model](THREAT_MODEL.md) describe remaining limitations. A successful local build and a zero-finding dependency scan do not establish production readiness.
