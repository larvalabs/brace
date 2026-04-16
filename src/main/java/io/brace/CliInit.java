package io.brace;

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
        var existing = Files.exists(gitignore) ? Files.readString(gitignore) : "";
        var needs = new ArrayList<String>();
        if (!existing.contains(".brace.local")) needs.add(".brace.local");
        if (!existing.contains("ops-private.key")) needs.add("ops-private.key");
        if (!needs.isEmpty()) {
            String addition = (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n")
                + "\n# brace CLI\n" + String.join("\n", needs) + "\n";
            Files.writeString(gitignore, existing + addition);
            local.add(new Check(".gitignore", true, "added " + String.join(", ", needs)));
            actions.add("Updated .gitignore");
        } else {
            local.add(new Check(".gitignore", true, "entries OK"));
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
        if (!r.actions().isEmpty()) {
            System.out.println();
            System.out.println("Actions:");
            for (var a : r.actions()) System.out.println("  - " + a);
        }
        System.out.println();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
