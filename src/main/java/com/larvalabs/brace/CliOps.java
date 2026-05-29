package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
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
        for (int i = 0; i < args.length - 1; i++) {
            if ("--label".equals(args[i])) { label = args[i + 1]; break; }
        }
        if (label == null || label.isBlank()) label = defaultLabel(projectDir);
        label = sanitizeLabel(label);
        var kp = OpsKeys.generateKeypair();

        // Write the private key first. Format must match OpsKeys.readKeyFile: private key on the
        // first non-comment line, public key on the second. Never clobber an existing key — that
        // would orphan a key already trusted by a deployed server's ops-authorized-keys.
        Path keyFile = projectDir.resolve("ops-private.key");
        if (Files.exists(keyFile)) {
            CliOutput.printError("ops-private.key already exists — refusing to overwrite. "
                + "Delete it first to generate a fresh keypair.");
            return 1;
        }
        try {
            Files.writeString(keyFile,
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
        String entry = kp.publicKey() + "  " + label;
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
                Files.writeString(file, String.join("\n", lines) + "\n");
            } else {
                Files.writeString(file,
                    "# Ops authorized public keys — one per line, optional label\n" + entry + "\n");
            }
        } catch (Exception e) {
            CliOutput.printError("Failed to write ops-authorized-keys: " + e.getMessage());
            return 1;
        }

        System.out.println("Public key:   " + kp.publicKey());
        System.out.println("Label:        " + label);
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
        return sanitizeLabel(host == null || host.isBlank() ? identity : identity + "@" + host);
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

    /** Collapse whitespace so the label stays a single trailing token on its line. */
    private static String sanitizeLabel(String s) {
        return s.strip().replaceAll("\\s+", "-");
    }

    /** The label portion (after the key) of an authorized-keys line, or null for blank/comment lines. */
    private static String labelOf(String line) {
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("#")) return null;
        int i = 0;
        while (i < t.length() && !Character.isWhitespace(t.charAt(i))) i++;
        return i >= t.length() ? "" : t.substring(i).strip();
    }

    public static int dashboard(Path projectDir, String[] args) {
        try {
            var cfg = CliConfig.load(projectDir, args);

            // Exchange bearer for single-use login token
            var loginResponse = CliAuth.sendAuthenticated(cfg, projectDir,
                HttpRequest.newBuilder()
                    .uri(URI.create(cfg.url() + "/ops/auth/login-token"))
                    .POST(HttpRequest.BodyPublishers.noBody()));

            if (loginResponse.statusCode() != 200) {
                CliOutput.printError("Failed to get login token: " + loginResponse.body());
                return 1;
            }

            JsonNode parsed = Json.mapper().readTree(loginResponse.body());
            String loginToken = parsed.get("loginToken").asText();
            String dashboardUrl = cfg.url() + "/ops/auth/exchange?token=" + loginToken;

            System.out.println("Opening dashboard...");
            openBrowser(dashboardUrl);
            return 0;
        } catch (Exception e) {
            CliOutput.printError(e.getMessage());
            return 1;
        }
    }

    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else if (os.contains("linux")) new ProcessBuilder("xdg-open", url).start();
            else if (os.contains("win")) new ProcessBuilder("cmd", "/c", "start", url).start();
        } catch (Exception ignored) {}
    }
}
