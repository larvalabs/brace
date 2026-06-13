package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuildCommandsTest {

    @Test
    void runModeIsProdDevModeIsDev() {
        assertEquals(List.of("java", "-Dbrace.mode=prod", "-cp", "cp", "app.App"),
                BuildCommands.javaCommand("prod", null, "cp", "app.App"));
        assertEquals(List.of("java", "-Dbrace.mode=dev", "-cp", "cp", "app.App"),
                BuildCommands.javaCommand("dev", null, "cp", "app.App"));
    }

    @Test
    void javaOptsSplitOnWhitespaceAfterModeFlag() {
        var cmd = BuildCommands.javaCommand("prod", "-Xmx512m  -Dfoo=bar", "cp", "app.App");
        assertEquals(List.of("java", "-Dbrace.mode=prod", "-Xmx512m", "-Dfoo=bar", "-cp", "cp", "app.App"), cmd);
    }

    @Test
    void javaOptsCanOverrideMode() {
        // Last -D wins in the JVM, so the user's flag must come after ours.
        var cmd = BuildCommands.javaCommand("prod", "-Dbrace.mode=dev", "cp", "app.App");
        assertTrue(cmd.indexOf("-Dbrace.mode=dev") > cmd.indexOf("-Dbrace.mode=prod"));
    }

    @Test
    void blankOptsIgnored() {
        assertEquals(List.of("java", "-Dbrace.mode=prod", "-cp", "cp", "app.App"),
                BuildCommands.javaCommand("prod", "   ", "cp", "app.App"));
    }
}
