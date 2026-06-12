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
        app.get("/", home::index);
        // DB-backed routes use the typed registration methods, e.g.:
        //   app.getRead("/posts", posts::index);   // read-only DB handler
        //   app.postDb("/posts", posts::create);   // transactional DB handler
    }
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
@param String title

@template.layout.main(title = title, content = @`
    <h1>${title}</h1>
    <p>Your Brace app is running.</p>
`)
""");

            // public/css/style.css
            Files.writeString(root.resolve("public/css/style.css"), """
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: system-ui, sans-serif; line-height: 1.6; max-width: 800px; margin: 0 auto; padding: 2rem; }
h1 { margin-bottom: 1rem; }
""");

            // Dockerfile — target/app.jar is the shaded executable jar
            // (fixed name via <finalName>app</finalName>); build it first
            // with `mvn package`.
            Files.writeString(root.resolve("Dockerfile"),
                "# Build the jar first: mvn package\n" +
                "FROM eclipse-temurin:21-jre\n" +
                "WORKDIR /app\n" +
                "COPY target/app.jar app.jar\n" +
                "COPY application.conf.example application.conf\n" +
                "COPY views/ views/\n" +
                "COPY public/ public/\n" +
                "COPY migrations/ migrations/\n" +
                "EXPOSE 8080\n" +
                "# Pass secrets via env vars: docker run -e SESSION_SECRET=... -e DB_PASS=...\n" +
                "CMD [\"java\", \"-jar\", \"app.jar\"]\n");

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
