# Security policy

This repository is a local portfolio backend, not an audited service. Its runtime dependency and secret-scan outcomes are in [verification](docs/VERIFICATION.md). A clean dependency scan does not establish application security.

## Protected assets and boundaries

Protected assets are user passwords and access tokens, uploaded bytes, owner-scoped asset metadata and digests, processing state, and infrastructure availability. Trust boundaries are the HTTP client/API, API/database, API/object volume, relay/Kafka/worker, and API/Redis. The host, database administrators and internal broker administrators are trusted. See the [threat model](docs/THREAT_MODEL.md).

## Implemented mitigations

- Spring Security PBKDF2 salted password hashing; passwords are never stored in plaintext. Unknown-user login performs a dummy hash verification.
- HS256 access tokens require a random Base64 secret of at least 32 bytes. Issuer, audience, expiry and UUID subject are validated. Tokens last 15 minutes by default; no cookies or session authentication are used.
- Every asset operation and every SSE poll enforces signed-owner access. Foreign and deleted IDs return the same 404 result.
- Filename traversal, control characters and ambiguous path syntax are rejected. Filenames are metadata only; storage uses UUID keys with no symlink following on reads.
- Strict MIME/signature agreement, streaming byte caps, bounded auth JSON (including unknown-length/chunked bodies), image pixel limits, PDF page limits, encrypted-PDF rejection and repeat integrity hashing. JSON rejects unknown fields, duplicate keys and trailing values. Rate/body filters use the decoded application path, including context/servlet prefixes.
- Atomic Redis rate limits for auth IPs and authenticated identities, with externally configured expiry. Redis outages fail closed. Forwarded-IP headers are not trusted.
- Safe centralized API errors, bounded request IDs, no stack traces in responses, no password/JWT/file-body logging. MockMvc request printing is disabled in tests.
- Environment-only secrets, ignored .env files, parameterized SQL/ORM queries, dependency audit script, source secret scanning in CI.
- Non-root application and Redis service configuration, private infrastructure services and a loopback-bound local HTTP port. Sensitive actuator endpoints are blocked; Prometheus requires `SCOPE_metrics.read`. Registration/login do not issue that authority. Container runtime behavior remains unverified locally without Docker.

## Known limitations

There is no malware scanner, file sanitization, digital signature validation or sandboxed parser process. A valid PDF can contain active content; the API does not serve uploaded bytes for browser execution. File/pixel/page limits reduce resource use but do not guarantee a CPU or heap bound against every hostile parser input. A transaction timeout is not a parser process kill switch. Deploy parser workers in an isolated resource-limited environment before accepting hostile public traffic.

Local objects and database volumes are not encrypted by this application. Kafka uses plaintext inside the Compose network with a single broker. Internet deployment requires TLS, service authentication, secret rotation and network policies. HMAC issuers and verifiers share signing authority; there is no refresh-token flow or immediate revocation. Rate limiting is fixed-window and can allow boundary bursts; registration has no email verification or account quota. Stream capacity is per process, and users do not have disk-storage quotas.

Deletion is a tombstone plus asynchronous byte removal. Processing history, receipts and backup copies are not erased automatically. Crashes can leave orphan objects; cleanup must preserve any referenced or in-flight content. The local database user creates its schema for convenience; production should split migration and runtime privileges. Metrics scraper tokens must be provisioned by a trusted issuer outside self-registration. There is no operator-token provisioning UI or refresh flow. The default Spring JWT timestamp validator permits clock skew (60 seconds); nominal token lifetime is 15 minutes, and SSE closes at the token's actual expiry.

## Disclosure

Do not publish exploit payloads, credentials or user data in a public issue. Use GitHub's private vulnerability reporting feature if the repository owner enables it, or a private contact channel listed on the owner's GitHub profile. No security email address or response-time guarantee is invented here. For harmless non-sensitive bugs, file a normal issue with a minimal reproduction.
