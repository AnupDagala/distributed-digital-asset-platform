# API v1

Base URL for local Compose: http://localhost:8080. Asset routes require Authorization: Bearer <access-token>. Registration/login accept JSON; upload accepts multipart/form-data with exactly the file part used by the API. No owner ID is accepted from the client.

## Authentication

POST /api/v1/auth/register takes email and password. Email is normalized to lowercase; password length is 12–128 characters. It returns 201 with accessToken, tokenType (Bearer) and expiresIn (seconds). POST /api/v1/auth/login takes the same fields and returns 200. Existing email registration returns 409; incorrect login returns 401. Auth bodies are limited to 16 KiB.

Use Swagger UI or scripts/smoke.mjs to exercise auth without placing a real password in shell history. Treat returned tokens as secrets; do not paste them into repository files or URL query strings.

## Upload

```sh
curl -X POST http://localhost:8080/api/v1/assets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $REQUEST_UUID" \
  -F 'file=@sample.png;type=image/png'
```

Success is 202 with Location, Idempotency-Replayed, and a body containing id, status=QUEUED and createdAt. The receipt is stable, not a promise that current status is still queued. The key is optional; if supplied it must have canonical UUID syntax. Matching owner/key/input replays the original receipt; changed input returns 409. Filename, detected type, size and SHA-256 define matching input. Keys are never reassigned, including after deletion.

Accepted declared types: image/jpeg, image/png, application/pdf. Bytes must match that signature. The default file cap is 20 MiB, with a 21 MiB multipart envelope limit. Upload-time checks can accept a signature that later fails structural parsing; such an asset transitions to REJECTED.

## Query and delete

GET /api/v1/assets accepts page (0–10000), size (1–100), sort (createdAt, updatedAt, fileSize or originalFileName followed by asc/desc), status and mediaType. Default sort is createdAt,desc with UUID as a stable tie-break. Example query: ?page=0&size=20&sort=createdAt,desc&status=COMPLETED&mediaType=image/png. The response contains content, page, size, totalElements and totalPages. List metadata is null; detail GET includes extracted width/height or pageCount when available.

GET /api/v1/assets/{id} returns metadata including content digest, terminal duplicateOf when applicable and optimistic version. It never returns a storage path. GET /status returns id, status, updatedAt and version from PostgreSQL.

DELETE /api/v1/assets/{id} returns 204 after a durable tombstone and object-cleanup outbox entry. Subsequent GET/DELETE return 404. Queued work for a tombstone is skipped. Bytes can remain briefly until relay cleanup; database history is retained.

## SSE

```sh
curl -N http://localhost:8080/api/v1/assets/$ASSET_ID/events \
  -H "Authorization: Bearer $TOKEN" \
  -H "Last-Event-ID: $LAST_EVENT_ID"
```

Omit Last-Event-ID to begin at zero. Events use name status, a numeric id and JSON data with id, status, code and createdAt. Comments are keepalives. The client stores the last received id and reconnects after disconnection, sending the same cursor and a valid token. IDs have gaps and should never be incremented client-side. Delivery can repeat around disconnections, so clients should ignore previously seen IDs.

Connections close after a terminal event, token expiry or 60 seconds. If the terminal event was already acknowledged, reconnect may close without a new event. A stream failure emits an error event with code STREAM_UNAVAILABLE when possible, then closes. Browser fetch streaming supports Authorization headers; native EventSource does not supply arbitrary headers, so do not put bearer tokens in URLs to work around it.

## Errors and limits

Errors contain timestamp, status, error, code, message, path and requestId. X-Request-ID accepts 1–64 letters/digits/underscore/hyphen, otherwise the server generates an ID. Responses do not include exception internals.

| Status | Meaning |
| --- | --- |
| 400 | Malformed JSON, invalid key/filename/query/cursor or validation failure |
| 401 | Missing, invalid or expired JWT; incorrect login |
| 404 | Missing, deleted or foreign asset |
| 409 | Existing email or conflicting idempotency input |
| 413 | File, multipart or auth JSON limit exceeded |
| 415 | Unsupported type, mismatched magic bytes or request content type |
| 429 | Rate or stream-capacity limit; request-rate responses include Retry-After |
| 503 | Temporary database/storage/rate-limiter failure |

The default fixed window is 60 seconds, with 10 authentication requests per direct IP and 120 authenticated API requests per signed subject. Health does not require authentication and omits component details. Prometheus requires a bearer token. API docs are disabled in base configuration and enabled by the local Compose configuration.
