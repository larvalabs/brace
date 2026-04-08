# S3-Compatible Storage Design

**Date:** 2026-04-08
**Status:** Approved

## Overview

Add S3-compatible object storage to Brace with built-in AWS Signature V4 signing. No SDK dependency. Works with AWS S3, Cloudflare R2, MinIO, DigitalOcean Spaces, and any S3-compatible provider.

## Goals

- Simple API: `storage.put()`, `storage.delete()`, `storage.url()`
- Built-in AWS Sig V4 signing using `javax.crypto.Mac` and `java.security.MessageDigest`
- Zero external dependencies
- Works with any S3-compatible endpoint
- Integrates with `req.file()` uploads
- Uses Brace's existing `Http` client (java.net.http.HttpClient) instead of raw HttpURLConnection

## API

### Configuration

```java
var storage = Storage.s3(config);  // reads s3.* keys from Config
app.storage(storage);             // registers with the app, makes available via req.storage()
```

Config keys:

```
s3.accessKeyId=AKIA...
s3.secretKey=wJalr...
s3.bucket=my-bucket
s3.region=us-east-1
s3.endpoint=                    # optional, for R2/MinIO (e.g., https://acct.r2.cloudflarestorage.com)
s3.publicUrl=                   # optional, public URL prefix (e.g., https://cdn.example.com)
```

### Storage Operations

```java
// Upload bytes, returns public URL
String url = storage.put("uploads/photo.jpg", bytes, "image/jpeg");

// Upload from file upload
var file = req.file("avatar");
String url = storage.put("avatars/" + file.name(), file.bytes(), file.contentType());

// Delete
storage.delete("uploads/photo.jpg");

// Get public URL for a key (no network call)
String url = storage.url("uploads/photo.jpg");

// Extract key from a full URL (inverse of url())
String key = storage.keyFromUrl("https://cdn.example.com/uploads/photo.jpg");
```

### Access from Request

When registered via `app.storage(storage)`, available in handlers:

```java
app.post("/upload", (req, db) -> {
    var file = req.file("photo");
    String url = req.storage().put("photos/" + file.name(), file.bytes(), file.contentType());
    return Json.of(Map.of("url", url));
});
```

## Internal Design

### Storage Class

Single class `Storage` (~200 lines):

```java
public class Storage {
    private final String accessKeyId;
    private final String secretKey;
    private final String bucket;
    private final String region;
    private final String endpoint;    // nullable
    private final String publicUrl;   // nullable

    public static Storage s3(Config config) { ... }

    public String put(String key, byte[] data, String contentType) { ... }
    public void delete(String key) { ... }
    public String url(String key) { ... }
    public String keyFromUrl(String url) { ... }
}
```

### AWS Signature V4 Signing

Private helper methods within `Storage`:

- `sign(String method, String key, byte[] body, String contentType)` — builds canonical request, string to sign, and authorization header
- `sha256Hex(byte[])` — SHA-256 hash as hex string
- `hmacSha256(byte[], String)` — HMAC-SHA256
- `getSignatureKey(String secretKey, String dateStamp, String region, String service)` — 4-stage HMAC key derivation

Signing follows the standard AWS Sig V4 flow:
1. Create canonical request (method, URI, query string, headers, payload hash)
2. Create string to sign (algorithm, timestamp, credential scope, canonical request hash)
3. Derive signing key (secret → date → region → service → "aws4_request")
4. Compute signature (HMAC-SHA256 of string to sign with signing key)
5. Build Authorization header

### HTTP Requests

Uses Brace's existing `java.net.http.HttpClient` directly (not the `Http` wrapper, which is for external API calls with JSON convenience). The S3 requests need precise header control for signing.

### Host and URL Logic

- **Custom endpoint** (R2, MinIO): host from endpoint, URL = `endpoint/bucket/key`, canonical URI = `/bucket/key`
- **Standard AWS S3**: host = `bucket.s3.region.amazonaws.com`, URL = `https://host/key`, canonical URI = `/key`

### Public URL Resolution

- `url(key)`: if `publicUrl` configured, returns `publicUrl/key`. Otherwise returns the S3 URL.
- `put()`: returns the public URL after successful upload.
- `keyFromUrl()`: strips `publicUrl` or endpoint prefix to extract the key.

### URI Encoding

Path segments are individually URI-encoded (preserving `/` separators). Spaces become `%20` (not `+`).

## Integration Points

### Brace App

- `Brace.storage(Storage)` — stores the instance
- Passed to `BraceHandler`, which makes it available on `Request`

### Request

- `req.storage()` — returns the `Storage` instance. Throws if not configured.

### No Dashboard Integration

Storage doesn't appear in the ops dashboard. File upload metrics (count, bytes) can be added later as custom metrics using `Stats.counter()`.

## Error Handling

- `put()` throws `RuntimeException` with status code and error body on non-2xx response
- `delete()` logs a warning on unexpected status codes but doesn't throw (idempotent operation)
- Config validation at startup: missing `accessKeyId`, `secretKey`, or `bucket` throws immediately

## Testing

- Unit test: URL construction for AWS S3 vs custom endpoint
- Unit test: canonical URI generation
- Unit test: public URL resolution and key extraction
- Unit test: URI encoding of paths with special characters
- Unit test: Sig V4 signing produces valid authorization header (test against known test vectors)
- Integration test: `req.storage()` available when configured, throws when not
- Integration test: put/delete/url round-trip with mock S3 server (or test against real R2 in CI)
