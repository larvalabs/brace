package io.brace;

import java.net.URI;
import java.net.http.*;


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
        String label = null;
        for (int i = 2; i < args.length - 1; i++) {
            if ("--label".equals(args[i])) {
                label = args[i + 1];
                break;
            }
        }

        var kp = OpsKeys.generateKeypair();
        System.out.println("# Ops keypair generated");
        System.out.println();
        System.out.println("# Add this line to your ops-authorized-keys file:");
        if (label != null) {
            System.out.println(kp.publicKey() + " " + label);
        } else {
            System.out.println(kp.publicKey());
        }
        System.out.println();
        System.out.println("# Save these two lines to a key file (e.g., ops-private.key):");
        System.out.println("# Line 1: private key, Line 2: public key");
        System.out.println(kp.privateKey());
        System.out.println(kp.publicKey());
    }

    private static void opsDashboard(String[] args) {
        String url = "http://localhost:8080";
        String keyPath = null;

        for (int i = 2; i < args.length - 1; i++) {
            if ("--url".equals(args[i])) {
                url = args[i + 1];
            } else if ("--key".equals(args[i])) {
                keyPath = args[i + 1];
            }
        }

        if (keyPath == null) {
            System.err.println("Usage: brace ops dashboard --key <private-key-file> [--url <url>]");
            System.exit(1);
        }

        try {
            var kp = OpsKeys.readKeyFile(keyPath);

            // Sign timestamp with private key
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
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

            // Extract token
            String respBody = response.body();
            int start = respBody.indexOf("\"token\":\"") + 9;
            int end = respBody.indexOf("\"", start);
            String token = respBody.substring(start, end);

            String dashboardUrl = url + "/ops/dashboard?token=" + token;
            System.out.println("Opening dashboard: " + dashboardUrl);

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
