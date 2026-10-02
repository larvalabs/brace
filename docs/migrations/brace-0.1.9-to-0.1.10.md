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

<!-- end section: dx -->
