package io.brace;

import java.io.IOException;
import java.nio.file.*;

public class ProjectGenerator {

    public static void generate(String name) {
        try {
            var root = Path.of(name);

            // Create directories
            Files.createDirectories(root.resolve("src/main/java/app/controllers"));
            Files.createDirectories(root.resolve("src/test/java/app"));
            Files.createDirectories(root.resolve("migrations"));
            Files.createDirectories(root.resolve("views/layout"));
            Files.createDirectories(root.resolve("views/home"));
            Files.createDirectories(root.resolve("public/css"));

            // pom.xml
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
        <brace.version>0.1.0-SNAPSHOT</brace.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>io.brace</groupId>
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
</project>
""");

            // App.java
            Files.writeString(root.resolve("src/main/java/app/App.java"), """
package app;

import io.brace.*;
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
            .ops(config.get("ops.secret"));

        var home = new HomeController();
        app.get("/", home::index);

        app.start();
    }
}
""");

            // HomeController.java
            Files.writeString(root.resolve("src/main/java/app/controllers/HomeController.java"), """
package app.controllers;

import io.brace.*;

public class HomeController {
    public Result index(Request req) {
        return View.of("home/index", "title", "Welcome");
    }
}
""");

            // HomeControllerTest.java
            Files.writeString(root.resolve("src/test/java/app/HomeControllerTest.java"), """
package app;

import io.brace.*;
import app.controllers.HomeController;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class HomeControllerTest {
    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test()
            .templates("views")
            .start(app -> {
                var home = new HomeController();
                app.get("/", home::index);
            });
    }

    @AfterAll
    static void teardown() throws Exception { testApp.stop(); }

    @Test
    void indexReturnsHtml() {
        var response = testApp.get("/");
        assertEquals(200, response.status());
        assertTrue(response.body().contains("Welcome"));
    }
}
""");

            // application.conf
            Files.writeString(root.resolve("application.conf"),
                "port=8080\n" +
                "db.url=jdbc:postgresql://localhost:5432/" + name + "\n" +
                "db.user=" + name + "\n" +
                "db.pass=\n" +
                "session.secret=CHANGE-ME-to-a-random-string-at-least-32-chars\n" +
                "ops.secret=CHANGE-ME-ops-secret\n" +
                "\n" +
                "%dev.port=9000\n" +
                "%dev.db.url=jdbc:h2:mem:dev\n" +
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

            // Dockerfile
            Files.writeString(root.resolve("Dockerfile"),
                "FROM eclipse-temurin:21-jre\n" +
                "WORKDIR /app\n" +
                "COPY target/*.jar app.jar\n" +
                "COPY application.conf .\n" +
                "COPY views/ views/\n" +
                "COPY public/ public/\n" +
                "COPY migrations/ migrations/\n" +
                "EXPOSE 8080\n" +
                "CMD [\"java\", \"-jar\", \"app.jar\"]\n");

            // CLAUDE.md
            Files.writeString(root.resolve("CLAUDE.md"),
                "# " + name + "\n" +
                "\n" +
                "Built with Brace framework.\n" +
                "\n" +
                "## Routes\n" +
                "- GET / → HomeController::index\n" +
                "\n" +
                "## Running\n" +
                "- Dev: `mvn compile exec:java -Dexec.mainClass=app.App -Dbrace.mode=dev`\n" +
                "- Prod: `java -jar target/" + name + ".jar`\n");

            // .gitignore
            Files.writeString(root.resolve(".gitignore"), """
target/
jte-classes/
*.class
.idea/
*.iml
.DS_Store
""");

            System.out.println("Created new Brace project: " + name);
            System.out.println("  cd " + name);
            System.out.println("  mvn compile exec:java -Dexec.mainClass=app.App -Dbrace.mode=dev");
        } catch (IOException e) {
            System.err.println("Failed to create project: " + e.getMessage());
            System.exit(1);
        }
    }
}
