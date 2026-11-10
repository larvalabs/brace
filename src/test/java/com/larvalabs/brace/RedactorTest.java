package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedactorTest {

    @Test
    void isSensitiveMatchesCommonCredentialNames() {
        assertTrue(Redactor.isSensitive("Authorization"));
        assertTrue(Redactor.isSensitive("Cookie"));
        assertTrue(Redactor.isSensitive("x-api-key"));
        assertTrue(Redactor.isSensitive("api_key"));
        assertTrue(Redactor.isSensitive("password"));
        assertTrue(Redactor.isSensitive("sessionId"));
        assertTrue(Redactor.isSensitive("csrf-token"));
        assertTrue(Redactor.isSensitive("X-Auth-Token"));
    }

    @Test
    void isSensitiveLeavesOrdinaryNamesAlone() {
        assertFalse(Redactor.isSensitive("username"));
        assertFalse(Redactor.isSensitive("count"));
        assertFalse(Redactor.isSensitive("path"));
        assertFalse(Redactor.isSensitive("author"));
        assertFalse(Redactor.isSensitive("monkey"));
        assertFalse(Redactor.isSensitive(null));
    }

    @Test
    void redactReplacesSensitiveValuesOnly() {
        var redacted = Redactor.redact(Map.of("password", "hunter2", "username", "bob"));
        assertEquals(Redactor.PLACEHOLDER, redacted.get("password"));
        assertEquals("bob", redacted.get("username"));
    }

    @Test
    void redactRecursesIntoMapsAndLists() {
        var nested = Map.<String, Object>of(
            "user", "bob",
            "headers", Map.of("authorization", "Bearer abc", "accept", "json"),
            "events", List.of(Map.of("token", "xyz", "kind", "login")));
        var redacted = Redactor.redact(nested);

        @SuppressWarnings("unchecked")
        var headers = (Map<String, Object>) redacted.get("headers");
        assertEquals(Redactor.PLACEHOLDER, headers.get("authorization"));
        assertEquals("json", headers.get("accept"));

        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) redacted.get("events");
        assertEquals(Redactor.PLACEHOLDER, events.get(0).get("token"));
        assertEquals("login", events.get(0).get("kind"));
    }

    @Test
    void redactQueryRedactsSensitiveParams() {
        assertEquals("a=1&token=[REDACTED]&b=2", Redactor.redactQuery("a=1&token=xyz&b=2"));
        assertEquals("password=[REDACTED]", Redactor.redactQuery("password=hunter2"));
        assertEquals("q=hello", Redactor.redactQuery("q=hello"));
        assertEquals("flag", Redactor.redactQuery("flag"));
        assertNull(Redactor.redactQuery(null));
    }

    @Test
    void logEntriesAreRedactedInTheRingBuffer() {
        // /ops/logs serves LogTap.snapshot(), so redaction at the log choke point protects it.
        LogTap.clear();
        Log.info("login attempt", Map.of("password", "secret123", "user", "bob"));
        var entry = LogTap.snapshot().stream()
            .filter(e -> "login attempt".equals(e.fields().get("message")))
            .findFirst().orElseThrow();
        assertEquals(Redactor.PLACEHOLDER, entry.fields().get("password"));
        assertEquals("bob", entry.fields().get("user"));
    }

    // ---- redactPath tests ----

    @Test
    void redactPathLeaves32HexTokenRedacted() {
        // 32-char hex token with mixed letters+digits — should be redacted
        String path = "/password-reset/a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String result = Redactor.redactPath(path);
        assertEquals("/password-reset/[redacted]", result);
    }

    @Test
    void redactPathLeavesNumericIdUntouched() {
        assertEquals("/users/42/profile", Redactor.redactPath("/users/42/profile"));
    }

    @Test
    void redactPathLeavesShortSlugUntouched() {
        assertEquals("/blog/hello-world", Redactor.redactPath("/blog/hello-world"));
    }

    @Test
    void redactPathLeavesUuidUntouched() {
        // UUIDs are identifiers, not secrets — needed for debugging
        String path = "/items/550e8400-e29b-41d4-a716-446655440000";
        assertEquals(path, Redactor.redactPath(path));
    }

    @Test
    void redactPathRedactsJwtShapedSegment() {
        // JWT: header.payload.signature
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String path = "/api/" + jwt;
        String result = Redactor.redactPath(path);
        assertEquals("/api/[redacted]", result);
    }

    @Test
    void redactPathHandlesRootAndEmptyPath() {
        assertEquals("/", Redactor.redactPath("/"));
        assertNull(Redactor.redactPath(null));
        assertEquals("", Redactor.redactPath(""));
    }

    @Test
    void redactPathRedactsBase64urlToken() {
        // 24-char base64url token (invite token style): mixed letters+digits
        String path = "/invite/abc123DEF456ghi789JKL0";
        assertEquals("/invite/[redacted]", Redactor.redactPath(path));
    }

    @Test
    void redactPathLeavesNormalWordSegmentsUntouched() {
        assertEquals("/admin/dashboard", Redactor.redactPath("/admin/dashboard"));
        assertEquals("/health", Redactor.redactPath("/health"));
    }

    // ---- redactMessage tests ----

    @Test
    void redactMessageRedactsBearerTokenInMessage() {
        // Simulated exception message containing a long token
        String token = "a3f9bc2d8ef14a5b6c7d8e9f01234567";   // 32-char hex
        String message = "Failed to validate token: " + token;
        String result = Redactor.redactMessage(message);
        assertFalse(result.contains(token), "token should be redacted");
        assertTrue(result.contains("[redacted]"));
        assertTrue(result.contains("Failed"));
    }

    @Test
    void redactMessageLeavesNormalMessageUntouched() {
        String message = "User not found for email: bob@example.com";
        // No high-entropy token here — email address contains dots and @ which are
        // outside the base64url charset, so it won't match the heuristic.
        assertEquals(message, Redactor.redactMessage(message));
    }

    @Test
    void redactMessageRedactsJwtInMessage() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String message = "Invalid JWT: " + jwt;
        String result = Redactor.redactMessage(message);
        assertFalse(result.contains(jwt), "JWT should be redacted from message");
        assertTrue(result.contains("[redacted]"));
    }

    @Test
    void redactMessageHandlesNullAndEmpty() {
        assertNull(Redactor.redactMessage(null));
        assertEquals("", Redactor.redactMessage(""));
    }

    @Test
    void redactMessageLeavesShortTokensUntouched() {
        String message = "NullPointerException: field was null";
        assertEquals(message, Redactor.redactMessage(message));
    }

    @Test
    void redactMessageKeepsPunctuationWhenNothingIsRedacted() {
        // "IllegalArgumentException" is long enough to be a candidate, but isn't secret-shaped
        // (no digit). The message used to come back rebuilt with single spaces, losing the
        // parentheses, commas and colon: "placeholder s but 1 path argument s".
        String message = "Too many params for pattern: /catalog/list — it has 0 placeholder(s) but "
            + "1 path argument(s) were passed. Pass Url.query(name, value, ...) instead "
            + "(IllegalArgumentException).";
        assertEquals(message, Redactor.redactMessage(message));
    }

    @Test
    void redactMessageReplacesOnlyTheSecretAndKeepsTheRest() {
        String token = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        assertEquals("Bad token (key=[redacted], user: bob).",
            Redactor.redactMessage("Bad token (key=" + token + ", user: bob)."));
        assertEquals("  [redacted]\n[redacted]:", Redactor.redactMessage("  " + token + "\n" + token + ":"));
    }

    // ---- isSecretShaped tests ----

    @Test
    void isSecretShapedReturnsTrueForLongMixedToken() {
        assertTrue(Redactor.isSecretShaped("a3f9bc2d8ef14a5b6c7d8e9f01234567"));
    }

    @Test
    void isSecretShapedReturnsFalseForNumericId() {
        assertFalse(Redactor.isSecretShaped("1234567890123456")); // all digits
    }

    @Test
    void isSecretShapedReturnsFalseForPurelyAlphaSlug() {
        assertFalse(Redactor.isSecretShaped("thisisaverylongslugwithnumbers")); // no digits
        // actually has no digits so should return false
    }

    @Test
    void isSecretShapedReturnsFalseForUuid() {
        assertFalse(Redactor.isSecretShaped("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void isSecretShapedReturnsTrueForJwt() {
        assertTrue(Redactor.isSecretShaped(
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"));
    }

    @Test
    void isSecretShapedReturnsFalseForShortToken() {
        assertFalse(Redactor.isSecretShaped("abc123")); // below MIN_SECRET_LENGTH
    }

    // ---- redactStackTrace tests ----

    private static String traceOf(Throwable t) {
        var sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    @Test
    void redactStackTraceRedactsEveryMessageLine() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String other = "9f8e7d6c5b4a39281706f5e4d3c2b1a0";
        var suppressed = new IllegalStateException("suppressed key " + other);
        var root = new java.sql.SQLException("connect failed password=" + secret);
        var top = new RuntimeException("outer", new RuntimeException("middle", root));
        top.addSuppressed(suppressed);

        String trace = traceOf(top);
        assertTrue(trace.contains(secret) && trace.contains(other), "precondition: raw trace has both");

        String redacted = Redactor.redactStackTrace(trace);
        assertFalse(redacted.contains(secret), "Caused by: message must be redacted");
        assertFalse(redacted.contains(other), "Suppressed: message must be redacted");
        // password= is a sensitive name, so its value is replaced by name (upper case placeholder).
        assertTrue(redacted.contains("Caused by: java.sql.SQLException: connect failed password=[REDACTED]"), redacted);
        assertTrue(redacted.contains("RuntimeException: outer"));
    }

    @Test
    void redactStackTraceRedactsTheFirstLine() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String redacted = Redactor.redactStackTrace(traceOf(new RuntimeException("bad token " + secret)));
        assertFalse(redacted.contains(secret));
        assertTrue(redacted.startsWith("java.lang.RuntimeException: bad token [redacted]"), redacted);
    }

    @Test
    void redactStackTraceRedactsContinuationLinesOfAMultiLineMessage() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String redacted = Redactor.redactStackTrace(
            traceOf(new RuntimeException("line one\nsecond line " + secret + "\nthird")));
        assertFalse(redacted.contains(secret));
        assertTrue(redacted.contains("line one\nsecond line [redacted]\nthird"), redacted);
    }

    @Test
    void redactStackTraceLeavesFrameLinesUntouched() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String trace = "java.lang.RuntimeException: boom " + secret + "\n"
            + "\tat com.example.Foo.bar(Foo.java:12)\n"
            + "\tat com.example.Foo.longMethodName0123456789abcdef(Foo.java:99)\n"
            + "Caused by: java.lang.Error: root " + secret + "\n"
            + "\tat com.example.Baz.qux(Baz.java:7)\n"
            + "\t... 3 more\n";
        assertEquals(trace.replace(secret, "[redacted]"), Redactor.redactStackTrace(trace));
    }

    @Test
    void redactStackTraceKeepsLineEndingsAndReturnsCleanTracesUnchanged() {
        String clean = "java.lang.RuntimeException: user not found\r\n\tat a.B.c(B.java:1)\r\n";
        assertSame(clean, Redactor.redactStackTrace(clean));
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        assertEquals("java.lang.RuntimeException: x [redacted]\r\n\tat a.B.c(B.java:1)\r\n",
            Redactor.redactStackTrace("java.lang.RuntimeException: x " + secret + "\r\n\tat a.B.c(B.java:1)\r\n"));
    }

    @Test
    void redactStackTraceHandlesNullAndEmpty() {
        assertNull(Redactor.redactStackTrace(null));
        assertEquals("", Redactor.redactStackTrace(""));
    }

    // ---- M6: value-shaped redaction of error-store headers and query strings ----

    static final String HEX32 = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
    static final String RANDOM32 = "Zx81kLm02PqR7sTuV4wXy9AbCd_eF3gH";
    static final String JWT =
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLTQyIiwiZXhwIjoxOTk5OTk5OTk5fQ.c2lnbmF0dXJlLWJ5dGVzLWhlcmUtMTIz";
    static final String OAUTH_CODE = "4/0AX4XfWhq8Lp3mN7vB2cD9eF1gH5jK";
    static final String OPS_TOKEN = OpsToken.create("ops-test-secret-0123456789abcdef0123", 300);

    @Test
    void isSensitiveMatchesTheM6Names() {
        for (String name : List.of("Cf-Access-Jwt-Assertion", "client_assertion", "jwt", "X-Amz-Signature",
                "signature", "X-Auth-Key", "nonce", "code", "Code", "sig", "SIG", "otp")) {
            assertTrue(Redactor.isSensitive(name), name);
        }
    }

    @Test
    void shortM6NamesMatchOnlyTheWholeName() {
        // code/sig/otp as substrings would hit ordinary words: whole normalized name only.
        for (String name : List.of("encode", "postcode", "barcode", "zip_code", "status_code", "country-code",
                "codec", "signal", "design", "assign", "sigma", "footprint", "hotpath", "otpauth_uri")) {
            assertFalse(Redactor.isSensitive(name), name);
        }
    }

    @Test
    void opsTokenAndTwoPartShapeAreSecretShaped() {
        assertTrue(Redactor.isSecretShaped(OPS_TOKEN), OPS_TOKEN);
        assertEquals("ops auth failed for [redacted]", Redactor.redactMessage("ops auth failed for " + OPS_TOKEN));
        assertTrue(Redactor.isSecretShaped("abcdefghij0123456789xy.ZYXWVUTSRQ9876543210_-"));
        // Not two-part: a side under 20, no digit, a second dot, a char outside base64url.
        for (String v : List.of("averyveryverylonghostnamelabel.example", "abcdefghij0123456789xy.short1",
                "abcdefghijklmnopqrstuvwxyz.abcdefghijklmnopqrstuvwxyz",
                "abcdefghij0123456789xy.abcdefghij0123456789xy.com", "abcdefghij01234567+9xy.abcdefghij0123456789xy",
                "app.3f9a1c2b.js", "main-3f9a1c2b4d5e6f7a8b9c0d1e.css", "release-2024.11.05-rc1")) {
            assertFalse(Redactor.isSecretShaped(v), v);
        }
    }

    /** Header values that MUST survive unredacted: false positives make the error store useless. */
    static final List<Map.Entry<String, String>> HEADERS_THAT_SURVIVE = List.of(
        Map.entry("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"),
        Map.entry("Accept-Language", "en-US,en;q=0.9,fr-CA;q=0.8"),
        Map.entry("Accept-Encoding", "gzip, deflate, br, zstd"),
        Map.entry("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.207 Safari/537.36"),
        Map.entry("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; SM-S918B Build/UP1A.231005.007)"),
        Map.entry("User-Agent", "MyApp/4.2.1 (iPhone; iOS 17.2; build 3f9a1c2b4d5e6f7a8b9c0d1e)"),
        Map.entry("Content-Type", "multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW"),
        Map.entry("Content-Type", "application/json; charset=utf-8"),
        Map.entry("Host", "my-very-long-subdomain-label-1234567890.eu-west-1.elb.amazonaws.com:8443"),
        Map.entry("X-Forwarded-For", "203.0.113.9, 2001:db8::1, 10.0.0.1"),
        Map.entry("Forwarded", "for=203.0.113.9;proto=https;host=app.example.com"),
        Map.entry("X-Request-Id", "550e8400-e29b-41d4-a716-446655440000"),
        Map.entry("X-Request-Id", "3f9a1c2b4d5e6f7a8b9c0d1e2f3a4b5c"),
        Map.entry("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"),
        Map.entry("X-Amzn-Trace-Id", "Root=1-67891233-abcdef012345678912345678"),
        Map.entry("Cf-Ray", "8a1b2c3d4e5f6a7b-IAD"),
        Map.entry("If-None-Match", "W/\"33a64df551425fcc55e4d42a148795d7f25f89d4\""),
        Map.entry("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\""),
        Map.entry("Sec-Fetch-Site", "same-origin"),
        Map.entry("Referer", "https://example.com/posts/42?page=2&sort=-date"),
        Map.entry("Referer", "https://example.com/search?q=hello+world&page=2&id=12345&email=a@b.c&lang=en-US"),
        Map.entry("Referer", "https://example.com/items/550e8400-e29b-41d4-a716-446655440000/edit"),
        Map.entry("Referer", "https://cdn.example.com/assets/app.3f9a1c2b.js"),
        // (main-<24 hex>.css is now redacted on purpose: contentHashesInFileNamesAreRedactedDeliberately)
        Map.entry("Referer", "https://example.com/docs/getting-started#installation"),
        Map.entry("Referer", "http://localhost:8080/"),
        Map.entry("Origin", "https://app.example.com"),
        Map.entry("Origin", "null"),
        Map.entry("HX-Current-URL", "https://example.com/posts/42?tab=comments"),
        Map.entry("X-App-Version", "4.12.0-beta.3+build.20241105"),
        Map.entry("X-Theme-Colors", "#ff0000, #00ff00, #0000ff, #123abc, #abcdef"),
        Map.entry("X-Feature-Flags", "new-checkout,dark-mode,beta-search"),
        Map.entry("X-Short", "dGVzdA== abc123 v2"),
        Map.entry("X-Hostname", "averyveryverylonghostnamelabel.example.com"),
        Map.entry("X-Client-Path", "/var/app/releases/current/public"),
        Map.entry("X-Return-To", "https://example.com/orders/12345?tab=items&page=2"));

    @Test
    void ordinaryHeadersSurviveUnredacted() {
        for (var h : HEADERS_THAT_SURVIVE) {
            var out = Redactor.redactHeaders(Map.of(h.getKey(), h.getValue()));
            assertEquals(h.getValue(), out.get(h.getKey()), h.getKey() + ": " + h.getValue());
        }
    }

    @Test
    void secretBearingHeadersAreRedacted() {
        String[][] cases = {
            // name-based
            {"Authorization", "Bearer abc", "[REDACTED]"},
            {"Cookie", "s=1", "[REDACTED]"},
            {"X-Auth-Key", "k3y-" + HEX32, "[REDACTED]"},
            {"Cf-Access-Jwt-Assertion", JWT, "[REDACTED]"},
            {"X-Amz-Signature", "abc", "[REDACTED]"},
            // value-shaped, under names that say nothing
            {"X-Amzn-Oidc-Data", JWT, "[redacted]"},
            {"X-Ops", OPS_TOKEN, "[redacted]"},
            {"X-Api", "v1 " + RANDOM32, "v1 [redacted]"},
            {"Sec-WebSocket-Protocol", "bearer, " + JWT, "bearer, [redacted]"},
            {"X-Callback", "https://hooks.example.com/notify/" + HEX32, "https://hooks.example.com/notify/[redacted]"},
            {"X-Callback", "see https://hooks.example.com/notify/" + HEX32, "see https://hooks.example.com/notify/[redacted]"},
            // A free-form header holding a bare path: a standard-base64 secret can start with '/',
            // so a path that is secret-shaped as a whole is replaced whole (as redactMessage does).
            {"X-Path", "/files/" + RANDOM32 + "/raw", "[redacted]"},
            {"X-Path", "/files/" + RANDOM32 + "/raw.txt", "/files/[redacted]/raw.txt"},
            // URL-valued: query by name, query by shape, path segment, fragment, userinfo
            {"Referer", "https://app.example.com/reset?token=" + HEX32 + "&lang=en",
                "https://app.example.com/reset?token=[REDACTED]&lang=en"},
            {"Referer", "https://app.example.com/reset?t=" + RANDOM32, "https://app.example.com/reset?t=[REDACTED]"},
            {"Referer", "https://app.example.com/reset/" + HEX32, "https://app.example.com/reset/[redacted]"},
            {"Referer", "https://app.example.com/cb?code=4%2F0AX4XfWh&state=xyz", "https://app.example.com/cb?code=[REDACTED]&state=xyz"},
            {"Referer", "https://app.example.com/cb#access_token=" + RANDOM32 + "&expires_in=3600",
                "https://app.example.com/cb#access_token=[REDACTED]&expires_in=3600"},
            {"Referer", "https://user:hunter2@app.example.com/x", "https://[REDACTED]@app.example.com/x"},
            {"Referer", "https://app.example.com/v?%74oken=abc", "https://app.example.com/v?%74oken=[REDACTED]"},
            {"X-Original-URL", "/reset/" + RANDOM32 + "/confirm", "/reset/[redacted]/confirm"},
            {"X-Forwarded-Uri", "/api/keys?jwt=" + JWT, "/api/keys?jwt=[REDACTED]"},
            {"Origin", HEX32 + HEX32, "[redacted]"},
        };
        for (String[] c : cases) {
            var out = Redactor.redactHeaders(Map.of(c[0], c[1]));
            assertEquals(c[2], out.get(c[0]), c[0] + ": " + c[1]);
        }
    }

    @Test
    void redactHeadersKeepsOrderAndNonStringValues() {
        var in = new java.util.LinkedHashMap<String, Object>();
        in.put("Host", "a");
        in.put("X-N", 3);
        in.put("Cookie", "c");
        var out = Redactor.redactHeaders(in);
        assertEquals(List.of("Host", "X-N", "Cookie"), List.copyOf(out.keySet()));
        assertEquals(3, out.get("X-N"));
    }

    /** Query strings that MUST come back unchanged. */
    static final List<String> QUERIES_THAT_SURVIVE = List.of(
        "q=hello+world", "page=2", "id=12345", "email=a@b.c", "sort=-date", "lang=en-US",
        "colors=ff0000,00ff00,0000ff", "colors=%23ff0000%2C%2300ff00%2C%230000ff",
        "id=550e8400-e29b-41d4-a716-446655440000", "zip_code=94107", "status_code=404", "encode=utf8",
        "postcode=SW1A1AA", "barcode=0123456789012", "country_code=US", "design=dark", "signal=on",
        "next=/posts/42", "next=https%3A%2F%2Fexample.com%2Fposts%2F42", "file=app.3f9a1c2b.js",
        "version=4.12.0", "flag", "a=1&&b=2&", "=x", "bad=%zz%", "%=1", "name=Jos%C3%A9", "emoji=%F0%9F%98%80",
        "utm_source=newsletter&utm_campaign=black-friday-sale", "short=dGVzdA%3D%3D",
        "hash=3f9a1c2b4d5e6f7", "a&a&a&a&a=1");

    @Test
    void valuesTheShapeRuleCannotTellFromSecretsAreRedactedConsistently() {
        // Judgment calls, kept consistent with redactPath: a 16+ char run of base64url/hex
        // characters with a digit and a letter is redacted wherever it appears. A dated slug and a
        // dash-joined hex list look exactly like a grouped recovery code; a 16+ hex hash looks
        // like a hex token. Short hex (< 16) and comma/#-separated colour lists survive above.
        assertEquals("utm_campaign=[REDACTED]", Redactor.redactQuery("utm_campaign=2024-black-friday-sale"));
        assertEquals("colors=[REDACTED]", Redactor.redactQuery("colors=ff0000-00ff00-0000ff"));
        assertEquals("hash=[REDACTED]", Redactor.redactQuery("hash=3f9a1c2b4d5e6f70"));
        assertEquals("/blog/[redacted]", Redactor.redactPath("/blog/2024-black-friday-sale"));
        // Final review costs (documented in docs/SECURITY.md, "Known over-redaction").
        assertEquals("/assets/[redacted].js", Redactor.redactPath("/assets/ProductDetail-a1B2c3D4.js"));
        assertEquals("[redacted]", Redactor.redactHeaders(Map.of("X-Path", "/files/Ab3dE9fG/raw")).get("X-Path"));
        assertEquals("[redacted] failed", Redactor.redactMessage("UTF8StreamJsonParser failed"));
        assertEquals("ticket_id=[REDACTED]&sig_alg=[REDACTED]", Redactor.redactQuery("ticket_id=4521&sig_alg=hs256"));
        // An email is PII, not a credential: kept, as before.
        assertEquals("email=a%40b.c", Redactor.redactQuery("email=a%40b.c"));
    }

    @Test
    void ordinaryQueriesSurviveUnredacted() {
        for (String q : QUERIES_THAT_SURVIVE) {
            assertEquals(q, Redactor.redactQuery(q), q);
        }
    }

    @Test
    void secretBearingQueriesAreRedacted() {
        String encodedCode = java.net.URLEncoder.encode(OAUTH_CODE, java.nio.charset.StandardCharsets.UTF_8);
        String[][] cases = {
            {"code=" + encodedCode + "&state=xyz", "code=[REDACTED]&state=xyz"},
            {"code=" + OAUTH_CODE, "code=[REDACTED]"},
            {"t=" + RANDOM32, "t=[REDACTED]"},
            {"sig=abc&sv=2024-11-04", "sig=[REDACTED]&sv=2024-11-04"},
            {"otp=123456", "otp=[REDACTED]"},
            {"%74oken=abc", "%74oken=[REDACTED]"},
            {"%74ok%65n=abc&page=2", "%74ok%65n=[REDACTED]&page=2"},
            {"token=abc%zz", "token=[REDACTED]"},
            {"jwt=x", "jwt=[REDACTED]"},
            {"client_assertion=x&client_id=web", "client_assertion=[REDACTED]&client_id=web"},
            {"X-Amz-Signature=abc&X-Amz-Expires=300", "X-Amz-Signature=[REDACTED]&X-Amz-Expires=300"},
            {"nonce=n-0S6_WzA2Mj", "nonce=[REDACTED]"},
            {"state=" + JWT, "state=[REDACTED]"},
            {"x=" + OPS_TOKEN, "x=[REDACTED]"},
            {"next=%2Freset%2F" + HEX32, "next=[REDACTED]"},
            {"next=https%3A%2F%2Fapp.example.com%2Freset%3Ftoken%3D" + HEX32, "next=[REDACTED]"},
            {"SAMLResponse=PHNhbWxwOlJlc3BvbnNlIHhtbG5zOnNhbWxwPSJ1cm46b2FzaXM6bmFtZXM+MjAy", "SAMLResponse=[REDACTED]"},
            {HEX32, "[REDACTED]"},
            {"a=1&" + HEX32 + "&b=2", "a=1&[REDACTED]&b=2"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactQuery(c[0]), c[0]);
        }
    }

    @Test
    void lenientDecodeNeverThrows() {
        assertEquals("token", Redactor.lenientDecode("%74oken"));
        assertEquals("a b", Redactor.lenientDecode("a+b"));
        assertEquals("%zz%", Redactor.lenientDecode("%zz%"));
        assertEquals("%4", Redactor.lenientDecode("%4"));
        assertEquals("José", Redactor.lenientDecode("Jos%C3%A9"));
        assertEquals("�", Redactor.lenientDecode("%C3"));
        assertEquals("é 😀", Redactor.lenientDecode("é%20😀"));
    }

    // ---- Final review (2026-11): runs, URLs in messages, encoded and matrix path segments ----

    /** A 24-character random token, the reviewer's T. */
    static final String T = "Tt1aB2cD3eF4gH5iJ6kL7mN8";

    @Test
    void urlsInsideMessagesGoThroughTheUrlRedactor() {
        // M3: every scheme://... in a message is redacted like a Referer: path segments, query,
        // fragment, and any user:password@ (always masked, whatever its shape).
        String[][] cases = {
            {"Server returned HTTP response code: 500 for URL: https://api.partner.example/v1/reset/Uu1aB2cD3eF4gH5iJ6kL7mN8",
                "Server returned HTTP response code: 500 for URL: https://api.partner.example/v1/reset/[redacted]"},
            {"GET https://api.example.com/v1/items?api_key=Mm1aB2cD3eF4gH5iJ6kL7mN8&expand=1 failed",
                "GET https://api.example.com/v1/items?api_key=[REDACTED]&expand=1 failed"},
            {"Connection to postgres://dbuser:Ww1aB2cD3eF4gH5iJ6kL7@db.internal:5432/app refused",
                "Connection to postgres://[REDACTED]@db.internal:5432/app refused"},
            {"bad url jdbc:postgresql://db.internal:5432/app?user=app&password=hunter2",
                "bad url jdbc:postgresql://db.internal:5432/app?user=app&password=[REDACTED]"},
            {"redis://:hunter2@cache.internal:6379 unreachable", "redis://[REDACTED]@cache.internal:6379 unreachable"},
            {"amqp://guest:guest@mq:5672/vhost", "amqp://[REDACTED]@mq:5672/vhost"},
            {"smtp://mailer:pw@smtp.example.com:587 rejected AUTH", "smtp://[REDACTED]@smtp.example.com:587 rejected AUTH"},
            {"NoSuchKey s3://bucket/exports/" + T + "/data.csv", "NoSuchKey s3://bucket/exports/[redacted]/data.csv"},
            {"failed (see https://x.example/reset/" + T + ").", "failed (see https://x.example/reset/[redacted])."},
            {"callback https://x.example/cb#access_token=abc&expires_in=3600",
                "callback https://x.example/cb#access_token=[REDACTED]&expires_in=3600"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactMessage(c[0]), c[0]);
            // and so in every message line of a stored stack trace
            assertEquals("java.io.IOException: " + c[1] + "\n\tat a.B.c(B.java:1)\n",
                Redactor.redactStackTrace("java.io.IOException: " + c[0] + "\n\tat a.B.c(B.java:1)\n"));
        }
    }

    @Test
    void aSecretRunIsRedactedWhateverPunctuationSurroundsIt() {
        // M4: the shape test ran on whole delimiter-separated tokens, so one adjacent character
        // ('.', '<', '~', '!') hid the token. Now each maximal run of the secret alphabet is tested,
        // and a dotted token is tested label by label.
        assertEquals("Rejected API key [redacted].", Redactor.redactMessage("Rejected API key " + T + "."));
        assertEquals("<[redacted]>", Redactor.redactMessage("<" + T + ">"));
        assertEquals("[redacted]~x", Redactor.redactHeaders(Map.of("X-Custom", T + "~x")).get("X-Custom"));
        assertEquals("x=[REDACTED]", Redactor.redactQuery("x=" + T + "!"));
        assertEquals("x=[REDACTED]", Redactor.redactQuery("x=" + T + ".json"));
        assertEquals("next=[REDACTED]", Redactor.redactQuery("next=%252Freset%252F" + T));
        assertEquals("/dl/[redacted].pdf", Redactor.redactPath("/dl/" + T + ".pdf"));
        String sendgrid = "SG.Aa1bB2cC3dD4eE5fF6gG7.Hh7iI8jJ9kK0lL1mM2nN3oO4pP5qQ6rR7sS8tT9uU0vV1";
        assertEquals("key SG.[redacted].[redacted] revoked", Redactor.redactMessage("key " + sendgrid + " revoked"));
        assertEquals("SG.[redacted].[redacted]", Redactor.redactHeaders(Map.of("X-Key", sendgrid)).get("X-Key"));
    }

    @Test
    void percentEncodedAndMatrixPathSecretsAreRedacted() {
        // M5: a segment is tested raw and percent-decoded (up to three rounds), and ;name=value
        // matrix parameters are redacted by name and by shape.
        assertEquals("/reset/[redacted]", Redactor.redactPath("/reset/Xx1aB2cD3eF4%2BgH5iJ6kL7%3D%3D"));
        assertEquals("/reset/[redacted]", Redactor.redactPath("/reset/Xx1aB2cD3eF4%252BgH5iJ6kL7"));
        assertEquals("/reset;token=[REDACTED]", Redactor.redactPath("/reset;token=" + T));
        assertEquals("/reset;token=[REDACTED]/confirm", Redactor.redactPath("/reset;token=abc/confirm"));
        assertEquals("/app;jsessionid=[REDACTED]", Redactor.redactPath("/app;jsessionid=0123ABCD"));
        assertEquals("/x;v=[redacted];page=2", Redactor.redactPath("/x;v=" + T + ";page=2"));
        assertEquals("https://app.example.com/cart;jsessionid=[REDACTED]?step=2",
            Redactor.redactHeaders(Map.of("Referer", "https://app.example.com/cart;jsessionid=0123ABCD?step=2")).get("Referer"));
        for (String ok : List.of("/files/report%20final.pdf", "/users/Jos%C3%A9", "/a%2Fb/c", "/search;page=2",
                "/posts/2024/11/hello-world", "/assets/app.3f9a1c2b.js", "/items/550e8400-e29b-41d4-a716-446655440000",
                "/v1/users/42/orders/12345", "/%E2%9C%93/done")) {
            assertEquals(ok, Redactor.redactPath(ok), ok);
        }
    }

    @Test
    void urlsAndCredentialsInsideOtherHeadersAreRedacted() {
        // M6: Forwarded and Via are no longer kept verbatim; embedded URLs (absolute, <...>, after
        // url= or uri=) go through redactUrl, and name=value pairs are tested by name.
        String[][] cases = {
            {"Link", "<https://app.example.com/reset?token=abc123>; rel=\"prev\"",
                "<https://app.example.com/reset?token=[REDACTED]>; rel=\"prev\""},
            {"Link", "</reset?token=abc123>; rel=\"next\"", "</reset?token=[REDACTED]>; rel=\"next\""},
            {"Forwarded", "for=1.2.3.4;uri=\"/reset?token=hunter2pw\"", "for=1.2.3.4;uri=\"/reset?token=[REDACTED]\""},
            {"Forwarded", "for=1.2.3.4;by=10.0.0.1;proto=https;uri=\"/reset/" + T + "\"",
                "for=1.2.3.4;by=10.0.0.1;proto=https;uri=\"/reset/[redacted]\""},
            {"Sec-WebSocket-Protocol", "base64url.bearer.authorization.k8s.io.Zx81kLm02PqR7sTuV4wXy9AbCd",
                "base64url.bearer.authorization.k8s.io.[redacted]"},
            {"Refresh", "0; url=https://app.example.com/reset?token=hunter2pw",
                "0; url=https://app.example.com/reset?token=[REDACTED]"},
            {"Refresh", "0; url=/reset?token=hunter2pw", "0; url=/reset?token=[REDACTED]"},
            {"X-Upstream-Info", "user=bob password=hunter2Hdr", "user=bob password=[REDACTED]"},
            {"X-Upstream-Info", "user=bob; password=\"hunter 2\"; realm=x", "user=bob; password=\"[REDACTED]\"; realm=x"},
            {"Via", "1.1 proxy.example.com, 1.1 cache (" + T + ")", "1.1 proxy.example.com, 1.1 cache ([redacted])"},
            // nit: the value boundary: a redacted value no longer swallows the next parameter name
            {"X-Params", "a=" + T + "&b=2", "a=[redacted]&b=2"},
            {"X-Params", "a=" + T + ";b=2", "a=[redacted];b=2"},
        };
        for (String[] c : cases) {
            assertEquals(c[2], Redactor.redactHeaders(Map.of(c[0], c[1])).get(c[0]), c[0] + ": " + c[1]);
        }
    }

    /** Header values the final-review rules must keep (in addition to HEADERS_THAT_SURVIVE). */
    static final List<Map.Entry<String, String>> MORE_HEADERS_THAT_SURVIVE = List.of(
        Map.entry("Forwarded", "for=\"[2001:db8:cafe::17]:4711\";by=203.0.113.43;proto=https"),
        Map.entry("Forwarded", "for=192.0.2.60;proto=http;by=203.0.113.43, for=198.51.100.17"),
        Map.entry("Via", "1.1 vegur"),
        Map.entry("Via", "1.1 varnish (Varnish/6.0), 2.0 2f8c1e4a9b3d7f6e5a4c3b2a1f0e9d8c.cloudfront.net (CloudFront)"),
        Map.entry("Link", "<https://cdn.example.com/app.3f9a1c2b.css>; rel=preload; as=style"),
        Map.entry("X-Upstream-Info", "user=bob; realm=\"Example Realm\"; region=eu-west-1"),
        Map.entry("X-Lb", "my-loadbalancer-1234567890.us-west-2.elb.amazonaws.com"),
        Map.entry("X-Error-Class", "com.amazonaws.services.s3.model.AmazonS3Exception"),
        Map.entry("X-Note", "see https://example.com/docs/getting-started#install, then retry"),
        Map.entry("X-Client-Path", "/var/app/releases/20241105/public"));

    @Test
    void moreOrdinaryHeadersSurvive() {
        for (var h : MORE_HEADERS_THAT_SURVIVE) {
            var out = Redactor.redactHeaders(Map.of(h.getKey(), h.getValue()));
            assertEquals(h.getValue(), out.get(h.getKey()), h.getKey() + ": " + h.getValue());
        }
    }

    /** Messages that must come back unchanged under the run rule. */
    static final List<String> MESSAGES_THAT_SURVIVE = List.of(
        "Caused by: com.amazonaws.services.s3.model.AmazonS3Exception: Access Denied",
        "Unexpected end-of-input: com.fasterxml.jackson.core.json.UTF8StreamJsonParser",
        "UnknownHostException: my-loadbalancer-1234567890.us-west-2.elb.amazonaws.com",
        "connect timed out to db.internal:5432 after 10000ms",
        "Row 550e8400-e29b-41d4-a716-446655440000 was updated by another transaction",
        "Version 4.12.0-beta.3+build.20241105 is not supported",
        "File not found: /srv/app/uploads/2024/11/report-final.pdf",
        "For input string: \"12345678901234567890\"",
        "duplicate key value violates unique constraint \"users_email_key\"",
        "Expected 3 arguments but got 2 (see https://example.com/docs/api#limits).",
        "rate limited, 100% of quota used",
        "Template app.3f9a1c2b.js not found");

    @Test
    void ordinaryMessagesSurvive() {
        for (String m : MESSAGES_THAT_SURVIVE) {
            assertEquals(m, Redactor.redactMessage(m), m);
        }
    }

    @Test
    void contentHashesInFileNamesAreRedactedDeliberately() {
        // A content hash of 16+ characters in a file name cannot be told from a download token
        // (/dl/<token>.pdf), so it is redacted; short fingerprints (Brace's own ?v=, app.3f9a1c2b.js)
        // survive. The cost is cosmetic: an error record shows /assets/[redacted].css.
        assertEquals("/assets/[redacted].css", Redactor.redactPath("/assets/main-3f9a1c2b4d5e6f7a8b9c0d1e.css"));
        assertEquals("https://cdn.example.com/assets/[redacted].css", Redactor.redactHeaders(
            Map.of("Referer", "https://cdn.example.com/assets/main-3f9a1c2b4d5e6f7a8b9c0d1e.css")).get("Referer"));
    }

    // ---- Final review (2026-11): name segments (L11) and the field rule (L12) ----

    /** Names {@link Redactor#isSensitive} (query strings, headers, pairs in transit) must match. */
    static final List<String> STRICT_NAMES_THAT_MATCH = List.of(
        // whole-name and auth-paired "code"
        "code", "Code", "X-Code", "verification_code", "verificationCode", "VerificationCode", "auth_code",
        "authCode", "authcode", "reset_code", "resetCode", "otp_code", "sms_code", "mfa_code", "2fa_code",
        "pin_code", "invite_code", "access_code", "login_code", "confirm_code", "confirmation_code",
        "activation_code", "recovery_code", "backup_code", "security_code", "magic_code", "device_code",
        "code_verifier", "user[auth_code]", "passcode", "pass_code", "Passcode",
        // short words as a whole segment
        "pin", "user_pin", "new-pin", "session", "user_session", "session_id", "sessionId", "sid", "account_sid",
        "ticket", "service_ticket", "sig", "SIG", "url_sig", "otp", "sms_otp",
        // auth as the last segment
        "X-Auth", "X-Upstream-Auth", "Proxy-Auth", "basic_auth",
        // the substring list (unchanged)
        "token", "api_key", "x-api-key", "password", "csrf-token", "X-Auth-Token", "client_assertion", "jwt", "nonce");

    /** Names {@link Redactor#isSensitive} must NOT match. */
    static final List<String> STRICT_NAMES_THAT_DONT = List.of(
        "zip_code", "zipcode", "status_code", "error_code", "country_code", "country-code", "currency_code",
        "postal_code", "promo_code", "language_code", "exit_code", "http_code", "response_code", "product_code",
        "code_challenge", "user_code", "postcode", "barcode", "encode", "codec", "signal", "design", "assign",
        "sigma", "footprint", "hotpath", "otpauth_uri", "pinned", "is_pinned", "spin", "pinterest", "sessions",
        "sidebar", "side", "tickets", "author", "authority", "auth_method", "auth_provider", "oauth_provider",
        "X-Auth-Request-User", "state", "username", "count", "path", "monkey");

    /** Names {@link Redactor#isSensitiveField} (log fields, route params) must match. */
    static final List<String> FIELD_NAMES_THAT_MATCH = List.of(
        "password", "token", "api_key", "secret", "csrf", "jwt", "sessionId", "session_id", "X-Auth-Token",
        "auth_code", "verification_code", "verificationCode", "reset_code", "pin_code", "passcode", "otp",
        "otp_code", "code_verifier", "device_code");

    /** Names {@link Redactor#isSensitiveField} must NOT match: the short words that are ordinary data in logs and routes. */
    static final List<String> FIELD_NAMES_THAT_DONT = List.of(
        "code", "Code", "sig", "pin", "session", "sid", "ticket", "state", "X-Auth", "otp_enabled",
        "zip_code", "status_code", "error_code", "country_code", "postcode", "sessions", "count", "user");

    @Test
    void sensitiveNamesMatchBySegment() {
        for (String n : STRICT_NAMES_THAT_MATCH) assertTrue(Redactor.isSensitive(n), "strict should match: " + n);
        for (String n : STRICT_NAMES_THAT_DONT) assertFalse(Redactor.isSensitive(n), "strict should not match: " + n);
        for (String n : FIELD_NAMES_THAT_MATCH) assertTrue(Redactor.isSensitiveField(n), "field should match: " + n);
        for (String n : FIELD_NAMES_THAT_DONT) assertFalse(Redactor.isSensitiveField(n), "field should not match: " + n);
    }

    @Test
    void shortSecretsUnderMissedNamesAreRedactedInTransit() {
        // L11: short secrets under names the old list missed.
        for (String name : List.of("verification_code", "auth_code", "reset_code", "passcode", "pin", "session",
                "sid", "ticket")) {
            assertEquals(name + "=[REDACTED]&page=2", Redactor.redactQuery(name + "=hunter2&page=2"), name);
        }
        assertEquals("[REDACTED]", Redactor.redactHeaders(Map.of("X-Auth", "hunter2pw")).get("X-Auth"));
        assertEquals("[REDACTED]", Redactor.redactHeaders(Map.of("X-Upstream-Auth", "password=hunter2Hdr")).get("X-Upstream-Auth"));
        // a short token nested in an encoded URL value
        assertEquals("next=[REDACTED]",
            Redactor.redactQuery("next=https%3A%2F%2Fapp.example.com%2Freset%3Ftoken%3Dhunter2pw"));
        assertEquals("next=[REDACTED]", Redactor.redactQuery("next=%2Freset%3Fcode%3Dhunter2"));
        // ordinary names and nested URLs survive
        assertEquals("zip_code=94107&status_code=404&next=%2Fposts%3Fpage%3D2",
            Redactor.redactQuery("zip_code=94107&status_code=404&next=%2Fposts%3Fpage%3D2"));
    }

    @Test
    void logFieldsAndRouteParamsUseTheFieldRule() {
        // L12: code/sig/pin/session/... are credentials in a query string, but ordinary data in a
        // structured log field or a route parameter.
        LogTap.clear();
        Log.info("charge failed", Map.of("code", "card_declined", "auth_code", "hunter2", "session", "s-42"));
        var entry = LogTap.snapshot().stream()
            .filter(e -> "charge failed".equals(e.fields().get("message")))
            .findFirst().orElseThrow();
        assertEquals("card_declined", entry.fields().get("code"));
        assertEquals("s-42", entry.fields().get("session"));
        assertEquals(Redactor.PLACEHOLDER, entry.fields().get("auth_code"));
        var countries = new Route("GET", "/countries/{code}", (Handler) r -> null, null);
        assertEquals("/countries/US", Analytics.storedPath(countries, "/countries/US"));
        var verify = new Route("GET", "/verify/{verification_code}", (Handler) r -> null, null);
        assertEquals("/verify/{verification_code}", Analytics.storedPath(verify, "/verify/123456"));
        var reset = new Route("GET", "/reset/{token}", (Handler) r -> null, null);
        assertEquals("/reset/{token}", Analytics.storedPath(reset, "/reset/abc"));
        // The strict rule still applies in transit.
        assertEquals("code=[REDACTED]", Redactor.redactQuery("code=card_declined"));
        // And messages use the field rule for name=value pairs.
        assertEquals("charge failed code=card_declined password=[REDACTED]",
            Redactor.redactMessage("charge failed code=card_declined password=hunter2"));
    }

    @Test
    void deeplyNestedLogFieldsAreBoundedNotAStackOverflow() {
        // nit: redact(Map) recursed once per level; 20,000 levels overflowed the stack.
        Map<String, Object> deep = new java.util.HashMap<>();
        Map<String, Object> cur = deep;
        for (int i = 0; i < 20_000; i++) {
            Map<String, Object> next = new java.util.HashMap<>();
            cur.put("n", i % 2 == 0 ? next : List.of(next));
            if (i % 2 != 0) cur.put("token", "x");
            cur = next;
        }
        var out = Redactor.redact(deep);
        Object level = out;
        int depth = 0;
        while (level instanceof Map<?, ?> m || level instanceof List<?>) {
            if (level instanceof List<?> l) level = l.get(0);
            else {
                var map = (Map<?, ?>) level;
                if (map.containsKey("token")) assertEquals(Redactor.PLACEHOLDER, map.get("token"));
                level = map.get("n");
            }
            depth++;
        }
        assertEquals(Redactor.TOO_DEEP, level);
        assertTrue(depth <= Redactor.MAX_NESTING + 1, "depth " + depth);
        // A map that contains itself is cut off the same way.
        Map<String, Object> self = new java.util.HashMap<>();
        self.put("me", self);
        self.put("again", self);
        assertNotNull(Redactor.redact(self));
        // A shared child under two keys at every level (2^40 paths) is bounded by the copy budget.
        Map<String, Object> shared = Map.of("leaf", 1);
        for (int i = 0; i < 40; i++) shared = Map.of("a", shared, "b", shared);
        long t0 = System.nanoTime();
        assertNotNull(Redactor.redact(shared));
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L);
    }

    @Test
    void reviewerWorstCasesStayLinear() {
        // Error path only, but attacker-sized. Each shape targets one scanner: percent runs,
        // ampersands, dots, %25 (decode rounds), '=' pairs, '<'+'/' spans, scheme scans, nested
        // URLs, quotes, name runs. 64 KB and 1 MB of each, through every entry point.
        List<String> units = List.of("%", "&", ".", "%25", "=", "</", "a:", "x://", "a=/", "\"", "a=\"",
            "https://h/?n=", "a[", "~", "$", "Ab1/", "a.b.c.", "%2", "x=%25");
        long t0 = System.nanoTime();
        for (int size : List.of(64 * 1024, 1024 * 1024)) {
            for (String unit : units) {
                String big = unit.repeat(size / unit.length());
                Redactor.redactQuery(big);
                Redactor.redactUrl("https://h/" + big);
                Redactor.redactPath("/" + big);
                Redactor.redactMessage(big);
                Redactor.redactStackTrace("java.lang.RuntimeException: " + big + "\n\tat a.B(B.java:1)\n");
                Redactor.redactHeaders(Map.of("X-Big", big, "Referer", "https://h/?" + big, "Forwarded", big));
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 20_000, "worst cases took " + ms + " ms");
    }

    @Test
    void longAdversarialInputStaysLinear() {
        // Error path only, but attacker-sized: a 1 MB value of near-misses must not blow up.
        String nearMiss = "a".repeat(19) + "." + "b".repeat(19);
        String big = (nearMiss + "/").repeat(25_000) + "%".repeat(10_000) + "=&".repeat(10_000);
        long t0 = System.nanoTime();
        Redactor.redactQuery(big);
        Redactor.redactUrl("https://h/" + big);
        Redactor.redactHeaders(Map.of("X-Big", big));
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L, "redaction of a 1 MB value must be fast");
    }
}
