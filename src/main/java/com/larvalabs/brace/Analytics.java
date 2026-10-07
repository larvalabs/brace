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
    private static final long FLUSH_INTERVAL_MS = 10_000;
    private static final long SUMMARY_TTL_MS = 15_000;

    public static Options options() {
        return new Options();
    }

    /**
     * How a route's views are recorded. {@code PATH} (default) records the concrete path,
     * {@code ROUTE} records the route pattern, {@code OFF} records nothing.
     */
    public enum Track { PATH, ROUTE, OFF }

    /** Covers the 30-day view plus the 30 days before it, which the comparison needs. */
    static final int DEFAULT_RETENTION_DAYS = 60;

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
         * How long raw page views are kept, e.g. {@code "90d"}. Default {@code "60d"}: the 30-day view
         * compares against the 30 days before it, so anything under 60 days drops that comparison.
         * Minimum 1 day.
         */
        public Options rawRetention(String duration) {
            this.rawRetention = Duration.ofMillis(Math.max(JobScheduler.parseInterval(duration),
                Duration.ofDays(1).toMillis()));
            return this;
        }

        /**
         * Require {@code Sec-Fetch-Mode: navigate} on every counted view. Removes nearly all scripted
         * traffic, at the cost of browsers too old to send fetch metadata (pre-2023 Safari).
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
        if (!htmxNavigation) {
            String mode = req.header("Sec-Fetch-Mode");
            if (mode != null && !mode.equals("navigate")) return Verdict.BACKGROUND;
            if (mode == null && options.strictNavigation) return Verdict.BOT;
        }

        if (UserAgents.isBot(req.header("User-Agent"))) return Verdict.BOT;
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
        String path = route.analytics() == Track.ROUTE ? route.pattern() : storedPath(req.path());
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

    /** Delete raw views and tallies past the retention window. Runs daily, once per fleet. */
    void prune(Database db) {
        LocalDate cutoff = today().minusDays(options.rawRetention.toDays());
        db.sql("DELETE FROM brace_analytics_pageviews WHERE view_date < ?", cutoff);
        db.sql("DELETE FROM brace_analytics_rejects WHERE view_date < ?", cutoff);
    }

    // ---------------------------------------------------------------- reading

    static final List<String> RANGES = List.of("today", "7d", "30d");

    /** Today's visitors, pageviews and live count. Cached briefly: the ops dashboard polls every 5s. */
    Summary summary() {
        var cached = cachedSummary;
        if (cached != null && System.currentTimeMillis() - cachedSummaryAt < SUMMARY_TTL_MS) return cached;
        LocalDate today = today();
        var s = databaseFactory.withSession(db -> {
            var t = totals(db, today, today, 23);
            return new Summary(t[0], t[1], live(db));
        });
        cachedSummary = s;
        cachedSummaryAt = System.currentTimeMillis();
        return s;
    }

    /** Everything the dashboard and {@code brace analytics} show for one range. */
    Report report(String range) {
        if (!RANGES.contains(range)) throw new IllegalArgumentException("Unknown range: " + range);
        ZonedDateTime now = ZonedDateTime.now(options.zone);
        LocalDate to = now.toLocalDate();
        int days = switch (range) {
            case "7d" -> 7;
            case "30d" -> 30;
            default -> 1;
        };
        LocalDate from = to.minusDays(days - 1);
        return databaseFactory.withSession(db -> {
            long[] cur = totals(db, from, to, 23);
            // "Today" compares with yesterday up to the same hour; the others with the period before.
            // Only when stored data reaches back to the start of that period: a previous period cut
            // short by retention, or by analytics having been on for less time, would read as a
            // dramatic rise. Null then, rather than a misleading number.
            LocalDate prevFrom = days == 1 ? from.minusDays(1) : from.minusDays(days);
            LocalDate earliest = earliestDay(db);
            long[] prev = earliest == null || earliest.isAfter(prevFrom) ? null
                : days == 1 ? totals(db, prevFrom, prevFrom, now.getHour())
                : totals(db, prevFrom, from.minusDays(1), 23);
            List<Point> series = days == 1 ? hourly(db, to, now.getHour()) : daily(db, from, to);
            return new Report(range, options.zone.getId(), from, to, cur[0], cur[1],
                prev == null ? null : prev[0], prev == null ? null : prev[1],
                live(db), series,
                breakdown(db, "path", from, to, TOP_LIMIT),
                breakdown(db, "source", from, to, TOP_LIMIT),
                breakdown(db, "device", from, to, BREAKDOWN_LIMIT),
                breakdown(db, "browser", from, to, BREAKDOWN_LIMIT),
                breakdown(db, "os", from, to, BREAKDOWN_LIMIT),
                options.countryHeader == null ? List.of() : breakdown(db, "country", from, to, BREAKDOWN_LIMIT),
                notCounted(db, from, to));
        });
    }

    private static LocalDate earliestDay(Database db) {
        var rows = db.sqlQuery("SELECT MIN(view_date) FROM brace_analytics_pageviews");
        Object v = rows.isEmpty() ? null : rows.getFirst()[0];
        return v == null ? null : toLocalDate(v);
    }

    private static long[] totals(Database db, LocalDate from, LocalDate to, int lastHour) {
        var rows = db.sqlQuery("SELECT COUNT(DISTINCT visitor), COUNT(*) FROM brace_analytics_pageviews "
            + "WHERE view_date >= ? AND view_date <= ? AND view_hour <= ?", from, to, lastHour);
        Object[] r = rows.getFirst();
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

    private static List<Point> daily(Database db, LocalDate from, LocalDate to) {
        var byDay = new LinkedHashMap<LocalDate, long[]>();
        for (Object[] r : db.sqlQuery("SELECT view_date, COUNT(DISTINCT visitor), COUNT(*) FROM brace_analytics_pageviews "
                + "WHERE view_date >= ? AND view_date <= ? GROUP BY view_date", from, to)) {
            byDay.put(toLocalDate(r[0]), new long[] {num(r[1]), num(r[2])});
        }
        var out = new ArrayList<Point>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long[] v = byDay.getOrDefault(d, new long[2]);
            out.add(new Point(d.toString(), v[0], v[1]));
        }
        return out;
    }

    private static List<Row> breakdown(Database db, String column, LocalDate from, LocalDate to, int limit) {
        // column is one of a fixed set of identifiers chosen above, never request input.
        var out = new ArrayList<Row>();
        for (Object[] r : db.sqlQuery("SELECT " + column + ", COUNT(DISTINCT visitor) AS v, COUNT(*) AS n "
                + "FROM brace_analytics_pageviews WHERE view_date >= ? AND view_date <= ? "
                + "GROUP BY " + column + " ORDER BY v DESC, n DESC LIMIT ?", from, to, limit)) {
            out.add(new Row(r[0] == null ? null : String.valueOf(r[0]), num(r[1]), num(r[2])));
        }
        return out;
    }

    private static Map<String, Long> notCounted(Database db, LocalDate from, LocalDate to) {
        var out = new LinkedHashMap<String, Long>();
        for (String reason : REJECT_REASONS) out.put(reason, 0L);
        for (Object[] r : db.sqlQuery("SELECT reason, SUM(n) FROM brace_analytics_rejects "
                + "WHERE view_date >= ? AND view_date <= ? GROUP BY reason", from, to)) {
            out.put(String.valueOf(r[0]), num(r[1]));
        }
        return out;
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
