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
    void generateIncludesNewCapabilityEntries() {
        var md = ClaudeMdGenerator.generate("myapp");
        // M9: previously omitted capabilities now indexed
        assertTrue(md.contains("Http.get"), "Http client entry missing");
        assertTrue(md.contains("fetchJson"), "Http fetchJson missing");
        assertTrue(md.contains(".bearer(token)"), "Http bearer missing");
        assertTrue(md.contains("bodyJson"), "Http bodyJson missing");
        assertTrue(md.contains("Assets.url"), "Assets fingerprinting entry missing");
        assertTrue(md.contains("Url.to(\"/users/{id}\", 42)"), "Url.to entry missing");
        assertTrue(md.contains("Log.debug/info/error"), "Log levels missing");
        assertTrue(md.contains("Log.event"), "Log.event missing");
        assertTrue(md.contains("Redirect.toLocal"), "Redirect.toLocal entry missing");
        // This branch's additions reflected in existing entries
        assertTrue(md.contains(".findOr404()"), "db.findOr404 missing");
        assertTrue(md.contains(".queryOneOr404()"), "db.queryOneOr404 missing");
        assertTrue(md.contains("req.jsonForm(Class)"), "req.jsonForm missing");
        assertTrue(md.contains("Json.obj"), "Json.obj missing");
        assertTrue(md.contains("app.getRead("), "typed getRead route method missing");
        assertTrue(md.contains("getReadFull"), "typed getReadFull route method missing");
    }

    @Test
    void generateHasNoStaleFacts() {
        var md = ClaudeMdGenerator.generate("myapp");
        assertTrue(md.contains("github.com/larvalabs/brace"), "repo link should point at larvalabs");
        assertFalse(md.contains("github.com/matth/brace"), "stale repo link present");
        assertTrue(md.contains("POST/PUT/DELETE/PATCH"), "CSRF line should include PATCH");
        assertFalse(md.replace("POST/PUT/DELETE/PATCH", "").contains("POST/PUT/DELETE"),
                "CSRF method list without PATCH present");
    }

    @Test
    void generateHasSingleOpsSection() {
        var md = ClaudeMdGenerator.generate("myapp");
        assertFalse(md.contains("Production ops (for agents)"), "duplicate ops section present");
        long opsHeadings = md.lines().filter(l -> l.startsWith("## ") && l.toLowerCase().contains("ops")).count();
        assertEquals(1, opsHeadings, "expected exactly one ops section heading");
        // Merged section keeps both tables, plus the previously missing rows
        assertTrue(md.contains("`brace check`"), "brace check row missing");
        assertTrue(md.contains("/ops/logs"), "/ops/logs endpoint row missing");
        assertTrue(md.contains("GET /ops/cache"), "/ops/cache endpoint row missing");
        assertTrue(md.contains("/ops/regressions"), "/ops/regressions endpoint row missing");
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
            assertTrue(content.contains("BRACE-OPS.md"), content);
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
