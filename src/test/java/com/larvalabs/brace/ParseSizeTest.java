package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** {@code maxUploadSize("10MB")} used to throw NumberFormatException: only K, M and G were accepted. */
class ParseSizeTest {

    @Test
    void acceptsSingleLetterAndKbMbGbSuffixesInAnyCase() {
        assertEquals(10L * 1024 * 1024, Brace.parseSize("10M"));
        assertEquals(10L * 1024 * 1024, Brace.parseSize("10m"));
        assertEquals(10L * 1024 * 1024, Brace.parseSize("10MB"));
        assertEquals(10L * 1024 * 1024, Brace.parseSize("10mb"));
        assertEquals(10L * 1024 * 1024, Brace.parseSize(" 10Mb "));
        assertEquals(256L * 1024, Brace.parseSize("256K"));
        assertEquals(256L * 1024, Brace.parseSize("256KB"));
        assertEquals(2L * 1024 * 1024 * 1024, Brace.parseSize("2GB"));
        assertEquals(2L * 1024 * 1024 * 1024, Brace.parseSize("2g"));
        assertEquals(4096L, Brace.parseSize("4096"));
    }

    @Test
    void stillRejectsGarbage() {
        for (String bad : new String[]{"", "MB", "10XB", "tenMB", "10 M B", "1.5MB"}) {
            assertThrows(NumberFormatException.class, () -> Brace.parseSize(bad), bad);
        }
    }

    @Test
    void theBuilderTakesTheFriendlySpelling() {
        assertDoesNotThrow(() -> Brace.app().maxUploadSize("10MB").uploadMemoryThreshold("256KB"));
    }
}
