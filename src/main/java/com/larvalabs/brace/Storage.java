package com.larvalabs.brace;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * S3-compatible storage client with built-in AWS Signature V4 signing.
 * Works with AWS S3, Cloudflare R2, MinIO, DigitalOcean Spaces.
 * No SDK dependency — uses java.net.http.HttpClient.
 */
public class Storage {

    /** Default per-request timeout; without one, a blackholed endpoint hangs the caller forever. */
    static final java.time.Duration DEFAULT_REQUEST_TIMEOUT = java.time.Duration.ofSeconds(60);
    private static final java.time.Duration CONNECT_TIMEOUT = java.time.Duration.ofSeconds(10);

    private final String accessKeyId;
    private final String secretKey;
    private final String bucket;
    private final String region;
    private final String endpoint;
    private final String publicUrl;
    private final String host;
    private final HttpClient httpClient;
    private final java.time.Duration requestTimeout;

    public Storage(String accessKeyId, String secretKey, String bucket, String region,
                   String endpoint, String publicUrl) {
        this(accessKeyId, secretKey, bucket, region, endpoint, publicUrl, DEFAULT_REQUEST_TIMEOUT);
    }

    public Storage(String accessKeyId, String secretKey, String bucket, String region,
                   String endpoint, String publicUrl, java.time.Duration requestTimeout) {
        this.accessKeyId = accessKeyId;
        this.secretKey = secretKey;
        this.bucket = bucket;
        this.region = region;
        this.endpoint = endpoint;
        this.publicUrl = publicUrl;
        this.requestTimeout = requestTimeout;

        if (endpoint != null) {
            this.host = endpoint.replaceFirst("https?://", "");
        } else {
            this.host = bucket + ".s3." + region + ".amazonaws.com";
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Create a Storage instance from Brace Config.
     * Required keys: s3.accessKeyId, s3.secretKey, s3.bucket, s3.region
     * Optional keys: s3.endpoint, s3.publicUrl, s3.timeoutSeconds (per-request, default 60)
     */
    public static Storage s3(Config config) {
        var accessKeyId = config.get("s3.accessKeyId");
        var secretKey = config.get("s3.secretKey");
        var bucket = config.get("s3.bucket");
        var region = config.get("s3.region");
        if (accessKeyId == null || secretKey == null || bucket == null || region == null) {
            throw new RuntimeException("Missing required S3 config: s3.accessKeyId, s3.secretKey, s3.bucket, s3.region");
        }
        var endpoint = config.get("s3.endpoint");
        var publicUrl = config.get("s3.publicUrl");
        var timeout = java.time.Duration.ofSeconds(
                config.getInt("s3.timeoutSeconds", (int) DEFAULT_REQUEST_TIMEOUT.toSeconds()));
        return new Storage(accessKeyId, secretKey, bucket, region, endpoint, publicUrl, timeout);
    }

    /**
     * Returns the public URL for a given object key.
     */
    public String url(String key) {
        requireSafeKey(key);
        var encoded = uriEncodePath(key);
        if (publicUrl != null) {
            return publicUrl + "/" + encoded;
        }
        if (endpoint != null) {
            return endpoint + "/" + bucket + "/" + encoded;
        }
        return "https://" + host + "/" + encoded;
    }

    /**
     * Extracts the object key from a full URL, or returns null if unrecognized.
     */
    public String keyFromUrl(String url) {
        if (url == null) return null;
        String encoded = null;
        if (publicUrl != null) {
            var prefix = publicUrl + "/";
            if (url.startsWith(prefix)) encoded = url.substring(prefix.length());
        }
        if (encoded == null && endpoint != null) {
            var prefix = endpoint + "/" + bucket + "/";
            if (url.startsWith(prefix)) encoded = url.substring(prefix.length());
        }
        if (encoded == null) {
            var awsPrefix = "https://" + bucket + ".s3." + region + ".amazonaws.com/";
            if (url.startsWith(awsPrefix)) encoded = url.substring(awsPrefix.length());
        }
        if (encoded == null) return null;
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    /**
     * Upload bytes to storage. Returns the public URL.
     *
     * <p>Holds the whole object in heap by definition. For anything that might be large, prefer
     * {@link #put(String, Path, String)} or {@link #put(String, UploadedFile)}, which stream.
     */
    public String put(String key, byte[] data, String contentType) {
        return putStreaming(key, () -> new java.io.ByteArrayInputStream(data), data.length, contentType);
    }

    /**
     * Upload a file from disk without reading it into the heap. Returns the public URL.
     */
    public String put(String key, Path path, String contentType) {
        long length;
        try {
            length = Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
        return putStreaming(key, () -> {
            try {
                return Files.newInputStream(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, length, contentType);
    }

    /**
     * Upload an UploadedFile with a user-specified key. Returns a StoredFile record.
     *
     * <p>Streams: an upload that spilled to a temp file goes to storage without ever being
     * materialized in the heap.
     */
    public StoredFile put(String key, UploadedFile file) {
        String url = putStreaming(key, file::stream, file.size(), file.contentType());
        return new StoredFile(key, url);
    }

    /**
     * Upload an UploadedFile with an auto-generated safe key (UUID-based).
     * Returns a StoredFile record containing the generated key and public URL.
     */
    public StoredFile putGenerated(String folder, UploadedFile file) {
        String key = safeKey(folder, file.filename());
        String url = putStreaming(key, file::stream, file.size(), file.contentType());
        return new StoredFile(key, url);
    }

    /**
     * The single upload path: hash the content, then send it, reading it twice and buffering none
     * of it.
     *
     * <p>SigV4 signs {@code x-amz-content-sha256}, so the digest has to be known before the first
     * byte goes out — which is why {@code source} must be repeatable rather than a one-shot stream.
     * The alternative is {@code UNSIGNED-PAYLOAD}, which drops the payload from the signature
     * entirely; keeping a real digest means the request stays byte-for-byte verifiable and works
     * identically on S3, R2, MinIO, and Spaces. The second pass is usually served from page cache.
     *
     * <p>{@code fromPublisher(..., length)} rather than a bare {@code ofInputStream}: the latter
     * publishes with an unknown length, so the JDK client falls back to chunked transfer encoding,
     * which S3 rejects for a plain {@code PUT}.
     */
    private String putStreaming(String key, java.util.function.Supplier<InputStream> source,
                                long length, String contentType) {
        requireSafeKey(key);
        if (length < 0) {
            throw new IllegalArgumentException(
                "Upload length must be known before signing (got " + length + " for key " + key + ")");
        }
        if (length > MAX_SINGLE_PUT_BYTES) {
            throw new IllegalArgumentException(
                "Object is " + length + " bytes; a single S3 PUT is capped at " + MAX_SINGLE_PUT_BYTES
                    + " (5 GiB). Objects above this need the multipart upload API, which Brace does "
                    + "not implement yet — upload directly to the bucket instead.");
        }
        try {
            var now = Instant.now();
            var amzDate = AMZ_DATE_FORMAT.format(now);
            var dateStamp = DATE_STAMP_FORMAT.format(now);

            var payloadHash = sha256Hex(source);
            var auth = buildAuthHeader("PUT", key, contentType, payloadHash, amzDate, dateStamp);

            var uploadUrl = buildUploadUrl(key);
            var body = HttpRequest.BodyPublishers.fromPublisher(
                    HttpRequest.BodyPublishers.ofInputStream(source::get), length);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .timeout(requestTimeout)
                    .method("PUT", body)
                    .header("Content-Type", contentType)
                    .header("x-amz-content-sha256", payloadHash)
                    .header("x-amz-date", amzDate)
                    .header("Authorization", auth)
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                Log.event("s3.put.error", java.util.Map.of(
                        "key", key, "status", response.statusCode(), "body", response.body()));
                throw new RuntimeException("S3 upload failed with status " + response.statusCode());
            }

            return url(key);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("S3 upload failed", e);
        }
    }

    /** S3's hard ceiling for a single PUT; larger objects require the multipart upload API. */
    static final long MAX_SINGLE_PUT_BYTES = 5L * 1024 * 1024 * 1024;

    /**
     * Generate a safe storage key from a folder and original filename.
     * Uses UUID to prevent conflicts and sanitizes the extension.
     * Example: safeKey("avatars", "user photo.jpg") -> "avatars/a1b2c3d4-e5f6-7890-abcd-ef1234567890.jpg"
     */
    public static String safeKey(String folder, String originalName) {
        String uuid = UUID.randomUUID().toString();
        String ext = extension(originalName);
        if (ext != null && !ext.isEmpty()) {
            return folder + "/" + uuid + "." + ext;
        }
        return folder + "/" + uuid;
    }

    /**
     * Extract the file extension from a filename (without the dot).
     * Returns null if no extension found.
     * Sanitizes the extension to contain only alphanumeric characters.
     */
    public static String extension(String filename) {
        if (filename == null || filename.isEmpty()) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        String ext = filename.substring(dot + 1).toLowerCase();
        // Only allow alphanumeric extensions (prevents path traversal)
        return ext.matches("[a-z0-9]+") ? ext : null;
    }

    /**
     * Delete an object from storage. Logs warning on failure but does not throw.
     */
    public void delete(String key) {
        requireSafeKey(key);
        try {
            var now = Instant.now();
            var amzDate = AMZ_DATE_FORMAT.format(now);
            var dateStamp = DATE_STAMP_FORMAT.format(now);

            var payloadHash = sha256Hex(new byte[0]);
            var auth = buildAuthHeader("DELETE", key, null, payloadHash, amzDate, dateStamp);

            var deleteUrl = buildUploadUrl(key);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(deleteUrl))
                    .timeout(requestTimeout)
                    .method("DELETE", HttpRequest.BodyPublishers.noBody())
                    .header("x-amz-content-sha256", payloadHash)
                    .header("x-amz-date", amzDate)
                    .header("Authorization", auth)
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 204 && response.statusCode() != 200) {
                Log.event("s3.delete.warning", java.util.Map.of(
                        "key", key, "status", response.statusCode()));
            }
        } catch (Exception e) {
            Log.event("s3.delete.error", java.util.Map.of("key", key, "error", e.getMessage()));
        }
    }

    /**
     * Build the Authorization header for an AWS Signature V4 signed request.
     * Package-private for testing.
     *
     * <p>{@code host} is signed but never set as a request header: {@code Host} is on the JDK
     * HttpClient's restricted list, and passing it to {@code HttpRequest.Builder.header} throws
     * {@code IllegalArgumentException: restricted header name: "Host"} unless the JVM was started
     * with {@code -Djdk.httpclient.allowRestrictedHeaders=host}. Every {@code put} and
     * {@code delete} used to set it, so both threw on every call in any JVM without that flag.
     * The client derives {@code Host} from the request URI, and {@link #host} is built from that
     * same URI's authority, so the signed value and the sent value agree without our help —
     * {@code StorageStreamingTest.signedHostMatchesTheHostActuallySent} pins that.
     */
    String buildAuthHeader(String method, String key, String contentType,
                           String payloadHash, String amzDate, String dateStamp) {
        try {
            var uri = canonicalUri(key);

            String canonicalHeaders;
            String signedHeaders;
            if (contentType != null) {
                canonicalHeaders = "content-type:" + contentType + "\n"
                        + "host:" + host + "\n"
                        + "x-amz-content-sha256:" + payloadHash + "\n"
                        + "x-amz-date:" + amzDate + "\n";
                signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date";
            } else {
                canonicalHeaders = "host:" + host + "\n"
                        + "x-amz-content-sha256:" + payloadHash + "\n"
                        + "x-amz-date:" + amzDate + "\n";
                signedHeaders = "host;x-amz-content-sha256;x-amz-date";
            }

            var canonicalRequest = method + "\n" + uri + "\n" + "\n"
                    + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

            var algorithm = "AWS4-HMAC-SHA256";
            var credentialScope = dateStamp + "/" + region + "/s3/aws4_request";
            var stringToSign = algorithm + "\n" + amzDate + "\n" + credentialScope + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

            var signature = bytesToHex(hmacSha256(signingKey(dateStamp), stringToSign));

            return algorithm + " Credential=" + accessKeyId + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build auth header", e);
        }
    }

    // --- Internal helpers ---

    // DateTimeFormatter is immutable and thread-safe; building one per put/delete was waste.
    private static final DateTimeFormatter AMZ_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private static final java.util.HexFormat HEX = java.util.HexFormat.of();

    // SigV4 signing keys depend only on (secret, dateStamp, region, service), all fixed per
    // instance except dateStamp, which changes once per UTC day — cache the derived key per
    // day instead of re-running the 4-step HMAC chain on every request. Benign race: two
    // threads may derive the same key concurrently and one wins the volatile swap.
    private record DayKey(String dateStamp, byte[] key) {}
    private volatile DayKey signingKeyCache;

    private byte[] signingKey(String dateStamp) {
        DayKey cached = signingKeyCache;
        if (cached != null && cached.dateStamp().equals(dateStamp)) {
            return cached.key();
        }
        byte[] key = getSignatureKey(secretKey, dateStamp, region, "s3");
        signingKeyCache = new DayKey(dateStamp, key);
        return key;
    }

    private String buildUploadUrl(String key) {
        var encoded = uriEncodePath(key);
        if (endpoint != null) {
            return endpoint + "/" + bucket + "/" + encoded;
        }
        return "https://" + host + "/" + encoded;
    }

    private String canonicalUri(String key) {
        var encoded = uriEncodePath(key);
        if (endpoint != null) {
            return "/" + bucket + "/" + encoded;
        }
        return "/" + encoded;
    }

    /**
     * Reject an object key that would resolve outside its intended prefix (2026-07 review, L5).
     *
     * <p>{@link #uriEncodePath} splits on {@code /} and percent-encodes each segment, but {@code .}
     * is unreserved — so a {@code ..} segment survived encoding intact, and
     * {@link #buildUploadUrl} and {@link #canonicalUri} built the same unnormalized path. The
     * request was therefore <em>validly signed</em> for the traversed key, and an endpoint that
     * normalizes the path would act on it. {@link #putGenerated} is unaffected (UUID keys); the
     * exposure is {@link #put(String, byte[], String)} with an app-assembled key containing user
     * input.
     */
    static void requireSafeKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Storage key must not be empty");
        }
        if (key.startsWith("/")) {
            throw new IllegalArgumentException("Storage key must not start with '/': " + key);
        }
        for (var segment : key.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new IllegalArgumentException(
                    "Storage key must not contain '.' or '..' path segments (use Storage.safeKey "
                        + "to build a key from user input): " + key);
            }
        }
    }

    static String uriEncodePath(String key) {
        var sb = new StringBuilder();
        for (var segment : key.split("/", -1)) {
            if (!sb.isEmpty()) sb.append("/");
            sb.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    static String sha256Hex(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Streaming digest — the first of {@code putStreaming}'s two passes. Buffers nothing. */
    static String sha256Hex(java.util.function.Supplier<InputStream> source) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var buf = new byte[64 * 1024];
            try (var in = source.get()) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return bytesToHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash upload payload", e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] hmacSha256(byte[] key, String data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] getSignatureKey(String secretKey, String dateStamp, String region, String service) {
        var kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        var kDate = hmacSha256(kSecret, dateStamp);
        var kRegion = hmacSha256(kDate, region);
        var kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    static String bytesToHex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    /**
     * Record representing a stored file with its key and public URL.
     */
    public record StoredFile(String key, String url) {}
}
