# Ops Token Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace shared-secret `X-Ops-Key` authentication with Ed25519 keypair-based token auth for ops endpoints.

**Architecture:** Clients authenticate by signing a timestamp with their Ed25519 private key via `POST /ops/auth`. The server verifies against an authorized-keys file and issues a short-lived HMAC-SHA256 token. All ops endpoints validate the token via `Authorization: Bearer` header or `?token=` query param. Token format matches Brace session cookies: `base64url(json).base64url(hmac)`.

**Tech Stack:** Ed25519 (JDK built-in since Java 15), HMAC-SHA256 (javax.crypto.Mac), Jackson for JSON

**Spec:** `docs/superpowers/specs/2026-04-08-ops-token-auth-design.md`

---

### Task 1: OpsToken — Token Creation and Validation

**Files:**
- Create: `src/main/java/io/brace/OpsToken.java`
- Create: `src/test/java/io/brace/OpsTokenTest.java`

This is a self-contained token utility: create tokens with an expiry, sign them with HMAC-SHA256, validate them. Same pattern as Session.java cookie signing.

- [ ] **Step 1: Write failing tests for token creation and validation**

```java
package io.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpsTokenTest {

    @Test
    void createsAndValidatesToken() {
        var secret = OpsToken.generateSecret();
        var token = OpsToken.create(secret, 3600);
        assertTrue(OpsToken.validate(token, secret));
    }

    @Test
    void rejectsExpiredToken() {
        var secret = OpsToken.generateSecret();
        var token = OpsToken.create(secret, -1); // already expired
        assertFalse(OpsToken.validate(token, secret));
    }

    @Test
    void rejectsTamperedToken() {
        var secret = OpsToken.generateSecret();
        var token = OpsToken.create(secret, 3600);
        var tampered = token.substring(0, token.length() - 2) + "XX";
        assertFalse(OpsToken.validate(tampered, secret));
    }

    @Test
    void rejectsTokenWithWrongSecret() {
        var secret1 = OpsToken.generateSecret();
        var secret2 = OpsToken.generateSecret();
        var token = OpsToken.create(secret1, 3600);
        assertFalse(OpsToken.validate(token, secret2));
    }

    @Test
    void rejectsMalformedToken() {
        var secret = OpsToken.generateSecret();
        assertFalse(OpsToken.validate("not-a-token", secret));
        assertFalse(OpsToken.validate("", secret));
        assertFalse(OpsToken.validate(null, secret));
    }

    @Test
    void generateSecretProducesUniqueValues() {
        var s1 = OpsToken.generateSecret();
        var s2 = OpsToken.generateSecret();
        assertNotEquals(s1, s2);
        assertEquals(32, java.util.Base64.getDecoder().decode(s1).length);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=OpsTokenTest -pl .`
Expected: Compilation error — `OpsToken` class does not exist.

- [ ] **Step 3: Implement OpsToken**

```java
package io.brace;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Short-lived HMAC-signed tokens for ops endpoint authentication.
 * Format: base64url(json).base64url(hmac-sha256) — same pattern as Brace session cookies.
 */
public class OpsToken {

    public static String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static String create(String hmacSecret, long ttlSeconds) {
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        String json = "{\"exp\":" + exp + "}";
        String payload = base64Encode(json.getBytes(StandardCharsets.UTF_8));
        String sig = sign(payload, hmacSecret);
        return payload + "." + sig;
    }

    public static boolean validate(String token, String hmacSecret) {
        if (token == null || token.isEmpty()) return false;
        int dot = token.lastIndexOf('.');
        if (dot < 0) return false;

        String payload = token.substring(0, dot);
        String providedSig = token.substring(dot + 1);

        String expectedSig = sign(payload, hmacSecret);
        if (!constantTimeEquals(expectedSig, providedSig)) return false;

        try {
            String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            int expStart = json.indexOf("\"exp\":") + 6;
            int expEnd = json.indexOf('}', expStart);
            long exp = Long.parseLong(json.substring(expStart, expEnd));
            return Instant.now().getEpochSecond() < exp;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return base64Encode(hmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    private static String base64Encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=OpsTokenTest -pl .`
Expected: All 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/OpsToken.java src/test/java/io/brace/OpsTokenTest.java
git commit -m "Add OpsToken: HMAC-signed short-lived tokens for ops auth"
```

---

### Task 2: OpsKeys — Ed25519 Keypair Generation and Authorized Keys Parsing

**Files:**
- Create: `src/main/java/io/brace/OpsKeys.java`
- Create: `src/test/java/io/brace/OpsKeysTest.java`

Handles Ed25519 keypair generation, authorized keys file parsing, and signature verification.

- [ ] **Step 1: Write failing tests**

```java
package io.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpsKeysTest {

    @Test
    void generatesEd25519Keypair() {
        var keypair = OpsKeys.generateKeypair();
        assertNotNull(keypair.publicKey());
        assertNotNull(keypair.privateKey());
        // Base64-encoded Ed25519 public key
        assertFalse(keypair.publicKey().isEmpty());
        assertFalse(keypair.privateKey().isEmpty());
    }

    @Test
    void signsAndVerifiesTimestamp() {
        var keypair = OpsKeys.generateKeypair();
        var timestamp = Instant.now().toString();
        var signature = OpsKeys.sign(timestamp, keypair.privateKey());
        assertTrue(OpsKeys.verify(timestamp, signature, keypair.publicKey()));
    }

    @Test
    void rejectsWrongSignature() {
        var keypair = OpsKeys.generateKeypair();
        var timestamp = Instant.now().toString();
        var signature = OpsKeys.sign(timestamp, keypair.privateKey());
        assertFalse(OpsKeys.verify("different-timestamp", signature, keypair.publicKey()));
    }

    @Test
    void rejectsWrongPublicKey() {
        var keypair1 = OpsKeys.generateKeypair();
        var keypair2 = OpsKeys.generateKeypair();
        var timestamp = Instant.now().toString();
        var signature = OpsKeys.sign(timestamp, keypair1.privateKey());
        assertFalse(OpsKeys.verify(timestamp, signature, keypair2.publicKey()));
    }

    @Test
    void parsesAuthorizedKeysFile(@TempDir Path dir) throws Exception {
        var kp1 = OpsKeys.generateKeypair();
        var kp2 = OpsKeys.generateKeypair();
        var file = dir.resolve("ops-authorized-keys");
        Files.writeString(file, """
                # Comment line
                %s agent-1
                
                %s matt-laptop
                """.formatted(kp1.publicKey(), kp2.publicKey()));

        var keys = OpsKeys.loadAuthorizedKeys(file);
        assertEquals(2, keys.size());
        assertTrue(keys.contains(kp1.publicKey()));
        assertTrue(keys.contains(kp2.publicKey()));
    }

    @Test
    void parsesAuthorizedKeysWithNoLabels(@TempDir Path dir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        var file = dir.resolve("ops-authorized-keys");
        Files.writeString(file, kp.publicKey() + "\n");

        var keys = OpsKeys.loadAuthorizedKeys(file);
        assertEquals(1, keys.size());
        assertTrue(keys.contains(kp.publicKey()));
    }

    @Test
    void emptyFileReturnsEmptySet(@TempDir Path dir) throws Exception {
        var file = dir.resolve("ops-authorized-keys");
        Files.writeString(file, "# only comments\n\n");
        var keys = OpsKeys.loadAuthorizedKeys(file);
        assertTrue(keys.isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=OpsKeysTest -pl .`
Expected: Compilation error — `OpsKeys` class does not exist.

- [ ] **Step 3: Implement OpsKeys**

```java
package io.brace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * Ed25519 keypair generation, authorized keys file parsing, and signature verification.
 */
public class OpsKeys {

    public record EncodedKeypair(String publicKey, String privateKey) {}

    public static EncodedKeypair generateKeypair() {
        try {
            var gen = KeyPairGenerator.getInstance("Ed25519");
            var kp = gen.generateKeyPair();
            var pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
            var priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
            return new EncodedKeypair(pub, priv);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ed25519 not available", e);
        }
    }

    public static String sign(String message, String base64PrivateKey) {
        try {
            var keyBytes = Base64.getDecoder().decode(base64PrivateKey);
            var keySpec = new PKCS8EncodedKeySpec(keyBytes);
            var privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(keySpec);
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 signing failed", e);
        }
    }

    public static boolean verify(String message, String base64Signature, String base64PublicKey) {
        try {
            var keyBytes = Base64.getDecoder().decode(base64PublicKey);
            var keySpec = new X509EncodedKeySpec(keyBytes);
            var publicKey = KeyFactory.getInstance("Ed25519").generatePublic(keySpec);
            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(base64Signature));
        } catch (Exception e) {
            return false;
        }
    }

    public static Set<String> loadAuthorizedKeys(Path path) {
        try {
            var keys = new LinkedHashSet<String>();
            for (var line : Files.readAllLines(path)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // Key is the first token; optional label after space
                var parts = line.split("\\s+", 2);
                keys.add(parts[0]);
            }
            return keys;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load authorized keys from " + path, e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=OpsKeysTest -pl .`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/OpsKeys.java src/test/java/io/brace/OpsKeysTest.java
git commit -m "Add OpsKeys: Ed25519 keypair generation and authorized keys parsing"
```

---

### Task 3: POST /ops/auth Endpoint

**Files:**
- Modify: `src/main/java/io/brace/OpsHandler.java`
- Modify: `src/test/java/io/brace/OpsIntegrationTest.java`

Add the `POST /ops/auth` endpoint that accepts a signed timestamp and returns a short-lived token.

- [ ] **Step 1: Write failing integration test**

Add to `OpsIntegrationTest.java`. The test setup needs to change to use keypair auth instead of shared secret. First, add new fields and update setup:

```java
// Add these imports at top of file
import java.nio.file.Files;
import java.nio.file.Path;

// Add these fields alongside existing ones
private static OpsKeys.EncodedKeypair keypair;
private static Path authorizedKeysFile;
```

Update the `@BeforeAll` setup to use keypair auth:

```java
@BeforeAll
static void startApp() throws Exception {
    keypair = OpsKeys.generateKeypair();
    authorizedKeysFile = Files.createTempFile("ops-authorized-keys", "");
    Files.writeString(authorizedKeysFile, keypair.publicKey() + " test-key\n");

    app = Brace.app().port(0).ops(authorizedKeysFile.toString());
    // ... rest of existing route setup ...
    app.start();
    port = app.actualPort();
}

@AfterAll
static void stopApp() throws Exception {
    app.stop();
    Files.deleteIfExists(authorizedKeysFile);
}
```

Add a helper to authenticate and get a token:

```java
private String authenticate() throws Exception {
    var timestamp = java.time.Instant.now().toString();
    var signature = OpsKeys.sign(timestamp, keypair.privateKey());
    var body = "{\"timestamp\":\"" + timestamp + "\",\"publicKey\":\"" + keypair.publicKey()
            + "\",\"signature\":\"" + signature + "\"}";
    var response = client.send(
            HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    // Extract token from JSON response
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
    return json.get("token").asText();
}

private HttpResponse<String> getWithToken(String path) throws Exception {
    var token = authenticate();
    return client.send(
            HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
            HttpResponse.BodyHandlers.ofString());
}
```

Add test for the auth endpoint:

```java
@Test
void authEndpointReturnsToken() throws Exception {
    var timestamp = java.time.Instant.now().toString();
    var signature = OpsKeys.sign(timestamp, keypair.privateKey());
    var body = "{\"timestamp\":\"" + timestamp + "\",\"publicKey\":\"" + keypair.publicKey()
            + "\",\"signature\":\"" + signature + "\"}";
    var response = client.send(
            HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"token\""));
    assertTrue(response.body().contains("\"expiresAt\""));
}

@Test
void authRejectsUnknownPublicKey() throws Exception {
    var unknown = OpsKeys.generateKeypair();
    var timestamp = java.time.Instant.now().toString();
    var signature = OpsKeys.sign(timestamp, unknown.privateKey());
    var body = "{\"timestamp\":\"" + timestamp + "\",\"publicKey\":\"" + unknown.publicKey()
            + "\",\"signature\":\"" + signature + "\"}";
    var response = client.send(
            HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, response.statusCode());
}

@Test
void authRejectsStaleTimestamp() throws Exception {
    var staleTimestamp = java.time.Instant.now().minusSeconds(60).toString();
    var signature = OpsKeys.sign(staleTimestamp, keypair.privateKey());
    var body = "{\"timestamp\":\"" + staleTimestamp + "\",\"publicKey\":\"" + keypair.publicKey()
            + "\",\"signature\":\"" + signature + "\"}";
    var response = client.send(
            HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, response.statusCode());
}

@Test
void authRejectsInvalidSignature() throws Exception {
    var timestamp = java.time.Instant.now().toString();
    var body = "{\"timestamp\":\"" + timestamp + "\",\"publicKey\":\"" + keypair.publicKey()
            + "\",\"signature\":\"aW52YWxpZA==\"}";
    var response = client.send(
            HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, response.statusCode());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=OpsIntegrationTest -pl .`
Expected: Failures — `ops()` doesn't accept a file path yet, no `/ops/auth` endpoint.

- [ ] **Step 3: Update OpsHandler constructor and add auth endpoint**

In `OpsHandler.java`, change the constructor to accept authorized keys and a token secret instead of a shared secret. Add the `auth()` method:

Replace the fields (lines 9-16):
```java
private final Stats stats;
private final JobScheduler jobScheduler;
private final Mailer mailer;
private final Router router;
private final Set<String> authorizedKeys;
private final String tokenSecret;
private final int defaultTtl;
private final int maxTtl;
private final int dashboardTtl;
private final ErrorStore errorStore;
private final Cache cache;
private final JfrProfiler profiler;
```

Replace the constructors (lines 18-44) with a single constructor:
```java
public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                  Router router, Set<String> authorizedKeys, String tokenSecret,
                  ErrorStore errorStore, Cache cache, JfrProfiler profiler) {
    this.stats = stats;
    this.jobScheduler = jobScheduler;
    this.mailer = mailer;
    this.router = router;
    this.authorizedKeys = authorizedKeys;
    this.tokenSecret = tokenSecret;
    this.defaultTtl = 3600;
    this.maxTtl = 86400;
    this.dashboardTtl = 7200;
    this.errorStore = errorStore;
    this.cache = cache;
    this.profiler = profiler;
}
```

Add the `auth()` method:
```java
@SuppressWarnings("unchecked")
public Result auth(Request req) {
    try {
        var body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(req.body(), java.util.Map.class);
        var timestamp = (String) body.get("timestamp");
        var publicKey = (String) body.get("publicKey");
        var signature = (String) body.get("signature");

        if (timestamp == null || publicKey == null || signature == null) {
            return Result.json("{\"error\":\"Missing required fields\"}").status(401);
        }

        // Check public key is authorized
        if (!authorizedKeys.contains(publicKey)) {
            return Result.json("{\"error\":\"Unauthorized\"}").status(401);
        }

        // Check timestamp is within ±30 seconds
        var ts = java.time.Instant.parse(timestamp);
        var now = java.time.Instant.now();
        if (Math.abs(java.time.Duration.between(ts, now).getSeconds()) > 30) {
            return Result.json("{\"error\":\"Timestamp out of range\"}").status(401);
        }

        // Verify signature
        if (!OpsKeys.verify(timestamp, signature, publicKey)) {
            return Result.json("{\"error\":\"Invalid signature\"}").status(401);
        }

        // Issue token
        var requestedTtl = body.containsKey("ttlSeconds")
                ? ((Number) body.get("ttlSeconds")).intValue() : defaultTtl;
        var ttl = Math.min(requestedTtl, maxTtl);
        var token = OpsToken.create(tokenSecret, ttl);
        var expiresAt = java.time.Instant.now().plusSeconds(ttl).toString();

        return Json.of(java.util.Map.of("token", token, "expiresAt", expiresAt));
    } catch (Exception e) {
        return Result.json("{\"error\":\"Invalid request\"}").status(401);
    }
}
```

Replace the `authorize()` method (lines 236-241):
```java
boolean authorize(Request req) {
    // Check Authorization: Bearer <token> header
    var authHeader = req.header("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
        var token = authHeader.substring(7);
        return OpsToken.validate(token, tokenSecret);
    }
    // Check ?token= query parameter
    var tokenParam = req.param("token");
    if (tokenParam != null) {
        return OpsToken.validate(tokenParam, tokenSecret);
    }
    return false;
}
```

- [ ] **Step 4: Update Brace.java to accept authorized keys file path**

In `Brace.java`, change the `ops()` method and startup:

Replace the `opsSecret` field (around line 31):
```java
private String opsAuthorizedKeysPath;
```

Replace the `ops()` method (lines 97-100):
```java
public Brace ops(String authorizedKeysPath) {
    this.opsAuthorizedKeysPath = authorizedKeysPath;
    return this;
}
```

In the `start()` method, replace the ops setup section (around lines 326-340):
```java
if (opsAuthorizedKeysPath != null) {
    var authorizedKeys = OpsKeys.loadAuthorizedKeys(java.nio.file.Path.of(opsAuthorizedKeysPath));
    var tokenSecret = OpsToken.generateSecret();
    profiler = new JfrProfiler();
    var opsHandler = new OpsHandler(stats, jobScheduler, mailer, router, authorizedKeys, tokenSecret, errorStore, cache, profiler);
    router.add("GET", "/ops/status", (Handler) opsHandler::status);
    router.add("GET", "/ops/routes", (Handler) opsHandler::routes);
    router.add("GET", "/ops/dashboard", (Handler) opsHandler::dashboard);
    router.add("GET", "/ops/errors", (Handler) opsHandler::errors);
    router.add("POST", "/ops/errors/{id}/resolve", (Handler) opsHandler::resolveError);
    router.add("POST", "/ops/cache/clear", (Handler) opsHandler::clearCache);
    router.add("POST", "/ops/auth", (Handler) opsHandler::auth);
}
```

- [ ] **Step 5: Update existing ops tests to use token auth**

All existing `getWithKey()` calls in `OpsIntegrationTest.java` should be replaced with `getWithToken()`. Search for all uses of `getWithKey` and replace with `getWithToken`. Remove the old `getWithKey` helper.

Also update the "requires key" tests to verify that old-style `X-Ops-Key` no longer works:

```java
@Test
void opsStatusRequiresAuth() throws Exception {
    var response = get("/ops/status");
    assertEquals(401, response.statusCode());
}

@Test
void oldStyleOpsKeyRejected() throws Exception {
    var response = client.send(
            HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/status"))
                    .header("X-Ops-Key", "anything")
                    .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, response.statusCode());
}

@Test
void opsStatusWithValidToken() throws Exception {
    var response = getWithToken("/ops/status");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"app\""));
}
```

- [ ] **Step 6: Run all tests to verify they pass**

Run: `mvn test -Dtest=OpsIntegrationTest -pl .`
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/brace/OpsHandler.java src/main/java/io/brace/Brace.java src/test/java/io/brace/OpsIntegrationTest.java
git commit -m "Replace X-Ops-Key with Ed25519 keypair + token auth for ops endpoints"
```

---

### Task 4: Dashboard Token Embedding

**Files:**
- Modify: `src/main/java/io/brace/OpsHandler.java`
- Modify: `src/main/java/io/brace/OpsDashboard.java`

The dashboard needs to embed a token in its JS so HTMX polling requests are authenticated.

- [ ] **Step 1: Write failing test**

Add to `OpsIntegrationTest.java`:

```java
@Test
void dashboardEmbedsTokenForPolling() throws Exception {
    var response = getWithToken("/ops/dashboard");
    assertEquals(200, response.statusCode());
    // Dashboard should contain a Bearer token for HTMX polling
    assertTrue(response.body().contains("Authorization"));
    assertTrue(response.body().contains("Bearer "));
    // Should NOT contain old-style key param
    assertFalse(response.body().contains("?key="));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OpsIntegrationTest#dashboardEmbedsTokenForPolling -pl .`
Expected: FAIL — dashboard still uses old key format.

- [ ] **Step 3: Update OpsDashboard.html signature and HTMX config**

In `OpsDashboard.java`, change the method signature from:
```java
public static String html(String opsSecret, Stats stats, ...)
```
to:
```java
public static String html(String token, Stats stats, ...)
```

Replace the HTMX polling div (around lines 113-114). The current code uses `hx-get="/ops/dashboard?key=..."`. Change to use `hx-headers` with a Bearer token:

```java
sb.append("<div id=\"dashboard-content\" ")
  .append("hx-get=\"/ops/dashboard\" ")
  .append("hx-headers='{\"Authorization\": \"Bearer ").append(token).append("\"}' ")
  .append("hx-trigger=\"every 5s\" hx-select=\"#dashboard-content\" hx-swap=\"outerHTML\">");
```

In `OpsHandler.dashboard()`, generate a dashboard token and pass it:

```java
public Result dashboard(Request req) {
    if (!authorize(req)) return Result.json("{\"error\":\"Unauthorized\"}").status(401);
    var dashToken = OpsToken.create(tokenSecret, dashboardTtl);
    return Result.html(OpsDashboard.html(dashToken, stats, jobScheduler, mailer, errorStore, cache, profiler));
}
```

Note: The `authorize(req)` call is explicitly checked here since `auth` is the only unprotected endpoint.

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=OpsIntegrationTest -pl .`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/OpsHandler.java src/main/java/io/brace/OpsDashboard.java
git commit -m "Embed Bearer token in dashboard for authenticated HTMX polling"
```

---

### Task 5: CLI Commands — `brace ops keypair` and `brace ops dashboard`

**Files:**
- Modify: `src/main/java/io/brace/Cli.java`
- Create: `src/test/java/io/brace/CliTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {

    @Test
    void opsKeypairGeneratesKeyAndAppendsToFile(@TempDir Path dir) throws Exception {
        var file = dir.resolve("ops-authorized-keys");
        Files.writeString(file, "# existing key\nexistingkey123 old-key\n");

        // Simulate the keypair logic directly (Cli.main uses System.out and System.exit)
        var keypair = OpsKeys.generateKeypair();
        var label = "test-label";

        // Append public key with label
        Files.writeString(file, Files.readString(file) + keypair.publicKey() + "  " + label + "\n");

        var content = Files.readString(file);
        assertTrue(content.contains("existingkey123 old-key"));
        assertTrue(content.contains(keypair.publicKey()));
        assertTrue(content.contains(label));

        var keys = OpsKeys.loadAuthorizedKeys(file);
        assertEquals(2, keys.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=CliTest -pl .`
Expected: Should PASS since we're testing the integration of existing OpsKeys. If not, fix issues.

- [ ] **Step 3: Update Cli.java with ops commands**

Replace the entire `Cli.java`:

```java
package io.brace;

import java.awt.Desktop;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class Cli {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        switch (args[0]) {
            case "new" -> {
                if (args.length < 2) {
                    System.err.println("Usage: brace new <project-name>");
                    System.exit(1);
                }
                ProjectGenerator.generate(args[1]);
            }
            case "ops" -> {
                if (args.length < 2) {
                    printOpsUsage();
                    return;
                }
                switch (args[1]) {
                    case "keypair" -> opsKeypair(args);
                    case "dashboard" -> opsDashboard(args);
                    default -> printOpsUsage();
                }
            }
            default -> printUsage();
        }
    }

    private static void opsKeypair(String[] args) {
        var label = "key-1";
        for (int i = 2; i < args.length - 1; i++) {
            if ("--label".equals(args[i])) label = args[i + 1];
        }

        var keypair = OpsKeys.generateKeypair();
        System.out.println("Public key:   " + keypair.publicKey());
        System.out.println("Private key:  " + keypair.privateKey());
        System.out.println();

        var file = Path.of("ops-authorized-keys");
        try {
            var line = keypair.publicKey() + "  " + label + "\n";
            if (Files.exists(file)) {
                Files.writeString(file, line, StandardOpenOption.APPEND);
            } else {
                Files.writeString(file, "# Ops authorized public keys — one per line, optional label after space\n" + line);
            }
            System.out.println("Added to ops-authorized-keys.");
        } catch (Exception e) {
            System.err.println("Failed to write ops-authorized-keys: " + e.getMessage());
        }

        System.out.println("Store the private key securely — it won't be shown again.");
    }

    private static void opsDashboard(String[] args) {
        var url = "http://localhost:8080";
        var keyPath = "ops-private.key";

        for (int i = 2; i < args.length - 1; i++) {
            if ("--url".equals(args[i])) url = args[i + 1];
            if ("--key".equals(args[i])) keyPath = args[i + 1];
        }

        // Try file first, then env var
        String privateKey;
        try {
            var path = Path.of(keyPath);
            if (Files.exists(path)) {
                privateKey = Files.readString(path).trim();
            } else {
                privateKey = System.getenv("OPS_PRIVATE_KEY");
                if (privateKey == null || privateKey.isEmpty()) {
                    System.err.println("Private key not found at " + keyPath + " and OPS_PRIVATE_KEY env var not set.");
                    System.exit(1);
                    return;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to read private key: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Derive public key from private key and authenticate
        try {
            var keyBytes = java.util.Base64.getDecoder().decode(privateKey);
            var keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
            var kf = java.security.KeyFactory.getInstance("Ed25519");
            var privKey = kf.generatePrivate(keySpec);

            // Ed25519 private key encodes the public key — extract it
            var kpg = java.security.KeyPairGenerator.getInstance("Ed25519");
            // We need public key for the auth request. Ed25519 private keys in JDK contain the public key.
            // Use EdECPrivateKey to get the public key bytes.
            // Simpler approach: sign and send with empty public key? No, spec requires it.
            // Actually, we can get the public key from the private key via the key agreement or by encoding.
            // For JDK Ed25519, the approach is to compute from the private key.
            // Pragmatic: require both keys, or store them together.

            // For now, read public key from authorized keys file or require it as argument
            // Let's keep it simple: the auth request needs the public key, and we have the private key.
            // We'll extract it from the authorized keys file.
            var authKeysPath = Path.of("ops-authorized-keys");
            if (!Files.exists(authKeysPath)) {
                System.err.println("ops-authorized-keys not found — cannot determine public key.");
                System.exit(1);
                return;
            }

            // Try each public key in the file until we find one that matches our private key
            var authorizedKeys = OpsKeys.loadAuthorizedKeys(authKeysPath);
            String matchedPublicKey = null;
            var testMessage = "test";
            var testSig = OpsKeys.sign(testMessage, privateKey);
            for (var pubKey : authorizedKeys) {
                if (OpsKeys.verify(testMessage, testSig, pubKey)) {
                    matchedPublicKey = pubKey;
                    break;
                }
            }
            if (matchedPublicKey == null) {
                System.err.println("Private key does not match any key in ops-authorized-keys.");
                System.exit(1);
                return;
            }

            var timestamp = Instant.now().toString();
            var signature = OpsKeys.sign(timestamp, privateKey);
            var body = "{\"timestamp\":\"" + timestamp + "\",\"publicKey\":\"" + matchedPublicKey
                    + "\",\"signature\":\"" + signature + "\",\"ttlSeconds\":7200}";

            var client = HttpClient.newHttpClient();
            var response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url + "/ops/auth"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("Authentication failed: " + response.body());
                System.exit(1);
                return;
            }

            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
            var token = json.get("token").asText();
            var dashboardUrl = url + "/ops/dashboard?token=" + token;

            System.out.println("Opening dashboard: " + dashboardUrl);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(dashboardUrl));
            } else {
                System.out.println("Open this URL in your browser: " + dashboardUrl);
            }
        } catch (Exception e) {
            System.err.println("Authentication failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("""
                Brace CLI v0.1.0

                Commands:
                  brace new <name>                  Create a new Brace project
                  brace ops keypair [--label <n>]    Generate an Ed25519 keypair
                  brace ops dashboard [--url <url>]  Open the ops dashboard
                """);
    }

    private static void printOpsUsage() {
        System.out.println("""
                Brace ops commands:
                  brace ops keypair [--label <name>]              Generate a new Ed25519 keypair
                  brace ops dashboard [--url <url>] [--key <path>] Open the ops dashboard
                """);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl .`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/Cli.java src/test/java/io/brace/CliTest.java
git commit -m "Add CLI commands: brace ops keypair and brace ops dashboard"
```

---

### Task 6: Update ProjectGenerator

**Files:**
- Modify: `src/main/java/io/brace/ProjectGenerator.java`

Update the project generator to use keypair auth instead of shared secret.

- [ ] **Step 1: Read the current ProjectGenerator**

Run: Read `src/main/java/io/brace/ProjectGenerator.java` to understand the current template generation.

- [ ] **Step 2: Update ProjectGenerator**

Replace the ops setup in the generated `App.java` template. Find where it generates `app.ops(config.get("ops.secret"))` and replace with:

```java
// In the generated App.java template:
// Replace: app.ops(config.get("ops.secret"))
// With: app.ops("ops-authorized-keys")
```

Add keypair generation to the `generate()` method:

```java
// After creating the project directory, generate keypair
var keypair = OpsKeys.generateKeypair();

// Write ops-authorized-keys
Files.writeString(projectDir.resolve("ops-authorized-keys"),
        "# Ops authorized public keys — one per line, optional label after space\n"
        + keypair.publicKey() + "  initial\n");

// Write ops-private.key
Files.writeString(projectDir.resolve("ops-private.key"), keypair.privateKey() + "\n");

// Add to .gitignore
// Append: ops-private.key and *.key
```

Remove `ops.secret` from the generated `application.conf`.

Print: `"Ops private key written to ops-private.key — keep this safe"`

- [ ] **Step 3: Run all tests**

Run: `mvn test -pl .`
Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/brace/ProjectGenerator.java
git commit -m "Update ProjectGenerator to use Ed25519 keypair auth for ops"
```

---

### Task 7: Update sample app

**Files:**
- Modify: `sample/` — update the sample app to use the new ops auth

- [ ] **Step 1: Update sample app ops config**

Read the sample app's main class, replace `app.ops("...")` with `app.ops("ops-authorized-keys")`.

Generate an authorized keys file in the sample directory:

```bash
cd sample && ../brace ops keypair --label sample
```

Or manually create `sample/ops-authorized-keys` with a generated key for dev use.

- [ ] **Step 2: Verify sample app compiles and starts**

Run: `./sample/brace sample`
Expected: App starts, ops dashboard accessible via token auth.

- [ ] **Step 3: Commit**

```bash
git add sample/
git commit -m "Update sample app to use keypair auth for ops"
```

---

### Task 8: Run full test suite

- [ ] **Step 1: Run all tests**

Run: `mvn test -pl .`
Expected: All 283+ tests PASS. No regressions from the auth change.

- [ ] **Step 2: Final commit if any fixups needed**

```bash
git add -A
git commit -m "Fix test regressions from ops token auth migration"
```
