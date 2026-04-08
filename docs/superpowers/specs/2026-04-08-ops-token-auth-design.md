# Ops Token Authentication Design

**Date:** 2026-04-08
**Status:** Approved
**Replaces:** Static `ops.secret` shared-key authentication

## Overview

Replace the current shared-secret `X-Ops-Key` authentication for ops endpoints with Ed25519 keypair-based token authentication. Follows the SSH authorized-keys model: the server holds a list of authorized public keys, clients authenticate by signing a timestamp with their private key, and receive a short-lived self-validating token.

## Goals

- Eliminate shared secrets for ops access
- Support multiple authorized agents/developers, each with their own keypair
- Provide short-lived tokens with configurable expiry
- Secure-by-default: `brace new` auto-generates keys so projects start with real auth
- Keep ops accessible when the database is down (file-based, no DB dependency)

## Keypair & Authorized Keys

### Key format

Ed25519 (available in JDK since Java 15). 32-byte keys, fast signing, constant-time operations.

### Key generation

Static utility method `Brace.generateOpsKeypair()` generates an Ed25519 keypair and returns both keys as base64-encoded strings. Used internally by `ProjectGenerator` and the CLI.

### Authorized keys file

`ops-authorized-keys` in the project root. Format:

```
# One Ed25519 public key per line. Optional label after a space.
MCowBQYDK2VwAyEA...  agent-1
MCowBQYDK2VwAyEA...  matt-laptop
```

- Lines starting with `#` are comments
- Empty lines are ignored
- Read once at startup, held in memory
- Safe to commit to version control (public keys only)

### API change

`Brace.ops(String secret)` becomes `Brace.ops(String authorizedKeysPath)`. At startup it reads the file, parses the public keys, and stores them in memory.

## Auth Flow

### Endpoint

`POST /ops/auth` — the only unprotected ops endpoint.

### Request

```json
{
  "timestamp": "2026-04-08T14:30:00Z",
  "publicKey": "MCowBQYDK2VwAyEA...",
  "signature": "<base64 Ed25519 signature of the timestamp string>"
}
```

The client includes their public key so the server can look it up in O(1) rather than trying all authorized keys.

### Server validation

1. Check the public key is in the authorized keys list
2. Check the timestamp is within ±30 seconds of server time
3. Verify the Ed25519 signature of the timestamp string against the public key

### Response

```json
{
  "token": "eyJleHAiOjE3MTI2MDAwMDB9.HMAC...",
  "expiresAt": "2026-04-08T15:30:00Z"
}
```

On failure: `401 Unauthorized` with a JSON error message.

## Token Format

Same pattern as Brace session cookies: `base64url(json).base64url(hmac-sha256)`.

### Payload

```json
{"exp": 1712600000}
```

No user identity, no scope. Just an expiry timestamp. Scope may be added later.

### Signing

The server generates a random 32-byte HMAC secret at startup. This secret is not configurable and not persisted. On server restart, all existing tokens naturally invalidate — acceptable since tokens are short-lived.

### Validation

When an ops endpoint receives a token:

1. Split on `.`, verify the HMAC signature
2. Decode the JSON, check `exp` > current time

No database, no in-memory token map, no cleanup.

### Token delivery

Clients provide tokens via:

- `Authorization: Bearer <token>` header (preferred for API clients)
- `?token=<token>` query parameter (for dashboard browser access)

## Expiry Configuration

### Defaults

| Use case | Default TTL |
|----------|-------------|
| API token | 1 hour |
| Dashboard token | 2 hours |
| Maximum allowed TTL | 24 hours |

### Configuration

```
ops.token.ttl=3600
ops.token.max-ttl=86400
ops.dashboard.token.ttl=7200
```

### Client-requested TTL

Clients may include `ttlSeconds` in the auth request:

```json
{
  "timestamp": "...",
  "publicKey": "...",
  "signature": "...",
  "ttlSeconds": 7200
}
```

The server caps this at `ops.token.max-ttl`.

## Ops Endpoint Changes

### authorize(req) method

Changes from checking `X-Ops-Key` / `?key=` against a shared secret to:

1. Check for `Authorization: Bearer <token>` header
2. If not present, check for `?token=<token>` query parameter
3. Validate the token (HMAC check + expiry check)

All existing ops endpoint methods continue calling `authorize(req)` — only the internals change.

### Dashboard

`OpsDashboard.html(opsSecret)` becomes `OpsDashboard.html(token)`. The dashboard endpoint issues a token with dashboard TTL and embeds it in the JS. The dashboard JavaScript uses `Authorization: Bearer <token>` for all fetch calls. When the token expires (API returns 401), the dashboard shows a message prompting the user to refresh. Navigating to `/ops/dashboard` without a valid token returns 401 — users must obtain a token via the CLI command or auth endpoint first.

### Removed

- `X-Ops-Key` header support
- `?key=` query parameter for shared secret
- `ops.secret` config key

## Project Generator Changes

`ProjectGenerator.generate()` updated to:

1. Generate an Ed25519 keypair via `Brace.generateOpsKeypair()`
2. Write `ops-authorized-keys` with the public key labeled `initial`
3. Write `ops-private.key` with the private key
4. Add `ops-private.key` and `*.key` to `.gitignore`
5. Generate `App.java` with `app.ops("ops-authorized-keys")` instead of `app.ops(config.get("ops.secret"))`
6. Remove `ops.secret` from generated `application.conf`
7. Print: "Ops private key written to ops-private.key — keep this safe"

## CLI Commands

Two new subcommands under `brace ops`, added to the existing `Cli.java` switch:

### `brace ops keypair [--label <name>]`

Generates a new Ed25519 keypair. Prints both keys. Appends the public key to `ops-authorized-keys` in the current directory with the given label (defaults to `key-N`).

```
$ brace ops keypair --label matt-laptop
Public key:   MCowBQYDK2VwAyEA...
Private key:  MC4CAQAwBQYDK2VwBCIEIA...

Added to ops-authorized-keys.
Store the private key securely — it won't be shown again.
```

### `brace ops dashboard [--url <url>] [--key <path>]`

Authenticates with a private key and opens the dashboard in the default browser.

```
$ brace ops dashboard --url http://localhost:8080 --key ops-private.key
Opening dashboard: http://localhost:8080/ops/dashboard?token=eyJ...
```

- `--url` defaults to `http://localhost:8080`
- `--key` defaults to `ops-private.key` in the current directory, falls back to `OPS_PRIVATE_KEY` env var
- Hits `POST /ops/auth` requesting dashboard TTL
- Opens the resulting URL via `Desktop.browse()` or equivalent

## OpsHandler Constructor Change

Current constructor takes `String opsSecret`. This changes to accept the parsed authorized keys and a token signer:

- `List<PublicKey> authorizedKeys` — parsed from the authorized keys file
- Token signing secret — generated at startup, passed in or generated internally

The exact constructor signature will be determined during implementation, but the key change is: no more shared secret string.

## Security Properties

- **No shared secrets.** Each client holds their own private key.
- **Revocation.** Remove a public key from the file, deploy.
- **Short-lived tokens.** Even if a token leaks, it expires.
- **No server-side token state.** Tokens are self-validating via HMAC. No cleanup, no memory growth.
- **No DB dependency.** Auth works when the database is down.
- **Replay protection.** Timestamp window (±30s) prevents replaying old auth requests.
- **Restart invalidation.** Random HMAC secret means all tokens invalidate on restart.

## Testing

- Unit tests for keypair generation, signature verification, token creation/validation
- Unit tests for authorized keys file parsing (comments, empty lines, labels)
- Integration test: full auth flow (generate keypair → auth request → use token → access ops endpoint)
- Integration test: expired token rejection
- Integration test: unauthorized public key rejection
- Integration test: timestamp outside ±30s window rejection
- Existing ops endpoint tests updated to use token auth
