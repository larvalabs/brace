package com.larvalabs.brace;

import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;

public class ProjectGenerator {

    // M13: the scaffold's base images, pinned by digest (tag kept for readability; the digest is
    // what Docker uses). Refresh: docker buildx imagetools inspect <image>:<tag>.
    static final String MAVEN_IMAGE =
        "maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976";
    static final String RUNTIME_IMAGE =
        "gcr.io/distroless/java25-debian13:nonroot@sha256:5d3177252775d4b01603470fe21c015393019089904f68724bd37242e4bc6e6d";
    static final String POSTGRES_IMAGE =
        "postgres:17-alpine@sha256:266d3c257594e195b309ae9d1805e54c788f1d7b375f56bf6c367aac6c917e7b";

    /**
     * Generate a cryptographically random session secret (32+ bytes, base64url-encoded).
     * Used at scaffold time to replace the placeholder with a real value.
     */
    private static String generateSessionSecret() {
        var random = new SecureRandom();
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static void generate(String name) {
        try {
            var root = Path.of(name);

            // Validate project name: extract the last path component and check it
            // Prevents path traversal and pom.xml injection
            var projectName = root.getFileName().toString();
            if (!projectName.matches("[A-Za-z0-9_-]+")) {
                System.err.println("Failed to create project: name must contain only letters, numbers, underscores, and hyphens.");
                System.exit(1);
            }

            if (Files.exists(root)) {
                System.err.println("Failed to create project: " + root.toAbsolutePath() + " already exists.");
                System.exit(1);
            }

            // Create directories
            Files.createDirectories(root.resolve("src/main/java/app/controllers"));
            Files.createDirectories(root.resolve("src/test/java/app"));
            Files.createDirectories(root.resolve("migrations"));
            Files.createDirectories(root.resolve("views/layout"));
            Files.createDirectories(root.resolve("views/home"));
            Files.createDirectories(root.resolve("public/css"));

            // pom.xml — pin the brace dependency to whatever version is running
            // this generator (so `brace new` from a 0.1.5 install pins to 0.1.5).
            // Resolves Brace via JitPack so the project opens cleanly in IDEs and
            // plain Maven without requiring GitHub Packages auth.
            Files.writeString(root.resolve("pom.xml"), """
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>app</groupId>
    <artifactId>""" + projectName + """
</artifactId>
    <version>1.0-SNAPSHOT</version>
    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <brace.version>v""" + BraceVersion.get() + """
</brace.version>
    </properties>
    <repositories>
        <repository>
            <id>jitpack.io</id>
            <url>https://jitpack.io</url>
        </repository>
    </repositories>
    <dependencies>
        <dependency>
            <groupId>com.github.larvalabs</groupId>
            <artifactId>brace</artifactId>
            <version>${brace.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.11.4</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <!-- Fixed jar name so the Dockerfile can COPY target/app.jar deterministically. -->
        <finalName>app</finalName>
        <plugins>
            <!-- Without this pin, Maven's inherited Surefire 2.x silently ignores
                 JUnit 5 tests ("Tests run: 0" + BUILD SUCCESS). Do not remove. -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
            <!-- mvn package builds an executable fat jar (target/app.jar). The
                 transformers matter: Jetty/Hibernate register implementations via
                 META-INF/services (merged by ServicesResourceTransformer), and
                 several dependencies are multi-release jars. -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.6.0</version>
                <configuration>
                    <createDependencyReducedPom>false</createDependencyReducedPom>
                    <transformers>
                        <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                            <mainClass>app.App</mainClass>
                            <manifestEntries>
                                <Multi-Release>true</Multi-Release>
                            </manifestEntries>
                        </transformer>
                        <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                    </transformers>
                    <filters>
                        <filter>
                            <artifact>*:*</artifact>
                            <excludes>
                                <exclude>META-INF/*.SF</exclude>
                                <exclude>META-INF/*.DSA</exclude>
                                <exclude>META-INF/*.RSA</exclude>
                                <exclude>module-info.class</exclude>
                                <exclude>META-INF/versions/*/module-info.class</exclude>
                            </excludes>
                        </filter>
                    </filters>
                </configuration>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                    </execution>
                </executions>
            </plugin>
            <!-- mvn package also precompiles views/ into target/jte-classes, which the
                 Dockerfile copies and prod mode loads: no compiler at runtime (a JRE
                 image is enough), and the classes always match the jar being shipped.
                 It runs Brace's own precompiler, so its JTE version is always Brace's. -->
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
        </plugins>
    </build>
</project>
""");

            // Generate ops keypair
            var opsKeypair = OpsKeys.generateKeypair();
            Files.writeString(root.resolve("ops-authorized-keys"),
                "# Authorized public keys for ops dashboard access\n" +
                opsKeypair.publicKey() + " dev\n");
            SecretFiles.writeStringWithOwnerOnlyPermissions(root.resolve("ops-private.key"),
                "# Private key for ops dashboard access (do not commit)\n" +
                opsKeypair.privateKey() + "\n" +
                opsKeypair.publicKey() + "\n");

            // App.java
            Files.writeString(root.resolve("src/main/java/app/App.java"), """
package app;

import com.larvalabs.brace.*;
import app.controllers.HomeController;
import java.nio.file.Path;

public class App {
    public static void main(String[] args) throws Exception {
        var config = Config.load(Path.of("application.conf"),
            System.getProperty("brace.mode"));

        var db = new DatabaseFactory(
            config.get("db.url"), config.get("db.user"), config.get("db.pass"),
            java.util.List.of());

        var app = Brace.app()
            .port(config.getInt("port", 8080))
            .database(db)
            .templates("views")
            .sessions(config.get("session.secret"))
            .ops("ops-authorized-keys")
            .opsOrigins(config.get("ops.origins"));

        routes(app);

        app.start();
    }

    /**
     * All route registration lives here, separate from config and server
     * startup, so tests can wire the exact same routes:
     *   Brace.test().templates("views").start(App::routes)
     */
    public static void routes(Brace app) {
        // Every route needs an access requirement or .publicRoute(); start() refuses otherwise.
        // Once the app has a login, require it everywhere by default and mark the public pages:
        //   app.defaultAccess(Access.session("userId", "/login"));
        var home = new HomeController();
        app.get("/", home::index).name(Routes.HOME).publicRoute();
        // DB-backed routes use the typed registration methods, e.g.:
        //   app.getRead("/posts", posts::index).name(Routes.POSTS);   // read-only DB handler
        //   app.postDb("/posts", posts::create);                      // transactional DB handler
    }
}
""");

            // Routes.java — route-name constants for reverse routing
            Files.writeString(root.resolve("src/main/java/app/Routes.java"), """
package app;

/**
 * Route names, used at registration ({@code .name(Routes.HOME)}) and everywhere a link
 * is built ({@code Url.to(Routes.HOME)}, {@code Url.to(Routes.POST, post.id)}). Both sides
 * share the constant, so a route's path is defined exactly once and links never go stale.
 * Templates: {@code @import app.Routes} and {@code @import com.larvalabs.brace.Url}.
 */
public final class Routes {
    public static final String HOME = "home";
    // public static final String POSTS = "posts";        // Url.to(Routes.POSTS)         -> /posts
    // public static final String POST = "posts.show";    // Url.to(Routes.POST, id)      -> /posts/{id}

    private Routes() {}
}
""");

            // HomeController.java
            Files.writeString(root.resolve("src/main/java/app/controllers/HomeController.java"), """
package app.controllers;

import com.larvalabs.brace.*;

public class HomeController {
    public Result index(Request req) {
        return View.of("home/index", "title", "Welcome");
    }
}
""");

            // HomeControllerTest.java
            Files.writeString(root.resolve("src/test/java/app/HomeControllerTest.java"), """
package app;

import com.larvalabs.brace.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class HomeControllerTest {
    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test()
            .templates("views")
            // .entities(Post.class)  // register the entities your routes query
            .start(App::routes);      // same wiring as main() — no duplication
    }

    @AfterAll
    static void teardown() throws Exception { testApp.stop(); }

    @Test
    void indexReturnsHtml() {
        var response = testApp.get("/");
        assertEquals(200, response.status());
        assertTrue(response.body().contains("Welcome"));
    }

    /**
     * Factory methods for test entities — one per entity. Insert with:
     *   testApp.withDb(db -> db.insert(TestData.post("Hello")));
     */
    static class TestData {
        // static Post post(String title) {
        //     var p = new Post();
        //     p.title = title;
        //     return p;
        // }
    }
}
""");

            // application.conf with a real random session secret
            var sessionSecret = generateSessionSecret();
            Files.writeString(root.resolve("application.conf"),
                "port=8080\n" +
                "db.url=jdbc:postgresql://localhost:5432/" + projectName + "\n" +
                "db.user=" + projectName + "\n" +
                "db.pass=\n" +
                "session.secret=" + sessionSecret + "\n" +
                "# The app's public origin; brace ops CLI logins are signed for it.\n" +
                "ops.origins=http://localhost:8080\n" +
                "\n" +
                "%dev.port=9000\n" +
                "%dev.ops.origins=http://localhost:9000\n" +
                "%dev.db.url=jdbc:h2:mem:dev;DB_CLOSE_DELAY=-1\n" +
                "%dev.db.user=\n" +
                "%dev.db.pass=\n");

            // application.conf.example — committed, and the Dockerfile ships it as the
            // container's application.conf, so it holds no values that differ per deployment.
            // They come from env vars via ${VAR}: a literal in the file would beat the env var
            // (Config only falls back to the environment for absent keys), and a placeholder
            // secret would boot with a warning instead of failing — an unset SESSION_SECRET
            // fails startup instead.
            Files.writeString(root.resolve("application.conf.example"),
                "# Committed template for application.conf. The Dockerfile ships it as the\n" +
                "# container's application.conf, so per-deployment values and secrets come from\n" +
                "# environment variables via ${VAR}. Keep them that way: a literal value here wins\n" +
                "# over an environment variable of the same name.\n" +
                "#   docker run -e DATABASE_URL=postgresql://user:pass@host:5432/" + projectName + " \\\n" +
                "#              -e SESSION_SECRET=\"$(openssl rand -base64 32)\" ...\n" +
                "# SESSION_SECRET must stay the same across restarts and instances (changing it logs\n" +
                "# everyone out); generate it once and store it with your other secrets.\n" +
                "# Locally, `brace new` already wrote application.conf (gitignored) with a generated\n" +
                "# session.secret. On a fresh clone, copy this file to application.conf and either\n" +
                "# set the variables or replace the ${VAR}s with local values.\n" +
                "port=8080\n" +
                "db.url=${DATABASE_URL}\n" +
                "db.user=${DB_USER}\n" +
                "db.pass=${DB_PASS}\n" +
                "session.secret=${SESSION_SECRET}\n" +
                "# The app's public origin, e.g. https://app.example.com: brace ops CLI logins are\n" +
                "# signed for it, and prod refuses to start with ops on and none set.\n" +
                "ops.origins=${OPS_ORIGINS}\n" +
                "\n" +
                "%dev.port=9000\n" +
                "%dev.ops.origins=http://localhost:9000\n" +
                "%dev.db.url=jdbc:h2:mem:dev;DB_CLOSE_DELAY=-1\n" +
                "%dev.db.user=\n" +
                "%dev.db.pass=\n");

            // V1__initial.sql
            Files.writeString(root.resolve("migrations/V1__initial.sql"), """
-- Initial schema
-- Add your tables here
""");

            // views/layout/main.jte
            Files.writeString(root.resolve("views/layout/main.jte"), """
@param String title
@param gg.jte.Content content

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${title}</title>
    <link rel="stylesheet" href="/public/css/style.css">
</head>
<body>
    <main>
        ${content}
    </main>
</body>
</html>
""");

            // views/home/index.jte
            Files.writeString(root.resolve("views/home/index.jte"), """
@import app.Routes
@import com.larvalabs.brace.Url
@param String title

@template.layout.main(title = title, content = @`
    <h1>${title}</h1>
    <p>Your Brace app is running. <a href="${Url.to(Routes.HOME)}">Home</a></p>
`)
""");

            // public/css/style.css
            Files.writeString(root.resolve("public/css/style.css"), """
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: system-ui, sans-serif; line-height: 1.6; max-width: 800px; margin: 0 auto; padding: 2rem; }
h1 { margin-bottom: 1rem; }
""");

            // Dockerfile (M13): builds in a JDK stage, runs on a distroless JRE as a non-root user.
            // Both base images are pinned by digest; .github/dependabot.yml below refreshes them.
            // The build stage runs `mvn package`, which also precompiles views/ into
            // target/jte-classes, so prod mode needs no compiler and the image builds from a
            // clean checkout (a platform building from git has no target/).
            Files.writeString(root.resolve("Dockerfile"),
                "# docker build -t " + projectName + " .\n" +
                "# The image builds from a clean checkout: the first stage runs `mvn package`.\n" +
                "#\n" +
                "# Both base images are pinned by digest, so a rebuild can't silently pick up a\n" +
                "# different image. .github/dependabot.yml proposes updates; to refresh by hand:\n" +
                "#   docker buildx imagetools inspect <image>:<tag>   (use the top-level Digest)\n" +
                "\n" +
                "FROM " + MAVEN_IMAGE + " AS build\n" +
                "WORKDIR /src\n" +
                "# Dependencies first, so a code change doesn't download them again.\n" +
                "COPY pom.xml .\n" +
                "RUN mvn -B -q dependency:go-offline\n" +
                "COPY src/ src/\n" +
                "COPY views/ views/\n" +
                "# Tests run in CI and locally, not in the image build.\n" +
                "RUN mvn -B -q package -DskipTests\n" +
                "\n" +
                "# Distroless Java 25: a JRE and nothing else (no shell, no package manager), running\n" +
                "# as the unprivileged \"nonroot\" user (uid 65532). The app's files are owned by root,\n" +
                "# so the process can read but not modify them.\n" +
                "FROM " + RUNTIME_IMAGE + "\n" +
                "WORKDIR /app\n" +
                "COPY --from=build /src/target/app.jar app.jar\n" +
                "# Precompiled templates, which prod mode loads instead of compiling at runtime.\n" +
                "COPY --from=build /src/target/jte-classes/ target/jte-classes/\n" +
                "COPY application.conf.example application.conf\n" +
                "COPY views/ views/\n" +
                "COPY public/ public/\n" +
                "COPY migrations/ migrations/\n" +
                "# Public keys only; App.java's .ops(...) refuses to start without this file.\n" +
                "COPY ops-authorized-keys ops-authorized-keys\n" +
                "USER 65532:65532\n" +
                "EXPOSE 8080\n" +
                "# Config comes from env vars (see application.conf.example and compose.yaml):\n" +
                "#   docker run -e DATABASE_URL=postgresql://user:pass@host:5432/db -e SESSION_SECRET=... \\\n" +
                "#              -e OPS_ORIGINS=https://app.example.com ...\n" +
                "\n" +
                "# JVM flags. The JVM reads JAVA_TOOL_OPTIONS itself, so no shell is needed to pass\n" +
                "# them; override at run time: docker run -e JAVA_TOOL_OPTIONS=\"-Xmx1g\" ...\n" +
                "#   -XX:MaxRAMPercentage=50  caps the heap at half the container's memory limit,\n" +
                "#       leaving the rest for metaspace, thread stacks and direct buffers.\n" +
                "#       Give the container a limit (docker run --memory=1g, compose mem_limit):\n" +
                "#       without one the JVM sizes the heap from the HOST's RAM, so on a shared\n" +
                "#       box use an explicit -Xmx instead.\n" +
                "#   -XX:+UseCompactObjectHeaders  (JDK 25+) smaller object headers, usually\n" +
                "#       10-20% less heap for entity-heavy apps. Worth adding once tried under load.\n" +
                "ENV JAVA_TOOL_OPTIONS=\"-XX:MaxRAMPercentage=50\"\n" +
                "# Polls GET /__brace/health, Brace's built-in liveness route. There is no curl in\n" +
                "# the image, so a small JVM makes the request; its flags keep it cheap.\n" +
                "HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \\\n" +
                "    CMD [\"java\", \"-XX:TieredStopAtLevel=1\", \"-XX:+UseSerialGC\", \"-Xmx32m\", \\\n" +
                "         \"-cp\", \"app.jar\", \"com.larvalabs.brace.HealthCheck\", \"8080\"]\n" +
                "# Exec form: java is PID 1 and gets SIGTERM from `docker stop`, so Brace's\n" +
                "# shutdown hook drains in-flight work. brace.mode=prod (as `brace run` sets) is\n" +
                "# what loads target/jte-classes.\n" +
                "ENTRYPOINT [\"java\", \"-Dbrace.mode=prod\", \"-jar\", \"app.jar\"]\n");

            // .dockerignore (M13): keeps local secrets and build output out of the build context.
            // application.conf holds the dev session secret and *.key the ops private key; the
            // image's config is application.conf.example, which reads its values from env vars.
            Files.writeString(root.resolve(".dockerignore"), """
# Secrets: the image reads its config from env vars (application.conf.example).
application.conf
*.key
.env
.env.*
# Build output and local state: the image builds its own.
target/
lib/
jte-classes/
.git/
.idea/
*.iml
.DS_Store
""");

            // compose.yaml (M13): a reference deployment with the app container locked down.
            Files.writeString(root.resolve("compose.yaml"),
                "# Reference deployment: the app and Postgres, with the app container locked down.\n" +
                "# Secrets come from the environment, e.g. a .env file next to this one (gitignored)\n" +
                "# or your platform's env settings. Never commit them.\n" +
                "#   SESSION_SECRET=   openssl rand -base64 32; keep it stable across deploys\n" +
                "#   POSTGRES_PASSWORD=openssl rand -base64 24\n" +
                "#   OPS_ORIGINS=      the app's public origin, e.g. https://app.example.com\n" +
                "#   TRUSTED_PROXIES=  the reverse proxy's address or network CIDR, e.g. the\n" +
                "#                     Traefik network's subnet (docker network inspect <net>)\n" +
                "#   docker compose up -d --build\n" +
                "services:\n" +
                "  app:\n" +
                "    build: .\n" +
                "    restart: unless-stopped\n" +
                "    environment:\n" +
                "      DATABASE_URL: postgresql://" + projectName + ":${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD}@db:5432/" + projectName + "\n" +
                "      SESSION_SECRET: ${SESSION_SECRET:?set SESSION_SECRET}\n" +
                "      OPS_ORIGINS: ${OPS_ORIGINS:?set OPS_ORIGINS}\n" +
                "      TRUSTED_PROXIES: ${TRUSTED_PROXIES:-}\n" +
                "    # Loopback only: put a TLS-terminating reverse proxy in front. Behind Traefik\n" +
                "    # (Dokploy), drop this and join the proxy's network instead.\n" +
                "    ports:\n" +
                "      - \"127.0.0.1:8080:8080\"\n" +
                "    # The image is immutable at runtime; /tmp (uploads spill there) is the only\n" +
                "    # writable path, in memory.\n" +
                "    read_only: true\n" +
                "    tmpfs:\n" +
                "      - /tmp\n" +
                "    cap_drop: [ALL]\n" +
                "    security_opt:\n" +
                "      - no-new-privileges:true\n" +
                "    mem_limit: 1g\n" +
                "    networks: [web, db]\n" +
                "    depends_on:\n" +
                "      db:\n" +
                "        condition: service_healthy\n" +
                "  db:\n" +
                "    image: " + POSTGRES_IMAGE + "\n" +
                "    restart: unless-stopped\n" +
                "    environment:\n" +
                "      POSTGRES_DB: " + projectName + "\n" +
                "      POSTGRES_USER: " + projectName + "\n" +
                "      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD}\n" +
                "    volumes:\n" +
                "      - pgdata:/var/lib/postgresql/data\n" +
                "    healthcheck:\n" +
                "      test: [\"CMD-SHELL\", \"pg_isready -U " + projectName + " -d " + projectName + "\"]\n" +
                "      interval: 5s\n" +
                "      retries: 10\n" +
                "    security_opt:\n" +
                "      - no-new-privileges:true\n" +
                "    # No ports: Postgres is reachable only from the app, over the internal network.\n" +
                "    networks: [db]\n" +
                "networks:\n" +
                "  web: {}\n" +
                "  # internal: no route out, and nothing outside the project can reach it.\n" +
                "  db:\n" +
                "    internal: true\n" +
                "volumes:\n" +
                "  pgdata: {}\n");

            // .github/dependabot.yml (M13, decision D7): proposes updates to the digest-pinned base
            // images. Brace itself is upgraded with its migration guides, not by a bot.
            Files.createDirectories(root.resolve(".github"));
            Files.writeString(root.resolve(".github/dependabot.yml"), """
version: 2
updates:
  # The Dockerfile's and compose.yaml's base images are pinned by digest; these keep them current.
  - package-ecosystem: docker
    directory: /
    schedule:
      interval: weekly
  - package-ecosystem: docker-compose
    directory: /
    schedule:
      interval: weekly
""");

            // CLAUDE.md — capability index with pointers to full reference
            ClaudeMdGenerator.write(projectName, root.resolve("CLAUDE.md"));

            // BRACE-AGENTS.md (full API reference) and BRACE-OPS.md (ops reference) —
            // the same bundled resources `brace agents-md` refreshes, loaded through
            // CliAgentsMd's constants and reader (UTF-8) so the entry paths and the
            // generator can't drift apart and silently stop shipping a doc.
            String agentsMd = CliAgentsMd.loadBundled(CliAgentsMd.JAR_ENTRY);
            if (agentsMd != null) {
                Files.writeString(root.resolve("BRACE-AGENTS.md"), agentsMd);
            }
            String opsMd = CliAgentsMd.loadBundled(CliAgentsMd.OPS_JAR_ENTRY);
            if (opsMd != null) {
                Files.writeString(root.resolve(CliAgentsMd.OPS_FILE), opsMd);
            }

            // .gitignore
            Files.writeString(root.resolve(".gitignore"), """
target/
lib/
jte-classes/
*.class
.idea/
*.iml
.DS_Store
*.key
application.conf
.env
.env.*
""");

            System.out.println("Created new Brace project: " + name);
            System.out.println("  cd " + name);
            // No `brace deps` needed first: the scaffold's only runtime dependency is
            // brace itself, which the toolchain lib dir already provides.
            System.out.println("  brace dev");
        } catch (IOException e) {
            System.err.println("Failed to create project: " + e.getMessage());
            System.exit(1);
        }
    }
}
