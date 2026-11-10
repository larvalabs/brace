package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The test harness listens on 127.0.0.1, not the wildcard address. On the wildcard, port(0) could
 * be given a port another local process holds on 127.0.0.1 (macOS allows that bind), and the
 * test's requests to localhost then reached that process: one-off failures with a 404 or 401
 * that Brace never sent.
 */
class TestHarnessLoopbackTest {

    @Test
    void harnessBindsLoopback() throws Exception {
        var app = Brace.test().start(a -> {
            a.defaultAccess(Access.none());
            a.get("/hi", req -> Result.text("hi"));
        });
        try {
            assertEquals("127.0.0.1", app.app().host());
            assertEquals("hi", app.get("/hi").body());
        } finally {
            app.stop();
        }
    }

    @Test
    void aLoopbackBindRefusesAPortAlreadyHeldOnLoopback() throws Exception {
        try (var squatter = new ServerSocket()) {
            squatter.setReuseAddress(true);
            squatter.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            var app = Brace.app().port(squatter.getLocalPort()).host("127.0.0.1").banner(false)
                .defaultAccess(Access.none());
            app.get("/", req -> Result.text("brace"));
            try {
                assertThrows(Exception.class, app::start, "a port held on 127.0.0.1 must not be shared");
            } finally {
                app.stop();
            }
        }
    }
}
