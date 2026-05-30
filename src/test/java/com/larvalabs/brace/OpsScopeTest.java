package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpsScopeTest {

    @Test
    void controlGrantsReadButNotViceVersa() {
        assertTrue(OpsScope.CONTROL.grants(OpsScope.READ));
        assertTrue(OpsScope.CONTROL.grants(OpsScope.CONTROL));
        assertTrue(OpsScope.READ.grants(OpsScope.READ));
        assertFalse(OpsScope.READ.grants(OpsScope.CONTROL));
    }

    @Test
    void minCapsAtLesserScope() {
        assertEquals(OpsScope.READ, OpsScope.CONTROL.min(OpsScope.READ));
        assertEquals(OpsScope.READ, OpsScope.READ.min(OpsScope.CONTROL));
        assertEquals(OpsScope.CONTROL, OpsScope.CONTROL.min(OpsScope.CONTROL));
    }

    @Test
    void parseFallsBackOnUnknownOrNull() {
        assertEquals(OpsScope.READ, OpsScope.parse("read", OpsScope.CONTROL));
        assertEquals(OpsScope.CONTROL, OpsScope.parse("CONTROL", OpsScope.READ));
        assertEquals(OpsScope.CONTROL, OpsScope.parse(null, OpsScope.CONTROL));
        assertEquals(OpsScope.READ, OpsScope.parse("bogus", OpsScope.READ));
    }

    @Test
    void wireIsLowercase() {
        assertEquals("read", OpsScope.READ.wire());
        assertEquals("control", OpsScope.CONTROL.wire());
    }
}
