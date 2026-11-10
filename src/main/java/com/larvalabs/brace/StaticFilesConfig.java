package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.List;

/**
 * Who may fetch the files of a just-registered {@link Brace#staticFiles(String, String) static
 * mapping} (M3, 2026-11 security review). Every mapping says one of two things, and
 * {@link Brace#start()} refuses to start while one says neither:
 *
 * <pre>{@code
 * app.staticFiles("/assets", "public").publicFiles();                 // everyone
 * app.staticFiles("/uploads", "/var/app/uploads", StaticFiles.UNTRUSTED)
 *    .access(Access.session("userId", "/login"));                     // logged-in users
 * }</pre>
 *
 * <p>{@link Brace#defaultAccess(Access)} does not apply to a mapping: an app's stylesheet is
 * almost always public, so inheriting a login default would break the login page, and
 * defaulting to public would publish an upload directory by omission. Both methods return the
 * app, so a builder chain continues after the declaration.
 */
public final class StaticFilesConfig {

    private final Brace app;
    private final String urlPrefix;
    private final String directory;
    private final StaticFiles mode;
    private final List<Access> access = new ArrayList<>();
    private boolean publicFiles;

    StaticFilesConfig(Brace app, String urlPrefix, String directory, StaticFiles mode) {
        this.app = app;
        this.urlPrefix = urlPrefix;
        this.directory = directory;
        this.mode = mode;
    }

    /**
     * Serve this mapping's files to everyone, logged in or not: stylesheets, scripts, images.
     * The explicit, greppable counterpart of a route's {@code .publicRoute()}.
     */
    public Brace publicFiles() {
        app.router().requireNotStarted("Marking static mapping " + urlPrefix + " public");
        if (!access.isEmpty()) {
            throw new IllegalStateException("Static mapping " + urlPrefix + " already has access requirements ("
                + accessLabel() + "); it can't also be .publicFiles().");
        }
        publicFiles = true;
        return app;
    }

    /**
     * Require every listed {@link Access} to pass before a file under this mapping is served or
     * even looked up. The requirements run after plain {@code before(...)} middleware, with the
     * session the rest of the request uses, and are bound to the mapping: every URL that reaches
     * its files goes through them. A rejected request gets the same response whether or not the
     * file exists. Responses are sent {@code Cache-Control: private, no-cache}.
     *
     * <p>The app's {@link Brace#defaultAccess(Access)} is not added; list what the mapping needs.
     */
    public Brace access(Access requirement, Access... more) {
        app.router().requireNotStarted("Adding access to static mapping " + urlPrefix);
        if (publicFiles) {
            throw new IllegalStateException("Static mapping " + urlPrefix
                + " is marked .publicFiles(); it can't also have access requirements.");
        }
        var added = new ArrayList<Access>();
        added.add(requirement);
        if (more != null) added.addAll(java.util.Arrays.asList(more));
        for (Access a : added) {
            if (a == null) throw new IllegalArgumentException("access requirement must not be null");
            if (a.isNone()) {
                throw new IllegalArgumentException("Access.none() is only valid as app.defaultAccess(...); "
                    + "use .publicFiles() to serve static mapping " + urlPrefix + " to everyone");
            }
        }
        access.addAll(added);
        return app;
    }

    String urlPrefix() { return urlPrefix; }

    String directory() { return directory; }

    StaticFiles mode() { return mode; }

    boolean isPublicFiles() { return publicFiles; }

    boolean isDeclared() { return publicFiles || !access.isEmpty(); }

    List<Access> declaredAccess() { return access; }

    private String accessLabel() {
        var labels = new ArrayList<String>();
        for (Access a : access) labels.add(a.label());
        return String.join(" + ", labels);
    }
}
