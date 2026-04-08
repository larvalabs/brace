package io.brace;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeMdGeneratorTest {

    static Brace app;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0);
        app.get("/", req -> Result.text("home"));
        app.start();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    @Test
    void generateIncludesBuildCommands() {
        var md = ClaudeMdGenerator.generate(app);
        assertTrue(md.contains("./brace dev"));
        assertTrue(md.contains("./brace test"));
    }

    @Test
    void generateMentionsBrace() {
        var md = ClaudeMdGenerator.generate(app);
        assertTrue(md.contains("Brace"));
        assertTrue(md.contains("main()"));
    }

    @Test
    void writesToFile() throws Exception {
        var path = Path.of("target/test-CLAUDE.md");
        app.generateClaudeMd(path);
        assertTrue(Files.exists(path));
        var content = Files.readString(path);
        assertTrue(content.contains("Brace"));
        Files.deleteIfExists(path);
    }
}
