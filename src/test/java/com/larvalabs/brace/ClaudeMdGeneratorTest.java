package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeMdGeneratorTest {

    @Test
    void generateIncludesBuildCommands() {
        var md = ClaudeMdGenerator.generate("myapp");
        assertTrue(md.contains("brace dev"));
        assertTrue(md.contains("brace test"));
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
    void generatedClaudeMdMentionsOpsCommands() throws Exception {
        Path tmp = Files.createTempDirectory("claudemd-");
        try {
            ClaudeMdGenerator.generate("DemoApp", tmp);
            String content = Files.readString(tmp.resolve("CLAUDE.md"));
            assertTrue(content.contains("brace status"), content);
            assertTrue(content.contains("brace errors"), content);
            assertTrue(content.contains("brace logs"), content);
            assertTrue(content.contains("agent-ops-guide.md"), content);
        } finally {
            Files.walk(tmp).sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
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
