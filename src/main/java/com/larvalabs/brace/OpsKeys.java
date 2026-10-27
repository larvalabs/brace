package com.larvalabs.brace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ed25519 keypair generation, signing, verification, and authorized-keys file parsing.
 */
public class OpsKeys {

    public record Keypair(String publicKey, String privateKey) {}

    /** Generate a new Ed25519 keypair. Keys are base64-encoded. */
    public static Keypair generateKeypair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = kpg.generateKeyPair();
            String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
            String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
            return new Keypair(pub, priv);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ed25519 not available", e);
        }
    }

    /**
     * Read a private key file. Format is two lines: private key, then public key (both base64).
     * Returns a Keypair.
     */
    public static Keypair readKeyFile(String path) {
        try {
            var lines = Files.readAllLines(Path.of(path)).stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
            if (lines.size() < 2) {
                throw new RuntimeException("Key file must contain private key and public key on separate lines");
            }
            return new Keypair(lines.get(1), lines.get(0));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read key file: " + path, e);
        }
    }

    /** Sign a message with an Ed25519 private key (base64-encoded). Returns base64 signature. */
    public static String sign(String message, String privateKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PrivateKey key = kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 signing failed", e);
        }
    }

    /**
     * The message an ops auth request (protocol v3) signs:
     * {@code "brace-ops-auth-v3\n" + origin + "\n" + publicKey + "\n" + timestamp + "\n" + nonce}.
     *
     * <p>M14 (2026-10 security review): v2 signed only the key, timestamp and nonce, so a
     * signature made for one server was good at any other within the ±30s window. The CLI takes
     * the server's URL from the committed {@code .brace} file, so a malicious change to
     * {@code ops.prod.url} could collect a fresh signature and relay it to real prod. The origin
     * ({@link Brace#normalizeOrigin normalized} {@code scheme://host[:port]}) binds the signature
     * to the server the CLI meant, and the server checks it against the host it was reached at.
     *
     * <p>Newline-delimited concatenation is unambiguous because no component can contain a
     * newline: the origin is normalized and validated, the public key is base64, the timestamp is
     * ISO-8601 and the nonce is base64url. The leading tag keeps a v3 signature from ever
     * verifying as another message format.
     */
    public static String v3AuthMessage(String origin, String publicKey, String timestamp, String nonce) {
        return "brace-ops-auth-v3\n" + origin + "\n" + publicKey + "\n" + timestamp + "\n" + nonce;
    }

    /** Verify a signature against a message and public key (base64-encoded). */
    public static boolean verify(String message, String signatureBase64, String publicKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey key = kf.generatePublic(new X509EncodedKeySpec(keyBytes));
            byte[] sigBytes = Base64.getDecoder().decode(signatureBase64);
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return sig.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Load authorized public keys and their scope ceilings from a file.
     * Format: one key per line, then optional tokens — a {@code scope:read} /
     * {@code scope:control} marker and/or a free-text label, in any order. A line
     * with no scope marker defaults to {@link OpsScope#CONTROL} (backward compatible
     * with key files written before scoping existed). Lines starting with {@code #}
     * are comments; empty lines are ignored.
     *
     * <p>The returned map's value is the key's scope <em>ceiling</em>: a token minted
     * for that key can never exceed it (see {@code OpsHandler.auth}).
     */
    public static Map<String, OpsScope> loadAuthorizedKeys(String path) {
        try {
            var keys = new LinkedHashMap<String, OpsScope>();
            for (String line : Files.readAllLines(Path.of(path))) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] tokens = line.split("\\s+");
                String key = tokens[0];
                // Tolerate a legacy "ed25519:" type prefix written by older `brace ops keypair`.
                // Base64 never contains ':', so a leading "algo:" is unambiguously a prefix — strip
                // it so a key file written by a buggy CLI still matches the raw base64 the client sends.
                if (key.startsWith("ed25519:")) key = key.substring("ed25519:".length());
                OpsScope scope = OpsScope.CONTROL;
                for (int i = 1; i < tokens.length; i++) {
                    if (tokens[i].startsWith("scope:")) {
                        scope = OpsScope.parse(tokens[i].substring("scope:".length()), OpsScope.CONTROL);
                        break;
                    }
                }
                keys.put(key, scope);
            }
            return keys;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load authorized keys from: " + path, e);
        }
    }

    /**
     * Short, stable fingerprint of a base64 public key — the first 12 chars of
     * base64url(SHA-256(key)). Embedded in tokens as {@code kid} so ops access can be
     * attributed to a key without carrying the full key in every token.
     */
    public static String fingerprint(String publicKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(publicKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
