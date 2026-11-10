package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code brace ops keypair --label X} replaces the existing entry for X, whatever the shape of
 * that line under the server's keys-file grammar (scope token anywhere, trailing comment). If it
 * missed the line, the old key stayed trusted (L11 final review).
 */
class CliOpsRotationTest {

    @TempDir Path projectDir;

    @BeforeEach
    void scaffold() throws Exception {
        Files.createDirectories(projectDir.resolve("src/main/java"));
    }

    private String keypair(String... args) {
        var out = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            assertEquals(0, CliOps.keypair(projectDir, args));
        } finally {
            System.setOut(prev);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private void keys(String content) throws Exception {
        Files.writeString(projectDir.resolve("ops-authorized-keys"), content);
    }

    private String keys() throws Exception {
        return Files.readString(projectDir.resolve("ops-authorized-keys"));
    }

    @Test
    void anOldEntryWithATrailingCommentIsReplaced() throws Exception {
        keys("OLDKEY mylabel # laptop\n");
        String out = keypair("--label", "mylabel", "--control");
        assertTrue(out.contains("updated"), out);
        assertFalse(keys().contains("OLDKEY"), keys());
    }

    @Test
    void anOldReadOnlyEntryWithTheScopeAfterTheLabelIsReplaced() throws Exception {
        keys("OLDKEY mylabel scope:read\n");
        String out = keypair("--label", "mylabel", "--read-only");
        assertTrue(out.contains("updated"), out);
        assertFalse(keys().contains("OLDKEY"), keys());
    }

    @Test
    void entriesWithTheScopeBeforeOrWithoutOneAreTheSameLabel() throws Exception {
        for (String old : new String[]{"OLDKEY scope:read mylabel", "OLDKEY mylabel", "OLDKEY scope:control mylabel",
                "OLDKEY   mylabel   scope:read   # why", "OLDKEY mylabel#notacomment-so-a-different-label"}) {
            keys(old + "\n");
            Files.deleteIfExists(projectDir.resolve("ops-private.key"));
            String out = keypair("--label", "mylabel", "--control");
            boolean same = !old.contains("#notacomment");
            assertEquals(same, out.contains("updated"), old + " -> " + out);
            assertEquals(!same, keys().contains("OLDKEY"), old + " -> " + keys());
        }
    }

    @Test
    void anUnrelatedLabelWithTheSamePrefixIsUntouched() throws Exception {
        keys("KEY1 mylabel2\nKEY2 my\nKEY3 mylabel-laptop scope:read\nKEY4 other mylabel\nOLDKEY mylabel\n");
        keypair("--label", "mylabel", "--control");
        String after = keys();
        assertTrue(after.contains("KEY1 mylabel2"), after);
        assertTrue(after.contains("KEY2 my"), after);
        assertTrue(after.contains("KEY3 mylabel-laptop scope:read"), after);
        assertTrue(after.contains("KEY4 other mylabel"), after);
        assertFalse(after.contains("OLDKEY"), after);
    }

    @Test
    void everyEntryForTheLabelGoesNotJustTheFirst() throws Exception {
        keys("OLD1 mylabel\nKEEP other\nOLD2 mylabel scope:read # again\n");
        keypair("--label", "mylabel", "--read-only");
        String after = keys();
        assertFalse(after.contains("OLD1") || after.contains("OLD2"), after);
        assertTrue(after.contains("KEEP other"), after);
    }

    @Test
    void aCommentedOutLineIsUntouched() throws Exception {
        keys("# OLDKEY mylabel\n   # OLD2 mylabel\n");
        String out = keypair("--label", "mylabel");
        assertTrue(out.contains("added"), out);
        assertTrue(keys().contains("# OLDKEY mylabel"), keys());
        assertTrue(keys().contains("# OLD2 mylabel"), keys());
    }

    // ---- L1 verification: a rotation keeps the entry's scope unless a flag changes it ----

    private record Run(int exit, String out, String err) {}

    private Run run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int exit = CliOps.keypair(projectDir, args);
            return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
    }

    private void rotate() throws Exception {
        Files.delete(projectDir.resolve("ops-private.key"));
    }

    @Test
    void aReadOnlyKeyRotatedWithoutAFlagStaysReadOnly() throws Exception {
        assertEquals(0, run("--label", "agent", "--read-only").exit());
        rotate();
        var r = run("--label", "agent");
        assertEquals(0, r.exit(), r.err());
        assertTrue(keys().contains("scope:read  agent"), keys());
        assertFalse(keys().contains("scope:control  agent"), keys());
        assertFalse(r.out().contains("scope changed"), r.out());
    }

    @Test
    void aControlKeyRotatedWithoutAFlagStaysControl() throws Exception {
        assertEquals(0, run("--label", "me").exit());
        rotate();
        assertEquals(0, run("--label", "me").exit());
        assertTrue(keys().contains("scope:control  me"), keys());
    }

    @Test
    void raisingTheScopeTakesControlAndSaysSo() throws Exception {
        assertEquals(0, run("--label", "agent", "--read-only").exit());
        rotate();
        var r = run("--label", "agent", "--control");
        assertEquals(0, r.exit(), r.err());
        assertTrue(keys().contains("scope:control  agent"), keys());
        assertTrue(r.out().contains("scope changed: read -> control"), r.out());
    }

    @Test
    void loweringTheScopeSaysSoToo() throws Exception {
        assertEquals(0, run("--label", "me", "--control").exit());
        rotate();
        var r = run("--label", "me", "--read-only");
        assertEquals(0, r.exit(), r.err());
        assertTrue(keys().contains("scope:read  me"), keys());
        assertTrue(r.out().contains("scope changed: control -> read"), r.out());
    }

    @Test
    void anOldFormatEntryNeedsAnExplicitScopeAndNothingIsWrittenWithoutOne() throws Exception {
        keys("OLDKEY agent\n");
        var r = run("--label", "agent");
        assertNotEquals(0, r.exit());
        assertTrue(r.err().contains("--read-only") && r.err().contains("--control"), r.err());
        assertFalse(Files.exists(projectDir.resolve("ops-private.key")), "no key is generated");
        assertEquals("OLDKEY agent\n", keys());

        var ok = run("--label", "agent", "--read-only");
        assertEquals(0, ok.exit(), ok.err());
        assertTrue(keys().contains("scope:read  agent"), keys());
    }

    @Test
    void bothScopeFlagsAreRefused() {
        var r = run("--label", "x", "--read-only", "--control");
        assertNotEquals(0, r.exit());
        assertFalse(Files.exists(projectDir.resolve("ops-private.key")));
    }

    @Test
    void aNewLabelWithoutAFlagIsControlAsBefore() throws Exception {
        assertEquals(0, run("--label", "fresh").exit());
        assertTrue(keys().contains("scope:control  fresh"), keys());
    }

    @Test
    void aLabelWithAnUnpairedSurrogateIsRefusedBeforeAnyKeyIsWritten() {
        var r = run("--label", "agent\uD800x");
        assertNotEquals(0, r.exit());
        assertFalse(Files.exists(projectDir.resolve("ops-private.key")), "no orphan private key");
        assertFalse(Files.exists(projectDir.resolve("ops-authorized-keys")));
    }
}
