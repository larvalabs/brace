package com.larvalabs.brace;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class CliInit {

    public record Result(boolean ok, List<Check> local, List<Check> remote, List<String> actions) {}
    public record Check(String name, boolean ok, String detail) {}

    private CliInit() {}

    public static Result run(Path projectDir) throws IOException {
        var local = new ArrayList<Check>();
        var actions = new ArrayList<String>();

        // .brace
        Path brace = projectDir.resolve(".brace");
        if (!Files.exists(brace)) {
            Files.writeString(brace,
                "# Brace CLI project config — committed to git\n" +
                "ops.local.url=http://localhost:8080\n" +
                "# ops.prod.url=https://your-app.example.com\n" +
                "ops.authorized_keys=ops-authorized-keys\n");
            local.add(new Check(".brace", true, "created"));
            actions.add("Created .brace");
        } else {
            local.add(new Check(".brace", true, "present"));
        }

        // .brace.local
        Path local1 = projectDir.resolve(".brace.local");
        if (!Files.exists(local1)) {
            Files.writeString(local1,
                "# Brace CLI per-developer overrides — gitignored\n" +
                "ops.key=ops-private.key\n" +
                "ops.env=local\n");
            local.add(new Check(".brace.local", true, "created"));
            actions.add("Created .brace.local");
        } else {
            local.add(new Check(".brace.local", true, "present"));
        }

        // .gitignore
        Path gitignore = projectDir.resolve(".gitignore");
        var existingLines = Files.exists(gitignore) ? Files.readAllLines(gitignore) : List.<String>of();
        var existing = String.join("\n", existingLines);
        var activeEntries = existingLines.stream()
            .map(String::trim)
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .toList();
        var needs = new ArrayList<String>();
        if (activeEntries.stream().noneMatch(l -> l.equals(".brace.local"))) needs.add(".brace.local");
        if (activeEntries.stream().noneMatch(l -> l.equals("ops-private.key"))) needs.add("ops-private.key");
        if (!needs.isEmpty()) {
            String addition = (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n")
                + "\n# brace CLI\n" + String.join("\n", needs) + "\n";
            Files.writeString(gitignore, existing + addition);
            local.add(new Check(".gitignore", true, "added " + String.join(", ", needs)));
            actions.add("Updated .gitignore");
        } else {
            local.add(new Check(".gitignore", true, "entries OK"));
        }

        // ops.prod.url — informational; absence doesn't fail init, but blocks `--env prod` commands.
        boolean hasProdUrl = Files.readAllLines(brace).stream()
            .map(String::trim)
            .anyMatch(l -> l.startsWith("ops.prod.url=")
                && l.length() > "ops.prod.url=".length());
        if (hasProdUrl) {
            local.add(new Check("ops.prod.url", true, "configured"));
        } else {
            local.add(new Check("ops.prod.url", true,
                "not set — uncomment in .brace to enable `--env prod` commands"));
        }

        // ops-authorized-keys
        Path authKeys = projectDir.resolve("ops-authorized-keys");
        boolean keypairOk = true;
        if (!Files.exists(authKeys) || Files.readString(authKeys).trim().isEmpty()) {
            local.add(new Check("ops-authorized-keys", false, "missing"));
            actions.add("Run `brace ops keypair` to generate one");
            keypairOk = false;
        } else {
            int keyCount = (int) Files.readAllLines(authKeys).stream()
                .filter(l -> !l.trim().isEmpty() && !l.trim().startsWith("#")).count();
            local.add(new Check("ops-authorized-keys", true, keyCount + " key(s)"));
        }

        // ops-private.key
        Path privKey = projectDir.resolve("ops-private.key");
        if (!Files.exists(privKey)) {
            local.add(new Check("ops-private.key", false, "missing"));
            if (keypairOk) actions.add("Run `brace ops keypair` to generate one");
            keypairOk = false;
        } else {
            local.add(new Check("ops-private.key", true, "present"));
        }

        boolean ok = local.stream().allMatch(Check::ok);
        return new Result(ok, local, List.of(), actions);
    }

    public static Result runWithRemote(Path projectDir) throws IOException {
        var localResult = run(projectDir);

        var braceFile = projectDir.resolve(".brace");
        String prodUrl = null;
        if (Files.exists(braceFile)) {
            for (var line : Files.readAllLines(braceFile)) {
                line = line.trim();
                if (line.startsWith("ops.prod.url=")) {
                    prodUrl = line.substring("ops.prod.url=".length()).trim();
                    break;
                }
            }
        }
        if (prodUrl == null || prodUrl.isEmpty()) return localResult;

        Path privKey = projectDir.resolve("ops-private.key");
        if (!Files.exists(privKey)) return localResult;

        var remote = new ArrayList<Check>();
        var actions = new ArrayList<>(localResult.actions());

        try {
            var cfg = new CliConfig(prodUrl, privKey.toString(), "ops-authorized-keys", "prod", Map.of());
            try {
                CliAuth.clearCache(projectDir);
                CliAuth.bearer(cfg, projectDir);
                remote.add(new Check("reachable", true, prodUrl));
                remote.add(new Check("authorized", true, "key accepted"));
            } catch (CliAuth.OpsAuthFailure e) {
                remote.add(new Check("reachable", true, prodUrl));
                String detail = "HTTP " + e.status
                    + (e.code != null ? " (" + e.code + ")" : "")
                    + ": " + (e.body == null || e.body.isBlank() ? "(empty body)" : e.body);
                remote.add(new Check("authorized", false, detail));
                addAuthFailureActions(projectDir, privKey, e, actions);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                if (msg.contains("Connection") || msg.contains("refused") || msg.contains("HostNotFound")) {
                    remote.add(new Check("reachable", false, "not reachable: " + msg));
                    actions.add("Verify " + prodUrl + " is reachable");
                } else {
                    remote.add(new Check("reachable", true, prodUrl));
                    remote.add(new Check("authorized", false, msg));
                    actions.add("Add your public key to server's ops-authorized-keys");
                }
            }
        } catch (Exception e) {
            remote.add(new Check("setup", false, e.getMessage()));
        }

        boolean ok = localResult.ok() && remote.stream().allMatch(Check::ok);
        return new Result(ok, localResult.local(), remote, actions);
    }

    public static void print(Result r, CliOutput.Mode mode) {
        if (mode == CliOutput.Mode.JSON) {
            System.out.println(CliOutput.json(Map.of(
                "ok", r.ok(),
                "local", r.local(),
                "remote", r.remote(),
                "actions", r.actions())));
            return;
        }
        System.out.println();
        System.out.println("Local setup");
        for (var c : r.local()) {
            System.out.println("  " + (c.ok() ? "✓" : "✗") + " "
                + pad(c.name(), 24) + " " + c.detail());
        }
        if (!r.remote().isEmpty()) {
            System.out.println();
            System.out.println("Remote (prod)");
            for (var c : r.remote()) {
                System.out.println("  " + (c.ok() ? "✓" : "✗") + " "
                    + pad(c.name(), 24) + " " + c.detail());
            }
        }
        if (!r.actions().isEmpty()) {
            System.out.println();
            System.out.println("Actions:");
            for (var a : r.actions()) System.out.println("  - " + a);
        }
        System.out.println();
    }

    private static void addAuthFailureActions(Path projectDir, Path privKey,
                                              CliAuth.OpsAuthFailure e, List<String> actions) {
        // csrf_required is server-side framework misconfiguration, not a key/auth problem
        if ("csrf_required".equals(e.code)) {
            actions.add("Server's brace version registers /ops/auth with CSRF enabled — "
                + "upgrade the server to brace >= 0.1.3, or remove .sessions(...) on the server.");
            return;
        }

        // Compare CLI's public key against the local authorized-keys file. If it's already
        // listed locally, the "add your key" remediation is wrong — the server is running
        // a different copy of the file, or some other layer is rejecting the request.
        String myPub = null;
        boolean keyInLocalFile = false;
        try {
            myPub = OpsKeys.readKeyFile(privKey.toString()).publicKey();
            Path authFile = projectDir.resolve("ops-authorized-keys");
            if (Files.exists(authFile)) {
                for (var line : Files.readAllLines(authFile)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    if (trimmed.contains(myPub)) { keyInLocalFile = true; break; }
                }
            }
        } catch (Exception ignored) {}

        if (keyInLocalFile) {
            actions.add("Your key is already in local ops-authorized-keys, but the server rejected it. "
                + "Likely causes: (1) server's ops-authorized-keys differs from local (stale deploy), "
                + "(2) clock skew between client and server, "
                + "(3) server returned a framework-level error before the auth handler ran "
                + "(check the response body shown above).");
        } else if (myPub != null) {
            actions.add("Add to server's ops-authorized-keys: " + myPub + "  <label>");
        } else {
            actions.add("Add your public key to server's ops-authorized-keys");
        }
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
