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
 *   <li>It is not a standard UUID, and not a {@linkplain #isNameLike name made of words}
 *       ({@code V3__create_users_table}, {@code invoice-48213-final-v2}).</li>
 * </ol>
 * A label holding {@code /} is a file path when every piece looks like part of one (no short
 * piece mixes upper case, lower case and digits, a long piece without digits is cased like words,
 * no {@code +}); a path whose pieces are all plain is kept. A path with a secret-shaped piece
 * loses that piece alone when the run starts like a path ({@code /}, {@code ./}, {@code ../},
 * {@code ~/}) or ends in a file extension, and is replaced whole otherwise. Any other label with
 * {@code /} is a base64 candidate and is judged whole.
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

    /**
     * What a redaction returns when it failed internally. Every public method here fails closed:
     * it never throws, and on an internal error it returns this marker (the map forms: this marker
     * for each value concerned) instead of the text it was given. The callers sit on error paths
     * (the 500 handler, the uncaught-exception handler, the job poller), where a throw would lose
     * the error being recorded and unredacted text must not be the fallback.
     */
    public static final String FAILED = "[redaction failed]";

    /**
     * Test seam, never set outside tests: run at the start of the two cores every entry point
     * reaches ({@link #redactText} and the name rule), so a test can make redaction fail inside
     * and check what each entry point returns then.
     */
    static Runnable faultForTest;

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
    private static final Set<String> TRANSIT_WORDS = Set.of("sig", "otp", "totp", "pin", "sid", "ticket", "session");

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
     *       boundaries; a leading {@code X-} ignored) is {@code code} or {@code codes} next to an
     *       auth-ish segment ({@link #CODE_QUALIFIERS}: {@code backup_codes}), or is an auth-ish
     *       word plus {@code code} or {@code codes} ({@code passcode}, {@code authcode}); or</li>
     *   <li>a segment is one of {@link #TRANSIT_WORDS} ({@code sig}, {@code otp}, {@code totp},
     *       {@code pin}, {@code sid}, {@code ticket}, {@code session}); or</li>
     *   <li>the whole name is {@code code} (an OAuth authorization code), or its last segment is
     *       {@code auth} ({@code X-Auth}, {@code X-Upstream-Auth}).</li>
     * </ul>
     * {@code zip_code}, {@code status_code}, {@code postcode}, {@code signal}, {@code pinned},
     * {@code sessions}, {@code auth_method} and {@code X-Auth-Request-User} do not match.
     */
    public static boolean isSensitive(String name) {
        try {
            return sensitiveName(name, true);
        } catch (RuntimeException | StackOverflowError e) {
            return true; // fail closed: a name that cannot be judged is treated as sensitive
        }
    }

    /**
     * The field name rule, for structured log fields ({@link #redact(Map)}, so every
     * {@code Log} entry) and route parameter names (analytics): the {@link #SENSITIVE} substrings
     * and the auth-paired {@code code}/{@code codes} rule of {@link #isSensitive}, plus a field
     * named exactly {@code otp} or {@code totp}, but not the short words that are ordinary data there: a field
     * {@code code:"card_declined"}, a route {@code /countries/{code}}, {@code sig}, {@code pin},
     * {@code sid}, {@code ticket}, {@code session}, {@code X-Auth}.
     */
    public static boolean isSensitiveField(String name) {
        try {
            return sensitiveName(name, false);
        } catch (RuntimeException | StackOverflowError e) {
            return true; // fail closed
        }
    }

    static boolean sensitiveName(String name, boolean strict) {
        if (faultForTest != null) faultForTest.run();
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
            // The plural is paired like the singular: backup_codes, recovery_codes, passcodes.
            int stem = seg.endsWith("codes") ? seg.length() - 5 : seg.endsWith("code") ? seg.length() - 4 : -1;
            if (stem == 0) {
                code = true;
            } else if (stem > 0 && CODE_QUALIFIERS.contains(seg.substring(0, stem))) {
                return true; // passcode, authcode, otpcode
            }
            if (CODE_QUALIFIERS.contains(seg)) qualifier = true;
            if (strict && TRANSIT_WORDS.contains(seg)) return true;
        }
        if (code && qualifier) return true;
        boolean single = segs.size() - first == 1;
        if (!strict) return single && (segs.get(first).equals("otp") || segs.get(first).equals("totp"));
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

    /** Nesting levels of maps and lists {@link #redact(Map)} copies; deeper values become {@link #TOO_DEEP}. */
    static final int MAX_NESTING = 64;

    /**
     * Stands in for a map or list nested deeper than {@link #MAX_NESTING} (a map that contains
     * itself ends here too) or past the {@link #MAX_ELEMENTS} copy budget.
     */
    static final String TOO_DEEP = "[nested too deep]";

    /**
     * Return a deep copy of {@code fields} (a structured log entry) with the values of keys that
     * are sensitive by the field rule ({@link #isSensitiveField}) replaced by {@link #PLACEHOLDER}.
     * Recurses into nested maps and lists. Never throws: {@code null} gives an empty map, and a
     * map that cannot be copied (its iteration throws, a key is not a String) gives its keys with
     * {@link #FAILED} for every value.
     */
    public static Map<String, Object> redact(Map<String, Object> fields) {
        if (fields == null) return new LinkedHashMap<>();
        try {
            return redactMap(fields, 0, new int[1]);
        } catch (RuntimeException | StackOverflowError e) {
            return failedCopy(fields);
        }
    }

    /**
     * The fail-closed result of a map form: every key of {@code map} with {@link #FAILED} as its
     * value, or the single entry {@code redaction=[redaction failed]} when the keys cannot be read
     * either.
     */
    private static Map<String, Object> failedCopy(Map<?, ?> map) {
        var out = new LinkedHashMap<String, Object>();
        try {
            for (Object key : map.keySet()) out.put(String.valueOf(key), FAILED);
        } catch (RuntimeException | StackOverflowError e) {
            out.clear();
            out.put("redaction", FAILED);
        }
        return out;
    }

    /**
     * {@code budget[0]} counts the values copied so far: past {@link #MAX_ELEMENTS}, containers
     * become {@link #TOO_DEEP}, so a shared child referenced from many keys (or a cycle) cannot
     * blow up the copy. Nesting past {@link #MAX_NESTING} is cut the same way.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> redactMap(Map<String, Object> fields, int depth, int[] budget) {
        var out = new LinkedHashMap<String, Object>(fields.size());
        for (var e : fields.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            budget[0]++;
            if (sensitiveName(key, false)) {
                out.put(key, PLACEHOLDER);
            } else if (value instanceof Map<?, ?> || value instanceof List<?>) {
                out.put(key, redactNested(value, depth + 1, budget));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object redactNested(Object value, int depth, int[] budget) {
        if (depth > MAX_NESTING || budget[0] > MAX_ELEMENTS) return TOO_DEEP;
        if (value instanceof Map<?, ?> m) return redactMap((Map<String, Object>) m, depth, budget);
        var list = (List<?>) value;
        var out = new ArrayList<Object>(list.size());
        for (Object item : list) {
            budget[0]++;
            out.add(item instanceof Map<?, ?> || item instanceof List<?> ? redactNested(item, depth + 1, budget) : item);
        }
        return out;
    }

    /** Values {@link #redact(Map)} copies before further nested containers become {@link #TOO_DEEP}. */
    static final int MAX_ELEMENTS = 100_000;

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
     * literally, never thrown. A leading {@code ?} is not expected (pass the raw query). On an
     * internal error the whole result is {@link #FAILED}.
     */
    public static String redactQuery(String query) {
        try {
            return redactQuery(query, DECODE_ROUNDS, 0);
        } catch (RuntimeException | StackOverflowError e) {
            return FAILED;
        }
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
            } else if (sensitiveName(lenientDecode(query.substring(start, eq)), true)
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
     *   <li>in a {@code name=value} pair whose name is sensitive, the value becomes
     *       {@link #PLACEHOLDER}: up to the next {@code & ; ,}, whitespace or bracket; or, when it
     *       opens with {@code " ' ( [ { <}, up to the matching closer ({@code password={a;b}},
     *       {@code codes=[a, b]}), the end of the line if there is none. A value that is a
     *       {@code /path} goes through {@link #redactUrl};</li>
     *   <li>every other maximal run of {@code [A-Za-z0-9_\-+/.~$%]} is tested by
     *       {@link #redactChunk}: decoded, then label by label.</li>
     * </ol>
     * {@code strict} picks the name rule ({@link #isSensitive} for values in transit,
     * {@link #isSensitiveField} for messages). {@code rounds} is the percent-decoding budget for
     * runs; {@code depth} bounds URL-inside-URL recursion. Linear in the input for fixed bounds.
     * Returns {@code s} itself when there is nothing to redact.
     */
    static String redactText(String s, boolean strict, int rounds, int depth) {
        if (faultForTest != null) faultForTest.run();
        if (s == null || s.isEmpty()) return s;
        int n = s.length();
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        int urlAt = -1; // a known scheme found at the end of the previous run ("/proxy/https://")
        while (i < n) {
            char c = s.charAt(i);
            String replacement = null;
            boolean url = false;
            int from = i;
            int to = i + 1;
            if (isAsciiLetter(c) && (i == 0 || !isSchemeChar(s.charAt(i - 1)) || i == urlAt)) {
                int end = urlEnd(s, i);
                if (end > i) {
                    to = end;
                    url = true;
                    replacement = urlReplacement(s.substring(i, end), rounds, depth);
                } else {
                    to = chunkEnd(s, i);
                    int u = knownSchemeStart(s, i, to);
                    if (u > i) {
                        to = u;
                        urlAt = u;
                    }
                    replacement = redactChunk(s, i, to, strict, rounds, depth);
                }
            } else if (c == '<' && i + 1 < n && s.charAt(i + 1) == '/') {
                // <path?query> (a Link header): the path up to '>'.
                from = i + 1;
                to = valueEnd(s, from, "\"'<>");
                url = true;
                replacement = urlReplacement(s.substring(from, to), rounds, depth);
            } else if (c == '=') {
                int vs = i + 1;
                boolean quoted = vs < n && s.charAt(vs) == '"';
                int inner = quoted ? vs + 1 : vs;
                if (sensitiveName(nameBefore(s, i), strict)) {
                    char close = quoted || inner >= n ? 0 : closerFor(s.charAt(inner));
                    from = inner;
                    if (quoted) {
                        to = closingQuote(s, inner);
                    } else if (close == 0) {
                        to = valueEnd(s, inner, "&;,\"'<>()[]{}|\\");
                    } else if (s.startsWith(PLACEHOLDER, inner) || s.startsWith(SHAPE_PLACEHOLDER, inner)) {
                        to = inner; // already redacted: [REDACTED] is not a bracketed value
                    } else {
                        // '..', (..), [..], {..}, <..>: everything up to the matching closer
                        from = inner + 1;
                        to = closerIndex(s, from, s.charAt(inner), close);
                    }
                    if (to > from) replacement = PLACEHOLDER;
                } else if (inner < n && s.charAt(inner) == '/') {
                    from = inner;
                    to = quoted ? closingQuote(s, inner) : valueEnd(s, inner, ";,\"'<>");
                    url = true;
                    replacement = urlReplacement(s.substring(from, to), rounds, depth);
                }
                if (replacement == null) {
                    to = i + 1; // just the '='; the value is scanned as ordinary text
                    from = i;
                }
            } else if (isChunkChar(c)) {
                to = chunkEnd(s, i);
                int u = knownSchemeStart(s, i, to);
                int end = u == i ? urlEnd(s, i) : -1;
                if (end > i) {
                    to = end; // jdbc:<subscheme>:// after a prefix ("-jdbc:sqlserver://")
                    url = true;
                    replacement = urlReplacement(s.substring(i, to), rounds, depth);
                } else {
                    if (u > i) {
                        to = u;
                        urlAt = u;
                    }
                    replacement = redactChunk(s, i, to, strict, rounds, depth);
                }
            }
            if (replacement != null && !replacement.contentEquals(s.subSequence(from, to))) {
                if (out == null) out = new StringBuilder(n);
                out.append(s, copied, from).append(replacement);
                copied = to;
            }
            i = Math.max(to, i + 1);
            // A URL that ended on the '=' of a pair, because the value starts with a character
            // that ends a URL (;password={..}, ;password="a b", ?token='..'): judge that pair
            // now, as text. The '=' branch always moves past it, so this cannot loop.
            if (url && to < n && to > from && s.charAt(to - 1) == '=') i = to - 1;
        }
        if (out == null) return s;
        return out.append(s, copied, n).toString();
    }

    /** The closer of a value that opens with {@code open}: {@code ' ( [ { <}; 0 for anything else. */
    private static char closerFor(char open) {
        switch (open) {
            case '\'': return '\'';
            case '(': return ')';
            case '[': return ']';
            case '{': return '}';
            case '<': return '>';
            default: return 0;
        }
    }

    /**
     * Index of the closer of a bracketed or single-quoted value whose content starts at
     * {@code from}, or the end of the line when there is none (the rest of the line is then the
     * value: better too much than the tail of a password). Brackets of the same kind nest. A
     * doubled closer inside {@code {..}} or {@code '..'} is an escaped one, as SQL Server
     * connection strings ({@code password={a}}b}}) and SQL literals ({@code 'it''s'}) write it.
     */
    private static int closerIndex(String s, int from, char open, char close) {
        int n = s.length();
        int depth = 1;
        for (int i = from; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') return i;
            if (c == close) {
                if (depth == 1 && (close == '}' || close == '\'') && i > from && i + 1 < n && s.charAt(i + 1) == close) {
                    i++;
                } else if (--depth == 0) {
                    return i;
                }
            } else if (c == open) {
                depth++;
            }
        }
        return n;
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
     * sentence punctuation ({@code . , ; : ! ? ) ]}; the {@code ]} of a placeholder stays).
     * Otherwise -1. One exception: such a character inside what can only be a password
     * ({@link #userInfoResume}, {@code postgres://app:p^ss@db}) does not end the URL. Scans at
     * most the scheme run, the URL and {@value #USERINFO_LOOKAHEAD} characters.
     */
    private static int urlEnd(String s, int start) {
        int n = s.length();
        int j = start;
        while (j < n && isSchemeChar(s.charAt(j))) {
            if (s.charAt(j) == ':' && s.startsWith("//", j + 1)) {
                if (j == start) return -1;
                int end = j + 3;
                while (end < n && !endsUrl(s.charAt(end))) end++;
                int at = end < n ? userInfoResume(s, j + 3, end) : -1;
                if (at > end) {
                    end = at;
                    while (end < n && !endsUrl(s.charAt(end))) end++;
                }
                while (end > j + 3 && ".,;:!?)]".indexOf(s.charAt(end - 1)) >= 0) {
                    if (s.charAt(end - 1) == ']' && (endsWithAt(s, end, PLACEHOLDER) || endsWithAt(s, end, SHAPE_PLACEHOLDER))) break;
                    end--;
                }
                return end;
            }
            j++;
        }
        return -1;
    }

    private static boolean endsWithAt(String s, int end, String suffix) {
        return end >= suffix.length() && s.startsWith(suffix, end - suffix.length());
    }

    /** How far past a URL-ending character the {@code @} of a {@code user:password@} is looked for. */
    private static final int USERINFO_LOOKAHEAD = 256;

    /**
     * A URL scan stopped at {@code s[stop]} with only {@code s[authStart, stop)} of the authority
     * read. If that text is the start of a {@code user:password} (it holds {@code :}, no
     * {@code @}, no {@code / ? #}, and is not {@linkplain #hostKind evidently a host}) and an
     * {@code @} follows before any whitespace, within {@value #USERINFO_LOOKAHEAD} characters,
     * the stop character belongs to the password: returns the index of that {@code @}, where the
     * scan resumes. Otherwise -1. A {@code "} always ends the URL (JSON puts one right after
     * every URL). A space is passed only once, and only when what follows the {@code :} cannot
     * be a port ({@code app:my pass@db}): then the {@code @} must be in the next word. Any other
     * whitespace ends the URL.
     */
    private static int userInfoResume(String s, int authStart, int stop) {
        char t = s.charAt(stop);
        if (t == '"' || (t <= ' ' && t != ' ') || t == 0x7f) return -1;
        boolean colon = false;
        for (int i = authStart; i < stop; i++) {
            char c = s.charAt(i);
            if (c == '@' || c == '/' || c == '?' || c == '#') return -1;
            if (c == ':') colon = true;
        }
        if (!colon) return -1;
        int kind = hostKind(s, authStart, stop);
        if (kind == HOST || (t == ' ' && kind != USERINFO)) return -1;
        int limit = Math.min(s.length(), stop + 1 + USERINFO_LOOKAHEAD);
        for (int i = stop + 1; i < limit; i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c == 0x7f) return -1;
            if (c == '@') return i;
        }
        return -1;
    }

    /** {@link #hostKind}: evidently a host or a host list; an {@code @} further on is not user info. */
    private static final int HOST = 0;
    /** {@link #hostKind}: one dotless {@code name:digits}; a host and port, or a user and a numeric password. */
    private static final int AMBIGUOUS = 1;
    /** {@link #hostKind}: not {@code host[:port]} at all ({@code user:pa}, {@code :pw}): the start of user info. */
    private static final int USERINFO = 2;

    /**
     * What the text {@code s[from, to)} between {@code ://} and the first {@code / ? #} (cut at a
     * {@code ;}, where connection parameters start) looks like, given that it holds {@code :} and
     * no {@code @}. It is a comma-separated list of {@code host[:port]} (host: letters, digits,
     * {@code . -}, starting with a letter or holding a dot, or a bracketed IPv6 literal; port: 1
     * to 5 digits) or it is {@link #USERINFO}. Such a list is {@link #HOST} when it has two or
     * more members ({@code h1:27017,h2:27017}) or its host holds a dot, is {@code localhost} or
     * is an IPv6 literal; a single dotless {@code name:digits} ({@code db:5432},
     * {@code user:12345}) is {@link #AMBIGUOUS}, and treated as user info when an {@code @}
     * follows: redaction wins the tie.
     */
    private static int hostKind(String s, int from, int to) {
        to = indexOfAny(s, from, to, ";");
        int members = 0;
        boolean evident = false;
        int i = from;
        while (true) {
            int start = i;
            if (i < to && s.charAt(i) == '[') {
                i = indexOfAny(s, i, to, "]");
                if (i == to) return USERINFO;
                i++;
                evident = true;
            } else {
                boolean dot = false;
                while (i < to) {
                    char c = s.charAt(i);
                    if (c == '.') dot = true;
                    else if (!(isAsciiLetter(c) || isAsciiDigit(c) || c == '-')) break;
                    i++;
                }
                if (i == start || (!dot && !isAsciiLetter(s.charAt(start)))) return USERINFO;
                if (dot || (i - start == 9 && s.regionMatches(true, start, "localhost", 0, 9))) evident = true;
            }
            if (i < to && s.charAt(i) == ':') {
                int digits = ++i;
                while (i < to && isAsciiDigit(s.charAt(i))) i++;
                if (i == digits || i - digits > 5) return USERINFO;
            }
            members++;
            if (i == to) break;
            if (s.charAt(i) != ',') return USERINFO;
            i++;
        }
        return members > 1 || evident ? HOST : AMBIGUOUS;
    }

    /**
     * Scheme names recognised even in the middle of a run, where a URL is otherwise not looked for
     * ({@code GET /proxy/https://user:pw@host}, {@code -https://}, {@code 1https://}). Any other
     * {@code [a-z][a-z0-9+.-]*://} is recognised at the start of a run only.
     */
    private static final List<String> KNOWN_SCHEMES = List.of(
        "http", "https", "ws", "wss", "ftp", "postgres", "postgresql", "mysql", "mariadb", "mongodb",
        "mongodb+srv", "redis", "rediss", "amqp", "amqps", "smtp", "smtps", "s3");

    /**
     * Where a URL starts inside the run {@code s[from, to)} that ends right before {@code ://}: at
     * a {@link #KNOWN_SCHEMES known scheme} that ends the run, or at the run itself when it follows
     * {@code jdbc:} ({@code -jdbc:sqlserver://}) and is a scheme name (a letter, then
     * {@code [A-Za-z0-9+.-]}: a run holding {@code _ / ~ $ %} is not one). Otherwise -1.
     */
    private static int knownSchemeStart(String s, int from, int to) {
        if (!s.startsWith("://", to)) return -1;
        int best = -1;
        for (String k : KNOWN_SCHEMES) {
            int st = to - k.length();
            if (st >= from && (best < 0 || st < best) && s.regionMatches(true, st, k, 0, k.length())) best = st;
        }
        if (best < 0 && from >= 5 && s.regionMatches(true, from - 5, "jdbc:", 0, 5) && isSchemeName(s, from, to)) {
            return from;
        }
        return best;
    }

    /** {@code s[from, to)} is a URL scheme name: a letter, then letters, digits, {@code + - .}. */
    private static boolean isSchemeName(String s, int from, int to) {
        if (from >= to || !isAsciiLetter(s.charAt(from))) return false;
        for (int i = from + 1; i < to; i++) {
            char c = s.charAt(i);
            if (!(isAsciiLetter(c) || isAsciiDigit(c) || c == '+' || c == '-' || c == '.')) return false;
        }
        return true;
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
        boolean pathEvidence = hasPathEvidence(chunk);
        for (int k = 0; k <= len; k++) {
            char c = k < len ? chunk.charAt(k) : '.';
            if (c == '.' || c == '~' || c == '$' || c == '%') {
                if (k - start >= MIN_SECRET_LENGTH) {
                    int path = indexOfAny(chunk, start, k, "/") < k ? pathKind(chunk, start, k) : NOT_A_PATH;
                    if (path == PATH_WITH_SECRET && pathEvidence) {
                        // A file path: each secret '/'-piece is replaced on its own, as redactPath does.
                        int p = start;
                        for (int m = start; m <= k; m++) {
                            if (m < k && chunk.charAt(m) != '/') continue;
                            if (isSecretLabel(chunk, p, m)) {
                                if (out == null) out = new StringBuilder(len);
                                out.append(chunk, copied, p).append(SHAPE_PLACEHOLDER);
                                copied = m;
                            }
                            p = m + 1;
                        }
                    } else if (path == PATH_WITH_SECRET || (path == NOT_A_PATH && isSecretLabel(chunk, start, k))) {
                        if (out == null) out = new StringBuilder(len);
                        out.append(chunk, copied, start).append(SHAPE_PLACEHOLDER);
                        copied = k;
                    }
                }
                start = k + 1;
            }
        }
        if (out == null) return null;
        return out.append(chunk, copied, len).toString();
    }

    /** {@link #pathKind}: not made of path pieces (a base64 secret, or no {@code /}); judged whole. */
    private static final int NOT_A_PATH = 0;
    /** {@link #pathKind}: every piece is plain; kept. */
    private static final int PLAIN_PATH = 1;
    /** {@link #pathKind}: plain pieces and at least one secret-shaped piece of 16 or more characters. */
    private static final int PATH_WITH_SECRET = 2;

    /**
     * Whether a label holding {@code /} is made of path pieces, and whether one of them is a
     * secret. Standard base64 uses {@code /} too, so each piece has to look like part of a path:
     * <ul>
     *   <li>a piece shorter than {@value #MIN_SECRET_LENGTH} must not mix upper case, lower case
     *       and digits ({@code aB3dE9fG});</li>
     *   <li>a longer piece without a digit must be cased like words: each {@code _}/{@code -}
     *       part all lower case, all upper case or Capitalized, or the piece a
     *       {@linkplain #isNameLike name} ({@code AdminDashboardController}); the tail of AWS's
     *       example key, {@code bPxRfiCYEXAMPLEKEY}, is not;</li>
     *   <li>a longer piece with a digit and a letter is either a {@linkplain #isSecretLabel
     *       secret} or plain (a UUID, a name such as {@code V3__create_users_table});</li>
     *   <li>no {@code +} anywhere.</li>
     * </ul>
     * Any piece failing this makes the label {@link #NOT_A_PATH}. So does a secret piece next to
     * a short piece that is not cased like words, simple camelCase ({@code MyApp},
     * {@code getUserById}) or a name: {@code /IShbxYCPZpUDcmS/DPH/<28 more>} is one base64 value,
     * not two directories and a token. Before the second review a label was a path unless a short
     * piece mixed the three classes, and every piece was then judged alone:
     * {@code wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY} survived whole.
     */
    private static int pathKind(String s, int from, int to) {
        boolean secret = false;
        boolean wordy = true;
        int start = from;
        for (int i = from; i <= to; i++) {
            char c = i < to ? s.charAt(i) : '/';
            if (c == '+') return NOT_A_PATH;
            if (c != '/') continue;
            if (i - start < MIN_SECRET_LENGTH) {
                if (mixesCaseAndDigits(s, start, i)) return NOT_A_PATH;
                if (wordy && !isCasedLikeWords(s, start, i) && !isSimpleCamel(s, start, i) && !isNameLike(s, start, i)) {
                    wordy = false;
                }
            } else if (isSecretLabel(s, start, i)) {
                secret = true;
            } else if (!hasDigit(s, start, i) && !isCasedLikeWords(s, start, i) && !isNameLike(s, start, i)) {
                return NOT_A_PATH;
            }
            start = i + 1;
        }
        if (!secret) return PLAIN_PATH;
        return wordy ? PATH_WITH_SECRET : NOT_A_PATH;
    }

    /**
     * {@code s[from, to)} is letters only, written as camelCase words of two or more letters:
     * an optional lower-case word, then Capitalized words ({@code MyApp}, {@code getUserById}).
     * No acronym, no one-letter word: {@code JPqyl}, {@code UlJypjT} and {@code iOS} are not.
     */
    private static boolean isSimpleCamel(String s, int from, int to) {
        int i = from;
        while (i < to && s.charAt(i) >= 'a' && s.charAt(i) <= 'z') i++;
        if (i - from == 1 || i == to) return false;
        while (i < to) {
            char c = s.charAt(i);
            if (c < 'A' || c > 'Z') return false;
            int lower = ++i;
            while (i < to && s.charAt(i) >= 'a' && s.charAt(i) <= 'z') i++;
            if (i == lower) return false;
        }
        return true;
    }

    /**
     * Positive evidence that a run is a file path, which lets a secret piece be replaced alone
     * ({@code /tmp/uploads/[redacted].tmp}) instead of the whole label: it starts with {@code /},
     * {@code ./}, {@code ../} or {@code ~/} (a drive letter's {@code C:} is outside the run, so
     * {@code C:/Users/...} starts with {@code /} too), or it ends in a file extension (a dot and
     * 1 to 5 letters or digits, one of them a letter). Without it a label with a secret piece
     * ({@code exports/<token>/data}) is replaced whole.
     */
    private static boolean hasPathEvidence(String chunk) {
        if (chunk.startsWith("/") || chunk.startsWith("./") || chunk.startsWith("../") || chunk.startsWith("~/")) {
            return true;
        }
        int dot = chunk.lastIndexOf('.');
        int ext = chunk.length() - dot - 1;
        if (dot <= 0 || ext < 1 || ext > 5) return false;
        boolean letter = false;
        for (int i = dot + 1; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (isAsciiLetter(c)) letter = true;
            else if (!isAsciiDigit(c)) return false;
        }
        return letter;
    }

    private static boolean hasDigit(String s, int from, int to) {
        for (int i = from; i < to; i++) if (isAsciiDigit(s.charAt(i))) return true;
        return false;
    }

    /**
     * Every {@code _}/{@code -}-separated part of {@code s[from, to)} is all lower case, all upper
     * case, or Capitalized ({@code customer-orders-final}, {@code README}, {@code Documents}): no
     * upper-case letter follows a lower-case one, and none but the first of a part precedes one.
     */
    private static boolean isCasedLikeWords(String s, int from, int to) {
        int partStart = from;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '-') {
                partStart = i + 1;
            } else if (c >= 'A' && c <= 'Z' && i > partStart) {
                char prev = s.charAt(i - 1);
                boolean nextLower = i + 1 < to && s.charAt(i + 1) >= 'a' && s.charAt(i + 1) <= 'z';
                if ((prev >= 'a' && prev <= 'z') || nextLower) return false;
            }
        }
        return true;
    }

    /**
     * A label ({@code [A-Za-z0-9_\-+/]}, no dots) that is secret-shaped: at least
     * {@value #MIN_SECRET_LENGTH} characters with a digit and a letter, not a UUID, and not a
     * {@linkplain #isNameLike name made of words}. A label with {@code /} reaches here whole only
     * when it is {@linkplain #pathKind not a path}; a piece of a path is judged by this too.
     */
    private static boolean isSecretLabel(String s, int from, int to) {
        if (to - from < MIN_SECRET_LENGTH) return false;
        if (to - from == 36 && UUID_SHAPE.matcher(s.substring(from, to)).matches()) return false;
        if (!hasDigitAndLetter(s, from, to)) return false;
        return !isNameLike(s, from, to);
    }

    /** Digits in a group of a name ({@code 20241105}, {@code 000482}); a longer group is an id or a token. */
    private static final int NAME_DIGITS = 8;

    /**
     * A label made of ordinary words, so not secret-shaped although it is long and holds a digit:
     * {@code V3__create_users_table}, {@code invoice-48213-final-v2}, {@code CONSTRAINT_INDEX_4},
     * {@code user_sessions_archive_2024q4}, {@code shippingAddressLine2},
     * {@code Http2ConnectionHandlerImpl}. The label is split into parts on {@code _} and {@code -},
     * and each part into words: a leading digit group, or a run of letters (cut at camelCase
     * boundaries) with an optional trailing digit group. It is a name when all of these hold:
     * <ul>
     *   <li>it holds only {@code [A-Za-z0-9_-]};</li>
     *   <li>it has two or more words, and at least one has three or more letters;</li>
     *   <li>a digit group has at most {@value #NAME_DIGITS} digits on its own and at most
     *       {@value #NAME_SUFFIX_DIGITS} after letters ({@code v2}, {@code V19}), and at most one
     *       word in the label has both letters and digits;</li>
     *   <li>a digit is followed directly by a letter (not at a camelCase boundary such as
     *       {@code Http2Connection}) at most once, and only inside a part of at most 8 characters
     *       ({@code 2024q4}): letters and digits interleaved within a long part
     *       ({@code upload-8a7b6c5d4e3f2a1b}, a SHA, a random token) are a secret;</li>
     *   <li>at most one word is a fragment (one or two letters inside a longer part, {@code q4});</li>
     *   <li>in a part with both cases (camelCase), every word has four or more letters
     *       ({@code Http}, {@code Line}), except a first lower-case word of three or more
     *       ({@code get}) and one acronym of two to four capitals ({@code UTF}): random
     *       mixed-case text cuts into short words ({@code Ym}, {@code Hf7}, {@code Oz});</li>
     *   <li>it is not three or more parts of (nearly) one length, the shape of a grouped code
     *       ({@code abcd5-efgh6-ijkl7}).</li>
     * </ul>
     * Also a name: a {@code 0x} literal of at most 16 hex digits (a lambda's address), and a
     * {@code git describe} suffix ({@code 1423-g3f9a1c2}: {@code g} plus 7 to 12 hex digits after
     * a digit group). A single word ({@code deadbeefcafe1234}, a 40-hex SHA) is never a name.
     */
    static boolean isNameLike(String s, int from, int to) {
        if (to - from > 2 && to - from <= 18 && s.charAt(from) == '0' && s.charAt(from + 1) == 'x'
                && isHex(s, from + 2, to)) {
            return true;
        }
        int words = 0;
        int longWords = 0;
        int fragments = 0;
        int interleaved = 0;
        int digitWords = 0;
        int parts = 0;
        int minPart = Integer.MAX_VALUE;
        int maxPart = 0;
        boolean prevDigits = false;
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '_' || c == '-') {
                i++;
                continue;
            }
            int end = i;
            boolean partLower = false;
            boolean partUpper = false;
            while (end < to && (isAsciiLetter(s.charAt(end)) || isAsciiDigit(s.charAt(end)))) {
                char e = s.charAt(end);
                if (e >= 'a' && e <= 'z') partLower = true;
                else if (e >= 'A' && e <= 'Z') partUpper = true;
                end++;
            }
            boolean camel = partLower && partUpper;
            if (end == i) return false; // '+', '/' or anything else: not a name
            int partLen = end - i;
            parts++;
            minPart = Math.min(minPart, partLen);
            maxPart = Math.max(maxPart, partLen);
            if (prevDigits && partLen >= 8 && partLen <= 13 && s.charAt(i) == 'g' && isLowerHex(s, i + 1, end)) {
                words++; // git describe: <tag>-<n>-g<hash>
                longWords++;
                prevDigits = false;
                i = end;
                continue;
            }
            int partWords = 0;
            int partFragments = 0;
            int acronyms = 0;
            boolean digitsOnly = true;
            int j = i;
            while (j < end) {
                int letters = 0;
                boolean lower = false;
                int wordStart = j;
                if (isAsciiLetter(s.charAt(j))) {
                    digitsOnly = false;
                    letters = 1;
                    int consonants = isConsonant(s.charAt(j)) ? 1 : 0;
                    j++;
                    while (j < end && isAsciiLetter(s.charAt(j)) && !camelBoundary(s, j, end)) {
                        if (s.charAt(j) >= 'a') lower = true;
                        consonants = isConsonant(s.charAt(j)) ? consonants + 1 : 0;
                        if (consonants >= 5) return false; // not a word: bxsngv, Snzkmpf
                        letters++;
                        j++;
                    }
                }
                int digits = 0;
                while (j < end && isAsciiDigit(s.charAt(j))) {
                    digits++;
                    j++;
                }
                if (j == wordStart) return false;
                if (digits > (letters > 0 ? NAME_SUFFIX_DIGITS : NAME_DIGITS)) return false;
                if (letters > 0 && digits > 0 && ++digitWords > 1) return false;
                if (camel) {
                    boolean capital = letters > 0 && s.charAt(wordStart) <= 'Z';
                    if (capital && letters >= 2 && !lower) {
                        // one short acronym, first in the part or before digits (XMLHttp, UTF8Stream)
                        if (letters > 4 || ++acronyms > 1 || (partWords > 0 && digits == 0)) return false;
                    } else if (capital ? letters < 4 : partWords > 0 || letters < 3 || digits > 0) {
                        return false; // also: a leading digit group, or digits on the first lower-case word
                    }
                }
                if (j < end && digits > 0 && !camelBoundary(s, j, end)) {
                    // a letter straight after a digit, not starting a camelCase word: only in a
                    // part shaped like 2024q4 (1-4 digits, one letter, 1-2 digits), once
                    if (!isQuarter(s, i, end) || ++interleaved > 1) return false;
                }
                partWords++;
                if (letters >= 3) longWords++;
                else if (letters > 0) partFragments++;
            }
            words += partWords;
            if (partWords > 1) fragments += partFragments;
            prevDigits = digitsOnly;
            i = end;
        }
        // Three or more parts of (nearly) one length are a grouped code (abcd5-efgh6-ijkl7), not words.
        if (parts >= 3 && maxPart - minPart <= 1) return false;
        return words >= 2 && longWords >= 1 && fragments <= 1;
    }

    /** Digits after the letters of a word in a name ({@code v2}, {@code V19}, {@code sha256}). */
    private static final int NAME_SUFFIX_DIGITS = 3;

    /** {@code s[from, to)} is 1 to 4 digits, one letter, then 1 or 2 digits: {@code 2024q4}, {@code 2024H1}. */
    private static boolean isQuarter(String s, int from, int to) {
        int i = from;
        while (i < to && isAsciiDigit(s.charAt(i))) i++;
        int lead = i - from;
        if (lead < 1 || lead > 4 || i >= to || !isAsciiLetter(s.charAt(i))) return false;
        int tail = to - i - 1;
        return tail >= 1 && tail <= 2 && isAsciiDigit(s.charAt(i + 1)) && (tail == 1 || isAsciiDigit(s.charAt(i + 2)));
    }

    /** A letter other than {@code a e i o u y}, either case. */
    private static boolean isConsonant(char c) {
        return "aeiouyAEIOUY".indexOf(c) < 0;
    }

    /** {@code s[j]} starts a camelCase word: an upper-case letter after a lower-case letter or a digit, or before a lower-case one. */
    private static boolean camelBoundary(String s, int j, int end) {
        char c = s.charAt(j);
        if (c < 'A' || c > 'Z') return false;
        char prev = s.charAt(j - 1);
        if ((prev >= 'a' && prev <= 'z') || isAsciiDigit(prev)) {
            // after a digit, only a Capitalized word counts ("Http2Connection", not "KQZR8MWPX")
            return !isAsciiDigit(prev) || (j + 1 < end && s.charAt(j + 1) >= 'a' && s.charAt(j + 1) <= 'z');
        }
        return j + 1 < end && s.charAt(j + 1) >= 'a' && s.charAt(j + 1) <= 'z';
    }

    private static boolean isHex(String s, int from, int to) {
        for (int i = from; i < to; i++) if (hexValue(s.charAt(i)) < 0) return false;
        return true;
    }

    private static boolean isLowerHex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!isAsciiDigit(c) && (c < 'a' || c > 'f')) return false;
        }
        return true;
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

    /**
     * Last labels of a private network name, which make a two-label name a hostname too:
     * {@code db-primary-7f8c9d.internal}.
     */
    private static final Set<String> PRIVATE_TLDS = Set.of("internal", "local", "localhost", "lan", "corp");

    /**
     * A DNS name of three or more labels ({@code [A-Za-z0-9-]}, 1 to 63) whose last label is in
     * {@link #HOST_TLDS}, or of two labels whose last is in {@link #PRIVATE_TLDS}.
     */
    private static boolean isHostname(String v) {
        int lastDot = v.lastIndexOf('.');
        if (lastDot < 0) return false;
        String tld = v.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        if (!HOST_TLDS.contains(tld)) return false;
        int minLabels = PRIVATE_TLDS.contains(tld) ? 2 : 3;
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
        return labels >= minLabels;
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
     * Non-string values are kept. Returns a new map in the same order. Never throws: a header
     * whose redaction fails gets {@link #FAILED} as its value, {@code null} gives an empty map,
     * and a map that cannot be read gives its keys with {@link #FAILED} for every value.
     */
    public static Map<String, Object> redactHeaders(Map<String, ?> headers) {
        if (headers == null) return new LinkedHashMap<>();
        try {
            var out = new LinkedHashMap<String, Object>(headers.size());
            for (var e : headers.entrySet()) {
                String name = e.getKey();
                Object redacted;
                try {
                    redacted = redactHeader(name, e.getValue());
                } catch (RuntimeException | StackOverflowError ex) {
                    redacted = FAILED; // fail closed, header by header
                }
                out.put(name, redacted);
            }
            return out;
        } catch (RuntimeException | StackOverflowError e) {
            return failedCopy(headers);
        }
    }

    private static Object redactHeader(String name, Object value) {
        if (sensitiveName(name, true)) return PLACEHOLDER;
        if (value instanceof String s && name != null) return redactHeaderValue(name, s);
        return value;
    }

    private static String redactHeaderValue(String name, String value) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (URL_HEADERS.contains(lower)) return redactUrl(value, DECODE_ROUNDS, 0);
        if (VERBATIM_HEADERS.contains(lower) || lower.startsWith("sec-fetch-") || lower.startsWith("sec-ch-")) {
            return value;
        }
        return redactValue(value, DECODE_ROUNDS, 0);
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
        try {
            return redactValue(value, DECODE_ROUNDS, 0);
        } catch (RuntimeException | StackOverflowError e) {
            return FAILED;
        }
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
     * garbage) goes through {@link #redactValue} instead; nothing throws ({@link #FAILED} on an
     * internal error).
     */
    public static String redactUrl(String url) {
        try {
            return redactUrl(url, DECODE_ROUNDS, 0);
        } catch (RuntimeException | StackOverflowError e) {
            return FAILED;
        }
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
            int at = userInfoEnd(url, authStart, authEnd);
            int hostStart = authStart;
            if (at >= authStart) {
                out.append(PLACEHOLDER);
                hostStart = at;
                if (at > authEnd) authEnd = indexOfAny(url, at, len, "/?#");
            }
            // Parameters after the host (jdbc:sqlserver://host:1433;user=sa;password=…): the
            // rest of the URL gets the name=value pass, whole. Such a URL has no path or query
            // to speak of, and a password there can hold '/', '?' or '#'.
            int semi = indexOfAny(url, hostStart, authEnd, ";");
            out.append(url, hostStart, semi);
            if (semi < authEnd) {
                return out.append(redactText(url.substring(semi), true, rounds, depth)).toString();
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

    /**
     * Index of the {@code @} that ends the user info of a URL, or -1 when it has none.
     * {@code authEnd} is the first {@code / ? #} after {@code ://}.
     * <ol>
     *   <li>An {@code @} before that (and before any {@code ;}, where connection parameters
     *       start): the last such one, as a URL parser would.</li>
     *   <li>Otherwise an {@code @} further on, when the text before {@code authEnd} holds
     *       {@code :} and can be the start of a {@code user:password} whose password holds an
     *       unencoded {@code / ? # ;} ({@code postgres://user:pa/ss@host}): the last {@code @}
     *       of the URL. Whether it can be is {@link #hostKind}'s call. {@link #USERINFO}
     *       ({@code user:pa}, {@code :pw}): yes. {@link #AMBIGUOUS} ({@code user:12345}, which
     *       is also what {@code db:5432} looks like): yes, unless parameters follow the port
     *       ({@code db:1433;user=sa;password=p@ss}: the parameter pass redacts that value).
     *       {@link #HOST}: no, in any scheme: {@code https://example.com:8443/users/@bob} and
     *       {@code mongodb://h1:27017,h2:27017/db?authSource=a@b} keep their hosts, path and
     *       query.</li>
     * </ol>
     */
    private static int userInfoEnd(String url, int authStart, int authEnd) {
        int semi = indexOfAny(url, authStart, authEnd, ";");
        if (semi < authEnd) {
            // Parameters are name=value: a ';' with no '=' before the next '@' is part of the
            // user info (postgres://us;er:pw@host), not the start of a parameter section.
            int next = indexOfAny(url, semi, authEnd, "=@");
            if (next == authEnd || url.charAt(next) == '@') semi = authEnd;
        }
        int at = -1;
        for (int i = authStart; i < semi; i++) {
            if (url.charAt(i) == '@') at = i;
        }
        if (at >= 0) return at;
        if (indexOfAny(url, authStart, semi, ":") == semi) return -1;
        int kind = hostKind(url, authStart, semi);
        if (kind == HOST || (kind == AMBIGUOUS && semi < authEnd)) return -1;
        int lastAt = url.lastIndexOf('@');
        return lastAt > semi ? lastAt : -1;
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
     * returned at once (this runs on every request's log line). Never throws: {@link #FAILED} on
     * an internal error.
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
        try {
            return redactPath(path, DECODE_ROUNDS, 0);
        } catch (RuntimeException | StackOverflowError e) {
            return FAILED;
        }
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
     * message with nothing to redact is returned unchanged. Never throws: {@link #FAILED} on an
     * internal error.
     */
    public static String redactMessage(String message) {
        try {
            if (message == null || message.isEmpty()) return message;
            // Check for JWT at the whole-message level first (message may be just a token)
            if (isJwt(message)) return PLACEHOLDER;
            return redactText(message, false, DECODE_ROUNDS, 0);
        } catch (RuntimeException | StackOverflowError e) {
            return FAILED;
        }
    }

    /**
     * Redact the message text in a printed stack trace. {@code Throwable.printStackTrace} embeds
     * the exception message verbatim in the first line, in every {@code Caused by:} and
     * {@code Suppressed:} line, and on the continuation lines of a multi-line message, so
     * redacting {@code getMessage()} alone leaves the same text readable in the trace. Every line
     * except a frame line ({@code at ...} or {@code ... N more} after one or more tabs, so the
     * frames of a {@code Suppressed:} exception and its causes too) goes through
     * {@link #redactMessage}; frame lines and line endings are kept exactly as they were. A trace
     * with nothing to redact is returned unchanged. Never throws; a line whose redaction fails
     * becomes {@link #FAILED}.
     */
    public static String redactStackTrace(String stackTrace) {
        try {
            return redactTrace(stackTrace);
        } catch (RuntimeException | StackOverflowError e) {
            return FAILED;
        }
    }

    /** Line by line; {@link #redactMessage} fails closed per line, so a failure costs one line, not the frames. */
    private static String redactTrace(String stackTrace) {
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
            if (!isFrameLine(stackTrace, pos, end) && end > pos) {
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
     * A frame line of a printed trace, at any indent: {@code printStackTrace} indents the frames of
     * a {@code Suppressed:} exception, and of the causes nested under it, with two or more tabs.
     * After at least one space or tab: {@code at } and a {@linkplain #isFrame frame},
     * {@code ... N more} or (Logback) {@code ... N common frames omitted}. Any other line that
     * starts with {@code at } is message text (Jackson's {@code  at [Source: ...]} excerpt of the
     * document it failed on) and is redacted like the rest.
     */
    static boolean isFrameLine(String s, int from, int end) {
        int i = from;
        while (i < end && (s.charAt(i) == '\t' || s.charAt(i) == ' ')) i++;
        if (i == from) return false;
        if (s.startsWith("at ", i)) return isFrame(s, i + 3, end);
        if (!s.startsWith("... ", i)) return false;
        int d = i + 4;
        while (d < end && isAsciiDigit(s.charAt(d))) d++;
        if (d == i + 4) return false;
        String rest = s.substring(d, end);
        return rest.equals(" more") || rest.equals(" common frames omitted");
    }

    /**
     * {@code s[from, end)} is a stack frame as {@code StackTraceElement.toString} writes it,
     * with Logback's packaging data if any, and nothing else:
     * <pre>
     *   [loader/][module[@version]/]pkg.Class.method(Source)[ ~[jar:version]]
     * </pre>
     * <ul>
     *   <li>the name before {@code (} is letters, digits and {@code _ $ . / @ < > - +}, with at
     *       least one {@code .}: {@code java.base/java.lang.Thread.run},
     *       {@code app//com.x.Y.z}, {@code com.x.Y.<init>}, {@code com.x.Y.lambda$run$0},
     *       {@code com.x.Y$$Lambda/0x000001f0a1047000.run}, Kotlin's {@code plus-WZ4Q5Ns};</li>
     *   <li>the source is {@code Native Method}, {@code Unknown Source}, or a file name (letters,
     *       digits, {@code _ $ . - < >}) with an optional {@code :line};</li>
     *   <li>the packaging data, if present, is {@code [..]} or {@code ~[..]} after one space,
     *       holds a {@code :} and only letters, digits and {@code . _ - / ! + $ :}.</li>
     * </ul>
     * No {@code =}, space, quote or {@code ://} fits anywhere, so a {@code name=value} pair or a
     * URL on such a line makes it message text.
     */
    private static boolean isFrame(String s, int from, int end) {
        int i = from;
        boolean dot = false;
        while (i < end) {
            char c = s.charAt(i);
            if (c == '.') dot = true;
            else if (!(Character.isLetterOrDigit(c) || "_$/@<>-+".indexOf(c) >= 0)) break;
            i++;
        }
        if (!dot || i >= end || s.charAt(i) != '(') return false;
        int j = i + 1;
        if (s.startsWith("Native Method)", j)) {
            j += 13;
        } else if (s.startsWith("Unknown Source)", j)) {
            j += 14;
        } else {
            while (j < end && (Character.isLetterOrDigit(s.charAt(j)) || "_$.-<>".indexOf(s.charAt(j)) >= 0)) j++;
            if (j == i + 1) return false;
            if (j < end && s.charAt(j) == ':') {
                int digits = ++j;
                while (j < end && isAsciiDigit(s.charAt(j))) j++;
                if (j == digits) return false;
            }
        }
        if (j >= end || s.charAt(j) != ')') return false;
        if (++j == end) return true;
        if (s.charAt(j) != ' ') return false;
        if (++j < end && s.charAt(j) == '~') j++;
        if (j >= end - 1 || s.charAt(j) != '[' || s.charAt(end - 1) != ']') return false;
        boolean colon = false;
        for (int k = j + 1; k < end - 1; k++) {
            char c = s.charAt(k);
            if (c == ':') colon = true;
            else if (!(isAsciiLetter(c) || isAsciiDigit(c) || "._-/!+$".indexOf(c) >= 0)) return false;
        }
        return colon;
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
