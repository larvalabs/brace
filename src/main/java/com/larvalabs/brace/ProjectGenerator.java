package com.larvalabs.brace;

import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;

public class ProjectGenerator {

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
    <artifactId>""" + name + """
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
            .ops("ops-authorized-keys");

        routes(app);

        app.start();
    }

    /**
     * All route registration lives here, separate from config and server
     * startup, so tests can wire the exact same routes:
     *   Brace.test().templates("views").start(App::routes)
     */
    public static void routes(Brace app) {
        var home = new HomeController();
        app.get("/", home::index).name(Routes.HOME);
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
                "db.url=jdbc:postgresql://localhost:5432/" + name + "\n" +
                "db.user=" + name + "\n" +
                "db.pass=\n" +
                "session.secret=" + sessionSecret + "\n" +
                "\n" +
                "%dev.port=9000\n" +
                "%dev.db.url=jdbc:h2:mem:dev;DB_CLOSE_DELAY=-1\n" +
                "%dev.db.user=\n" +
                "%dev.db.pass=\n");

            // application.conf.example with placeholder for documentation
            Files.writeString(root.resolve("application.conf.example"),
                "# Copy this file to application.conf and set real values, especially session.secret.\n" +
                "# Never commit application.conf with real secrets; use env vars in production:\n" +
                "#   SESSION_SECRET=<random-string> java -jar app.jar\n" +
                "port=8080\n" +
                "db.url=jdbc:postgresql://localhost:5432/" + name + "\n" +
                "db.user=" + name + "\n" +
                "db.pass=\n" +
                "session.secret=CHANGE-ME-to-a-random-string-at-least-32-chars\n" +
                "\n" +
                "%dev.port=9000\n" +
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

            // Dockerfile — target/app.jar is the shaded executable jar
            // (fixed name via <finalName>app</finalName>) and target/jte-classes the
            // precompiled templates; `mvn package` builds both. Precompiled templates are
            // what let the runtime image be a JRE (restores 213ac8c, lost in merge b8609b6).
            Files.writeString(root.resolve("Dockerfile"),
                "# Build first: mvn package (writes target/app.jar and target/jte-classes)\n" +
                "#\n" +
                "# A JRE image is enough: templates are precompiled at build time, so the JDK's\n" +
                "# compiler never runs in production.\n" +
                "FROM eclipse-temurin:25-jre\n" +
                "WORKDIR /app\n" +
                "COPY target/app.jar app.jar\n" +
                "COPY application.conf.example application.conf\n" +
                "# Prod mode loads these instead of compiling templates at runtime.\n" +
                "COPY target/jte-classes/ target/jte-classes/\n" +
                "COPY views/ views/\n" +
                "COPY public/ public/\n" +
                "COPY migrations/ migrations/\n" +
                "# Public keys only; App.java's .ops(...) refuses to start without this file.\n" +
                "COPY ops-authorized-keys ops-authorized-keys\n" +
                "EXPOSE 8080\n" +
                "# Pass secrets via env vars: docker run -e SESSION_SECRET=... -e DB_PASS=...\n" +
                "\n" +
                "# JVM flags; override at run time: docker run -e JAVA_OPTS=\"-Xmx1g\" ...\n" +
                "#   -XX:MaxRAMPercentage=50  caps the heap at half the container's memory limit,\n" +
                "#       leaving the rest for metaspace, thread stacks and direct buffers.\n" +
                "#       Give the container a limit (docker run --memory=1g, compose mem_limit):\n" +
                "#       without one the JVM sizes the heap from the HOST's RAM, so on a shared\n" +
                "#       box use an explicit -Xmx instead.\n" +
                "#   -XX:+UseCompactObjectHeaders  (JDK 25+) smaller object headers, usually\n" +
                "#       10-20% less heap for entity-heavy apps. Worth adding once tried under load.\n" +
                "ENV JAVA_OPTS=\"-XX:MaxRAMPercentage=50\"\n" +
                "# sh -c expands $JAVA_OPTS; exec replaces the shell so java is PID 1 and gets\n" +
                "# SIGTERM from `docker stop`, letting Brace's shutdown hook drain in-flight work.\n" +
                "# brace.mode=prod (as `brace run` sets) is what loads target/jte-classes.\n" +
                "ENTRYPOINT [\"sh\", \"-c\", \"exec java -Dbrace.mode=prod $JAVA_OPTS -jar app.jar\"]\n");

            // CLAUDE.md — capability index with pointers to full reference
            ClaudeMdGenerator.write(name, root.resolve("CLAUDE.md"));

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
