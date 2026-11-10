package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Field-name-based and value-shaped redaction for log entries and ops diagnostics.
 * Replaces the <em>values</em> of fields whose name looks sensitive (authorization,
 * cookie, token, secret, password, api key, …) with {@code [REDACTED]} before they
 * reach stdout, the log ring buffer, or the {@code /ops/*} endpoints.
 *
 * <p>This is an allowlist-of-shape — it matches field <em>names</em>, not value
 * content; it is not PII detection. It deliberately over-redacts (a field named
 * {@code token_count} is redacted) rather than risk leaking a credential. Matching
 * is on the normalized name (lowercased, {@code -}/{@code _} stripped) containing a
 * sensitive token as a substring, or, for the short names {@code code}, {@code sig} and
 * {@code otp}, being exactly that name.
 *
 * <p><strong>Value-shaped redaction</strong> ({@link #redactPath} and
 * {@link #redactMessage}) targets high-entropy tokens by their shape rather than
 * their field name — covering URL path segments that carry reset/invite/API tokens
 * and exception messages that may contain credentials or SQL literals.
 *
 * <h3>Heuristic (path segments and message tokens)</h3>
 * A segment is redacted when <em>all</em> of these hold:
 * <ol>
 *   <li>Length ≥ {@value #MIN_SECRET_LENGTH} characters.</li>
 *   <li>Every character is in the base64url-or-hex alphabet
 *       ({@code [A-Za-z0-9_\-+/=]}).</li>
 *   <li>Contains at least one ASCII digit <em>and</em> at least one ASCII letter
 *       (rules out purely numeric IDs and purely alphabetic slugs).</li>
 * </ol>
 * Exceptions kept visible (conservative):
 * <ul>
 *   <li>Standard UUIDs (8-4-4-4-12 hex with hyphens) — usually record identifiers
 *       needed for debugging, not bearer secrets. {@code redactPath} leaves them
 *       as-is; callers that need UUID redaction can do so explicitly.</li>
 *   <li>Purely numeric segments (page IDs, user IDs, order numbers).</li>
 *   <li>Short slugs and ordinary words (length &lt; {@value #MIN_SECRET_LENGTH}).</li>
 *   <li>Segments with characters outside the base64url-or-hex set (e.g. dots in
 *       file names, spaces in messages).</li>
 * </ul>
 * JWT-shaped tokens (two dots dividing three base64url parts where the outer parts
 * are long enough) and two-part {@code payload.signature} tokens (one dot, at least
 * {@value #TWO_PART_MIN} base64url characters on each side, mixed content: the ops
 * token's shape) are caught as special cases before the per-segment heuristic.
 *
 * <p><strong>Error-store request capture</strong> combines both passes:
 * {@link #redactQuery} redacts by decoded parameter name and by value shape, and
 * {@link #redactHeaders} redacts by header name, then URL-valued headers through
 * {@link #redactUrl} and other free-form headers through {@link #redactValue}, keeping
 * protocol-metadata headers (Accept, Content-Type, User-Agent, ETags, request ids)
 * verbatim.
 */
public class Redactor {

    public static final String PLACEHOLDER = "[REDACTED]";

    /** Normalized substrings that mark a field name as sensitive. */
    private static final Set<String> SENSITIVE = Set.of(
        "authorization", "cookie", "password", "passwd", "pwd", "secret",
        "token", "apikey", "bearer", "credential", "privatekey", "accesskey",
        "sessionid", "csrf",
        // M6: identity-proxy and signed-URL names (Cf-Access-Jwt-Assertion, client_assertion,
        // X-Amz-Signature, X-Auth-Key, OIDC nonce). Safe as substrings: no common word contains them.
        "jwt", "assertion", "signature", "authkey", "nonce");

    /**
     * Short sensitive names matched against the WHOLE normalized name only. As substrings they
     * hit ordinary words ({@code encode}, {@code postcode}, {@code barcode}; {@code design},
     * {@code assign}; {@code footprint}), so {@code ?code=} (an OAuth authorization code),
     * {@code ?sig=} (Azure SAS) and {@code ?otp=} are redacted while {@code zip_code},
     * {@code status_code} and {@code signal} are not.
     */
    private static final Set<String> SENSITIVE_EXACT = Set.of("code", "sig", "otp");

    // ---- Value-shaped redaction constants ----

    /**
     * Minimum segment length to trigger value-shaped redaction. Segments shorter
     * than this are always kept visible (slugs, short words, small numeric IDs).
     * Tunable: raise to be less aggressive, lower to catch shorter secrets.
     */
    static final int MIN_SECRET_LENGTH = 16;

    /**
     * Characters allowed in base64url / hex / base64 tokens. Segments containing
     * only these characters are candidates for entropy-based redaction.
     */
    private static final Pattern SECRET_CHARS = Pattern.compile("[A-Za-z0-9_\\-+/=]+");

    /**
     * Standard UUID shape (8-4-4-4-12 hex digits with hyphens). UUIDs are usually
     * record identifiers — kept visible for debugging, not redacted.
     */
    private static final Pattern UUID_SHAPE =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * JWT shape: three base64url segments separated by exactly two dots, where the
     * first and last segments are at least 10 characters each.
     */
    private static final Pattern JWT_SHAPE =
        Pattern.compile("[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]{10,}");

    /**
     * A candidate token in an exception message for value-shaped redaction: a run of
     * characters between delimiters (whitespace, quotes, commas, colons, semicolons,
     * equals, parentheses and brackets).
     */
    private static final Pattern MESSAGE_TOKEN = Pattern.compile("[^\\s\"'`,;:=()\\[\\]{}]+");

    private Redactor() {}

    /** True if a field with this name should have its value redacted. */
    public static boolean isSensitive(String name) {
        if (name == null) return false;
        String norm = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (SENSITIVE_EXACT.contains(norm)) return true;
        for (String token : SENSITIVE) {
            if (norm.contains(token)) return true;
        }
        return false;
    }

    /**
     * Return a deep copy of {@code fields} with the values of sensitive-named keys
     * replaced by {@link #PLACEHOLDER}. Recurses into nested maps and lists.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redact(Map<String, Object> fields) {
        var out = new LinkedHashMap<String, Object>(fields.size());
        for (var e : fields.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (isSensitive(key)) {
                out.put(key, PLACEHOLDER);
            } else if (value instanceof Map<?, ?> m) {
                out.put(key, redact((Map<String, Object>) m));
            } else if (value instanceof List<?> list) {
                out.put(key, redactList(list));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> redactList(List<?> list) {
        var out = new ArrayList<Object>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(redact((Map<String, Object>) m));
            } else if (item instanceof List<?> l) {
                out.add(redactList(l));
            } else {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * Redact a URL query string (e.g. {@code a=1&token=xyz&b=2} → {@code a=1&token=[REDACTED]&b=2}).
     * A parameter's value is replaced with {@link #PLACEHOLDER} when:
     * <ul>
     *   <li>its name, percent-decoded, is sensitive ({@link #isSensitive}): {@code %74oken=} is
     *       {@code token=}; or</li>
     *   <li>its value, raw or percent-decoded, contains a secret-shaped token (the
     *       {@link #redactMessage} heuristic, JWTs and two-part tokens included), or is a URL or
     *       path one of whose {@code / ? & #}-delimited pieces is secret-shaped (a
     *       {@code ?next=/reset/<token>}).</li>
     * </ul>
     * A part without {@code =} is replaced whole when it is secret-shaped. The raw (encoded)
     * name and every other part are kept exactly as sent. Malformed percent-escapes are kept
     * literally, never thrown. A leading {@code ?} is not expected (pass the raw query).
     */
    public static String redactQuery(String query) {
        if (query == null || query.isEmpty()) return query;
        var out = new StringBuilder(query.length());
        int start = 0;
        int len = query.length();
        while (true) {
            int amp = query.indexOf('&', start);
            int end = amp < 0 ? len : amp;
            // Search for '=' within this part only: indexOf(char, from) would scan past `end` on
            // every '='-less part, quadratic on "a&a&a&…&a=1".
            int eq = indexOfAny(query, start, end, "=");
            if (eq == end) {
                String part = query.substring(start, end);
                out.append(isSecretValue(part) ? PLACEHOLDER : part);
            } else if (isSensitive(lenientDecode(query.substring(start, eq)))
                    || isSecretValue(query.substring(eq + 1, end))) {
                out.append(query, start, eq + 1).append(PLACEHOLDER);
            } else {
                out.append(query, start, end);
            }
            if (amp < 0) break;
            out.append('&');
            start = amp + 1;
        }
        return out.toString();
    }

    /** A raw query value that holds a secret-shaped token, before or after percent-decoding. */
    private static boolean isSecretValue(String raw) {
        if (raw.length() < MIN_SECRET_LENGTH) return false;
        if (containsSecretToken(raw)) return true;
        String decoded = lenientDecode(raw);
        return !decoded.equals(raw) && containsSecretToken(decoded);
    }

    /**
     * True when {@link #redactValue} would change {@code value}. Kept separate so a caller that
     * replaces the whole value does not build the redacted copy.
     */
    private static boolean containsSecretToken(String value) {
        var matcher = MESSAGE_TOKEN.matcher(value);
        while (matcher.find()) {
            if (matcher.end() - matcher.start() < MIN_SECRET_LENGTH) continue;
            if (isSecretToken(matcher.group())) return true;
        }
        return false;
    }

    /**
     * A message/header token that is secret-shaped as a whole, or that is a URL or path with a
     * secret-shaped piece between {@code /}, {@code ?}, {@code &} and {@code #}. Both checks are
     * needed: splitting on {@code /} alone would cut a standard-base64 secret into short pieces.
     */
    private static boolean isSecretToken(String token) {
        if (isSecretShaped(token)) return true;
        int len = token.length();
        int start = 0;
        for (int i = 0; i <= len; i++) {
            char c = i < len ? token.charAt(i) : '/';
            if (c == '/' || c == '?' || c == '&' || c == '#') {
                boolean wholeToken = start == 0 && i == len; // already checked above
                if (!wholeToken && i - start >= MIN_SECRET_LENGTH && isSecretShaped(token.substring(start, i))) {
                    return true;
                }
                start = i + 1;
            }
        }
        return false;
    }

    /**
     * Percent-decode {@code s} the way a query string is decoded ({@code +} is a space), keeping
     * a malformed escape ({@code %zz}, a trailing {@code %}) literally instead of throwing, and
     * replacing invalid UTF-8 with U+FFFD. Returns {@code s} itself when there is nothing to
     * decode. Linear in the input.
     */
    static String lenientDecode(String s) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) return s;
        var bytes = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hexValue(s.charAt(i + 1)) >= 0 && hexValue(s.charAt(i + 2)) >= 0) {
                bytes.write(hexValue(s.charAt(i + 1)) * 16 + hexValue(s.charAt(i + 2)));
                i += 2;
            } else if (c == '+') {
                bytes.write(' ');
            } else if (c < 0x80) {
                bytes.write(c);
            } else {
                int cp = s.codePointAt(i);
                byte[] utf8 = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                bytes.write(utf8, 0, utf8.length);
                i += Character.charCount(cp) - 1;
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    // ---- Request headers (error store) ----

    /**
     * Headers whose value is a URL. Their path goes through {@link #redactPath}, their query and
     * fragment through {@link #redactQuery}, and any {@code user:password@} in the authority is
     * replaced: a {@code Referer} from {@code /reset?token=T} or {@code /reset/T} carries T.
     */
    private static final Set<String> URL_HEADERS = Set.of(
        "referer", "origin", "location", "content-location", "x-original-url", "x-forwarded-uri",
        "x-rewrite-url", "hx-current-url");

    /**
     * Headers kept verbatim: their values are protocol metadata, never a user's credential, and
     * the value-shaped pass would misfire on them (an ETag or a request id is a long hex string, a
     * multipart boundary is a long mixed token, a User-Agent can carry a build hash). Lowercase.
     * Also kept: every {@code sec-fetch-*} and {@code sec-ch-*} header. Not on this list on
     * purpose: {@code Sec-WebSocket-Protocol}, which clients use to smuggle bearer tokens.
     */
    private static final Set<String> VERBATIM_HEADERS = Set.of(
        "accept", "accept-charset", "accept-encoding", "accept-language", "cache-control",
        "connection", "content-encoding", "content-language", "content-length", "content-type",
        "dnt", "expect", "host", "http2-settings", "if-match", "if-modified-since", "if-none-match",
        "if-range", "if-unmodified-since", "keep-alive", "pragma", "priority", "range", "te",
        "upgrade", "upgrade-insecure-requests", "user-agent", "via", "x-requested-with",
        // client address and proxy metadata
        "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-port", "x-forwarded-proto",
        "x-real-ip", "cf-connecting-ip", "true-client-ip", "cf-ipcountry", "cf-visitor",
        // request ids and trace context: identifiers, needed to correlate the error with logs
        "x-request-id", "x-correlation-id", "request-id", "traceparent", "tracestate", "cf-ray",
        "x-amzn-trace-id", "x-cloud-trace-context", "b3", "x-b3-traceid", "x-b3-spanid",
        "x-b3-parentspanid", "x-b3-sampled",
        // WebSocket handshake nonce/version (not a credential)
        "sec-websocket-key", "sec-websocket-version", "sec-websocket-extensions",
        // htmx request metadata (HX-Current-URL is a URL header above; HX-Prompt is user text)
        "hx-request", "hx-boosted", "hx-history-restore-request", "hx-target", "hx-trigger",
        "hx-trigger-name");

    /**
     * Redact request headers for the error store (M6). For each header:
     * <ol>
     *   <li>A sensitive name ({@link #isSensitive}: {@code Authorization}, {@code Cookie},
     *       {@code X-Auth-Key}, {@code Cf-Access-Jwt-Assertion}, …) → {@link #PLACEHOLDER}.</li>
     *   <li>A URL-valued header ({@code Referer}, {@code Origin}, {@code X-Original-URL}, …) →
     *       {@link #redactUrl}.</li>
     *   <li>A protocol-metadata header ({@code Accept}, {@code Content-Type}, {@code User-Agent},
     *       {@code If-None-Match}, {@code X-Request-Id}, {@code X-Forwarded-For}, …) → kept.</li>
     *   <li>Anything else → {@link #redactValue}: each secret-shaped token (high-entropy,
     *       JWT, two-part) becomes {@code [redacted]}, so {@code X-Amzn-Oidc-Data: eyJ…} is
     *       caught by shape.</li>
     * </ol>
     * Non-string values are kept. Returns a new map in the same order.
     */
    public static Map<String, Object> redactHeaders(Map<String, ?> headers) {
        var out = new LinkedHashMap<String, Object>(headers.size());
        for (var e : headers.entrySet()) {
            String name = e.getKey();
            Object value = e.getValue();
            if (isSensitive(name)) {
                out.put(name, PLACEHOLDER);
            } else if (value instanceof String s && name != null) {
                out.put(name, redactHeaderValue(name, s));
            } else {
                out.put(name, value);
            }
        }
        return out;
    }

    private static String redactHeaderValue(String name, String value) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (URL_HEADERS.contains(lower)) return redactUrl(value);
        if (VERBATIM_HEADERS.contains(lower) || lower.startsWith("sec-fetch-") || lower.startsWith("sec-ch-")) {
            return value;
        }
        return redactValue(value);
    }

    /**
     * Value-shaped redaction of a free-form value (a header). A value that is a single URL or
     * absolute path ({@code scheme://…} or {@code /…}, no whitespace) goes through
     * {@link #redactUrl}, unless it is secret-shaped as a whole (a standard-base64 secret can
     * start with {@code /}). Otherwise it is redacted like {@link #redactMessage}, and a token
     * that is a URL or path is also replaced when one of its {@code / ? & #}-delimited pieces is
     * secret-shaped. Delimiters and everything else are kept; a value with nothing to redact is
     * returned unchanged.
     */
    static String redactValue(String value) {
        if (value == null || value.isEmpty()) return value;
        if (looksLikeUrl(value)) {
            return isSecretShaped(value) ? "[redacted]" : redactUrl(value);
        }
        var matcher = MESSAGE_TOKEN.matcher(value);
        StringBuilder out = null;
        int copied = 0;
        while (matcher.find()) {
            if (matcher.end() - matcher.start() < MIN_SECRET_LENGTH) continue;
            if (!isSecretToken(matcher.group())) continue;
            if (out == null) out = new StringBuilder(value.length());
            out.append(value, copied, matcher.start()).append("[redacted]");
            copied = matcher.end();
        }
        if (out == null) return value;
        return out.append(value, copied, value.length()).toString();
    }

    /**
     * Redact a URL or a path-plus-query (a {@code Referer}, an {@code X-Original-URL}): any
     * {@code userinfo@} in the authority becomes {@link #PLACEHOLDER}, the path goes through
     * {@link #redactPath}, the query and the fragment through {@link #redactQuery}. Scheme, host
     * and port are kept. A value that is neither {@code scheme://…} nor {@code /…} (an
     * {@code Origin: null}, garbage) goes through {@link #redactValue} instead; nothing throws.
     */
    public static String redactUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        int len = url.length();
        var out = new StringBuilder(len);
        int pos;
        int sep = url.indexOf("://");
        if (sep > 0 && isScheme(url, sep)) {
            int authStart = sep + 3;
            int authEnd = indexOfAny(url, authStart, len, "/?#");
            out.append(url, 0, authStart);
            int at = url.lastIndexOf('@', authEnd - 1);
            if (at >= authStart) {
                out.append(PLACEHOLDER).append(url, at, authEnd);
            } else {
                out.append(url, authStart, authEnd);
            }
            pos = authEnd;
        } else if (url.charAt(0) == '/') {
            pos = 0;
        } else {
            return redactValue(url);
        }
        int hash = url.indexOf('#', pos);
        int fragStart = hash < 0 ? len : hash;
        int q = url.indexOf('?', pos);
        int pathEnd = q < 0 || q > fragStart ? fragStart : q;
        out.append(redactPath(url.substring(pos, pathEnd)));
        if (pathEnd < fragStart) {
            out.append('?').append(redactQuery(url.substring(pathEnd + 1, fragStart)));
        }
        if (fragStart < len) {
            out.append('#').append(redactQuery(url.substring(fragStart + 1)));
        }
        return out.toString();
    }

    /** {@code scheme://…} or {@code /…} with no whitespace: what {@link #redactUrl} parses. */
    private static boolean looksLikeUrl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return false;
        }
        if (value.charAt(0) == '/') return true;
        int sep = value.indexOf("://");
        return sep > 0 && isScheme(value, sep);
    }

    private static boolean isScheme(String s, int end) {
        if (!Character.isLetter(s.charAt(0))) return false;
        for (int i = 1; i < end; i++) {
            char c = s.charAt(i);
            if (!(c < 0x80 && (Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.'))) return false;
        }
        return true;
    }

    private static int indexOfAny(String s, int from, int to, String chars) {
        for (int i = from; i < to; i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return to;
    }

    /**
     * Redact high-entropy path segments in a URL path. Each {@code /}-delimited
     * segment is tested with {@link #isSecretShaped(String)}; those that match are
     * replaced with {@code [redacted]}. The leading {@code /} and segment separators
     * are preserved.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code /users/42/profile} → unchanged (numeric id, short slug)</li>
     *   <li>{@code /password-reset/a3f9Bc2d8eF1g4h5} → {@code /password-reset/[redacted]}</li>
     *   <li>{@code /api/eyJhbGci.payload.signature} → {@code /api/[redacted]} (JWT)</li>
     *   <li>{@code /items/550e8400-e29b-41d4-a716-446655440000} → unchanged (UUID)</li>
     * </ul>
     */
    public static String redactPath(String path) {
        if (path == null || path.isEmpty()) return path;
        String[] segments = path.split("/", -1);
        var out = new StringBuilder(path.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) out.append('/');
            String seg = segments[i];
            out.append(isSecretShaped(seg) ? "[redacted]" : seg);
        }
        return out.toString();
    }

    /**
     * Redact high-entropy tokens embedded in an exception message. Each token between
     * common delimiters (whitespace, quotes, punctuation) that looks like a secret
     * (same heuristic as {@link #redactPath}) is replaced with {@code [redacted]} in
     * place; everything else, delimiters included, is kept exactly as it was. A
     * message with nothing to redact is returned unchanged.
     */
    public static String redactMessage(String message) {
        if (message == null || message.isEmpty()) return message;
        // Check for JWT at the whole-message level first (message may be just a token)
        if (JWT_SHAPE.matcher(message).matches()) return PLACEHOLDER;
        var matcher = MESSAGE_TOKEN.matcher(message);
        StringBuilder out = null;
        int copied = 0;
        while (matcher.find()) {
            // Length check first: most tokens are short words and never reach the regexes.
            if (matcher.end() - matcher.start() < MIN_SECRET_LENGTH) continue;
            if (!isSecretShaped(matcher.group())) continue;
            if (out == null) out = new StringBuilder(message.length());
            out.append(message, copied, matcher.start()).append("[redacted]");
            copied = matcher.end();
        }
        if (out == null) return message;
        return out.append(message, copied, message.length()).toString();
    }

    /**
     * Redact the message text in a printed stack trace. {@code Throwable.printStackTrace} embeds
     * the exception message verbatim in the first line, in every {@code Caused by:} and
     * {@code Suppressed:} line, and on the continuation lines of a multi-line message, so
     * redacting {@code getMessage()} alone leaves the same text readable in the trace. Every line
     * except a frame line ({@code \tat ...} or {@code \t... N more}) goes through
     * {@link #redactMessage}; frame lines and line endings are kept exactly as they were. A trace
     * with nothing to redact is returned unchanged.
     */
    public static String redactStackTrace(String stackTrace) {
        if (stackTrace == null || stackTrace.isEmpty()) return stackTrace;
        int len = stackTrace.length();
        StringBuilder out = null;
        int copied = 0;
        int pos = 0;
        while (pos < len) {
            int nl = stackTrace.indexOf('\n', pos);
            int next = nl < 0 ? len : nl + 1;
            int end = nl < 0 ? len : nl;
            if (end > pos && stackTrace.charAt(end - 1) == '\r') end--;
            boolean frame = stackTrace.startsWith("\tat ", pos) || stackTrace.startsWith("\t... ", pos);
            if (!frame && end > pos) {
                String line = stackTrace.substring(pos, end);
                String redacted = redactMessage(line);
                if (!redacted.equals(line)) {
                    if (out == null) out = new StringBuilder(len);
                    out.append(stackTrace, copied, pos).append(redacted);
                    copied = end;
                }
            }
            pos = next;
        }
        if (out == null) return stackTrace;
        return out.append(stackTrace, copied, len).toString();
    }

    /**
     * Return {@code true} if {@code value} looks like a bearer secret or high-entropy
     * token that should be redacted. Applies the heuristic described in the class
     * Javadoc:
     * <ol>
     *   <li>Not null or empty.</li>
     *   <li>Not a standard UUID (kept visible for debugging).</li>
     *   <li>Length ≥ {@value #MIN_SECRET_LENGTH}.</li>
     *   <li>JWT shape (two-dot three-part base64url) → secret.</li>
     *   <li>Two-part shape (one dot, {@value #TWO_PART_MIN}+ base64url characters on each side,
     *       a digit and a letter) → secret; any other dotted value → not a secret.</li>
     *   <li>All characters in base64url-or-hex alphabet.</li>
     *   <li>Contains at least one digit AND at least one letter.</li>
     * </ol>
     */
    static boolean isSecretShaped(String value) {
        if (value == null || value.isEmpty()) return false;
        // Length first (M8): the common case — short segments like "users", "42", "edit" —
        // must not touch the regex engine. Safe before the UUID check: UUIDs are 36 chars,
        // always >= MIN_SECRET_LENGTH, so no UUID is ever rejected by the length test.
        if (value.length() < MIN_SECRET_LENGTH) return false;
        // UUIDs are identifiers, not secrets — leave them for debugging.
        if (UUID_SHAPE.matcher(value).matches()) return false;
        // JWT: header.payload.signature — always a secret regardless of char diversity.
        if (JWT_SHAPE.matcher(value).matches()) return true;
        // Two-part base64url (payload.signature, the ops token's own shape; L13/M6): exactly one
        // dot, at least TWO_PART_MIN characters on each side, and mixed content like the rule
        // below. Hostnames and dotted file names never have two 20+ character labels.
        int dot = value.indexOf('.');
        if (dot >= 0) {
            return dot >= TWO_PART_MIN && value.length() - dot - 1 >= TWO_PART_MIN
                && value.indexOf('.', dot + 1) < 0
                && isBase64Url(value, 0, dot) && isBase64Url(value, dot + 1, value.length())
                && hasDigitAndLetter(value);
        }
        // Must consist entirely of base64url/hex characters.
        if (!SECRET_CHARS.matcher(value).matches()) return false;
        // Must have mixed content: at least one digit and at least one letter.
        return hasDigitAndLetter(value);
    }

    /** Minimum length of each side of a two-part {@code payload.signature} token. */
    static final int TWO_PART_MIN = 20;

    private static boolean isBase64Url(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasDigitAndLetter(String value) {
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') hasDigit = true;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) hasLetter = true;
            if (hasDigit && hasLetter) return true;
        }
        return false;
    }
}
