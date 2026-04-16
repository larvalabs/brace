package io.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliConfigTest {

    @TempDir Path tmp;

    @Test
    void defaultsWhenNoFiles() throws Exception {
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals("http://localhost:8080", cfg.url());
        assertEquals("ops-private.key", cfg.keyPath());
        assertEquals("ops-authorized-keys", cfg.authorizedKeysPath());
        assertEquals("local", cfg.env());
    }

    @Test
    void readsBraceFile() throws Exception {
        Files.writeString(tmp.resolve(".brace"),
            "ops.local.url=http://dev.local:9000\n" +
            "ops.prod.url=https://app.example.com\n");
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals("http://dev.local:9000", cfg.url());
    }

    @Test
    void envFlagSelectsProdUrl() throws Exception {
        Files.writeString(tmp.resolve(".brace"),
            "ops.local.url=http://dev.local:9000\n" +
            "ops.prod.url=https://app.example.com\n");
        var cfg = CliConfig.load(tmp, new String[]{"--env", "prod"});
        assertEquals("https://app.example.com", cfg.url());
        assertEquals("prod", cfg.env());
    }

    @Test
    void localFileOverridesCommittedFile() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://committed:8080\n");
        Files.writeString(tmp.resolve(".brace.local"), "ops.local.url=http://override:9000\n");
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals("http://override:9000", cfg.url());
    }

    @Test
    void cliFlagOverridesEverything() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://committed:8080\n");
        var cfg = CliConfig.load(tmp, new String[]{"--url", "http://flag:1234"});
        assertEquals("http://flag:1234", cfg.url());
    }

    @Test
    void cliKeyFlagOverridesKeyPath() throws Exception {
        var cfg = CliConfig.load(tmp, new String[]{"--key", "/etc/secret.key"});
        assertEquals("/etc/secret.key", cfg.keyPath());
    }

    @Test
    void authorizedKeysOverrideFromBraceFile() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.authorized_keys=/etc/keys\n");
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals("/etc/keys", cfg.authorizedKeysPath());
    }

    @Test
    void valueWithEmbeddedEquals() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=https://host?token=abc\n");
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals("https://host?token=abc", cfg.url());
    }

    @Test
    void unknownEnvFallsBackToLocal() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://x:1\n");
        var cfg = CliConfig.load(tmp, new String[]{"--env", "staging"});
        assertEquals("http://localhost:8080", cfg.url());
        assertEquals("staging", cfg.env());
    }
}
