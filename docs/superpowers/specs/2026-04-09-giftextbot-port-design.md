# GifTextBot — Play 1 to Brace Port

## Overview

Port [giftextbot](../../../) (a Telegram bot that puts text on GIFs/MP4s) from Play Framework 1.7.1 to Brace. This serves as a real-world sanity check on whether Brace has a complete feature set for a production app.

The current app is ~2,200 lines of Java across 17 source files with significant code quality issues (hardcoded secrets, resource leaks, duplicated bot classes, bundled GIF library). The Brace version will be a clean rewrite targeting ~500 lines.

## Scope

**In scope:**
- Single Brace app, no database, no sessions, no web UI
- Single Telegram bot class (prod/dev via config)
- Giphy search via `Http`, cached via `Cache`
- Text overlay rendering (Impact font, dynamic sizing, outline)
- ffmpeg MP4 composition with parallel download + text render
- HTTP endpoints to serve generated MP4s and thumbnails to Telegram
- Temp file cleanup via `JobScheduler`
- Custom metrics (renders, searches, ffmpeg timing)
- Ops dashboard for monitoring
- All secrets and settings via `Config`
- Docker deployment with ffmpeg

**Out of scope:**
- Slack integration (no longer used)
- Web UI (was only for Slack OAuth landing page)
- Database / persistence
- Java GIF encoder/decoder library (standardize on ffmpeg)
- Speech bubble processor (meme text only)

**Framework gap surfaced:** `Jobs.run()` / `Jobs.submit()` for simple async background tasks (non-scheduled, non-durable). Added to Brace TODO.

## Architecture

Single Brace app with two roles:

1. **Telegram bot** — long-polling thread that receives messages/inline queries, searches Giphy, and replies with MP4 URLs pointing back at this server
2. **HTTP server** — serves generated MP4s when Telegram fetches them, plus the Brace ops dashboard

No database, no sessions. The bot starts after `app.start()` via a startup callback — the Telegram bot library manages its own long-polling thread.

### Brace features used

| Feature | Usage |
|---|---|
| `Config` | All secrets and settings (bot token, Giphy key, base URL, pool sizes) |
| `Http` | Giphy API calls |
| `Cache` | Giphy search results with TTL |
| `JobScheduler` | Temp file cleanup (recurring) |
| `Stats` + custom metrics | Render counts, search counts, ffmpeg timing |
| Ops dashboard | Production monitoring |

## Components

| File | Purpose |
|---|---|
| `Main.java` | App setup, routes, bot registration, jobs |
| `TelegramBot.java` | Single bot class — commands, inline queries, query parsing |
| `GiphyClient.java` | Giphy search via `Http`, results cached via `Cache` |
| `TextRenderer.java` | Renders text overlay PNG (Impact font, sizing, outline) |
| `FfmpegRenderer.java` | Downloads MP4, composites with text overlay via ffmpeg, outputs MP4 |
| `RenderController.java` | HTTP endpoints that serve generated MP4s and thumbnails |

## Data Flow

### Inline query (primary use case)

```
User types: @txtgifbot hello.world
  -> TelegramBot receives inline query
  -> Parses "hello" (search) and "world" (caption)
  -> GiphyClient.search("hello") -- hits Cache, falls back to Http + Giphy API
  -> Returns list of results with URLs pointing to this server:
       https://{baseUrl}/render/mp4?gif={url}&text=world
  -> Telegram fetches that URL when user selects a result
  -> RenderController receives request
  -> In parallel (CompletableFuture on managed ExecutorService):
       1. Download source MP4
       2. Render text overlay PNG via TextRenderer
  -> Both complete -> ffmpeg composites MP4 + overlay -> temp file
  -> Stream MP4 back as response
```

### Thumbnail generation

Same flow but ffmpeg outputs a single frame (JPEG) or 4-frame grid for Telegram's inline result preview.

## Parallelism

The render pipeline maximizes responsiveness by running work in parallel, matching the current app's approach:

- **Download pool** — downloads source MP4s from Giphy
- **Text render pool** — renders text overlay PNGs (CPU-bound but fast)
- **ffmpeg pool** — runs ffmpeg composition (CPU-heavy, configurable size)

Download and text render run concurrently via `CompletableFuture.supplyAsync()` on shared `ExecutorService` pools. Once both complete, ffmpeg composites the result. Pool sizes are configurable via `Config`.

This uses app-managed `ExecutorService` pools rather than Brace's `JobScheduler` because the render pipeline needs multiple specialized pools with different sizes and fan-out-and-join semantics within a single request.

## Text Rendering

Port of the current `MemeText` processor:

- Load Impact.ttf font from `resources/fonts/`
- Dynamic font sizing: start at 160pt, shrink until text fits within 80% width and 20% height
- Minimum font size: 10pt
- Position text at bottom of image with 10px margin
- White text with black outline (2px on all sides)
- Output: PNG with transparent background, same dimensions as source

## Configuration

All via `Config` (file + env var override):

| Key | Purpose | Example |
|---|---|---|
| `bot.token` | Telegram bot token | `305133551:AAE...` |
| `bot.name` | Bot username | `txtgifbot` |
| `giphy.key` | Giphy API key | `UKgTz...` |
| `app.baseUrl` | Public URL for render callbacks | `https://gifmsgbot.larvalabs.com` |
| `render.threads` | ffmpeg pool size | `4` |
| `render.tmpDir` | Temp directory for renders | `/tmp/giftextbot` |

Dev vs prod via mode prefixes (`%prod.bot.token`, `%dev.bot.token`).

## Temp File Cleanup

`JobScheduler.every("5m", ...)` scans the render temp directory and deletes files older than 1 hour. This replaces the current app's approach of never cleaning up temp files.

## Error Handling

- **ffmpeg failures** (bad input, timeout): return 500, log via `Log`, increment `Stats.counter("ffmpeg.errors")`
- **Giphy API failures**: return empty results to the bot, log the error
- **Download failures** (MP4 URL dead): return 500, log
- **Telegram API errors**: caught and logged by the bot library, bot keeps polling

No retry logic — if a render fails, Telegram shows a broken result and the user retries. Keeps it simple.

## Custom Metrics

- `Stats.counter("renders")` — total render requests
- `Stats.counter("giphy.searches")` — Giphy API calls (cache misses)
- `Stats.counter("ffmpeg.errors")` — failed renders
- `Stats.timer("ffmpeg.render", ms)` — ffmpeg execution time

Visible in ops dashboard with sparklines and in `/ops/status` JSON.

## Testing

- **TextRenderer** — unit test: produces PNG of expected dimensions, text fits within bounds
- **GiphyClient** — unit test: mocked HTTP responses (don't hit real Giphy in tests)
- **FfmpegRenderer** — integration test: runs ffmpeg on a small test MP4 (skipped if ffmpeg not on PATH)
- **RenderController** — `TestApp`-based tests: HTTP endpoints return MP4 content type, non-empty body
- **TelegramBot** — unit test: query parsing logic (splitting search/caption on "." and "/")

No database means no migrations, no test DB setup.

## Dependency Management

The app depends on Brace, which is not yet on Maven Central.

**Approach:** Commit the Brace JAR to the project (e.g., `lib/brace-0.1.0-SNAPSHOT.jar`) as a system-scoped Maven dependency. Brace's transitive dependencies are listed explicitly in the app's `pom.xml`.

**App dependencies (in pom.xml):**

| Dependency | Why |
|---|---|
| `lib/brace-0.1.0-SNAPSHOT.jar` | Brace framework (system scope) |
| `org.eclipse.jetty:jetty-server` | HTTP server (Brace runtime dep) |
| `com.fasterxml.jackson.core:jackson-databind` | JSON (Brace runtime dep) |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | Date/time JSON support (Brace runtime dep) |
| `gg.jte:jte` | Templates (Brace runtime dep — needed even without views for ops dashboard) |
| `org.telegram:telegrambots` | Telegram bot API |
| `org.junit.jupiter:junit-jupiter` | Tests (test scope) |

**Not needed** (no database in this project): Hibernate, HikariCP, Flyway, H2, jBCrypt, Jakarta Mail.

> **Note:** Once Brace is published to Maven Central, replace the system-scoped JAR and explicit Brace dependencies with a single Maven dependency:
> ```xml
> <dependency>
>     <groupId>io.brace</groupId>
>     <artifactId>brace</artifactId>
>     <version>${brace.version}</version>
> </dependency>
> ```
> This will pull in all transitive dependencies automatically. At that point, remove from `pom.xml`: `jetty-server`, `jackson-databind`, `jackson-datatype-jsr310`, `jte`, and the `lib/brace-*.jar` system dependency.

## Deployment

**Dockerfile** (multi-stage):

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/giftextbot.jar /app/app.jar
WORKDIR /app
EXPOSE 9000
CMD ["java", "-jar", "app.jar"]
```

Fonts bundled in the JAR via `src/main/resources/fonts/`. ffmpeg is the only external runtime dependency.

Dokploy configuration via `.dokploy.json`, same as the current app.
