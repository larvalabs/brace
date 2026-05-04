package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CliOutputTest {

    @Test
    void tableRendersHeadersAndRows() {
        var out = CliOutput.table(
            List.of("ID", "MESSAGE"),
            List.of(
                List.of("1", "first"),
                List.of("42", "second")));
        assertTrue(out.contains("ID"));
        assertTrue(out.contains("MESSAGE"));
        assertTrue(out.contains("first"));
        assertTrue(out.contains("second"));
    }

    @Test
    void tableAlignsColumnWidths() {
        var out = CliOutput.table(
            List.of("A", "B"),
            List.of(List.of("short", "long-value")));
        var lines = out.split("\n");
        int bHeader = lines[0].indexOf("B");
        int bRow = lines[1].indexOf("long-value");
        assertEquals(bHeader, bRow);
    }

    @Test
    void truncatesOverlongValues() {
        var huge = "x".repeat(500);
        var out = CliOutput.table(List.of("M"), List.of(List.of(huge)), 80);
        for (var line : out.split("\n")) {
            assertTrue(line.length() <= 80, "line too long: " + line.length());
        }
    }

    @Test
    void truncatesMultipleWideColumns() {
        var out = CliOutput.table(
            List.of("A", "B"),
            List.of(List.of("x".repeat(50), "y".repeat(50))),
            40);
        for (var line : out.split("\n")) {
            assertTrue(line.length() <= 40, "line too long: " + line.length());
        }
    }

    @Test
    void formatsJson() throws Exception {
        var out = CliOutput.json(Map.of("ok", true, "count", 5));
        assertTrue(out.contains("\"ok\""));
        assertTrue(out.contains("\"count\""));
    }

    @Test
    void modeFromEnvForcesJson() {
        assertEquals(CliOutput.Mode.JSON, CliOutput.modeFrom(false, false, false));
        assertEquals(CliOutput.Mode.HUMAN, CliOutput.modeFrom(true, false, false));
        assertEquals(CliOutput.Mode.JSON, CliOutput.modeFrom(true, true, false));
        assertEquals(CliOutput.Mode.HUMAN, CliOutput.modeFrom(false, false, true));
    }
}
