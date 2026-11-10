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
 * {@code token_count} is redacted) rather than risk leaking a credential. There are two name
 * rules: {@link #isSensitive} for values in transit (query strings, headers), which also treats
 * short words such as {@code code}, {@code sig}, {@code pin} and {@code session} as credentials,
 * and {@link #isSensitiveField} for log fields and route parameters, where those words are
 * ordinary data ({@code code:"card_declined"}, {@code /countries/{code}}).
 *
 * <p><strong>Value-shaped redaction</strong> ({@link #redactPath} and
 * {@link #redactMessage}) targets high-entropy tokens by their shape rather than
 * their field name — covering URL path segments that carry reset/invite/API tokens
 * and exception messages that may contain credentials or SQL literals.
 *
 * <h3>Heuristic (the run rule)</h3>
 * Text is scanned for maximal runs of {@code [A-Za-z0-9_\-+/.~$%]}, wherever they sit (next to a
 * quote, a {@code <}, a trailing full stop). Within a run, each label between {@code . ~ $ %} is
 * redacted when <em>all</em> of these hold:
 * <ol>
 *   <li>Length ≥ {@value #MIN_SECRET_LENGTH} characters (of {@code [A-Za-z0-9_\-+/]}).</li>
 *   <li>Contains at least one ASCII digit <em>and</em> at least one ASCII letter
 *       (rules out purely numeric IDs and purely alphabetic slugs).</li>
 *   <li>It is not a standard UUID, and not an absolute path whose {@code /}-pieces are all short
 *       and none mixes upper case, lower case and digits.</li>
 * </ol>
 * So {@code T.}, {@code <T>}, {@code T~x}, {@code /dl/T.pdf} and {@code SG.x.y} lose T. A run
 * holding {@code %} is also percent-decoded (up to {@value #DECODE_ROUNDS} rounds) and replaced
 * whole when the decoded text holds anything to redact. Kept whole: a JWT or two-part
 * {@code payload.signature} token is replaced whole (the ops token's shape: one dot, at least
 * {@value #TWO_PART_MIN} base64url characters each side); a Java qualified name
 * ({@code com.amazonaws.services.s3.model.AmazonS3Exception}) and a hostname of three or more
 * labels ending in a common TLD are kept. Also kept: short words, numbers, UUIDs, dotted versions,
 * and file names whose labels are short ({@code app.3f9a1c2b.js}). A file name with a 16+
 * character hash label ({@code main-<24 hex>.css}) is redacted: it cannot be told from a token.
 *
 * <p>Free text also gets two structural passes first ({@link #redactText}): every
 * {@code scheme://…} (and {@code </path?query>}, and a {@code /path} after {@code name=}) goes
 * through {@link #redactUrl}, and a {@code name=value} pair with a sensitive name has its value
 * replaced by {@link #PLACEHOLDER}.
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
     * Name segments that make a {@code code} segment a credential ({@code auth_code},
     * {@code verificationCode}, {@code otpcode}, {@code passcode}, {@code code_verifier}). A
     * {@code code} next to anything else ({@code zip_code}, {@code status_code},
     * {@code country_code}, {@code promo_code}, {@code code_challenge}) is ordinary data.
     */
    private static final Set<String> CODE_QUALIFIERS = Set.of(
        "auth", "authorization", "authentication", "oauth", "verification", "verify", "verifier", "reset",
        "otp", "totp", "hotp", "sms", "mfa", "2fa", "tfa", "pin", "pass", "password", "invite", "invitation",
        "access", "login", "signin", "confirm", "confirmation", "activation", "activate", "recovery",
        "backup", "security", "magic", "device", "unlock");

    /**
     * Short words that are credentials in transit (query strings, headers) when they are a whole
     * name segment ({@code ?sig=}, {@code ?pin=}, {@code ?ticket=}, {@code user_session},
     * {@code account_sid}), but ordinary data in a log field or a route parameter. Matched by
     * {@link #isSensitive} only.
     */
    private static final Set<String> TRANSIT_WORDS = Set.of("sig", "otp", "pin", "sid", "ticket", "session");

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

    /** Shape-based replacement (name-based is {@link #PLACEHOLDER}). */
    private static final String SHAPE_PLACEHOLDER = "[redacted]";

    /**
     * Rounds of percent-decoding tried before a value is judged ({@code %252F} is {@code %2F} is
     * {@code /}). Bounded so the work stays linear.
     */
    static final int DECODE_ROUNDS = 3;

    /**
     * URL-inside-value nesting followed before the rest is replaced whole: a query value holding a
     * URL whose query holds a URL... Bounded so recursion depth and work stay small.
     */
    private static final int MAX_DEPTH = 4;

    /**
     * First labels of a Java qualified name ({@code com.amazonaws.services.s3.model.AmazonS3Exception}):
     * such a dotted name is kept whole, so a class name with a digit in a long label survives.
     */
    private static final Set<String> PACKAGE_ROOTS = Set.of(
        "java", "javax", "jakarta", "jdk", "sun", "com", "org", "net", "io", "kotlin", "scala", "groovy",
        "dev", "app", "edu", "gov", "ch", "de", "fr", "nl", "uk", "eu", "me", "co", "ai", "br", "au", "ca");

    /**
     * Last labels that make a dotted name of three or more labels a hostname, kept whole:
     * {@code my-loadbalancer-1234567890.us-west-2.elb.amazonaws.com} and a CloudFront {@code Via}.
     */
    private static final Set<String> HOST_TLDS = Set.of(
        "com", "net", "org", "io", "dev", "app", "co", "ai", "cloud", "internal", "local", "localhost",
        "lan", "home", "corp", "us", "uk", "de", "eu", "ca", "au", "fr", "jp", "nl", "ch", "se", "no", "es",
        "it", "br", "in", "info", "biz", "me", "sh", "xyz", "site", "tech", "online", "edu", "gov", "mil",
        "int", "arpa", "example", "test", "invalid");

    private Redactor() {}

    /**
     * The strict name rule, for values in transit: query parameters, request headers, and
     * {@code name=value} pairs inside header values, URLs and path segments. True when:
     * <ul>
     *   <li>the normalized name (lowercased, {@code -}/{@code _} stripped) contains a
     *       {@link #SENSITIVE} substring ({@code token}, {@code password}, {@code apikey}, …); or</li>
     *   <li>a name segment (split on {@code _ - . [ ]} and other punctuation, and at camelCase
     *       boundaries; a leading {@code X-} ignored) is {@code code} next to an auth-ish segment
     *       ({@link #CODE_QUALIFIERS}), or is an auth-ish word plus {@code code}
     *       ({@code passcode}, {@code authcode}); or</li>
     *   <li>a segment is one of {@link #TRANSIT_WORDS} ({@code sig}, {@code otp}, {@code pin},
     *       {@code sid}, {@code ticket}, {@code session}); or</li>
     *   <li>the whole name is {@code code} (an OAuth authorization code), or its last segment is
     *       {@code auth} ({@code X-Auth}, {@code X-Upstream-Auth}).</li>
     * </ul>
     * {@code zip_code}, {@code status_code}, {@code postcode}, {@code signal}, {@code pinned},
     * {@code sessions}, {@code auth_method} and {@code X-Auth-Request-User} do not match.
     */
    public static boolean isSensitive(String name) {
        return sensitiveName(name, true);
    }

    /**
     * The field name rule, for structured log fields ({@link #redact(Map)}, so every
     * {@code Log} entry) and route parameter names (analytics): the {@link #SENSITIVE} substrings
     * and the auth-paired {@code code} rule of {@link #isSensitive}, plus a field named exactly
     * {@code otp}, but not the short words that are ordinary data there: a field
     * {@code code:"card_declined"}, a route {@code /countries/{code}}, {@code sig}, {@code pin},
     * {@code sid}, {@code ticket}, {@code session}, {@code X-Auth}.
     */
    public static boolean isSensitiveField(String name) {
        return sensitiveName(name, false);
    }

    private static boolean sensitiveName(String name, boolean strict) {
        if (name == null || name.isEmpty()) return false;
        String norm = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (String token : SENSITIVE) {
            if (norm.contains(token)) return true;
        }
        List<String> segs = nameSegments(name);
        if (segs.isEmpty()) return false;
        int first = segs.size() > 1 && segs.get(0).equals("x") ? 1 : 0;
        boolean code = false;
        boolean qualifier = false;
        for (int i = first; i < segs.size(); i++) {
            String seg = segs.get(i);
            if (seg.equals("code")) {
                code = true;
            } else if (seg.length() > 4 && seg.endsWith("code")
                    && CODE_QUALIFIERS.contains(seg.substring(0, seg.length() - 4))) {
                return true; // passcode, authcode, otpcode
            }
            if (CODE_QUALIFIERS.contains(seg)) qualifier = true;
            if (strict && TRANSIT_WORDS.contains(seg)) return true;
        }
        if (code && qualifier) return true;
        boolean single = segs.size() - first == 1;
        if (!strict) return single && segs.get(first).equals("otp");
        return (single && segs.get(first).equals("code")) || segs.get(segs.size() - 1).equals("auth");
    }

    /**
     * Lowercased segments of a name: split on anything but ASCII letters and digits, and at
     * camelCase boundaries ({@code verificationCode}, {@code ABCCode} is {@code abc}, {@code code}).
     */
    static List<String> nameSegments(String name) {
        var out = new ArrayList<String>(4);
        int n = name.length();
        int start = -1;
        for (int i = 0; i <= n; i++) {
            char c = i < n ? name.charAt(i) : ' ';
            boolean alnum = isAsciiLetter(c) || isAsciiDigit(c);
            boolean boundary = false;
            if (alnum && start >= 0 && c >= 'A' && c <= 'Z') {
                char prev = name.charAt(i - 1);
                boolean nextLower = i + 1 < n && name.charAt(i + 1) >= 'a' && name.charAt(i + 1) <= 'z';
                boundary = (prev >= 'a' && prev <= 'z') || isAsciiDigit(prev)
                    || (prev >= 'A' && prev <= 'Z' && nextLower);
            }
            if (start >= 0 && (!alnum || boundary)) {
                out.add(name.substring(start, i).toLowerCase(Locale.ROOT));
                start = -1;
            }
            if (alnum && start < 0) start = i;
        }
        return out;
    }

    /**
     * Return a deep copy of {@code fields} (a structured log entry) with the values of keys that
     * are sensitive by the field rule ({@link #isSensitiveField}) replaced by {@link #PLACEHOLDER}.
     * Recurses into nested maps and lists.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redact(Map<String, Object> fields) {
        var out = new LinkedHashMap<String, Object>(fields.size());
        for (var e : fields.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (isSensitiveField(key)) {
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
        return redactQuery(query, DECODE_ROUNDS, 0);
    }

    private static String redactQuery(String query, int rounds, int depth) {
        if (query == null || query.isEmpty()) return query;
        if (depth > MAX_DEPTH) return SHAPE_PLACEHOLDER;
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
                out.append(isSecretValue(part, rounds, depth) ? PLACEHOLDER : part);
            } else if (isSensitive(lenientDecode(query.substring(start, eq)))
                    || isSecretValue(query.substring(eq + 1, end), rounds, depth)) {
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

    /**
     * A raw query value that the free-form pass ({@link #redactText}) would change, as sent or
     * after each of up to {@code rounds} rounds of percent-decoding: a secret-shaped run, a URL
     * with something to redact ({@code next=https%3A…%3Ftoken%3D…}), or a sensitive
     * {@code name=value} pair inside it.
     */
    private static boolean isSecretValue(String raw, int rounds, int depth) {
        String r = raw;
        for (int round = 0; ; round++) {
            if (!redactText(r, true, 0, depth).equals(r)) return true;
            if (round >= rounds) return false;
            String d = lenientDecode(r);
            if (d.equals(r)) return false;
            r = d;
        }
    }

    // ---- The free-form pass: embedded URLs, name=value pairs, secret-shaped runs ----

    /**
     * Redact free text (an exception message, a header value, a path segment, a query value):
     * <ol>
     *   <li>every {@code scheme://…} (any scheme, {@code jdbc:postgresql://} included) and every
     *       {@code </path?query>} goes through {@link #redactUrl};</li>
     *   <li>in a {@code name=value} pair whose name is sensitive, the value (up to the next
     *       {@code & ; ,}, whitespace or bracket, or the closing quote) becomes
     *       {@link #PLACEHOLDER}; a value that is a {@code /path} goes through {@link #redactUrl};</li>
     *   <li>every other maximal run of {@code [A-Za-z0-9_\-+/.~$%]} is tested by
     *       {@link #redactChunk}: decoded, then label by label.</li>
     * </ol>
     * {@code strict} picks the name rule ({@link #isSensitive} for values in transit,
     * {@link #isSensitiveField} for messages). {@code rounds} is the percent-decoding budget for
     * runs; {@code depth} bounds URL-inside-URL recursion. Linear in the input for fixed bounds.
     * Returns {@code s} itself when there is nothing to redact.
     */
    static String redactText(String s, boolean strict, int rounds, int depth) {
        if (s == null || s.isEmpty()) return s;
        int n = s.length();
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            String replacement = null;
            int from = i;
            int to = i + 1;
            if (isAsciiLetter(c) && (i == 0 || !isSchemeChar(s.charAt(i - 1)))) {
                int end = urlEnd(s, i);
                if (end > i) {
                    to = end;
                    replacement = urlReplacement(s.substring(i, end), rounds, depth);
                } else {
                    to = chunkEnd(s, i);
                    replacement = redactChunk(s, i, to, strict, rounds, depth);
                }
            } else if (c == '<' && i + 1 < n && s.charAt(i + 1) == '/') {
                // <path?query> (a Link header): the path up to '>'.
                from = i + 1;
                to = valueEnd(s, from, "\"'<>");
                replacement = urlReplacement(s.substring(from, to), rounds, depth);
            } else if (c == '=') {
                int vs = i + 1;
                boolean quoted = vs < n && s.charAt(vs) == '"';
                int inner = quoted ? vs + 1 : vs;
                if (sensitiveName(nameBefore(s, i), strict)) {
                    from = inner;
                    to = quoted ? closingQuote(s, inner) : valueEnd(s, inner, "&;,\"'<>()[]{}|\\");
                    if (to > from) replacement = PLACEHOLDER;
                } else if (inner < n && s.charAt(inner) == '/') {
                    from = inner;
                    to = quoted ? closingQuote(s, inner) : valueEnd(s, inner, ";,\"'<>");
                    replacement = urlReplacement(s.substring(from, to), rounds, depth);
                }
                if (replacement == null) {
                    to = i + 1; // just the '='; the value is scanned as ordinary text
                    from = i;
                }
            } else if (isChunkChar(c)) {
                to = chunkEnd(s, i);
                replacement = redactChunk(s, i, to, strict, rounds, depth);
            }
            if (replacement != null && !replacement.contentEquals(s.subSequence(from, to))) {
                if (out == null) out = new StringBuilder(n);
                out.append(s, copied, from).append(replacement);
                copied = to;
            }
            i = Math.max(to, i + 1);
        }
        if (out == null) return s;
        return out.append(s, copied, n).toString();
    }

    /**
     * A URL or path found inside free text, where the context ({@code scheme://}, {@code <…>},
     * {@code uri=/…}) says it is one: {@link #redactUrl}, segment by segment.
     */
    private static String urlReplacement(String url, int rounds, int depth) {
        if (url.isEmpty()) return url;
        if (depth >= MAX_DEPTH) return SHAPE_PLACEHOLDER;
        return redactUrl(url, rounds, depth + 1);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Characters of a URL scheme, plus ':' so a double scheme ({@code jdbc:postgresql}) is one. */
    private static boolean isSchemeChar(char c) {
        return isAsciiLetter(c) || isAsciiDigit(c) || c == '+' || c == '-' || c == '.' || c == ':';
    }

    /**
     * If a {@code scheme://} starts at {@code start}, the end of that URL in {@code s}: up to
     * whitespace, a quote, {@code < > \ ^ { } |} or a control character, then without trailing
     * sentence punctuation ({@code . , ; : ! ? ) ]}). Otherwise -1. Scans at most the scheme run
     * plus the URL.
     */
    private static int urlEnd(String s, int start) {
        int n = s.length();
        int j = start;
        while (j < n && isSchemeChar(s.charAt(j))) {
            if (s.charAt(j) == ':' && s.startsWith("//", j + 1)) {
                if (j == start) return -1;
                int end = j + 3;
                while (end < n && !endsUrl(s.charAt(end))) end++;
                while (end > j + 3 && ".,;:!?)]".indexOf(s.charAt(end - 1)) >= 0) end--;
                return end;
            }
            j++;
        }
        return -1;
    }

    private static boolean endsUrl(char c) {
        return c <= ' ' || c == 0x7f || "\"'`<>\\^{}|".indexOf(c) >= 0;
    }

    /** End of a value starting at {@code from}: the first whitespace, control or {@code stops} character. */
    private static int valueEnd(String s, int from, String stops) {
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c <= ' ' || c == 0x7f || stops.indexOf(c) >= 0) break;
            i++;
        }
        return i;
    }

    /** Index of the {@code "} closing a quoted value whose content starts at {@code from}, or the end. */
    private static int closingQuote(String s, int from) {
        int q = s.indexOf('"', from);
        return q < 0 ? s.length() : q;
    }

    /** The parameter name just before the {@code =} at {@code eq}: {@code [A-Za-z0-9_\-.\[\]%]}, percent-decoded. */
    private static String nameBefore(String s, int eq) {
        int i = eq;
        while (i > 0) {
            char c = s.charAt(i - 1);
            if (!(isAsciiLetter(c) || isAsciiDigit(c) || c == '_' || c == '-' || c == '.' || c == '['
                    || c == ']' || c == '%')) {
                break;
            }
            i--;
        }
        return i == eq ? "" : lenientDecode(s.substring(i, eq));
    }

    /**
     * A character of a candidate run: the secret alphabet {@code [A-Za-z0-9_\-+/]} plus the label
     * separators {@code .}, {@code ~} and {@code $}, and {@code %} (the run is decoded). Not
     * {@code =}: it separates a name from its value, and base64 padding is not secret.
     */
    private static boolean isChunkChar(char c) {
        return isAsciiLetter(c) || isAsciiDigit(c) || c == '_' || c == '-' || c == '+' || c == '/'
            || c == '.' || c == '~' || c == '$' || c == '%';
    }

    private static int chunkEnd(String s, int from) {
        int i = from;
        while (i < s.length() && isChunkChar(s.charAt(i))) i++;
        return i;
    }

    /**
     * The replacement for the run {@code s[from, to)}, or null to keep it. A run shorter than
     * {@value #MIN_SECRET_LENGTH} is kept. Otherwise:
     * <ol>
     *   <li>if it holds {@code %} and {@code rounds > 0}, each percent-decoding round is checked
     *       with {@link #redactText}; anything to redact replaces the whole run;</li>
     *   <li>a JWT or two-part token (outer dots aside) is replaced whole;</li>
     *   <li>a Java qualified name ({@code com.example.Foo2Bar}) or a hostname of three or more
     *       labels ending in a common TLD is kept;</li>
     *   <li>each label between {@code . ~ $ %} that {@link #isSecretLabel} is replaced, the
     *       separators kept ({@code /dl/[redacted].pdf}).</li>
     * </ol>
     */
    private static String redactChunk(String s, int from, int to, boolean strict, int rounds, int depth) {
        if (to - from < MIN_SECRET_LENGTH) return null;
        String chunk = s.substring(from, to);
        if (rounds > 0 && chunk.indexOf('%') >= 0) {
            if (depth >= MAX_DEPTH) return SHAPE_PLACEHOLDER;
            String r = chunk;
            for (int round = 0; round < rounds; round++) {
                String d = percentDecode(r);
                if (d.equals(r)) break;
                if (!redactText(d, strict, 0, depth + 1).equals(d)) return SHAPE_PLACEHOLDER;
                r = d;
            }
        }
        int a = 0;
        int b = chunk.length();
        while (a < b && chunk.charAt(a) == '.') a++;
        while (b > a && chunk.charAt(b - 1) == '.') b--;
        String core = chunk.substring(a, b);
        if (isJwt(core) || isTwoPart(core)) {
            return chunk.substring(0, a) + SHAPE_PLACEHOLDER + chunk.substring(b);
        }
        if (isQualifiedName(core) || isHostname(core)) return null;
        StringBuilder out = null;
        int copied = 0;
        int start = 0;
        int len = chunk.length();
        for (int k = 0; k <= len; k++) {
            char c = k < len ? chunk.charAt(k) : '.';
            if (c == '.' || c == '~' || c == '$' || c == '%') {
                if (k - start >= MIN_SECRET_LENGTH && isSecretLabel(chunk, start, k)) {
                    if (out == null) out = new StringBuilder(len);
                    out.append(chunk, copied, start).append(SHAPE_PLACEHOLDER);
                    copied = k;
                }
                start = k + 1;
            }
        }
        if (out == null) return null;
        return out.append(chunk, copied, len).toString();
    }

    /**
     * A label ({@code [A-Za-z0-9_\-+/]}, no dots) that is secret-shaped: at least
     * {@value #MIN_SECRET_LENGTH} characters with a digit and a letter, not a UUID, and not
     * {@linkplain #isPathLike path-like}.
     */
    private static boolean isSecretLabel(String s, int from, int to) {
        if (to - from < MIN_SECRET_LENGTH) return false;
        if (to - from == 36 && UUID_SHAPE.matcher(s.substring(from, to)).matches()) return false;
        if (!hasDigitAndLetter(s, from, to)) return false;
        return !isPathLike(s, from, to);
    }

    /**
     * An absolute file or URL path rather than a standard-base64 secret: it holds {@code /}, no
     * {@code +}, and every {@code /}-piece is shorter than {@value #MIN_SECRET_LENGTH} and does not
     * mix upper case, lower case and digits ({@code /var/app/releases/20241105} is a path;
     * {@code aB3dE/fG7hI9jK1/mN2oP4qR} is not).
     */
    private static boolean isPathLike(String s, int from, int to) {
        if (indexOfAny(s, from, to, "/") == to) return false;
        int start = from;
        for (int i = from; i <= to; i++) {
            char c = i < to ? s.charAt(i) : '/';
            if (c == '+') return false;
            if (c == '/') {
                if (i - start >= MIN_SECRET_LENGTH || mixesCaseAndDigits(s, start, i)) return false;
                start = i + 1;
            }
        }
        return true;
    }

    private static boolean mixesCaseAndDigits(String s, int from, int to) {
        boolean upper = false, lower = false, digit = false;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') upper = true;
            else if (c >= 'a' && c <= 'z') lower = true;
            else if (c >= '0' && c <= '9') digit = true;
        }
        return upper && lower && digit;
    }

    private static boolean isJwt(String v) {
        int dots = 0;
        for (int i = 0; i < v.length() && dots < 3; i++) if (v.charAt(i) == '.') dots++;
        return dots == 2 && JWT_SHAPE.matcher(v).matches();
    }

    /** {@code payload.signature}: one dot, {@value #TWO_PART_MIN}+ base64url characters each side, a digit and a letter. */
    private static boolean isTwoPart(String v) {
        int dot = v.indexOf('.');
        return dot >= TWO_PART_MIN && v.length() - dot - 1 >= TWO_PART_MIN
            && v.indexOf('.', dot + 1) < 0
            && isBase64Url(v, 0, dot) && isBase64Url(v, dot + 1, v.length())
            && hasDigitAndLetter(v, 0, v.length());
    }

    /** {@code root.label.Label} with three or more Java-identifier labels and a known first label. */
    private static boolean isQualifiedName(String v) {
        int firstDot = v.indexOf('.');
        if (firstDot < 0 || !PACKAGE_ROOTS.contains(v.substring(0, firstDot))) return false;
        int labels = 0;
        int start = 0;
        for (int i = 0; i <= v.length(); i++) {
            char c = i < v.length() ? v.charAt(i) : '.';
            if (c == '.' || c == '$') {
                if (i == start || isAsciiDigit(v.charAt(start))) return false;
                labels++;
                start = i + 1;
            } else if (!(isAsciiLetter(c) || isAsciiDigit(c) || c == '_')) {
                return false;
            }
        }
        return labels >= 3;
    }

    /** A DNS name of three or more labels ({@code [A-Za-z0-9-]}, 1 to 63) whose last label is in {@link #HOST_TLDS}. */
    private static boolean isHostname(String v) {
        int lastDot = v.lastIndexOf('.');
        if (lastDot < 0 || !HOST_TLDS.contains(v.substring(lastDot + 1).toLowerCase(Locale.ROOT))) return false;
        int labels = 0;
        int start = 0;
        for (int i = 0; i <= v.length(); i++) {
            char c = i < v.length() ? v.charAt(i) : '.';
            if (c == '.') {
                int len = i - start;
                if (len == 0 || len > 63 || v.charAt(start) == '-' || v.charAt(i - 1) == '-') return false;
                labels++;
                start = i + 1;
            } else if (!(isAsciiLetter(c) || isAsciiDigit(c) || c == '-')) {
                return false;
            }
        }
        return labels >= 3;
    }

    /**
     * Percent-decode {@code s} keeping {@code +} (a base64 character in paths and free text) and
     * any malformed escape literally. Linear; returns {@code s} itself when there is no {@code %}.
     */
    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        return lenientDecode(s.replace("+", "%2B"));
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
     * purpose: {@code Sec-WebSocket-Protocol}, which clients use to smuggle bearer tokens, and
     * {@code Forwarded} and {@code Via}, which a proxy fills from the request ({@code uri="/reset?token=…"}):
     * they get the free-form pass, which keeps {@code for=}, {@code by=}, {@code proto=} and
     * {@code host=} readable. Of the {@code X-Forwarded-*} family only the four listed here are
     * kept; {@code X-Forwarded-Uri} is a URL header and the rest get the free-form pass.
     */
    private static final Set<String> VERBATIM_HEADERS = Set.of(
        "accept", "accept-charset", "accept-encoding", "accept-language", "cache-control",
        "connection", "content-encoding", "content-language", "content-length", "content-type",
        "dnt", "expect", "host", "http2-settings", "if-match", "if-modified-since", "if-none-match",
        "if-range", "if-unmodified-since", "keep-alive", "pragma", "priority", "range", "te",
        "upgrade", "upgrade-insecure-requests", "user-agent", "x-requested-with",
        // client address and proxy metadata
        "x-forwarded-for", "x-forwarded-host", "x-forwarded-port", "x-forwarded-proto",
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
     * start with {@code /}). Otherwise it gets the free-form pass with the strict name rule
     * ({@link #redactText}): embedded URLs ({@code Link: <…>}, {@code Refresh: 0; url=…},
     * {@code Forwarded: uri="…"}), sensitive {@code name=value} pairs, and secret-shaped runs.
     * Delimiters and everything else are kept; a value with nothing to redact is returned
     * unchanged.
     */
    static String redactValue(String value) {
        return redactValue(value, DECODE_ROUNDS, 0);
    }

    private static String redactValue(String value, int rounds, int depth) {
        if (value == null || value.isEmpty()) return value;
        if (looksLikeUrl(value)) {
            return isSecretShaped(value) ? SHAPE_PLACEHOLDER : redactUrl(value, rounds, depth);
        }
        return redactText(value, true, rounds, depth);
    }

    /**
     * Redact a URL or a path-plus-query (a {@code Referer}, an {@code X-Original-URL}, a URL
     * inside an exception message): any {@code userinfo@} in the authority becomes
     * {@link #PLACEHOLDER} whatever its shape ({@code postgres://user:pw@host}), the path goes
     * through {@link #redactPath}, the query and the fragment through {@link #redactQuery}.
     * Scheme (a double scheme such as {@code jdbc:postgresql} included), host and port are kept.
     * A value that is neither {@code scheme://…} nor {@code /…} (an {@code Origin: null},
     * garbage) goes through {@link #redactValue} instead; nothing throws.
     */
    public static String redactUrl(String url) {
        return redactUrl(url, DECODE_ROUNDS, 0);
    }

    private static String redactUrl(String url, int rounds, int depth) {
        if (url == null || url.isEmpty()) return url;
        if (depth > MAX_DEPTH) return SHAPE_PLACEHOLDER;
        int len = url.length();
        var out = new StringBuilder(len);
        int pos;
        int sep = url.indexOf("://");
        if (sep > 0 && isScheme(url, sep)) {
            int authStart = sep + 3;
            int authEnd = indexOfAny(url, authStart, len, "/?#");
            out.append(url, 0, authStart);
            int at = -1;
            for (int i = authStart; i < authEnd; i++) {
                if (url.charAt(i) == '@') at = i;
            }
            if (at >= authStart) {
                out.append(PLACEHOLDER).append(url, at, authEnd);
            } else {
                out.append(url, authStart, authEnd);
            }
            pos = authEnd;
        } else if (url.charAt(0) == '/') {
            pos = 0;
        } else {
            return redactValue(url, rounds, depth);
        }
        int hash = url.indexOf('#', pos);
        int fragStart = hash < 0 ? len : hash;
        int q = indexOfAny(url, pos, fragStart, "?");
        out.append(redactPath(url.substring(pos, q), rounds, depth));
        if (q < fragStart) {
            out.append('?').append(redactQuery(url.substring(q + 1, fragStart), rounds, depth));
        }
        if (fragStart < len) {
            out.append('#').append(redactQuery(url.substring(fragStart + 1), rounds, depth));
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

    /** {@code s[0, end)} is a URL scheme; {@code :} is allowed inside for a double scheme ({@code jdbc:postgresql}). */
    private static boolean isScheme(String s, int end) {
        if (!isAsciiLetter(s.charAt(0))) return false;
        for (int i = 1; i < end; i++) {
            if (!isSchemeChar(s.charAt(i))) return false;
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
     * Redact secrets in a URL path. Each {@code /}-delimited segment gets the free-form pass with
     * the strict name rule ({@link #redactText}): a secret-shaped run becomes {@code [redacted]}
     * (a dotted segment label by label, so {@code T.pdf} is {@code [redacted].pdf}); a segment
     * holding {@code %} is also tested after up to {@value #DECODE_ROUNDS} rounds of
     * percent-decoding and replaced whole if the decoded text holds a secret; and a
     * {@code ;name=value} matrix parameter is redacted by name ({@code ;jsessionid=},
     * {@code ;token=} become {@code [REDACTED]}) or by shape. The leading {@code /} and segment
     * separators are preserved. Paths whose segments are all short and hold no {@code =} are
     * returned at once (this runs on every request's log line).
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code /users/42/profile} → unchanged (numeric id, short slug)</li>
     *   <li>{@code /password-reset/a3f9Bc2d8eF1g4h5} → {@code /password-reset/[redacted]}</li>
     *   <li>{@code /api/eyJhbGci.payload.signature} → {@code /api/[redacted]} (JWT)</li>
     *   <li>{@code /reset/Xx1a…%2BgH5…%3D%3D} → {@code /reset/[redacted]} (encoded)</li>
     *   <li>{@code /reset;token=abc} → {@code /reset;token=[REDACTED]} (matrix)</li>
     *   <li>{@code /items/550e8400-e29b-41d4-a716-446655440000} → unchanged (UUID)</li>
     * </ul>
     */
    public static String redactPath(String path) {
        return redactPath(path, DECODE_ROUNDS, 0);
    }

    private static String redactPath(String path, int rounds, int depth) {
        if (path == null || path.isEmpty()) return path;
        if (!mayHoldPathSecret(path)) return path;
        if (depth > MAX_DEPTH) return SHAPE_PLACEHOLDER;
        StringBuilder out = null;
        int len = path.length();
        int start = 0;
        for (int i = 0; i <= len; i++) {
            if (i < len && path.charAt(i) != '/') continue;
            if (i > start) {
                String seg = path.substring(start, i);
                String redacted = redactText(seg, true, rounds, depth);
                if (out != null || !redacted.equals(seg)) {
                    if (out == null) out = new StringBuilder(len).append(path, 0, start);
                    out.append(redacted);
                }
            }
            if (out != null && i < len) out.append('/');
            start = i + 1;
        }
        return out == null ? path : out.toString();
    }

    /** False when every segment is shorter than {@value #MIN_SECRET_LENGTH} and there is no {@code =}. */
    private static boolean mayHoldPathSecret(String path) {
        int segStart = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '=') return true;
            if (c == '/') segStart = i + 1;
            else if (i - segStart + 1 >= MIN_SECRET_LENGTH) return true;
        }
        return false;
    }

    /**
     * Redact secrets embedded in an exception message, in place; everything else, delimiters
     * included, is kept exactly as it was. Every {@code scheme://…} in it goes through
     * {@link #redactUrl} (userinfo, path, query, fragment); a {@code name=value} pair whose name
     * is sensitive by {@link #isSensitiveField} has its value replaced by {@link #PLACEHOLDER};
     * every other secret-shaped run becomes {@code [redacted]} (see {@link #redactText}). A
     * message with nothing to redact is returned unchanged.
     */
    public static String redactMessage(String message) {
        if (message == null || message.isEmpty()) return message;
        // Check for JWT at the whole-message level first (message may be just a token)
        if (isJwt(message)) return PLACEHOLDER;
        return redactText(message, false, DECODE_ROUNDS, 0);
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
     *   <li>Not a path: a value with {@code /} whose pieces are all short and none mixes upper
     *       case, lower case and digits ({@code /var/app/releases/20241105}) is not a secret.</li>
     * </ol>
     * This is the test for a value taken <em>as a whole</em>. Text is redacted run by run and
     * label by label ({@link #redactText}), so {@code T.pdf} is caught there although it is not
     * secret-shaped as a whole.
     */
    static boolean isSecretShaped(String value) {
        if (value == null || value.isEmpty()) return false;
        // Length first (M8): the common case — short segments like "users", "42", "edit" —
        // must not touch the regex engine. Safe before the UUID check: UUIDs are 36 chars,
        // always >= MIN_SECRET_LENGTH, so no UUID is ever rejected by the length test.
        if (value.length() < MIN_SECRET_LENGTH) return false;
        // UUIDs are identifiers, not secrets — leave them for debugging.
        if (value.length() == 36 && UUID_SHAPE.matcher(value).matches()) return false;
        // JWT: header.payload.signature — always a secret regardless of char diversity.
        if (isJwt(value)) return true;
        // Two-part base64url (payload.signature, the ops token's own shape; L13/M6): exactly one
        // dot, at least TWO_PART_MIN characters on each side, and mixed content like the rule
        // below. Hostnames and dotted file names never have two 20+ character labels.
        if (value.indexOf('.') >= 0) return isTwoPart(value);
        // Must consist entirely of base64url/hex characters.
        if (!SECRET_CHARS.matcher(value).matches()) return false;
        // Must have mixed content: at least one digit and at least one letter.
        return hasDigitAndLetter(value, 0, value.length()) && !isPathLike(value, 0, value.length());
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

    private static boolean hasDigitAndLetter(String value, int from, int to) {
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = from; i < to; i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') hasDigit = true;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) hasLetter = true;
            if (hasDigit && hasLetter) return true;
        }
        return false;
    }
}
