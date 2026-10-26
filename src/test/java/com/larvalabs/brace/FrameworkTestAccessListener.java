package com.larvalabs.brace;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Gives every app built in the framework's own test suite {@code Access.none()} as its fallback
 * default (H1), so the hundreds of fixture routes that predate the access model don't each need
 * a {@code .publicRoute()}. Registered through {@code META-INF/services} in test resources only:
 * apps, and their tests, get no such fallback. Tests of the access check itself opt back out with
 * {@code Brace.strictAccessCheck()}.
 */
public class FrameworkTestAccessListener implements LauncherSessionListener {
    @Override
    public void launcherSessionOpened(LauncherSession session) {
        Brace.frameworkTestDefaultAccess = Access.none();
    }
}
