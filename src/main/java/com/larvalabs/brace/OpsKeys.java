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
     * Load authorized public keys and their scope ceilings from a file (UTF-8; a leading
     * byte-order mark is ignored). The format, line by line:
     * <ul>
     *   <li>{@code #} at the start of a line or after whitespace starts a comment that runs to the
     *       end of the line. Blank and comment-only lines are ignored.</li>
     *   <li>Otherwise the first whitespace-separated token is the public key (base64, with an
     *       optional legacy {@code ed25519:} prefix). The remaining tokens, in any order:</li>
     *   <li>A token containing {@code :} or {@code =} must be exactly {@code scope:read} or
     *       {@code scope:control} ({@code scope:} lowercase, the value case-insensitive), at most
     *       once per line. Anything else with {@code :} or {@code =} ({@code scope: read},
     *       {@code Scope:read}, {@code scope=read}, {@code --scope=read}, {@code role:read},
     *       {@code scope:read-only}, a second scope token) fails startup.</li>
     *   <li>Every other token is label text ({@code ci-agent}, {@code control},
     *       {@code --label scope-ci}), with one exception: a lone {@code read}, {@code ro},
     *       {@code readonly}, {@code read-only} or {@code control} as the only token after the
     *       key ({@code KEY read}) fails startup, asking for {@code scope:read}.</li>
     *   <li>A line with no scope token is {@link OpsScope#CONTROL} (backward compatible with key
     *       files written before scoping existed).</li>
     * </ul>
     * Failures name the file and line number, never the key.
     *
     * <p>The returned map's value is the key's scope <em>ceiling</em>: a token minted
     * for that key can never exceed it (see {@code OpsHandler.auth}).
     */
    public static Map<String, OpsScope> loadAuthorizedKeys(String path) {
        return loadAuthorizedKeys(path, false);
    }

    /**
     * {@link #loadAuthorizedKeys(String)}; with {@code logKeys} each key's fingerprint and effective
     * scope is logged ({@code ops.key}), including keys that fell back to CONTROL because their line
     * has no scope token. Never logs the key itself.
     *
     * <p>L1 (2026-11 review): a malformed scope used to fall back to CONTROL silently, so a
     * hand-written {@code scope:read-only} handed an agent a full-privilege key. The grammar in
     * {@link #loadAuthorizedKeys(String)} (rewritten after the review of that fix, so that a
     * label such as {@code control} or a trailing comment loads, while {@code --scope=read} and
     * {@code role:read} fail) leaves no token that silently means CONTROL. A key listed twice
     * keeps the lower scope.
     */
    public static Map<String, OpsScope> loadAuthorizedKeys(String path, boolean logKeys) {
        try {
            var keys = new LinkedHashMap<String, OpsScope>();
            var implicit = new java.util.HashSet<String>();
            int lineNo = 0;
            for (String line : Files.readAllLines(Path.of(path))) {
                lineNo++;
                // A leading UTF-8 byte-order mark (Windows editors) would otherwise stick to the
                // first key, which then silently never matches.
                if (lineNo == 1 && line.startsWith("﻿")) line = line.substring(1);
                line = stripComment(line).strip();
                if (line.isEmpty()) continue;
                String[] tokens = line.split("\\s+");
                String key = tokens[0];
                // Tolerate a legacy "ed25519:" type prefix written by older `brace ops keypair`.
                // Base64 never contains ':', so a leading "algo:" is unambiguously a prefix — strip
                // it so a key file written by a buggy CLI still matches the raw base64 the client sends.
                if (key.startsWith("ed25519:")) key = key.substring("ed25519:".length());
                if (tokens.length == 2 && BARE_SCOPE_WORDS.contains(tokens[1].toLowerCase(java.util.Locale.ROOT))) {
                    // The classic mistake: `KEY read`. Alone after the key, it is surely meant as
                    // a scope, and as a label it would silently grant CONTROL.
                    String meant = tokens[1].toLowerCase(java.util.Locale.ROOT).startsWith("c") ? "control" : "read";
                    throw new IllegalStateException(badScope(path, lineNo, "'" + tokens[1]
                        + "' alone after the key is not a scope; write scope:" + meant));
                }
                OpsScope scope = null;
                for (int i = 1; i < tokens.length; i++) {
                    String token = tokens[i];
                    if (token.indexOf(':') < 0 && token.indexOf('=') < 0) continue; // label text
                    OpsScope parsed = token.startsWith("scope:")
                        ? OpsScope.parseOrNull(token.substring("scope:".length())) : null;
                    if (parsed == null) {
                        throw new IllegalStateException(badScope(path, lineNo,
                            "a token with ':' or '=' that is not scope:read or scope:control"));
                    }
                    if (scope != null) {
                        throw new IllegalStateException(badScope(path, lineNo, "more than one scope token"));
                    }
                    scope = parsed;
                }
                boolean explicit = scope != null;
                if (scope == null) scope = OpsScope.CONTROL;
                OpsScope existing = keys.get(key);
                if (existing != null) {
                    scope = scope.min(existing);
                    Log.warn("ops authorized keys " + path + " line " + lineNo + ": key "
                        + fingerprint(key) + " is listed more than once; keeping the lower scope ("
                        + scope.wire() + ")");
                }
                keys.put(key, scope);
                if (explicit) implicit.remove(key); else if (existing == null) implicit.add(key);
            }
            if (logKeys) {
                for (var e : keys.entrySet()) {
                    Log.info("ops.key", Map.of("kid", fingerprint(e.getKey()), "scope", e.getValue().wire(),
                        "scopeSource", implicit.contains(e.getKey()) ? "default (no scope: token)" : "explicit"));
                }
            }
            return keys;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load authorized keys from: " + path, e);
        }
    }

    private static String badScope(String path, int lineNo, String what) {
        return "Invalid ops authorized keys file " + path + ", line " + lineNo + ": " + what
            + ". Format: KEY [scope:read|scope:control] [label words...] [# comment]. Write scope: "
            + "in lowercase with no space after the colon, at most once; omit it for control.";
    }

    /** Words that, as the only token after a key, are a mistyped scope rather than a label. */
    private static final java.util.Set<String> BARE_SCOPE_WORDS =
        java.util.Set.of("read", "ro", "readonly", "read-only", "control");

    /** Drops a comment: a {@code #} at the start of the line or after whitespace, to end of line. */
    static String stripComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
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
