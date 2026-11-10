package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class CliOps {

    private CliOps() {}

    public static int keypair(Path projectDir, String[] args) {
        String label = null;
        boolean readOnly = false;
        for (int i = 0; i < args.length; i++) {
            if ("--label".equals(args[i]) && i + 1 < args.length) { label = args[i + 1]; }
            else if ("--read-only".equals(args[i])) { readOnly = true; }
        }
        label = label == null ? "" : sanitizeLabel(label);
        if (label.isEmpty()) label = defaultLabel(projectDir);
        // Refuse, before any key exists, a label the server's keys-file grammar would reject at
        // startup or cut short as a comment (L1 follow-up).
        String problem = labelProblem(label, readOnly);
        if (problem != null) {
            CliOutput.printError(problem);
            return 1;
        }
        var kp = OpsKeys.generateKeypair();

        // Write the private key first. Format must match OpsKeys.readKeyFile: private key on the
        // first non-comment line, public key on the second. Never clobber an existing key — that
        // would orphan a key already trusted by a deployed server's ops-authorized-keys.
        Path keyFile = projectDir.resolve("ops-private.key");
        try {
            // Fail before generating anything if the authorized-keys list is a planted symlink.
            SecretFiles.refuseSymlink(projectDir.resolve("ops-authorized-keys"));
        } catch (IOException e) {
            CliOutput.printError(e.getMessage());
            return 1;
        }
        if (Files.exists(keyFile)) {
            CliOutput.printError("ops-private.key already exists — refusing to overwrite. "
                + "Delete it first to generate a fresh keypair.");
            return 1;
        }
        try {
            SecretFiles.writeStringWithOwnerOnlyPermissions(keyFile,
                "# Brace ops private key — keep secret (gitignored)\n"
                + kp.privateKey() + "\n" + kp.publicKey() + "\n");
        } catch (Exception e) {
            CliOutput.printError("Failed to write ops-private.key: " + e.getMessage());
            return 1;
        }

        // Record the public key in ops-authorized-keys. Stored as raw base64 with a label after a
        // space — the exact form OpsKeys.loadAuthorizedKeys parses and the server compares against
        // (no algorithm prefix). Keyed by label: if an entry with this label already exists (e.g. a
        // re-run on the same machine after deleting the private key), replace it in place rather
        // than leaving an orphaned, still-trusted line. Different developers get different labels
        // (identity@host), so they never clobber each other's entry.
        Path file = projectDir.resolve("ops-authorized-keys");
        // A read-only key carries a `scope:read` marker; the server caps any token it mints at
        // READ, so it can pull status/errors/logs but never hit a control endpoint. Hand this to
        // an autonomous agent. Without the flag the entry has no scope token (server: CONTROL).
        String entry = readOnly
            ? kp.publicKey() + "  scope:read  " + label
            : kp.publicKey() + "  " + label;
        boolean replaced = false;
        try {
            if (Files.exists(file)) {
                var lines = new ArrayList<>(Files.readAllLines(file));
                for (int i = 0; i < lines.size(); i++) {
                    if (label.equals(labelOf(lines.get(i)))) {
                        lines.set(i, entry);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) lines.add(entry);
                SecretFiles.writeProjectFile(file, String.join("\n", lines) + "\n");
            } else {
                SecretFiles.writeProjectFile(file,
                    "# Ops authorized public keys — one per line, optional label\n" + entry + "\n");
            }
        } catch (Exception e) {
            CliOutput.printError("Failed to write ops-authorized-keys: " + e.getMessage());
            return 1;
        }

        System.out.println("Public key:   " + kp.publicKey());
        System.out.println("Label:        " + label);
        System.out.println("Scope:        " + (readOnly ? "read (read-only — cannot hit control endpoints)" : "control"));
        System.out.println();
        System.out.println("Wrote ops-private.key (gitignored) and " + (replaced ? "updated" : "added")
            + " the entry for \"" + label + "\" in ops-authorized-keys.");
        System.out.println("Commit ops-authorized-keys and deploy it to authorize this key on the server.");
        return 0;
    }

    /**
     * Default authorized-keys label identifying this developer and machine: {@code <identity>@<host>}.
     * Identity is the project's git {@code user.email}, falling back to the OS user name. The host
     * disambiguates a single developer's multiple machines (each holds its own private key). The
     * label is the dedup key when re-running keypair, so a rotation replaces the right line and two
     * developers sharing the committed ops-authorized-keys never overwrite each other's entry.
     */
    static String defaultLabel(Path projectDir) {
        String identity = gitEmail(projectDir);
        if (identity == null || identity.isBlank()) identity = System.getProperty("user.name");
        if (identity == null || identity.isBlank()) identity = "user";
        String host = hostname();
        return safeDefaultLabel(sanitizeLabel(host == null || host.isBlank() ? identity : identity + "@" + host));
    }

    /** Project-local git user.email, or null if git is unavailable, not a repo, or unset. */
    private static String gitEmail(Path projectDir) {
        try {
            Process p = new ProcessBuilder("git", "config", "user.email")
                .directory(projectDir.toFile())
                .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            return p.exitValue() == 0 && !out.isBlank() ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Short local hostname (domain suffix stripped), or null if it can't be determined. */
    private static String hostname() {
        String h = null;
        try {
            h = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            h = System.getenv("HOSTNAME");
            if (h == null) h = System.getenv("COMPUTERNAME");
        }
        if (h == null || h.isBlank()) return null;
        int dot = h.indexOf('.');
        return dot > 0 ? h.substring(0, dot) : h;
    }

    /**
     * Why the server's authorized-keys grammar ({@link OpsKeys#loadAuthorizedKeys(String)}) would
     * refuse or cut short the line {@code KEY [scope:read] LABEL}, or null when it loads as
     * written. {@code label} is already sanitized (one token, no whitespace). L1 follow-up.
     */
    static String labelProblem(String label, boolean readOnly) {
        String rule = " The ops-authorized-keys format is KEY [scope:read|scope:control] [label] [# comment].";
        for (int i = 0; i < label.length(); i++) {
            if (Character.isISOControl(label.charAt(i))) {
                return "The label may not contain a control character." + rule;
            }
        }
        if (label.indexOf(':') >= 0 || label.indexOf('=') >= 0) {
            return "The label may not contain ':' or '=': after the key, a token with either must be"
                + " exactly scope:read or scope:control, and the server refuses to start otherwise."
                + " Use --read-only for a read-only key." + rule;
        }
        if (label.startsWith("#")) {
            return "The label may not start with '#': after whitespace, '#' starts a comment, so the"
                + " label would be dropped." + rule;
        }
        if (!readOnly && BARE_SCOPE_WORDS.contains(label.toLowerCase(java.util.Locale.ROOT))) {
            return "The label '" + label + "' alone after the key reads as a mistyped scope, and the"
                + " server refuses to start. For a read-only key pass --read-only (it writes"
                + " scope:read); otherwise choose another label." + rule;
        }
        return null;
    }

    /** {@code OpsKeys}' words that, as the only token after a key, fail startup. */
    private static final java.util.Set<String> BARE_SCOPE_WORDS =
        java.util.Set.of("read", "ro", "readonly", "read-only", "control");

    /**
     * The default label, made acceptable to the keys file: it comes from git {@code user.email}
     * and the host name, which the user didn't type, so {@code :} and {@code =} become {@code -}
     * and leading {@code #} are dropped rather than refused.
     */
    static String safeDefaultLabel(String s) {
        String safe = s.replace(':', '-').replace('=', '-');
        int start = 0;
        while (start < safe.length() && safe.charAt(start) == '#') start++;
        safe = safe.substring(start);
        return safe.isEmpty() ? "user" : safe;
    }

    /**
     * Collapse whitespace so the label stays a single trailing token on its line. Any Unicode
     * whitespace or space separator counts: the server treats a {@code #} after one as a comment.
     */
    private static String sanitizeLabel(String s) {
        var out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                pendingSpace = out.length() > 0;
            } else {
                if (pendingSpace) out.append('-');
                pendingSpace = false;
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The label portion of an authorized-keys line, or null for blank/comment lines. Skips the
     * key and an optional {@code scope:...} marker so rotation still matches by label when an
     * entry carries a scope.
     */
    private static String labelOf(String line) {
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("#")) return null;
        String[] tokens = t.split("\\s+");
        int i = 1; // skip the key
        if (i < tokens.length && tokens[i].startsWith("scope:")) i++; // skip optional scope marker
        return i >= tokens.length ? "" : String.join(" ", java.util.Arrays.copyOfRange(tokens, i, tokens.length));
    }

    public static int dashboard(Path projectDir, String[] args) {
        try {
            var cfg = CliConfig.load(projectDir, args);

            // Exchange bearer for single-use login token
            var loginResponse = CliAuth.sendAuthenticated(cfg, projectDir,
                HttpRequest.newBuilder()
                    .uri(URI.create(CliAuth.baseUrl(cfg) + "/ops/auth/login-token"))
                    .POST(HttpRequest.BodyPublishers.noBody()));

            if (loginResponse.statusCode() != 200) {
                CliOutput.printError("Failed to get login token: " + loginResponse.body());
                return 1;
            }

            JsonNode parsed = Json.mapper().readTree(loginResponse.body());
            String loginToken = parsed.get("loginToken").asText();
            // M8: the token comes off the wire, and openBrowser hands the assembled URL to a
            // shell-parsed command on Windows. Validate the server's value before it can become
            // part of a command line.
            if (!isBase64Url(loginToken)) {
                CliOutput.printError("Server returned a malformed login token; refusing to open it.");
                return 1;
            }
            String dashboardUrl = CliAuth.baseUrl(cfg) + "/ops/auth/exchange?token=" + loginToken
                + (CliCommands.hasFlag(args, "--analytics") ? "&next=analytics" : "");
            if (!dashboardUrl.startsWith("http://") && !dashboardUrl.startsWith("https://")) {
                CliOutput.printError("Refusing to open a non-http(s) dashboard URL: " + dashboardUrl);
                return 1;
            }

            System.out.println("Opening dashboard...");
            openBrowser(dashboardUrl);
            return 0;
        } catch (Exception e) {
            CliOutput.printError(e.getMessage());
            return 1;
        }
    }

    /**
     * A login token is base64url — the only shape {@code OpsToken} ever mints. Anything else
     * came from a server that is malfunctioning or hostile, and must not reach a command line
     * (M8).
     */
    static boolean isBase64Url(String token) {
        if (token == null || token.isEmpty() || token.length() > 4096) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '=';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Open a URL in the user's browser.
     *
     * <p><strong>Windows (M8).</strong> This used to run {@code cmd /c start <url>}. {@code cmd}
     * re-parses its command line, so an {@code &} in the URL terminates {@code start} and begins
     * a second command — and the URL carries a token the *server* supplied. A compromised app
     * server, a hostile server an operator was asked to inspect, or a MITM on a plain-http
     * {@code cfg.url()} could therefore execute code on the operator's workstation. Neither
     * {@code rundll32 url.dll,FileProtocolHandler} nor the macOS/Linux openers go through a
     * shell parser: they take the URL as a single argv entry.
     */
    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception ignored) {}
    }
}
