package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    @TempDir Path tempDir;

    private Config configFromString(String content) throws IOException {
        var file = tempDir.resolve("application.conf");
        Files.writeString(file, content);
        return Config.load(file, null);
    }

    @Test
    void readsSimpleProperties() throws IOException {
        var config = configFromString("port=8080\napp.name=MyApp");
        assertEquals("8080", config.get("port"));
        assertEquals("MyApp", config.get("app.name"));
    }

    @Test
    void returnsNullForMissingKey() throws IOException {
        var config = configFromString("port=8080");
        assertNull(config.get("missing"));
    }

    @Test
    void getIntWithDefault() throws IOException {
        var config = configFromString("port=9000");
        assertEquals(9000, config.getInt("port", 8080));
        assertEquals(8080, config.getInt("missing", 8080));
    }

    @Test
    void modePrefix() throws IOException {
        var config = configFromString("""
            port=8080
            %dev.port=9000
            %prod.port=80
            """);
        var devConfig = Config.load(tempDir.resolve("application.conf"), "dev");
        assertEquals("9000", devConfig.get("port"));

        var prodConfig = Config.load(tempDir.resolve("application.conf"), "prod");
        assertEquals("80", prodConfig.get("port"));
    }

    @Test
    void modePrefixFallsBackToUnprefixed() throws IOException {
        var config = configFromString("""
            port=8080
            app.name=MyApp
            %dev.port=9000
            """);
        var devConfig = Config.load(tempDir.resolve("application.conf"), "dev");
        assertEquals("9000", devConfig.get("port"));
        assertEquals("MyApp", devConfig.get("app.name"));
    }

    @Test
    void envVarInterpolation() throws IOException {
        var config = configFromString("secret=${MY_SECRET}");
        assertNull(config.get("secret"));
    }

    @Test
    void ignoresCommentLines() throws IOException {
        var config = configFromString("""
            # This is a comment
            port=8080
            """);
        assertEquals("8080", config.get("port"));
    }

    @Test
    void ignoresBlankLines() throws IOException {
        var config = configFromString("""
            port=8080

            app.name=MyApp
            """);
        assertEquals("8080", config.get("port"));
        assertEquals("MyApp", config.get("app.name"));
    }
}
