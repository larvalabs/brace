package io.brace;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class Cli {
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        switch (args[0]) {
            case "new" -> {
                if (args.length < 2) {
                    System.err.println("Usage: brace new <project-name>");
                    System.exit(1);
                }
                ProjectGenerator.generate(args[1]);
            }
            case "ops" -> {
                if (args.length < 2) {
                    System.err.println("Usage: brace ops <command>");
                    printOpsUsage();
                    System.exit(1);
                }
                switch (args[1]) {
                    case "keypair" -> opsKeypair(args);
                    case "dashboard" -> opsDashboard(args);
                    default -> {
                        System.err.println("Unknown ops command: " + args[1]);
                        printOpsUsage();
                        System.exit(1);
                    }
                }
            }
            default -> printUsage();
        }
    }

    private static void opsKeypair(String[] args) {
        String label = "key-1";
        for (int i = 2; i < args.length - 1; i++) {
            if ("--label".equals(args[i])) {
                label = args[i + 1];
                break;
            }
        }

        var kp = OpsKeys.generateKeypair();
        System.out.println("Public key:   " + kp.publicKey());
        System.out.println("Private key:  " + kp.privateKey());
        System.out.println();

        var file = Path.of("ops-authorized-keys");
        try {
            var line = kp.publicKey() + "  " + label + "\n";
            if (Files.exists(file)) {
                Files.writeString(file, line, StandardOpenOption.APPEND);
            } else {
                Files.writeString(file, "# Ops authorized public keys — one per line, optional label after space\n" + line);
            }
            System.out.println("Added to ops-authorized-keys.");
        } catch (Exception e) {
            System.err.println("Failed to write ops-authorized-keys: " + e.getMessage());
        }
        System.out.println("Store the private key securely — it won't be shown again.");
    }

    private static void opsDashboard(String[] args) {
        String url = "http://localhost:8080";
        String keyPath = "ops-private.key";

        for (int i = 2; i < args.length - 1; i++) {
            if ("--url".equals(args[i])) {
                url = args[i + 1];
            } else if ("--key".equals(args[i])) {
                keyPath = args[i + 1];
            }
        }

        OpsKeys.Keypair kp;
        try {
            if (Files.exists(Path.of(keyPath))) {
                kp = OpsKeys.readKeyFile(keyPath);
            } else {
                var envKey = System.getenv("OPS_PRIVATE_KEY");
                if (envKey != null && !envKey.isEmpty()) {
                    // Env var contains raw private key — need to match against authorized keys
                    var authKeysPath = Path.of("ops-authorized-keys");
                    if (!Files.exists(authKeysPath)) {
                        System.err.println("ops-authorized-keys not found — cannot determine public key.");
                        System.exit(1);
                        return;
                    }
                    var authorizedKeys = OpsKeys.loadAuthorizedKeys(authKeysPath.toString());
                    String matchedPub = null;
                    var testSig = OpsKeys.sign("test", envKey);
                    for (var pub : authorizedKeys) {
                        if (OpsKeys.verify("test", testSig, pub)) { matchedPub = pub; break; }
                    }
                    if (matchedPub == null) {
                        System.err.println("OPS_PRIVATE_KEY does not match any key in ops-authorized-keys.");
                        System.exit(1);
                        return;
                    }
                    kp = new OpsKeys.Keypair(matchedPub, envKey);
                } else {
                    System.err.println("Private key not found at " + keyPath + " and OPS_PRIVATE_KEY env var not set.");
                    System.exit(1);
                    return;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to read private key: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            // Sign timestamp with private key
            String timestamp = Instant.now().toString();
            String signature = OpsKeys.sign(timestamp, kp.privateKey());
            String publicKey = kp.publicKey();

            String body = "{\"publicKey\":\"" + publicKey + "\",\"timestamp\":\"" + timestamp + "\",\"signature\":\"" + signature + "\"}";

            var client = HttpClient.newHttpClient();
            var response = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url + "/ops/auth"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("Authentication failed: " + response.body());
                System.exit(1);
            }

            // Extract bearer token
            String respBody = response.body();
            int start = respBody.indexOf("\"token\":\"") + 9;
            int end = respBody.indexOf("\"", start);
            String token = respBody.substring(start, end);

            // Exchange bearer token for a single-use login token
            var loginResponse = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url + "/ops/auth/login-token"))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());

            if (loginResponse.statusCode() != 200) {
                System.err.println("Failed to get login token: " + loginResponse.body());
                System.exit(1);
            }

            String loginBody = loginResponse.body();
            int ltStart = loginBody.indexOf("\"loginToken\":\"") + 14;
            int ltEnd = loginBody.indexOf("\"", ltStart);
            String loginToken = loginBody.substring(ltStart, ltEnd);

            String dashboardUrl = url + "/ops/auth/exchange?token=" + loginToken;
            System.out.println("Opening dashboard...");

            // Try to open browser
            try {
                var os = System.getProperty("os.name").toLowerCase();
                if (os.contains("mac")) {
                    new ProcessBuilder("open", dashboardUrl).start();
                } else if (os.contains("linux")) {
                    new ProcessBuilder("xdg-open", dashboardUrl).start();
                } else if (os.contains("win")) {
                    new ProcessBuilder("cmd", "/c", "start", dashboardUrl).start();
                }
            } catch (Exception e) {
                // Ignore — user can copy the URL
            }
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Brace CLI v0.1.0");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  brace new <name>              Create a new Brace project");
        System.out.println("  brace ops keypair             Generate an Ed25519 keypair for ops auth");
        System.out.println("  brace ops dashboard           Open the ops dashboard in a browser");
    }

    private static void printOpsUsage() {
        System.out.println();
        System.out.println("Ops commands:");
        System.out.println("  brace ops keypair [--label <name>]              Generate an Ed25519 keypair");
        System.out.println("  brace ops dashboard --key <path> [--url <url>]  Open the ops dashboard");
    }
}
