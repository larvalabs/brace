package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M7 (2026-07 security review): {@link Http.Multipart} concatenated part names and filenames
 * into the part headers raw and wrote them verbatim, so CR/LF in either value terminated the
 * headers and let the caller forge additional parts in the outbound request — parameter
 * smuggling into whatever third-party API the app was calling.
 */
class MultipartPartHeaderTest {


    @Test
    void crlfInAPartValueIsRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> Http.Multipart.escapePartValue("evil\r\n\r\n--boundary\r\nContent-Disposition: x"));
        assertTrue(ex.getMessage().contains("control characters"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Http.Multipart.escapePartValue("a\u007f"));
        assertThrows(IllegalArgumentException.class, () -> Http.Multipart.escapePartValue("a\u0000"));
    }

    @Test
    void quotesAndBackslashesAreEscapedNotDropped() {
        assertEquals("a\\\"b", Http.Multipart.escapePartValue("a\"b"));
        assertEquals("a\\\\b", Http.Multipart.escapePartValue("a\\b"));
    }

    @Test
    void ordinaryPartValuesAreUnchanged() {
        assertEquals("report.pdf", Http.Multipart.escapePartValue("report.pdf"));
        assertEquals("файл.txt", Http.Multipart.escapePartValue("файл.txt"));
        assertEquals("", Http.Multipart.escapePartValue(null));
    }
}
