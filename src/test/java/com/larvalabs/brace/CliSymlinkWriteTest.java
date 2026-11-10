package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** A repo can commit symlinks at the files the CLI writes; the CLI must not write through them. */
class CliSymlinkWriteTest {

    @TempDir Path project;
    @TempDir Path outside;
    Path victim;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(project.resolve("src/main/java"));
        victim = outside.resolve("victim");
        Files.writeString(victim, "ORIGINAL");
    }

    private void link(String name, Path target) throws IOException {
        try {
            Files.createSymbolicLink(project.resolve(name), target);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("filesystem cannot create symlinks: " + e);
        }
    }

    private void assertVictimUntouched() throws IOException {
        assertEquals("ORIGINAL", Files.readString(victim));
    }

    @Test
    void agentsMdRefusesSymlinkedBraceAgents() throws Exception {
        link("BRACE-AGENTS.md", victim);
        assertThrows(IOException.class, () -> CliAgentsMd.run(project, new String[]{}));
        assertVictimUntouched();
    }

    @Test
    void agentsMdRefusesSymlinkedOpsFileBeforeWritingAnything() throws Exception {
        link(CliAgentsMd.OPS_FILE, victim);
        assertThrows(IOException.class, () -> CliAgentsMd.run(project, new String[]{}));
        assertVictimUntouched();
        assertFalse(Files.exists(project.resolve("BRACE-AGENTS.md")));
    }

    @Test
    void agentsMdRefusesDanglingSymlink() throws Exception {
        Path missing = outside.resolve("not-there");
        link("BRACE-AGENTS.md", missing);
        assertThrows(IOException.class, () -> CliAgentsMd.run(project, new String[]{}));
        assertFalse(Files.exists(missing));
    }

    @Test
    void initLeavesLiveSymlinkedBraceLocalAlone() throws Exception {
        link(".brace.local", victim);
        CliInit.run(project);   // existing file (via link): reported present, never rewritten
        assertVictimUntouched();
    }

    @Test
    void initRefusesDanglingBraceSymlink() throws Exception {
        Path missing = outside.resolve("dangling");
        link(".brace", missing);
        assertThrows(IOException.class, () -> CliInit.run(project));
        assertFalse(Files.exists(missing), "init must not create the link's target");
    }

    @Test
    void initDoesNotAppendToSymlinkedGitignore() throws Exception {
        link(".gitignore", victim);
        assertThrows(IOException.class, () -> CliInit.run(project));
        assertVictimUntouched();
    }

    @Test
    void keypairRefusesSymlinkedAuthorizedKeysAndGeneratesNothing() throws Exception {
        link("ops-authorized-keys", victim);
        assertEquals(1, CliOps.keypair(project, new String[]{"--label", "x"}));
        assertVictimUntouched();
        assertFalse(Files.exists(project.resolve("ops-private.key")));
    }

    @Test
    void keypairReplacesSymlinkedPrivateKeyWithoutTouchingTarget() throws Exception {
        link("ops-private.key", outside.resolve("dangling-key"));
        assertEquals(0, CliOps.keypair(project, new String[]{"--label", "x"}));
        assertFalse(Files.exists(outside.resolve("dangling-key")));
        assertFalse(Files.isSymbolicLink(project.resolve("ops-private.key")));
    }

    @Test
    void normalOperationUnchanged() throws Exception {
        assertEquals(0, CliAgentsMd.run(project, new String[]{}));
        assertTrue(Files.isRegularFile(project.resolve("BRACE-AGENTS.md")));
        CliInit.run(project);
        assertTrue(Files.readString(project.resolve(".gitignore")).contains(".brace.local"));
        assertEquals(0, CliOps.keypair(project, new String[]{"--label", "x"}));
        assertTrue(Files.isRegularFile(project.resolve("ops-authorized-keys")));
    }
}
