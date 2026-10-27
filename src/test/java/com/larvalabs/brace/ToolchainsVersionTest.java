package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolchainsVersionTest {

    @Test
    void acceptsReleaseAndSnapshotVersions() {
        for (String v : new String[] {"0.1.10", "1.2.3", "10.20.30", "0.1.11-SNAPSHOT", "1.0.0-rc.1"}) {
            assertTrue(Toolchains.isValidVersion(v), v);
            assertEquals(v, Toolchains.requireValidVersion(v));
        }
    }

    @Test
    void rejectsPathsAndMalformedVersions() {
        String[] bad = {
            null, "", " ", "v0.1.10", "0.1", "0.1.x", "latest",
            "../../../../proc/self/cwd", "..", "0.1.10/../../x", "0.1.10/x", "0.1.10\\x",
            "/etc", "0.1.10\n../x", "0.1.10\n", "0.1.10 ", "0.1.10;rm", "0.1.10-", "-0.1.10",
            "0.1.10-a/b", "0.1.10$(id)"
        };
        for (String v : bad) {
            assertFalse(Toolchains.isValidVersion(v), String.valueOf(v));
            assertThrows(IllegalArgumentException.class, () -> Toolchains.requireValidVersion(v));
        }
    }

    @Test
    void errorMessageNamesTheValueWithoutControlCharacters() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> Toolchains.requireValidVersion("../x\u001b[31m"));
        assertTrue(e.getMessage().contains("../x?[31m"), e.getMessage());
    }

    @Test
    void installRefusesAPathLikeVersionBeforeTouchingAnything(@TempDir Path tmp) throws Exception {
        Path victim = Files.createDirectories(tmp.resolve("victim"));
        Path dest = tmp.resolve("toolchains").resolve("../victim");
        assertThrows(IllegalArgumentException.class, () -> Toolchains.install("../victim", dest));
        assertTrue(Files.isDirectory(victim), "install must not delete a directory named by a bad version");
    }

    @Test
    void downloadsMustBeHttpsUnlessOverridden() {
        // L8: a toolchain is code the CLI runs; TLS is what authenticates it until releases are signed.
        Toolchains.requireSecure("https://github.com/larvalabs/brace/releases/download/v1.0.0/brace-1.0.0.zip", null);
        Toolchains.requireSecure("file:///tmp/releases/v1.0.0/brace-1.0.0.zip", null);
        var e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> Toolchains.requireSecure("http://mirror.example/v1.0.0/brace-1.0.0.zip", null));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("non-https"), e.getMessage());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> Toolchains.requireSecure("ftp://mirror.example/x.zip", "0"));
        Toolchains.requireSecure("http://mirror.example/v1.0.0/brace-1.0.0.zip", "1");
    }
}
