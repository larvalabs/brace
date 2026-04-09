package io.brace;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * S3-compatible storage client with built-in AWS Signature V4 signing.
 * Works with AWS S3, Cloudflare R2, MinIO, DigitalOcean Spaces.
 * No SDK dependency — uses java.net.http.HttpClient.
 */
public class Storage {

    private final String accessKeyId;
    private final String secretKey;
    private final String bucket;
    private final String region;
    private final String endpoint;
    private final String publicUrl;
    private final String host;
    private final HttpClient httpClient;

    public Storage(String accessKeyId, String secretKey, String bucket, String region,
                   String endpoint, String publicUrl) {
        this.accessKeyId = accessKeyId;
        this.secretKey = secretKey;
        this.bucket = bucket;
        this.region = region;
        this.endpoint = endpoint;
        this.publicUrl = publicUrl;

        if (endpoint != null) {
            this.host = endpoint.replaceFirst("https?://", "");
        } else {
            this.host = bucket + ".s3." + region + ".amazonaws.com";
        }

        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Create a Storage instance from Brace Config.
     * Required keys: s3.accessKeyId, s3.secretKey, s3.bucket, s3.region
     * Optional keys: s3.endpoint, s3.publicUrl
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
        return new Storage(accessKeyId, secretKey, bucket, region, endpoint, publicUrl);
    }

    /**
     * Returns the public URL for a given object key.
     */
    public String url(String key) {
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
     */
    public String put(String key, byte[] data, String contentType) {
        try {
            var now = Instant.now();
            var amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC).format(now);
            var dateStamp = DateTimeFormatter.ofPattern("yyyyMMdd")
                    .withZone(ZoneOffset.UTC).format(now);

            var payloadHash = sha256Hex(data);
            var auth = buildAuthHeader("PUT", key, contentType, payloadHash, amzDate, dateStamp);

            var uploadUrl = buildUploadUrl(key);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .method("PUT", HttpRequest.BodyPublishers.ofByteArray(data))
                    .header("Content-Type", contentType)
                    .header("Host", host)
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

    /**
     * Delete an object from storage. Logs warning on failure but does not throw.
     */
    public void delete(String key) {
        try {
            var now = Instant.now();
            var amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC).format(now);
            var dateStamp = DateTimeFormatter.ofPattern("yyyyMMdd")
                    .withZone(ZoneOffset.UTC).format(now);

            var payloadHash = sha256Hex(new byte[0]);
            var auth = buildAuthHeader("DELETE", key, null, payloadHash, amzDate, dateStamp);

            var deleteUrl = buildUploadUrl(key);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(deleteUrl))
                    .method("DELETE", HttpRequest.BodyPublishers.noBody())
                    .header("Host", host)
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

            var signingKey = getSignatureKey(secretKey, dateStamp, region, "s3");
            var signature = bytesToHex(hmacSha256(signingKey, stringToSign));

            return algorithm + " Credential=" + accessKeyId + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build auth header", e);
        }
    }

    // --- Internal helpers ---

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
        var sb = new StringBuilder(bytes.length * 2);
        for (var b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
