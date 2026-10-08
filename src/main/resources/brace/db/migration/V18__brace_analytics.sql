-- Server-side page-view analytics (0.1.11), enabled with app.analytics().
-- Design: docs/2026-10-07-brace-analytics.md.
--
-- Base tier (runs on H2 + Postgres): collection and the /ops/analytics queries run on both.
-- IF NOT EXISTS for upgrade-safety, consistent with the other framework migrations.

-- One row per counted page view. No IP or user agent is stored: visitor is a 64-bit hash of a
-- daily salt (below), the host, the IP and the UA, unlinkable once that day's salt is deleted.
-- view_date/view_hour are the calendar day and hour in the app's configured analytics timezone,
-- computed at write time so grouping needs no dialect-specific date functions. (Not day/hour:
-- DAY and HOUR are reserved words in H2.)
CREATE TABLE IF NOT EXISTS brace_analytics_pageviews (
    ts        TIMESTAMP WITH TIME ZONE NOT NULL,
    view_date DATE         NOT NULL,
    view_hour SMALLINT     NOT NULL,
    visitor   BIGINT       NOT NULL,
    path      VARCHAR(512) NOT NULL,
    source    VARCHAR(255),
    device    VARCHAR(16),
    browser   VARCHAR(32),
    os        VARCHAR(32),
    country   VARCHAR(2)
);
CREATE INDEX IF NOT EXISTS idx_brace_analytics_pageviews_day ON brace_analytics_pageviews (view_date);

-- Requests that looked like page views but were filtered (bots, htmx partials, prefetches, ...),
-- shown on the dashboard so the counted numbers can be judged. Each instance adds to its own rows,
-- so a fleet never contends on one counter; readers sum across instance_id.
CREATE TABLE IF NOT EXISTS brace_analytics_rejects (
    view_date   DATE         NOT NULL,
    reason      VARCHAR(32)  NOT NULL,
    instance_id VARCHAR(128) NOT NULL,
    n           BIGINT       NOT NULL,
    PRIMARY KEY (view_date, reason, instance_id)
);

-- One row per day per breakdown value, written by the nightly rollup for each completed day and
-- kept indefinitely (raw rows above are pruned). dim is total | path | source | device | browser |
-- os | country | notcounted. dim_value is '' for total and for direct traffic (a NULL source);
-- for notcounted it is the filter reason and pageviews holds the count. Each breakdown keeps the
-- day's top values only (see Analytics.ROLLUP_CAPS); the total row is exact.
CREATE TABLE IF NOT EXISTS brace_analytics_daily (
    view_date DATE         NOT NULL,
    dim       VARCHAR(16)  NOT NULL,
    dim_value VARCHAR(512) NOT NULL,
    visitors  BIGINT       NOT NULL,
    pageviews BIGINT       NOT NULL,
    PRIMARY KEY (view_date, dim, dim_value)
);

-- The day's random salt for visitor hashing, shared by every instance. Hex-encoded (portable
-- across H2 and Postgres). Rows are deleted a few minutes after their day ends.
CREATE TABLE IF NOT EXISTS brace_analytics_salts (
    view_date DATE        PRIMARY KEY,
    salt      VARCHAR(64) NOT NULL
);
