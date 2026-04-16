package io.brace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

public class CliOps {

    private static final HttpClient http = HttpClient.newHttpClient();

    private CliOps() {}

    public static int keypair(Path projectDir, String[] args) {
        String label = "key-1";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--label".equals(args[i])) { label = args[i + 1]; break; }
        }
        var kp = OpsKeys.generateKeypair();
        System.out.println("Public key:   " + kp.publicKey());
        System.out.println("Private key:  " + kp.privateKey());
        System.out.println();

        Path file = projectDir.resolve("ops-authorized-keys");
        try {
            String line = "ed25519:" + kp.publicKey() + "  " + label + "\n";
            if (Files.exists(file)) {
                Files.writeString(file, line, StandardOpenOption.APPEND);
            } else {
                Files.writeString(file,
                    "# Ops authorized public keys — one per line, optional label\n" + line);
            }
            System.out.println("Added to ops-authorized-keys.");
        } catch (Exception e) {
            CliOutput.printError("Failed to write ops-authorized-keys: " + e.getMessage());
            return 1;
        }
        System.out.println("Store the private key securely — it won't be shown again.");
        return 0;
    }

    public static int dashboard(Path projectDir, String[] args) {
        try {
            var cfg = CliConfig.load(projectDir, args);
            String bearer = CliAuth.bearer(cfg, projectDir);

            // Exchange bearer for single-use login token
            var loginResponse = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(cfg.url() + "/ops/auth/login-token"))
                    .header("Authorization", "Bearer " + bearer)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());

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
