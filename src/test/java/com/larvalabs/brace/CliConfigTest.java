package com.larvalabs.brace;

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
            "ops.local.url=http://dev.local:9000\n");
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals("http://dev.local:9000", cfg.url());
    }

    @Test
    void defaultsToProdWhenProdUrlConfigured() throws Exception {
        Files.writeString(tmp.resolve(".brace"),
            "ops.local.url=http://dev.local:9000\n" +
            "ops.prod.url=https://app.example.com\n");
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals("prod", cfg.env());
        assertEquals("https://app.example.com", cfg.url());
    }

    @Test
    void explicitOpsEnvOverridesProdAutoDefault() throws Exception {
        Files.writeString(tmp.resolve(".brace"),
            "ops.env=local\n" +
            "ops.local.url=http://dev.local:9000\n" +
            "ops.prod.url=https://app.example.com\n");
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals("local", cfg.env());
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
    void checkThresholdsFromBraceFile() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "check.slow_route_ms=1000\n");
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals(1000, cfg.checkThresholds().slowRouteMs());
    }

    @Test
    void unknownNonLocalEnvFailsClearly() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://x:1\n");
        var ex = assertThrows(java.io.IOException.class,
            () -> CliConfig.load(tmp, new String[]{"--env", "staging"}));
        assertTrue(ex.getMessage().contains("ops.staging.url"), ex.getMessage());
    }

    @Test
    void unknownEnvWithUrlFlagStillWorks() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://x:1\n");
        var cfg = CliConfig.load(tmp,
            new String[]{"--env", "staging", "--url", "https://staging.example.com"});
        assertEquals("https://staging.example.com", cfg.url());
        assertEquals("staging", cfg.env());
    }
}
