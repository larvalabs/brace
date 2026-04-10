package io.brace;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeMdGeneratorTest {

    @Test
    void generateIncludesBuildCommands() {
        var md = ClaudeMdGenerator.generate("myapp");
        assertTrue(md.contains("./brace dev"));
        assertTrue(md.contains("./brace test"));
    }

    @Test
    void generateIncludesProjectName() {
        var md = ClaudeMdGenerator.generate("myapp");
        assertTrue(md.contains("# myapp"));
    }

    @Test
    void generateIncludesCapabilities() {
        var md = ClaudeMdGenerator.generate("myapp");
        assertTrue(md.contains("Brace Capabilities"));
        assertTrue(md.contains("Routing"));
        assertTrue(md.contains("Database"));
        assertTrue(md.contains("Ops"));
    }

    @Test
    void generateMentionsBrace() {
        var md = ClaudeMdGenerator.generate("myapp");
        assertTrue(md.contains("Brace"));
        assertTrue(md.contains("main()"));
    }

    @Test
    void writesToFile() throws Exception {
        var path = Path.of("target/test-CLAUDE.md");
        ClaudeMdGenerator.write("myapp", path);
        assertTrue(Files.exists(path));
        var content = Files.readString(path);
        assertTrue(content.contains("# myapp"));
        assertTrue(content.contains("Brace"));
        Files.deleteIfExists(path);
    }
}
