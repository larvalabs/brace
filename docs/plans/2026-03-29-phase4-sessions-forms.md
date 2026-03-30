# Phase 4: Sessions, CSRF, Form Binding, Validation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add cookie-based sessions (HMAC-SHA256 signed), CSRF protection, form binding with validation annotations, and the Passwords helper. At the end, a developer can build login forms, validate user input, and maintain session state.

**Architecture:** Sessions are `Map<String, String>` serialized to JSON, signed with HMAC-SHA256, stored as a cookie. CSRF tokens live in the session and are auto-validated on POST/PUT/DELETE. Form binding deserializes request params into Java records with validation annotations.

**Tech Stack:** javax.crypto (HMAC-SHA256, built into JDK), jBCrypt, JUnit 5

---

## File Structure

```
src/main/java/io/brace/
├── Session.java                # Cookie-based signed session
├── Csrf.java                   # CSRF token generation and validation middleware
├── Form.java                   # Form<T> wrapper with validation results
├── Errors.java                 # Validation error collection
├── Passwords.java              # bcrypt helper
├── annotation/
│   ├── Required.java
│   ├── MinLength.java
│   ├── MaxLength.java
│   ├── Min.java
│   ├── Max.java
│   ├── Pattern.java
│   ├── Email.java
│   ├── In.java
│   └── Optional.java
├── FormBinder.java             # Reflection-based form binding + validation
├── Invoker.java                # Updated — recognizes Session.class
├── BraceHandler.java           # Updated — session read/write, CSRF check
├── Brace.java                  # Updated — sessions() config
├── Request.java                # Updated — form() method
src/test/java/io/brace/
├── SessionTest.java
├── FormTest.java
├── CsrfTest.java
├── PasswordsTest.java
├── SessionIntegrationTest.java
```

---

### Task 1: Add jBCrypt Dependency + Passwords Helper

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/io/brace/Passwords.java`
- Create: `src/test/java/io/brace/PasswordsTest.java`

- [ ] **Step 1: Add jBCrypt to pom.xml**

Add `org.mindrot:jbcrypt` (latest 0.4 or similar). Add version property.

- [ ] **Step 2: Implement Passwords**

```java
package io.brace;

import org.mindrot.jbcrypt.BCrypt;

public class Passwords {
    public static String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(12));
    }

    public static boolean check(String password, String hash) {
        return BCrypt.checkpw(password, hash);
    }
}
```

- [ ] **Step 3: Write tests**

```java
package io.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordsTest {
    @Test
    void hashAndCheck() {
        var hash = Passwords.hash("secret123");
        assertNotNull(hash);
        assertTrue(Passwords.check("secret123", hash));
        assertFalse(Passwords.check("wrong", hash));
    }

    @Test
    void hashesAreDifferentEachTime() {
        var hash1 = Passwords.hash("secret123");
        var hash2 = Passwords.hash("secret123");
        assertNotEquals(hash1, hash2);
    }
}
```

- [ ] **Step 4: Run tests, commit**

```
git commit -m "Phase 4 Task 1: Passwords helper with bcrypt"
```

---

### Task 2: Session Implementation

**Files:**
- Create: `src/main/java/io/brace/Session.java`
- Create: `src/test/java/io/brace/SessionTest.java`

- [ ] **Step 1: Implement Session**

Session is a `Map<String, String>` wrapper that can serialize/deserialize to a signed cookie.

```java
package io.brace;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Session {

    private final Map<String, String> data;
    private boolean modified = false;

    public Session() {
        this.data = new LinkedHashMap<>();
    }

    public Session(Map<String, String> data) {
        this.data = new LinkedHashMap<>(data);
    }

    // Accessors
    public String get(String key) { return data.get(key); }
    public int getInt(String key) { return Integer.parseInt(data.get(key)); }
    public long getLong(String key) { return Long.parseLong(data.get(key)); }
    public boolean has(String key) { return data.containsKey(key); }

    public void set(String key, Object value) {
        data.put(key, String.valueOf(value));
        modified = true;
    }

    public void remove(String key) {
        data.remove(key);
        modified = true;
    }

    public void clear() {
        data.clear();
        modified = true;
    }

    public boolean isModified() { return modified; }

    // Factory for test injection
    public static Session of(Object... keyValues) {
        var session = new Session();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            session.set((String) keyValues[i], keyValues[i + 1]);
        }
        session.modified = false; // fresh session, not "modified"
        return session;
    }

    // Serialization: JSON map → Base64
    String serialize() {
        // Simple JSON: {"key":"value","key2":"value2"}
        var sb = new StringBuilder("{");
        var first = true;
        for (var entry : data.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
              .append(escapeJson(entry.getValue())).append("\"");
            first = false;
        }
        sb.append("}");
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // Deserialization: Base64 → JSON map
    static Session deserialize(String encoded) {
        var json = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        // Simple JSON parser for {"key":"value"} format
        var data = new LinkedHashMap<String, String>();
        json = json.trim();
        if (json.equals("{}")) return new Session(data);
        // Strip { and }
        json = json.substring(1, json.length() - 1);
        // Split on ","  (simple — doesn't handle escaped quotes in values, but fine for session data)
        for (var pair : json.split(",")) {
            var parts = pair.split(":", 2);
            var key = unquote(parts[0].trim());
            var value = unquote(parts[1].trim());
            data.put(key, value);
        }
        return new Session(data);
    }

    // Cookie format: base64data.signature
    public String toCookie(String secret) {
        var payload = serialize();
        var signature = hmacSha256(payload, secret);
        return payload + "." + signature;
    }

    public static Session fromCookie(String cookie, String secret) {
        if (cookie == null || !cookie.contains(".")) return new Session();
        var dotIndex = cookie.lastIndexOf('.');
        var payload = cookie.substring(0, dotIndex);
        var signature = cookie.substring(dotIndex + 1);
        // Verify signature
        var expected = hmacSha256(payload, secret);
        if (!constantTimeEquals(signature, expected)) return new Session();
        return deserialize(payload);
    }

    // HMAC-SHA256
    private static String hmacSha256(String data, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    // Constant-time comparison to prevent timing attacks
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unquote(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
```

- [ ] **Step 2: Write tests**

```java
package io.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

    private static final String SECRET = "test-secret-key-at-least-32-characters-long";

    @Test
    void setAndGet() {
        var session = new Session();
        session.set("userId", 42);
        assertEquals("42", session.get("userId"));
        assertEquals(42, session.getInt("userId"));
    }

    @Test
    void has() {
        var session = new Session();
        assertFalse(session.has("key"));
        session.set("key", "value");
        assertTrue(session.has("key"));
    }

    @Test
    void remove() {
        var session = new Session();
        session.set("key", "value");
        session.remove("key");
        assertFalse(session.has("key"));
    }

    @Test
    void clear() {
        var session = new Session();
        session.set("a", "1");
        session.set("b", "2");
        session.clear();
        assertFalse(session.has("a"));
        assertFalse(session.has("b"));
    }

    @Test
    void toCookieAndFromCookie() {
        var session = new Session();
        session.set("userId", 42);
        session.set("role", "admin");
        var cookie = session.toCookie(SECRET);

        var restored = Session.fromCookie(cookie, SECRET);
        assertEquals("42", restored.get("userId"));
        assertEquals("admin", restored.get("role"));
    }

    @Test
    void invalidSignatureReturnsEmptySession() {
        var session = new Session();
        session.set("userId", 42);
        var cookie = session.toCookie(SECRET);

        var tampered = Session.fromCookie(cookie, "wrong-secret");
        assertFalse(tampered.has("userId"));
    }

    @Test
    void nullCookieReturnsEmptySession() {
        var session = Session.fromCookie(null, SECRET);
        assertFalse(session.has("anything"));
    }

    @Test
    void ofFactory() {
        var session = Session.of("userId", 1, "role", "admin");
        assertEquals(1, session.getInt("userId"));
        assertEquals("admin", session.get("role"));
    }

    @Test
    void modifiedTracking() {
        var session = new Session();
        assertFalse(session.isModified());
        session.set("key", "value");
        assertTrue(session.isModified());
    }
}
```

- [ ] **Step 3: Run tests, commit**

```
git commit -m "Phase 4 Task 2: Session — HMAC-SHA256 signed cookie sessions"
```

---

### Task 3: Update Invoker + BraceHandler for Sessions

**Files:**
- Modify: `src/main/java/io/brace/Invoker.java`
- Modify: `src/main/java/io/brace/BraceHandler.java`
- Modify: `src/main/java/io/brace/Brace.java`
- Create: `src/main/java/io/brace/SessionHandler.java` (functional interface for handlers needing Session)
- Create: `src/test/java/io/brace/SessionIntegrationTest.java`

- [ ] **Step 1: Create SessionHandler and DbSessionHandler functional interfaces**

We need handlers that can take Session, and handlers that take both Database and Session:

```java
// SessionHandler: (Request, Session) -> Result
@FunctionalInterface
public interface SessionHandler {
    Result apply(Request request, Session session);
}
```

We also need combinations: (Request, Database, Session). Rather than creating every permutation, let's create one more:

```java
// FullHandler: (Request, Database, Session) -> Result
@FunctionalInterface
public interface FullHandler {
    Result apply(Request request, Database database, Session session);
}
```

- [ ] **Step 2: Update Invoker**

Change Session matching from name-based to type-based:
```java
} else if (type == Session.class) {
    paramTypes.add(ParamType.SESSION);
}
```

Add `fromSessionFunction(SessionHandler)` and `fromFullFunction(FullHandler)` factory methods.

- [ ] **Step 3: Update Brace with session config and handler overloads**

```java
private String sessionSecret;

public Brace sessions(String secret) {
    this.sessionSecret = secret;
    return this;
}
```

Add `get/post/put/delete` overloads for `SessionHandler` and `FullHandler`.

- [ ] **Step 4: Update BraceHandler for session lifecycle**

On each request:
1. Read `brace_session` cookie from request
2. `Session.fromCookie(cookie, secret)` to deserialize and verify
3. Pass session to invoker if `needsSession()`
4. After handler returns, if session was modified, set `Set-Cookie` header on response

- [ ] **Step 5: Write integration tests**

Test login flow: set session value, verify cookie returned, send cookie back, verify session read works.

- [ ] **Step 6: Run all tests, commit**

```
git commit -m "Phase 4 Task 3: Session integration in BraceHandler with cookie read/write"
```

---

### Task 4: Validation Annotations + FormBinder + Form

**Files:**
- Create: `src/main/java/io/brace/annotation/Required.java`
- Create: `src/main/java/io/brace/annotation/MinLength.java`
- Create: `src/main/java/io/brace/annotation/MaxLength.java`
- Create: `src/main/java/io/brace/annotation/Min.java`
- Create: `src/main/java/io/brace/annotation/Max.java`
- Create: `src/main/java/io/brace/annotation/Email.java`
- Create: `src/main/java/io/brace/annotation/In.java`
- Create: `src/main/java/io/brace/annotation/Optional.java`
- Create: `src/main/java/io/brace/Errors.java`
- Create: `src/main/java/io/brace/Form.java`
- Create: `src/main/java/io/brace/FormBinder.java`
- Modify: `src/main/java/io/brace/Request.java` — add `form()` method
- Create: `src/test/java/io/brace/FormTest.java`

- [ ] **Step 1: Create validation annotations**

Each annotation targets RECORD_COMPONENT and FIELD, retention RUNTIME:

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface Required {}

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface MinLength { int value(); }

// ... etc for MaxLength, Min, Max, Email, In, Optional, Pattern
```

`@In` takes `String[] value()`.
`@Pattern` takes `String value()`.
`@Optional` is a marker — field is not required (default assumption for non-@Required fields).

- [ ] **Step 2: Implement Errors**

```java
package io.brace;

import java.util.*;

public class Errors {
    private final Map<String, List<String>> errors = new LinkedHashMap<>();

    public void add(String field, String message) {
        errors.computeIfAbsent(field, k -> new ArrayList<>()).add(message);
    }

    public boolean hasErrors() { return !errors.isEmpty(); }
    public Map<String, List<String>> all() { return Collections.unmodifiableMap(errors); }
    public List<String> get(String field) { return errors.getOrDefault(field, List.of()); }
}
```

- [ ] **Step 3: Implement Form<T>**

```java
package io.brace;

import java.util.*;

public class Form<T> {
    private final T value;
    private final Errors errors;
    private final Map<String, String> rawValues;

    public Form(T value, Errors errors, Map<String, String> rawValues) {
        this.value = value;
        this.errors = errors;
        this.rawValues = rawValues;
    }

    public boolean valid() { return !errors.hasErrors(); }
    public T value() { return value; }
    public Errors errors() { return errors; }
    public Map<String, List<String>> allErrors() { return errors.all(); }
    public List<String> errors(String field) { return errors.get(field); }
    public String raw(String field) { return rawValues.get(field); }
}
```

- [ ] **Step 4: Implement FormBinder**

FormBinder takes a Map<String, String> (from request params) and a record class, and:
1. For each record component, finds the matching param by name
2. Converts the string to the component's type (String, int, long, etc.)
3. Validates using annotations (@Required, @MinLength, etc.)
4. If a `validate(Errors)` method exists on the record, calls it for custom validation
5. Returns Form<T> with the constructed record (or null if type conversion failed) and any errors

Use Java's record reflection: `recordClass.getRecordComponents()` to get component names and types.
Construct the record via its canonical constructor.

- [ ] **Step 5: Update Request with form() method**

Request needs access to form parameters (POST body, URL-encoded). Add:
```java
public <T> Form<T> form(Class<T> type) {
    // Parse URL-encoded form body into Map<String, String>
    var params = parseFormBody(body());
    // Also include query params as fallback
    return FormBinder.bind(type, params);
}
```

The form body parser handles `application/x-www-form-urlencoded`: `title=Hello&body=World` → `{"title": "Hello", "body": "World"}`.

- [ ] **Step 6: Write FormTest**

Test binding, validation (required, minLength, email, etc.), custom validate method, type conversion, raw value preservation.

- [ ] **Step 7: Run all tests, commit**

```
git commit -m "Phase 4 Task 4: Form binding with validation annotations"
```

---

### Task 5: CSRF Protection

**Files:**
- Create: `src/main/java/io/brace/Csrf.java`
- Modify: `src/main/java/io/brace/BraceHandler.java` — auto-validate CSRF on POST/PUT/DELETE
- Modify: `src/main/java/io/brace/View.java` — make csrfField available to templates
- Create: `src/test/java/io/brace/CsrfTest.java`

- [ ] **Step 1: Implement Csrf**

```java
package io.brace;

import java.security.SecureRandom;
import java.util.Base64;

public class Csrf {
    private static final String TOKEN_KEY = "_csrf";

    public static String generateToken() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static void ensureToken(Session session) {
        if (!session.has(TOKEN_KEY)) {
            session.set(TOKEN_KEY, generateToken());
        }
    }

    public static String getToken(Session session) {
        return session.get(TOKEN_KEY);
    }

    public static boolean validateToken(Session session, String submittedToken) {
        var expected = session.get(TOKEN_KEY);
        if (expected == null || submittedToken == null) return false;
        return expected.equals(submittedToken);
    }

    public static String hiddenField(Session session) {
        return "<input type=\"hidden\" name=\"_csrf\" value=\"" + getToken(session) + "\">";
    }
}
```

- [ ] **Step 2: Update BraceHandler**

For POST/PUT/DELETE requests (when sessions are enabled):
1. Ensure CSRF token exists in session
2. Check `_csrf` form parameter or `X-CSRF-Token` header against session token
3. Skip CSRF check if `Content-Type: application/json` (API requests)
4. Return 403 if token invalid

- [ ] **Step 3: Make csrfField available in templates**

When rendering a View, automatically add `csrfField` to the template params (the HTML hidden input string). This requires the session to be accessible during View rendering. The simplest approach: BraceHandler adds `csrfField` to the Result's params if it's a View and sessions are enabled.

- [ ] **Step 4: Write tests**

Test CSRF validation, token generation, skip for JSON, 403 on missing/invalid token.

- [ ] **Step 5: Run all tests, commit**

```
git commit -m "Phase 4 Task 5: CSRF protection with auto-validation"
```

---

## Phase 4 Complete

At this point:
- HMAC-SHA256 signed cookie sessions
- CSRF protection on POST/PUT/DELETE (auto, skip for JSON API)
- Form binding to Java records with validation annotations
- Custom validation via `validate(Errors)` method on records
- Passwords helper (bcrypt)
- Session, Database, and Request all injectable into controller methods

**Next:** Phase 5 adds the job scheduler, durable job queue, and mailer.
