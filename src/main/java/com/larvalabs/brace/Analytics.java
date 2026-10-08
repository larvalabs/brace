package com.larvalabs.brace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Server-side page-view analytics: visitors, pageviews, top pages, sources, devices. No cookies,
 * no client script, no IPs or user agents stored. Enabled with {@code app.analytics()} and viewed
 * at {@code /ops/analytics} behind ops auth. Design: {@code docs/2026-10-07-brace-analytics.md}.
 *
 * <p>Every response passes through {@link #observe} after it is sent. It decides whether the
 * request was a human page view ({@link #classify}); counted views go into a bounded in-memory
 * buffer that a background thread writes to {@code brace_analytics_pageviews} every few seconds.
 * The visitor id is a 64-bit hash of a daily random salt, the host, the client IP and the user
 * agent. The salt is shared by the fleet through {@code brace_analytics_salts} and deleted shortly
 * after its day ends, so yesterday's ids can't be recomputed from a list of IPs.
 */
public final class Analytics {

    /** Cookie that marks a browser whose visits are not counted (set from {@code /ops/analytics}). */
    static final String IGNORE_COOKIE = "__brace_analytics_ignore";
    static final int TOP_LIMIT = 10;
    static final int BREAKDOWN_LIMIT = 8;
    /** Window for the "visitors now" count. */
    static final Duration LIVE_WINDOW = Duration.ofMinutes(5);
    /** How long a day's salt outlives its day, so events buffered just before midnight hash with it. */
    static final Duration SALT_GRACE = Duration.ofMinutes(5);
    static final long FLUSH_INTERVAL_MS = 10_000;
    private static final long SUMMARY_TTL_MS = 15_000;

    public static Options options() {
        return new Options();
    }

    /**
     * How a route's views are recorded. {@code PATH} (default) records the concrete path,
     * {@code ROUTE} records the route pattern, {@code OFF} records nothing.
     */
    public enum Track { PATH, ROUTE, OFF }

    /**
     * Raw rows are only read for today, yesterday's hour-by-hour comparison and days the rollup
     * hasn't reached; everything older comes from {@code brace_analytics_daily}. 35 days keeps a
     * month of per-view detail for investigating something recent.
     */
    static final int DEFAULT_RETENTION_DAYS = 35;

    /** Configuration for {@code app.analytics(...)}. */
    public static final class Options {
        private ZoneId zone = ZoneOffset.UTC;
        private final List<Middleware.PathPattern> excludes = new ArrayList<>();
        private TrustedProxies excludeIps;
        private String countryHeader;
        private Duration rawRetention = Duration.ofDays(DEFAULT_RETENTION_DAYS);
        private boolean strictNavigation;
        private int bufferLimit = 20_000;

        private Options() {}

        /** Day boundaries and salt rotation follow this zone. Default UTC. */
        public Options timezone(String zoneId) {
            this.zone = ZoneId.of(zoneId);
            return this;
        }

        /** Paths that are never counted: exact ({@code /admin}) or prefix ({@code /admin/*}). */
        public Options exclude(String... patterns) {
            for (String p : patterns) excludes.add(Middleware.PathPattern.compile(p));
            return this;
        }

        /** Client IPs or CIDR ranges whose visits are never counted (an office, a monitor). */
        public Options excludeIps(String... cidrs) {
            this.excludeIps = new TrustedProxies(cidrs);
            return this;
        }

        /**
         * Request header carrying a two-letter country code set by a proxy in front of the app,
         * e.g. {@code CF-IPCountry} behind Cloudflare. Read only from trusted-proxy peers.
         */
        public Options countryHeader(String header) {
            this.countryHeader = header;
            return this;
        }

        /**
         * How long raw page views are kept, e.g. {@code "90d"}. Default {@code "35d"}. Reports don't
         * depend on it: completed days are summarized into {@code brace_analytics_daily}, which is
         * kept indefinitely. Minimum 1 day.
         */
        public Options rawRetention(String duration) {
            this.rawRetention = Duration.ofMillis(Math.max(JobScheduler.parseInterval(duration),
                Duration.ofDays(1).toMillis()));
            return this;
        }

        /**
         * Require {@code Sec-Fetch-Mode: navigate} on every counted view. Without it the header is
         * already required from UAs claiming a browser that always sends it (modern Chrome, Edge,
         * Firefox); strict extends that to every UA, at the cost of browsers too old to send fetch
         * metadata (pre-2023 Safari).
         */
        public Options strictNavigation(boolean strict) {
            this.strictNavigation = strict;
            return this;
        }

        Options bufferLimit(int limit) {
            this.bufferLimit = limit;
            return this;
        }

        ZoneId zone() { return zone; }
        Duration rawRetention() { return rawRetention; }
    }

    /** Outcome of {@link #classify}. The rejected ones are tallied and shown on the dashboard. */
    enum Verdict {
        COUNT(null), IGNORE(null),
        BOT("bot"), HTMX("htmx"), PREFETCH("prefetch"), BACKGROUND("background"), EXCLUDED("excluded");

        final String wire;
        Verdict(String wire) { this.wire = wire; }
    }

    /** Rejection reasons in storage/display order; {@code dropped} is a full buffer. */
    static final List<String> REJECT_REASONS =
        List.of("bot", "htmx", "background", "prefetch", "excluded", "dropped");

    /** One counted view, held in memory until the next flush. IP and UA never leave this record. */
    record Event(Instant ts, LocalDate day, int hour, String host, String ip, String ua, String path,
                 String source, String device, String browser, String os, String country) {}

    public record Row(String key, long visitors, long pageviews) {}

    public record Point(String bucket, long visitors, long pageviews) {}

    /** Today's headline numbers, for the ops dashboard card and {@code /ops/status}. */
    public record Summary(long visitors, long pageviews, long live) {}

    public record Report(String range, String timezone, LocalDate from, LocalDate to,
                         long visitors, long pageviews, Long previousVisitors, Long previousPageviews,
                         long live, List<Point> series, List<Row> pages, List<Row> sources,
                         List<Row> devices, List<Row> browsers, List<Row> os, List<Row> countries,
                         Map<String, Long> notCounted) {}

    private final Options options;
    private final DatabaseFactory databaseFactory;
    private final ConcurrentLinkedQueue<Event> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger buffered = new AtomicInteger();
    private final ConcurrentHashMap<LocalDate, AtomicLongArray> rejects = new ConcurrentHashMap<>();
    private final Map<LocalDate, byte[]> salts = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    // Replaced by the real instance id at start(); random until then so tallies never collide.
    private volatile String instanceId = "pending-" + Long.toHexString(new SecureRandom().nextLong());
    private final java.util.concurrent.atomic.AtomicBoolean warnedUntrustedProxy =
        new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Summary cachedSummary;
    private volatile long cachedSummaryAt;
    private Thread flusher;

    Analytics(Options options, DatabaseFactory databaseFactory) {
        this.options = options;
        this.databaseFactory = databaseFactory;
    }

    void setInstanceId(String instanceId) {
        if (instanceId != null) this.instanceId = instanceId;
    }

    void start() {
        flusher = Thread.ofVirtual().name("brace-analytics-flush").start(() -> {
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                try {
                    flush();
                } catch (Exception e) {
                    Log.warn("analytics flush failed: " + e);
                }
            }
        });
    }

    /** Stop the flusher and write out what is buffered. Called by {@code Brace.stop()}. */
    void close() {
        if (flusher != null) flusher.interrupt();
        try {
            flush();
        } catch (Exception e) {
            // Best effort on shutdown.
        }
    }

    // ---------------------------------------------------------------- collection

    /**
     * Called once per response after it is written. Never throws: analytics must not be able to
     * break a request that already succeeded.
     */
    void observe(Request req, Route route, Result result) {
        try {
            Verdict verdict = classify(req, route, result, options);
            if (verdict == Verdict.COUNT) {
                enqueue(req, route);
            } else if (verdict != Verdict.IGNORE) {
                tally(verdict.wire, today());
            }
        } catch (RuntimeException e) {
            Log.debug("analytics.observe_failed", Map.of("error", String.valueOf(e)));
        }
    }

    /**
     * Is this response a human page view? {@link Verdict#IGNORE} is for requests that were never
     * candidates (non-GET, assets, JSON, errors); the other non-COUNT verdicts are candidates we
     * rejected, which are tallied so the dashboard can show what was filtered.
     */
    static Verdict classify(Request req, Route route, Result result, Options options) {
        if (!"GET".equals(req.method()) || route == null || result == null) return Verdict.IGNORE;
        if (route.analytics() == Track.OFF) return Verdict.IGNORE;
        String pattern = route.pattern();
        if (pattern.startsWith("/ops/") || pattern.startsWith("/__brace/")) return Verdict.IGNORE;

        int status = result.status();
        if (status == 304) {
            // A revalidated page: the 304 carries no content type, so judge by what was asked for.
            String accept = req.header("Accept");
            if (accept == null || !accept.contains("text/html")) return Verdict.IGNORE;
        } else if (status < 200 || status >= 300) {
            return Verdict.IGNORE;
        } else {
            String type = result.contentType();
            if (type == null || !type.regionMatches(true, 0, "text/html", 0, 9)) return Verdict.IGNORE;
        }

        if (req.cookie(IGNORE_COOKIE) != null) return Verdict.EXCLUDED;
        String path = req.path();
        for (var p : options.excludes) {
            if (p.matches(path)) return Verdict.EXCLUDED;
        }
        if (options.excludeIps != null && options.excludeIps.isTrusted(req.ip())) return Verdict.EXCLUDED;

        if (isPrefetch(req)) return Verdict.PREFETCH;

        // htmx: a partial swap is not a page load, but a boosted link click or a history restore
        // is a navigation. Those arrive as fetches (Sec-Fetch-Mode: cors), so they skip that check.
        boolean htmxNavigation = false;
        if ("true".equals(req.header("HX-Request"))) {
            htmxNavigation = "true".equals(req.header("HX-Boosted"))
                || "true".equals(req.header("HX-History-Restore-Request"));
            if (!htmxNavigation) return Verdict.HTMX;
        }
        String ua = req.header("User-Agent");
        if (!htmxNavigation) {
            String mode = req.header("Sec-Fetch-Mode");
            if (mode != null && !mode.equals("navigate")) return Verdict.BACKGROUND;
            // A missing header is allowed for browsers that predate it, but not from a UA claiming
            // to be one that always sends it. Browsers send it only over HTTPS (and localhost),
            // which Brace assumes in production, as the session cookie's Secure default does.
            if (mode == null && (options.strictNavigation || UserAgents.sendsFetchMetadata(ua))) return Verdict.BOT;
        }

        if (UserAgents.isBot(ua)) return Verdict.BOT;
        return Verdict.COUNT;
    }

    private static boolean isPrefetch(Request req) {
        String purpose = req.header("Sec-Purpose");
        if (purpose == null) purpose = req.header("Purpose");
        if (purpose != null && purpose.toLowerCase(Locale.ROOT).contains("prefetch")) return true;
        String moz = req.header("X-Moz");
        return moz != null && moz.equalsIgnoreCase("prefetch");
    }

    private void enqueue(Request req, Route route) {
        Instant now = Instant.now();
        ZonedDateTime local = now.atZone(options.zone);
        LocalDate day = local.toLocalDate();
        if (buffered.incrementAndGet() > options.bufferLimit) {
            buffered.decrementAndGet();
            tally("dropped", day);
            return;
        }
        warnIfProxied(req);
        String ua = req.header("User-Agent");
        String host = normalizeHost(req.host());
        String path = route.analytics() == Track.ROUTE ? route.pattern() : storedPath(route, req.path());
        String country = null;
        if (options.countryHeader != null && req.fromTrustedProxy()) {
            country = countryCode(req.header(options.countryHeader));
        }
        buffer.add(new Event(now, day, local.getHour(), host, req.ip(), ua, path,
            source(req, host), UserAgents.device(ua, req.header("Sec-CH-UA-Mobile")),
            UserAgents.browser(ua), UserAgents.os(ua, req.header("Sec-CH-UA-Platform")), country));
    }

    /**
     * Behind a proxy that isn't in trustedProxies, req.ip() is the proxy's address, so every
     * visitor hashes alike and the site shows one visitor a day. Say so once.
     */
    private void warnIfProxied(Request req) {
        if (!req.fromTrustedProxy() && req.header("X-Forwarded-For") != null
                && warnedUntrustedProxy.compareAndSet(false, true)) {
            Log.warn("Analytics sees X-Forwarded-For from a peer that is not a trusted proxy, so it "
                + "counts every visitor at the proxy's address and unique visitors will read near 1. "
                + "Configure app.trustedProxies(...) — e.g. TrustedProxies.cloudflare() behind Cloudflare, "
                + "or \"127.0.0.1\" behind a local reverse proxy.");
        }
    }

    private void tally(String reason, LocalDate day) {
        rejects.computeIfAbsent(day, d -> new AtomicLongArray(REJECT_REASONS.size()))
            .incrementAndGet(REJECT_REASONS.indexOf(reason));
    }

    private LocalDate today() {
        return LocalDate.now(options.zone);
    }

    /**
     * The path to record for a view of {@code route}. A parameter whose <em>name</em> marks it as
     * sensitive ({@link Redactor#isSensitive}: token, secret, password, apikey, ...) keeps its
     * placeholder, so {@code /reset/abc123} is stored as {@code /reset/{token}}: those views are
     * still counted, grouped together, and the value is never written. Other segments go through
     * {@link #storedPath(String)}, which also redacts values that look like secrets.
     */
    static String storedPath(Route route, String rawPath) {
        if (route.paramNames().stream().noneMatch(Redactor::isSensitive)) return storedPath(rawPath);
        var pattern = segments(route.pattern());
        var actual = segments(rawPath);
        // The route matched this path, so the segment counts agree; if they somehow don't,
        // the pattern alone is the safe thing to store.
        if (pattern.size() != actual.size()) return route.pattern();
        var sb = new StringBuilder();
        for (int i = 0; i < pattern.size(); i++) {
            String p = pattern.get(i);
            boolean sensitive = p.startsWith("{") && p.endsWith("}") && Redactor.isSensitive(p.substring(1, p.length() - 1));
            sb.append('/').append(sensitive ? p : actual.get(i));
        }
        return storedPath(sb.isEmpty() ? "/" : sb.toString());
    }

    private static List<String> segments(String path) {
        var out = new ArrayList<String>();
        for (String s : path.split("/")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** Decoded, redacted, control-free path, capped to the column width. */
    static String storedPath(String rawPath) {
        String p = Redactor.redactPath(Route.decodePath(rawPath));
        var sb = new StringBuilder(Math.min(p.length(), 512));
        for (int i = 0; i < p.length() && sb.length() < 512; i++) {
            char c = p.charAt(i);
            if (c >= 0x20 && c != 0x7f) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Where the visitor came from: {@code utm_source} or {@code ref} when the link carried one,
     * otherwise the referring host. {@code null} means direct (or same-site navigation).
     */
    static String source(Request req, String ownHost) {
        String tagged = req.queryParam("utm_source");
        if (tagged == null) tagged = req.queryParam("ref");
        if (tagged != null) {
            var sb = new StringBuilder();
            for (char c : tagged.strip().toLowerCase(Locale.ROOT).toCharArray()) {
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_') {
                    sb.append(c);
                }
                if (sb.length() >= 64) break;
            }
            if (!sb.isEmpty()) return sb.toString();
        }
        String referer = req.header("Referer");
        if (referer == null || referer.isBlank()) return null;
        String host = normalizeHost(ProxyHeaders.hostOf(referer));
        if (host == null || host.equals(ownHost)) return null;
        return host.length() > 255 ? host.substring(0, 255) : host;
    }

    static String normalizeHost(String host) {
        if (host == null || host.isBlank()) return null;
        String h = Request.stripPort(host.strip()).toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        return h.isEmpty() ? null : h;
    }

    static String countryCode(String value) {
        if (value == null) return null;
        String v = value.strip().toUpperCase(Locale.ROOT);
        if (v.length() != 2 || v.equals("XX")) return null;
        if (!Character.isLetterOrDigit(v.charAt(0)) || !Character.isLetterOrDigit(v.charAt(1))) return null;
        return v;
    }

    // ---------------------------------------------------------------- storage

    /**
     * Write buffered views and rejection tallies, then drop expired salts. Serialized so the
     * periodic, shutdown and test-triggered flushes can't interleave. Returns the views written.
     */
    synchronized int flush() {
        var events = new ArrayList<Event>();
        Event e;
        while ((e = buffer.poll()) != null) {
            buffered.decrementAndGet();
            events.add(e);
        }
        var tallies = new LinkedHashMap<LocalDate, long[]>();
        for (var day : rejects.keySet()) {
            var counts = rejects.remove(day);
            if (counts == null) continue;
            long[] drained = new long[counts.length()];
            boolean any = false;
            for (int i = 0; i < drained.length; i++) {
                drained[i] = counts.getAndSet(i, 0);
                any |= drained[i] > 0;
            }
            if (any) tallies.put(day, drained);
        }
        LocalDate saltCutoff = Instant.now().minus(SALT_GRACE).atZone(options.zone).toLocalDate();
        if (events.isEmpty() && tallies.isEmpty()) {
            salts.keySet().removeIf(d -> d.isBefore(saltCutoff));
            return 0;
        }
        databaseFactory.withSession(db -> {
            if (!events.isEmpty()) insertEvents(db, events);
            for (var t : tallies.entrySet()) addTallies(db, t.getKey(), t.getValue());
            db.sql("DELETE FROM brace_analytics_salts WHERE view_date < ?", saltCutoff);
        });
        salts.keySet().removeIf(d -> d.isBefore(saltCutoff));
        cachedSummary = null;
        return events.size();
    }

    private void insertEvents(Database db, List<Event> events) {
        var digest = sha256();
        var ids = new long[events.size()];
        for (int i = 0; i < events.size(); i++) {
            var ev = events.get(i);
            ids[i] = visitorId(digest, salt(db, ev.day()), ev.host(), ev.ip(), ev.ua());
        }
        db.jdbc((Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO brace_analytics_pageviews "
                    + "(ts, view_date, view_hour, visitor, path, source, device, browser, os, country) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (int i = 0; i < events.size(); i++) {
                    var ev = events.get(i);
                    ps.setObject(1, OffsetDateTime.ofInstant(ev.ts(), ZoneOffset.UTC));
                    ps.setObject(2, ev.day());
                    ps.setInt(3, ev.hour());
                    ps.setLong(4, ids[i]);
                    ps.setString(5, ev.path());
                    setNullable(ps, 6, ev.source());
                    ps.setString(7, ev.device());
                    ps.setString(8, ev.browser());
                    ps.setString(9, ev.os());
                    setNullable(ps, 10, ev.country());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws java.sql.SQLException {
        if (value == null) ps.setNull(index, Types.VARCHAR);
        else ps.setString(index, value);
    }

    /**
     * Per-instance rows, so two instances never contend for one counter: each adds to its own
     * (day, reason, instance) row and readers sum across instances.
     */
    private void addTallies(Database db, LocalDate day, long[] counts) {
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            String reason = REJECT_REASONS.get(i);
            Long existing = db.sqlQueryLong("SELECT COUNT(*) FROM brace_analytics_rejects "
                + "WHERE view_date = ? AND reason = ? AND instance_id = ?", day, reason, instanceId);
            if (existing != null && existing > 0) {
                db.sql("UPDATE brace_analytics_rejects SET n = n + ? WHERE view_date = ? AND reason = ? AND instance_id = ?",
                    counts[i], day, reason, instanceId);
            } else {
                db.sql("INSERT INTO brace_analytics_rejects (view_date, reason, instance_id, n) VALUES (?, ?, ?, ?)",
                    day, reason, instanceId, counts[i]);
            }
        }
    }

    /** The day's salt: from memory, else the shared table, else a new one this instance creates. */
    private byte[] salt(Database db, LocalDate day) {
        byte[] cached = salts.get(day);
        if (cached != null) return cached;
        var rows = db.sqlQuery("SELECT salt FROM brace_analytics_salts WHERE view_date = ?", day);
        if (rows.isEmpty()) {
            byte[] fresh = new byte[32];
            random.nextBytes(fresh);
            String hex = HexFormat.of().formatHex(fresh);
            if (databaseFactory.isPostgres()) {
                db.sql("INSERT INTO brace_analytics_salts (view_date, salt) VALUES (?, ?) ON CONFLICT (view_date) DO NOTHING",
                    day, hex);
                rows = db.sqlQuery("SELECT salt FROM brace_analytics_salts WHERE view_date = ?", day);
            } else {
                // H2 is single-process and flush() is synchronized, so nothing can race this insert.
                db.sql("INSERT INTO brace_analytics_salts (view_date, salt) VALUES (?, ?)", day, hex);
                salts.put(day, fresh);
                return fresh;
            }
        }
        Object value = rows.getFirst()[0];
        byte[] salt = HexFormat.of().parseHex(String.valueOf(value));
        salts.put(day, salt);
        return salt;
    }

    static long visitorId(MessageDigest digest, byte[] salt, String host, String ip, String ua) {
        digest.reset();
        digest.update(salt);
        digest.update(bytes(host));
        digest.update((byte) 0);
        digest.update(bytes(ip));
        digest.update((byte) 0);
        digest.update(bytes(ua));
        byte[] h = digest.digest();
        long id = 0;
        for (int i = 0; i < 8; i++) id = (id << 8) | (h[i] & 0xff);
        return id;
    }

    private static byte[] bytes(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- daily rollup

    /**
     * Rows kept per breakdown per day in {@code brace_analytics_daily}. Long ranges read only the
     * top of each day, so a page that never makes a day's top 500 is missing from a 12-month list;
     * the totals row is always exact.
     */
    static final Map<String, Integer> ROLLUP_CAPS = Map.of(
        "path", 500, "source", 200, "device", 10, "browser", 50, "os", 50, "country", 250);

    /**
     * Summarize every completed day (before today, in the analytics timezone) that isn't in
     * {@code brace_analytics_daily} yet, oldest first. Each day is replaced whole, so a rerun is
     * harmless, and a missed night is caught up by the next run. Returns the number of days written.
     * Runs daily, once per fleet, before {@link #prune}.
     */
    int rollup(Database db) {
        LocalDate yesterday = today().minusDays(1);
        LocalDate through = rolledThrough(db);
        LocalDate start = through != null ? through.plusDays(1) : earliestRaw(db);
        if (start == null) return 0;
        int days = 0;
        for (LocalDate d = start; !d.isAfter(yesterday); d = d.plusDays(1)) {
            rollupDay(db, d);
            days++;
        }
        return days;
    }

    private static void rollupDay(Database db, LocalDate day) {
        db.sql("DELETE FROM brace_analytics_daily WHERE view_date = ?", day);
        var rows = new ArrayList<Object[]>();
        Object[] t = db.sqlQuery("SELECT COUNT(DISTINCT visitor), COUNT(*) FROM brace_analytics_pageviews "
            + "WHERE view_date = ?", day).getFirst();
        // Written even for a day with no views: the totals row marks the day as summarized.
        rows.add(new Object[] {"total", "", num(t[0]), num(t[1])});
        for (var dim : List.of("path", "source", "device", "browser", "os", "country")) {
            for (var r : rawBreakdown(db, dim, day, day, ROLLUP_CAPS.get(dim))) {
                rows.add(new Object[] {dim, r.key() == null ? "" : r.key(), r.visitors(), r.pageviews()});
            }
        }
        for (Object[] r : db.sqlQuery("SELECT reason, SUM(n) FROM brace_analytics_rejects WHERE view_date = ? "
                + "GROUP BY reason", day)) {
            rows.add(new Object[] {"notcounted", String.valueOf(r[0]), 0L, num(r[1])});
        }
        db.jdbc((Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO brace_analytics_daily "
                    + "(view_date, dim, dim_value, visitors, pageviews) VALUES (?, ?, ?, ?, ?)")) {
                for (Object[] r : rows) {
                    ps.setObject(1, day);
                    ps.setString(2, (String) r[0]);
                    ps.setString(3, (String) r[1]);
                    ps.setLong(4, (Long) r[2]);
                    ps.setLong(5, (Long) r[3]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /** The last summarized day, or null before the first rollup. */
    private static LocalDate rolledThrough(Database db) {
        return firstDate(db.sqlQuery("SELECT MAX(view_date) FROM brace_analytics_daily WHERE dim = 'total'"));
    }

    private static LocalDate earliestRaw(Database db) {
        LocalDate views = firstDate(db.sqlQuery("SELECT MIN(view_date) FROM brace_analytics_pageviews"));
        LocalDate rejected = firstDate(db.sqlQuery("SELECT MIN(view_date) FROM brace_analytics_rejects"));
        return min(views, rejected);
    }

    /**
     * Delete raw views and tallies past the retention window. Runs daily, once per fleet, after
     * {@link #rollup}. Never deletes a day that hasn't been summarized, so a failed rollup costs a
     * day of history only if it keeps failing past the retention window.
     */
    void prune(Database db) {
        LocalDate through = rolledThrough(db);
        if (through == null) return;
        LocalDate cutoff = today().minusDays(options.rawRetention.toDays());
        if (cutoff.isAfter(through.plusDays(1))) cutoff = through.plusDays(1);
        db.sql("DELETE FROM brace_analytics_pageviews WHERE view_date < ?", cutoff);
        db.sql("DELETE FROM brace_analytics_rejects WHERE view_date < ?", cutoff);
    }

    // ---------------------------------------------------------------- reading

    static final List<String> RANGES = List.of("today", "7d", "30d", "12mo");

    /** Today's visitors, pageviews and live count. Cached briefly: the ops dashboard polls every 5s. */
    Summary summary() {
        var cached = cachedSummary;
        if (cached != null && System.currentTimeMillis() - cachedSummaryAt < SUMMARY_TTL_MS) return cached;
        LocalDate today = today();
        var s = databaseFactory.withSession(db -> {
            var t = rawTotals(db, today, today, 23);
            return new Summary(t[0], t[1], live(db));
        });
        cachedSummary = s;
        cachedSummaryAt = System.currentTimeMillis();
        return s;
    }

    /**
     * Everything the dashboard and {@code brace analytics} show for one range. Days already in
     * {@code brace_analytics_daily} are read from it; later days (always today, plus any day the
     * nightly rollup hasn't reached) from the raw rows. Visitors add up across the two because a
     * visitor id never spans days.
     */
    Report report(String range) {
        if (!RANGES.contains(range)) throw new IllegalArgumentException("Unknown range: " + range);
        ZonedDateTime now = ZonedDateTime.now(options.zone);
        LocalDate to = now.toLocalDate();
        LocalDate from = switch (range) {
            case "7d" -> to.minusDays(6);
            case "30d" -> to.minusDays(29);
            case "12mo" -> to.withDayOfMonth(1).minusMonths(11);
            default -> to;
        };
        boolean today = range.equals("today");
        return databaseFactory.withSession(db -> {
            LocalDate through = rolledThrough(db);
            long[] cur = today ? rawTotals(db, to, to, 23) : totals(db, through, from, to);
            // "Today" compares with yesterday up to the same hour (from raw rows, which keep the
            // hour); the others with the period of the same length before. Only when stored data
            // reaches back to the start of that period: a period cut short by retention, or by
            // analytics having been on for less time, would read as a dramatic rise.
            LocalDate prevFrom = switch (range) {
                case "today" -> to.minusDays(1);
                case "12mo" -> from.minusMonths(12);
                default -> from.minusDays(java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1);
            };
            LocalDate earliest = today ? firstDate(db.sqlQuery("SELECT MIN(view_date) FROM brace_analytics_pageviews"))
                : min(earliestRaw(db), firstDate(db.sqlQuery("SELECT MIN(view_date) FROM brace_analytics_daily")));
            long[] prev = earliest == null || earliest.isAfter(prevFrom) ? null
                : today ? rawTotals(db, prevFrom, prevFrom, now.getHour())
                : totals(db, through, prevFrom, from.minusDays(1));
            List<Point> series = today ? hourly(db, to, now.getHour())
                : range.equals("12mo") ? monthly(daily(db, through, from, to))
                : daily(db, through, from, to);
            return new Report(range, options.zone.getId(), from, to, cur[0], cur[1],
                prev == null ? null : prev[0], prev == null ? null : prev[1],
                live(db), series,
                breakdown(db, through, "path", from, to, TOP_LIMIT),
                breakdown(db, through, "source", from, to, TOP_LIMIT),
                breakdown(db, through, "device", from, to, BREAKDOWN_LIMIT),
                breakdown(db, through, "browser", from, to, BREAKDOWN_LIMIT),
                breakdown(db, through, "os", from, to, BREAKDOWN_LIMIT),
                options.countryHeader == null ? List.of() : breakdown(db, through, "country", from, to, BREAKDOWN_LIMIT),
                notCounted(db, through, from, to));
        });
    }

    /**
     * The part of [from, to] covered by the rollup ({@code [0]}) and the part read raw ({@code [1]});
     * each is {@code {start, end}} or null when empty.
     */
    private static LocalDate[][] split(LocalDate through, LocalDate from, LocalDate to) {
        if (through == null || through.isBefore(from)) return new LocalDate[][] {null, {from, to}};
        if (!through.isBefore(to)) return new LocalDate[][] {{from, to}, null};
        return new LocalDate[][] {{from, through}, {through.plusDays(1), to}};
    }

    private static long[] totals(Database db, LocalDate through, LocalDate from, LocalDate to) {
        var parts = split(through, from, to);
        long[] out = new long[2];
        if (parts[0] != null) {
            Object[] r = db.sqlQuery("SELECT SUM(visitors), SUM(pageviews) FROM brace_analytics_daily "
                + "WHERE dim = 'total' AND view_date >= ? AND view_date <= ?", parts[0][0], parts[0][1]).getFirst();
            out[0] += num(r[0]);
            out[1] += num(r[1]);
        }
        if (parts[1] != null) {
            long[] raw = rawTotals(db, parts[1][0], parts[1][1], 23);
            out[0] += raw[0];
            out[1] += raw[1];
        }
        return out;
    }

    private static long[] rawTotals(Database db, LocalDate from, LocalDate to, int lastHour) {
        Object[] r = db.sqlQuery("SELECT COUNT(DISTINCT visitor), COUNT(*) FROM brace_analytics_pageviews "
            + "WHERE view_date >= ? AND view_date <= ? AND view_hour <= ?", from, to, lastHour).getFirst();
        return new long[] {num(r[0]), num(r[1])};
    }

    private long live(Database db) {
        Instant since = Instant.now().minus(LIVE_WINDOW);
        LocalDate sinceDay = since.atZone(options.zone).toLocalDate();
        Long n = db.sqlQueryLong("SELECT COUNT(DISTINCT visitor) FROM brace_analytics_pageviews "
            + "WHERE view_date >= ? AND ts >= ?", sinceDay, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
        return n == null ? 0 : n;
    }

    private static List<Point> hourly(Database db, LocalDate day, int lastHour) {
        var byHour = new long[24][2];
        for (Object[] r : db.sqlQuery("SELECT view_hour, COUNT(DISTINCT visitor), COUNT(*) FROM brace_analytics_pageviews "
                + "WHERE view_date = ? GROUP BY view_hour", day)) {
            int h = (int) num(r[0]);
            byHour[h][0] = num(r[1]);
            byHour[h][1] = num(r[2]);
        }
        var out = new ArrayList<Point>();
        for (int h = 0; h <= lastHour; h++) {
            out.add(new Point(String.format("%02d", h), byHour[h][0], byHour[h][1]));
        }
        return out;
    }

    private static List<Point> daily(Database db, LocalDate through, LocalDate from, LocalDate to) {
        var parts = split(through, from, to);
        var byDay = new LinkedHashMap<LocalDate, long[]>();
        if (parts[0] != null) {
            for (Object[] r : db.sqlQuery("SELECT view_date, visitors, pageviews FROM brace_analytics_daily "
                    + "WHERE dim = 'total' AND view_date >= ? AND view_date <= ?", parts[0][0], parts[0][1])) {
                byDay.put(toLocalDate(r[0]), new long[] {num(r[1]), num(r[2])});
            }
        }
        if (parts[1] != null) {
            for (Object[] r : db.sqlQuery("SELECT view_date, COUNT(DISTINCT visitor), COUNT(*) FROM brace_analytics_pageviews "
                    + "WHERE view_date >= ? AND view_date <= ? GROUP BY view_date", parts[1][0], parts[1][1])) {
                byDay.put(toLocalDate(r[0]), new long[] {num(r[1]), num(r[2])});
            }
        }
        var out = new ArrayList<Point>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long[] v = byDay.getOrDefault(d, new long[2]);
            out.add(new Point(d.toString(), v[0], v[1]));
        }
        return out;
    }

    /** Daily points summed into calendar months ({@code bucket} {@code "2026-10"}). */
    static List<Point> monthly(List<Point> days) {
        var byMonth = new LinkedHashMap<String, long[]>();
        for (var p : days) {
            long[] v = byMonth.computeIfAbsent(p.bucket().substring(0, 7), k -> new long[2]);
            v[0] += p.visitors();
            v[1] += p.pageviews();
        }
        var out = new ArrayList<Point>();
        byMonth.forEach((month, v) -> out.add(new Point(month, v[0], v[1])));
        return out;
    }

    /**
     * Top {@code limit} values of one breakdown over the range, merging the rollup and raw parts.
     * Each part is read deeper than {@code limit} so a value that ranks moderately in both still
     * surfaces after the merge.
     */
    private static List<Row> breakdown(Database db, LocalDate through, String dim, LocalDate from, LocalDate to, int limit) {
        var parts = split(through, from, to);
        var merged = new LinkedHashMap<String, long[]>();
        int depth = limit * 5;
        if (parts[0] != null) {
            for (Object[] r : db.sqlQuery("SELECT dim_value, SUM(visitors) AS v, SUM(pageviews) AS n "
                    + "FROM brace_analytics_daily WHERE dim = ? AND view_date >= ? AND view_date <= ? "
                    + "GROUP BY dim_value ORDER BY v DESC, n DESC LIMIT ?", dim, parts[0][0], parts[0][1], depth)) {
                String key = String.valueOf(r[0]);
                add(merged, key.isEmpty() ? null : key, num(r[1]), num(r[2]));
            }
        }
        if (parts[1] != null) {
            for (var r : rawBreakdown(db, dim, parts[1][0], parts[1][1], depth)) {
                add(merged, r.key(), r.visitors(), r.pageviews());
            }
        }
        return merged.entrySet().stream()
            .map(e -> new Row(e.getKey(), e.getValue()[0], e.getValue()[1]))
            .sorted((a, b) -> a.visitors() != b.visitors() ? Long.compare(b.visitors(), a.visitors())
                : Long.compare(b.pageviews(), a.pageviews()))
            .limit(limit)
            .toList();
    }

    private static void add(Map<String, long[]> merged, String key, long visitors, long pageviews) {
        // HashMap-style null key: LinkedHashMap allows it, and null means "direct" for sources.
        long[] v = merged.computeIfAbsent(key, k -> new long[2]);
        v[0] += visitors;
        v[1] += pageviews;
    }

    private static List<Row> rawBreakdown(Database db, String column, LocalDate from, LocalDate to, int limit) {
        // column is one of a fixed set of identifiers, never request input. A null source means
        // direct traffic and is a row of its own; for the other columns null means "unknown"
        // (no country header) and is left out.
        var out = new ArrayList<Row>();
        String notNull = column.equals("source") ? "" : " AND " + column + " IS NOT NULL";
        for (Object[] r : db.sqlQuery("SELECT " + column + ", COUNT(DISTINCT visitor) AS v, COUNT(*) AS n "
                + "FROM brace_analytics_pageviews WHERE view_date >= ? AND view_date <= ?" + notNull
                + " GROUP BY " + column + " ORDER BY v DESC, n DESC LIMIT ?", from, to, limit)) {
            out.add(new Row(r[0] == null ? null : String.valueOf(r[0]), num(r[1]), num(r[2])));
        }
        return out;
    }

    private static Map<String, Long> notCounted(Database db, LocalDate through, LocalDate from, LocalDate to) {
        var parts = split(through, from, to);
        var out = new LinkedHashMap<String, Long>();
        for (String reason : REJECT_REASONS) out.put(reason, 0L);
        if (parts[0] != null) {
            for (Object[] r : db.sqlQuery("SELECT dim_value, SUM(pageviews) FROM brace_analytics_daily "
                    + "WHERE dim = 'notcounted' AND view_date >= ? AND view_date <= ? GROUP BY dim_value",
                    parts[0][0], parts[0][1])) {
                out.merge(String.valueOf(r[0]), num(r[1]), Long::sum);
            }
        }
        if (parts[1] != null) {
            for (Object[] r : db.sqlQuery("SELECT reason, SUM(n) FROM brace_analytics_rejects "
                    + "WHERE view_date >= ? AND view_date <= ? GROUP BY reason", parts[1][0], parts[1][1])) {
                out.merge(String.valueOf(r[0]), num(r[1]), Long::sum);
            }
        }
        return out;
    }

    private static LocalDate firstDate(List<Object[]> rows) {
        Object v = rows.isEmpty() ? null : rows.getFirst()[0];
        return v == null ? null : toLocalDate(v);
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(o));
    }
}
