package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Correctness review L5 (SigV4 encoding), L8 (interval units), L9 (CIDR prefix validation). */
class SmallFixesUnitTest {

    // --- L5: Storage.uriEncodePath follows SigV4's unreserved set ---

    @Test
    void sigV4UnreservedCharactersArePreserved() {
        // RFC 3986 / SigV4 unreserved: A-Za-z0-9-._~ — notably "~" must NOT be encoded.
        assertEquals("a-b_c.d~e", Storage.uriEncodePath("a-b_c.d~e"));
    }

    @Test
    void asteriskIsEncodedAsSigV4Requires() {
        // URLEncoder leaves "*" literal; SigV4 wants %2A. A key containing it used to produce a
        // canonical request S3 could not reproduce, i.e. SignatureDoesNotMatch.
        assertEquals("star%2Aname", Storage.uriEncodePath("star*name"));
    }

    @Test
    void spacesAreEncodedAsPercent20NotPlus() {
        assertEquals("my%20file.jpg", Storage.uriEncodePath("my file.jpg"));
    }

    @Test
    void slashesRemainSeparators() {
        assertEquals("avatars/2026/my%20pic.png", Storage.uriEncodePath("avatars/2026/my pic.png"));
    }

    @Test
    void nonAsciiIsUtf8PercentEncoded() {
        assertEquals("caf%C3%A9.txt", Storage.uriEncodePath("café.txt"));
    }

    // --- L8: interval grammar matches Cache.parseTtl ---

    @Test
    void intervalsAcceptDaysLikeCacheTtlsDo() {
        assertEquals(86_400_000L, JobScheduler.parseInterval("1d"));
        assertEquals(2 * 86_400_000L, JobScheduler.parseInterval("2d"));
        // The pairing that used to disagree: same string, two grammars.
        assertEquals(java.time.Duration.ofDays(1).toMillis(), JobScheduler.parseInterval("1d"));
        assertEquals(java.time.Duration.ofDays(1), Cache.parseTtl("1d"));
    }

    @Test
    void existingIntervalUnitsAreUnchanged() {
        assertEquals(1000L, JobScheduler.parseInterval("1s"));
        assertEquals(60_000L, JobScheduler.parseInterval("1m"));
        assertEquals(3_600_000L, JobScheduler.parseInterval("1h"));
    }

    @Test
    void unknownIntervalUnitsStillThrowAndListTheValidOnes() {
        var e = assertThrows(IllegalArgumentException.class, () -> JobScheduler.parseInterval("1y"));
        assertTrue(e.getMessage().contains("s, m, h, or d"), e.getMessage());
    }

    // --- L9: CIDR prefix bounds ---

    @Test
    void negativeCidrPrefixIsRejectedRatherThanTrustingEverything() {
        // "/-1" used to produce an all-zero mask, which matches every address — turning a config
        // typo into "trust all forwarding headers".
        assertThrows(IllegalArgumentException.class, () -> new TrustedProxies("10.0.0.0/-1"));
    }

    @Test
    void oversizedCidrPrefixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TrustedProxies("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedProxies("::1/129"));
    }

    @Test
    void validCidrsStillWork() {
        var proxies = new TrustedProxies("10.0.0.0/8", "127.0.0.1", "192.168.1.0/24");
        assertTrue(proxies.isTrusted("10.1.2.3"));
        assertTrue(proxies.isTrusted("127.0.0.1"));
        assertTrue(proxies.isTrusted("192.168.1.7"));
        assertEquals(false, proxies.isTrusted("8.8.8.8"));
    }

    @Test
    void zeroPrefixStillMeansEverythingBecauseThatIsWhatItMeans() {
        assertTrue(new TrustedProxies("0.0.0.0/0").isTrusted("8.8.8.8"));
    }
}
