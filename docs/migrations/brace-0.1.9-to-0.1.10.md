# Migrating from Brace 0.1.9 → 0.1.10

<!-- In progress. Each workstream fills in only its own section below. The intro, the
breaking-change summary and the Index table are written at integration time, from the
sections. -->

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|

---

<!-- section: correctness -->
## Correctness fixes

_No entries yet._

<!-- end section: correctness -->

---

<!-- section: streaming-io -->
## Streaming uploads and responses

_No entries yet._

<!-- end section: streaming-io -->

---

<!-- section: proxies -->
## Trusted proxies

_No entries yet._

<!-- end section: proxies -->

---

<!-- section: http-streaming -->
## Streaming in the `Http` client

_No entries yet._

<!-- end section: http-streaming -->

---

<!-- section: ops-dashboard -->
## Ops dashboard

_No entries yet._

<!-- end section: ops-dashboard -->

---

<!-- section: ops-jvm-cli -->
## GC pause figures and CLI

_No entries yet._

<!-- end section: ops-jvm-cli -->

---

<!-- section: dx -->
## New projects and custom metrics

### Fix: scaffolded `Dockerfile` (precompiled templates on a JRE, `JAVA_OPTS`, heap cap)

**What changed.** 0.1.7 changed the scaffolded `Dockerfile` to precompile templates and run
in prod mode on `eclipse-temurin:25-jre`, but a merge dropped that change before release, so
0.1.7 through 0.1.9 still scaffolded `FROM eclipse-temurin:21-jre` with `java -jar app.jar`.
That image fails on the first rendered page, because JTE then compiles templates with
`javac` and a JRE doesn't include it. `brace new` now writes a `Dockerfile` that:

- runs on `eclipse-temurin:25-jre`, copies `target/jte-classes/`, and runs with
  `-Dbrace.mode=prod`, so templates load precompiled and no compiler is needed;
- runs `exec java -Dbrace.mode=prod $JAVA_OPTS -jar app.jar` through `sh -c`, so JVM flags can
  be set per deployment and `java` is PID 1 (it gets `docker stop`'s SIGTERM and Brace's
  shutdown hook runs);
- defaults `JAVA_OPTS` to `-XX:MaxRAMPercentage=50`. Without a heap flag the JVM sizes its heap
  from the host's RAM when the container has no memory limit;
- copies `ops-authorized-keys`, which the scaffold's `main()` fails to start without.

The scaffolded `pom.xml` also precompiles `views/` into `target/jte-classes` during
`mvn package`, so the Dockerfile's single build step always ships classes that match the jar.
`brace compile` writes the same directory.

Forgetting the precompile step now fails at startup with a message naming `brace compile`: in
prod mode on a JRE with no matching precompiled classes, `app.templates(...)` throws
`IllegalStateException` instead of failing inside JTE's compiler. On a JDK, prod mode still
compiles all templates at startup as before.

**Who needs to act.** Existing projects keep the `Dockerfile` and `pom.xml` they were generated
with; nothing regenerates them. If your Dockerfile still says `eclipse-temurin:21-jre` or
`CMD ["java", "-jar", "app.jar"]`, update it by hand:

1. Precompile, copy the classes and run in prod mode as described in
   [Deploying with Docker](brace-0.1.6-to-0.1.7.md#deploying-with-docker-or-any-non-cli-launch)
   in the 0.1.6 → 0.1.7 guide. Precompile as part of `mvn package` (below) rather than as a
   separate manual step: stale `target/jte-classes` from an earlier build would be served as-is.
2. Replace the `CMD` with the `JAVA_OPTS` entrypoint, and copy `ops-authorized-keys` if
   `main()` calls `.ops(...)` (never copy `ops-private.key`).

**Before (0.1.9 scaffold):**

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/app.jar app.jar
COPY application.conf.example application.conf
COPY views/ views/
COPY public/ public/
COPY migrations/ migrations/
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

**After (0.1.10 scaffold):**

```dockerfile
# Build first: mvn package (writes target/app.jar and target/jte-classes)
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/app.jar app.jar
COPY application.conf.example application.conf
COPY target/jte-classes/ target/jte-classes/
COPY views/ views/
COPY public/ public/
COPY migrations/ migrations/
COPY ops-authorized-keys ops-authorized-keys
EXPOSE 8080
# Heap cap as a share of the container's memory limit. Run with a limit (docker run
# --memory=1g); without one, use an explicit -Xmx. On JDK 25 consider adding
# -XX:+UseCompactObjectHeaders (usually 10-20% less heap for entity-heavy apps).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50"
ENTRYPOINT ["sh", "-c", "exec java -Dbrace.mode=prod $JAVA_OPTS -jar app.jar"]
```

**Add to `pom.xml`**, inside `<plugins>` after `maven-shade-plugin`:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.5.0</version>
    <executions>
        <execution>
            <id>precompile-templates</id>
            <phase>package</phase>
            <goals><goal>exec</goal></goals>
            <configuration>
                <executable>${java.home}/bin/java</executable>
                <arguments>
                    <argument>-cp</argument>
                    <classpath/>
                    <argument>com.larvalabs.brace.TemplatePrecompiler</argument>
                    <argument>views</argument>
                    <argument>target/jte-classes</argument>
                </arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Keep the `exec` in the entrypoint. Without it `sh` stays PID 1, does not forward SIGTERM, and
the container is killed after the stop timeout without a clean shutdown. Prod mode also applies
`%prod.` config keys, so check for any your container wasn't using before.

---

### Fix: scaffolded container config reads secrets from the environment

**What changed.** The scaffolded `Dockerfile` copies `application.conf.example` into the image as
`application.conf`. That file used to hold a placeholder `session.secret` and literal database
settings. `Config` only falls back to an environment variable when a key is *absent* from the
file, so `docker run -e SESSION_SECRET=... -e DB_PASS=...` (as the Dockerfile suggested) was
ignored: every container signed sessions with the public placeholder, and only logged a "weak
secret" warning. `brace new` now writes the example with `${VAR}` references, so the container
takes them from the environment and fails to start when `SESSION_SECRET` is unset.

The local `application.conf` (gitignored, with a generated secret) is unchanged.

**Who needs to act.** Projects scaffolded before 0.1.10 whose Docker image copies
`application.conf.example`: your containers are running on the placeholder secret unless you
edited the file. Change the per-deployment keys to `${VAR}` references, set the variables in
your deploy platform, and redeploy. Changing the secret logs everyone out once.

**Before (0.1.9 `application.conf.example`):**

```properties
port=8080
db.url=jdbc:postgresql://localhost:5432/myapp
db.user=myapp
db.pass=
session.secret=CHANGE-ME-to-a-random-string-at-least-32-chars
```

**After (0.1.10):**

```properties
port=8080
db.url=${DATABASE_URL}
db.user=${DB_USER}
db.pass=${DB_PASS}
session.secret=${SESSION_SECRET}
```

`DATABASE_URL` may be a JDBC URL or a PaaS-style `postgresql://user:pass@host:5432/db`
(credentials embedded in it are used when `DB_USER`/`DB_PASS` are unset). Generate the secret
once, for example `openssl rand -base64 32`, and keep it identical across instances and restarts.

---

### New (optional): static custom metrics with `Metrics`

**What changed.** `Metrics.counter(...)`, `Metrics.gauge(...)` and `Metrics.timer(...)` are
static, like `Log`, so a service can record a metric without being handed the app's `Stats`.
They record into the same `Stats` that `app.stats()` returns: the most recently constructed
app's. Metrics recorded before `Brace.app()` runs (for example a gauge registered in a service
constructor earlier in `main()`) are kept and adopted by the first app.

**Who needs to act.** Nobody. `app.stats()` and its `counter`/`gauge`/`timer` methods are
unchanged. If you thread `app.stats()` into services only to record metrics, you can drop that
plumbing. Keep using `app.stats()` in tests that read values (`counterTotal(name)`) or that run
several apps in one JVM.

There is no static `Stats.counter(...)`; older docs showed it, but it never compiled.

**Before (0.1.9):**

```java
// main()
var weather = new WeatherClient(http).withStats(app.stats());

// WeatherClient
private Stats stats;
public WeatherClient withStats(Stats stats) { this.stats = stats; return this; }
void fetch() { ...; if (stats != null) stats.counter("weather.calls"); }
```

**After (0.1.10):**

```java
// main()
var weather = new WeatherClient(http);

// WeatherClient
void fetch() { ...; Metrics.counter("weather.calls"); }
```

---

### Docs: java.time values in JSON responses

**What changed.** Documentation only; `Json` already behaved this way. `BRACE-AGENTS.md` and the
`CLAUDE.md` that `brace new` writes now say: put `LocalDateTime`/`LocalDate`/`Instant` values
into the returned record or `Json.obj(...)` and let `Json` serialize them as ISO-8601. Don't
call `.toString()` on them: `LocalDateTime.toString()` drops zero seconds (`2025-06-15T09:00`
instead of `2025-06-15T09:00:00`), which strict ISO-8601 consumers reject.

**Who needs to act.** Nobody. `brace agents-md` picks up the `BRACE-AGENTS.md` change. Existing
projects' `CLAUDE.md` is not regenerated; to give agents the hint there too, add this to its
Responses line: "Put java.time values in as objects (`Json` writes ISO-8601); never
`.toString()` them."

<!-- end section: dx -->
