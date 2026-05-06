package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseFactoryUrlParsingTest {

    @Test
    void leavesJdbcUrlAlone() {
        var cfg = DatabaseFactory.parseDbConfig("jdbc:postgresql://db.example.com:5432/app", null, null);
        assertEquals("jdbc:postgresql://db.example.com:5432/app", cfg.url());
        assertNull(cfg.user());
        assertNull(cfg.pass());
    }

    @Test
    void prefixesBarePostgresqlScheme() {
        var cfg = DatabaseFactory.parseDbConfig("postgresql://db.example.com/app", null, null);
        assertEquals("jdbc:postgresql://db.example.com/app", cfg.url());
    }

    @Test
    void rewritesPostgresShortScheme() {
        var cfg = DatabaseFactory.parseDbConfig("postgres://db.example.com/app", null, null);
        assertEquals("jdbc:postgresql://db.example.com/app", cfg.url());
    }

    @Test
    void extractsEmbeddedCreds() {
        var cfg = DatabaseFactory.parseDbConfig(
            "postgresql://alice:s3cret@db.example.com:5432/app", null, null);
        assertEquals("jdbc:postgresql://db.example.com:5432/app", cfg.url());
        assertEquals("alice", cfg.user());
        assertEquals("s3cret", cfg.pass());
    }

    @Test
    void paasInjectedShape() {
        // Literal PaaS-injected shape (Dokploy / Heroku-style): bare scheme + user:pass@.
        var cfg = DatabaseFactory.parseDbConfig(
            "postgresql://postgres:hunter2@app-postgres-abc123:5432/postgres", null, null);
        assertEquals("jdbc:postgresql://app-postgres-abc123:5432/postgres", cfg.url());
        assertEquals("postgres", cfg.user());
        assertEquals("hunter2", cfg.pass());
    }

    @Test
    void explicitCredsWinOverEmbedded() {
        var cfg = DatabaseFactory.parseDbConfig(
            "postgresql://alice:s3cret@db.example.com/app", "bob", "different");
        assertEquals("jdbc:postgresql://db.example.com/app", cfg.url());
        assertEquals("bob", cfg.user());
        assertEquals("different", cfg.pass());
    }

    @Test
    void blankExplicitCredsFallBackToEmbedded() {
        var cfg = DatabaseFactory.parseDbConfig(
            "postgresql://alice:s3cret@db.example.com/app", "", "  ");
        assertEquals("alice", cfg.user());
        assertEquals("s3cret", cfg.pass());
    }

    @Test
    void urlEncodedPasswordIsDecoded() {
        var cfg = DatabaseFactory.parseDbConfig(
            "postgresql://alice:p%40ss%20word@db.example.com/app", null, null);
        assertEquals("p@ss word", cfg.pass());
    }

    @Test
    void userOnlyAuthority() {
        var cfg = DatabaseFactory.parseDbConfig(
            "postgresql://alice@db.example.com/app", null, null);
        assertEquals("jdbc:postgresql://db.example.com/app", cfg.url());
        assertEquals("alice", cfg.user());
        assertNull(cfg.pass());
    }

    @Test
    void noAuthorityLeavesUrlUntouched() {
        var cfg = DatabaseFactory.parseDbConfig(
            "postgresql://db.example.com/app", null, null);
        assertEquals("jdbc:postgresql://db.example.com/app", cfg.url());
        assertNull(cfg.user());
        assertNull(cfg.pass());
    }

    @Test
    void nullUrlReturnsNullUrl() {
        var cfg = DatabaseFactory.parseDbConfig(null, "alice", "secret");
        assertNull(cfg.url());
        assertEquals("alice", cfg.user());
        assertEquals("secret", cfg.pass());
    }

    @Test
    void h2UrlIsLeftAlone() {
        var cfg = DatabaseFactory.parseDbConfig("jdbc:h2:mem:testdb", null, null);
        assertEquals("jdbc:h2:mem:testdb", cfg.url());
    }
}
