# Threat model

| Actor or boundary | Abuse case | Implemented control | Residual risk |
| --- | --- | --- | --- |
| Unauthenticated client | Brute force, account creation spam | PBKDF2, generic login failure, auth IP rate limit | No email verification or account lifecycle; distributed attackers can use many IPs |
| Authenticated user | Read/delete another owner's asset | Signed subject and owner-scoped queries/locks; 404 on mismatch | Database/host administrators remain trusted |
| Upload client | Path traversal, alternate streams, malformed media | Strict filename policy, UUID paths, magic bytes, parser checks | Not comprehensive malware detection |
| Upload client | Exhaust memory or disk | Streaming limits, decoded-pixel cap, PDF page cap, request rate limits | No per-user storage quota; hostile parsers need process isolation |
| Repeating/concurrent client | Duplicate requests or conflicting receipts | Owner/key advisory lock, persistent fingerprint and unique key | Extremely large receipt histories need retention planning |
| Kafka delivery | Duplicate or malformed messages | Version/ID validation, locked job state, bounded retries and DLT | Broker access is trusted; payload confidentiality needs broker TLS/auth |
| Relay/database boundary | Commit/publish partial failure | Transactional outbox and at-least-once handling | Broker retention and database/object reconciliation remain operational concerns |
| Worker crash | Leave an asset stuck in processing | Committed start plus rollback-safe final transaction and Kafka redelivery | Long outages beyond Kafka retention need replay |
| Redis boundary | Counter races or stale status | Atomic Lua counter with expiry; status stays in PostgreSQL | Redis restarts/eviction policies can affect rate windows; API fails closed on errors |
| SSE client | Connection hoarding or stale authorization | Global capacity cap, deadline, signed owner rechecked each poll | No distributed connection quota; one slow send can delay peers |
| Source repository | Credential leak or vulnerable dependencies | Generated local secrets, .gitignore, Gitleaks and OSV audit | Scans have blind spots and require repeat execution |

There is no anonymous file-download endpoint. Duplicate detection is owner-scoped and does not provide a cross-user hash-existence oracle. Content fingerprint collisions are treated as SHA-256 equality; no byte-by-byte collision verification or cryptographic authenticity is claimed.
