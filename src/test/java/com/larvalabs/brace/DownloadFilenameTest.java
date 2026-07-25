package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M6 (2026-07 security review): {@link Result#download} interpolated the filename raw into
 * {@code attachment; filename="…"}, so a name containing a double quote closed the
 * quoted-string early and everything after it was parsed by the client as further parameters.
 * Serving a user-uploaded file under its original name is the method's primary use case, which
 * is exactly where the value is attacker-supplied.
 */
class DownloadFilenameTest {


    @Test
    void quoteInFilenameCannotInjectParameters() {
        // Verified on the wire before the fix: this produced
        //   Content-Disposition: attachment; filename="x"; name="y"
        String cd = Result.contentDisposition("x\"; name=\"y");
        assertFalse(cd.contains("\"; name=\"y\""), "quoted-string was escaped early: " + cd);
        assertTrue(cd.startsWith("attachment; filename=\"x_; name=_y\""), cd);
    }

    @Test
    void controlCharactersAreNeutralisedInTheAsciiForm() {
        String cd = Result.contentDisposition("a\r\nX-Injected: yes");
        assertFalse(cd.contains("\r"), cd);
        assertFalse(cd.contains("\n"), cd);
    }

    @Test
    void directoryComponentsAreDropped() {
        assertTrue(Result.contentDisposition("../../etc/passwd").contains("filename=\"passwd\""));
        assertTrue(Result.contentDisposition("C:\\windows\\win.ini").contains("filename=\"win.ini\""));
        assertEquals("attachment", Result.contentDisposition(".."));
    }

    @Test
    void nonAsciiRoundTripsViaFilenameStar() {
        String cd = Result.contentDisposition("отчёт.pdf");
        assertTrue(cd.contains("filename*=UTF-8''"), cd);
        // Non-ASCII is placeholder'd in the plain parameter but exact in the extended one.
        assertTrue(cd.contains("%D0%BE"), "expected percent-encoded UTF-8 bytes: " + cd);
        assertTrue(cd.contains(".pdf"), cd);
    }

    @Test
    void ordinaryFilenamesSurviveIntact() {
        String cd = Result.contentDisposition("quarterly-report.pdf");
        assertTrue(cd.startsWith("attachment; filename=\"quarterly-report.pdf\""), cd);
        assertTrue(cd.endsWith("filename*=UTF-8''quarterly-report.pdf"), cd);
    }

    @Test
    void blankFilenameFallsBackToBareAttachment() {
        assertEquals("attachment", Result.contentDisposition(null));
        assertEquals("attachment", Result.contentDisposition("   "));
    }

    @Test
    void downloadUsesTheSanitisedHeader() {
        var result = Result.download("hi".getBytes(), "text/plain", "x\"; name=\"y");
        assertEquals(Result.contentDisposition("x\"; name=\"y"),
            result.header("Content-Disposition"));
    }
}
