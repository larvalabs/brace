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
            // A path from a committed config may name a FIFO or a device: refuse before reading.
            if (Files.exists(Path.of(path)) && (!Files.isRegularFile(Path.of(path)) || Files.size(Path.of(path)) > 64 * 1024)) {
                throw new RuntimeException("Key file must be a regular file of at most 64 KB: " + path);
            }
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
     * Load authorized public keys and their scope ceilings from a file (UTF-8; a byte-order mark
     * at the very start of the file is ignored). Every key line states its scope; nothing is
     * inferred, so there is no way to write a line that quietly means more than it says. The
     * format, line by line:
     * <ul>
     *   <li>{@code #} at the start of a line or after whitespace starts a comment that runs to the
     *       end of the line. Blank and comment-only lines are ignored.</li>
     *   <li>Otherwise the first whitespace-separated token is the public key: the base64 Ed25519
     *       key {@code brace ops keypair} prints, with an optional legacy {@code ed25519:} prefix.
     *       Anything else (truncated or padded base64, an OpenSSH {@code ssh-ed25519 AAAA…} line)
     *       fails startup: such a line could never authenticate anyone.</li>
     *   <li>Exactly one of the remaining tokens must be {@code scope:read} or {@code scope:control}
     *       ({@code scope:} lowercase, the value case-insensitive). <strong>A line with no scope
     *       token fails startup</strong> (final review; such a line used to mean CONTROL, which is
     *       what {@code KEY read ci-agent}, {@code KEY ci-agent read-only} and {@code KEY (read)}
     *       silently got). Add {@code scope:control} to keep that meaning.</li>
     *   <li>Any other token containing {@code :} or {@code =} ({@code scope: read},
     *       {@code Scope:read}, {@code scope=read}, {@code --scope=read}, {@code role:read},
     *       {@code scope:read-only}, a second scope token) fails startup.</li>
     *   <li>Every other token is label text ({@code ci-agent}, {@code control}, {@code read}).</li>
     *   <li>A comment on a key line may not contain {@code scope:} or {@code scope=} (a scope
     *       written after a {@code #} is ignored, which is almost certainly not what was meant),
     *       and a line may not contain non-ASCII whitespace, a byte-order mark, or a look-alike of
     *       {@code :} or {@code =} such as the fullwidth colon: each makes a line read differently
     *       from how it parses.</li>
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
     * {@link #loadAuthorizedKeys(String)}; with {@code logKeys} each key's fingerprint and scope is
     * logged ({@code ops.key}). Never logs the key itself.
     *
     * <p>L1 (2026-11 review): a malformed scope used to fall back to CONTROL silently, so a
     * hand-written {@code scope:read-only} handed an agent a full-privilege key. Two rounds of
     * refusing particular wrong spellings left others ({@code KEY read ci-agent}); the final
     * review made the scope token mandatory instead. A key listed twice keeps the lower scope.
     */
    public static Map<String, OpsScope> loadAuthorizedKeys(String path, boolean logKeys) {
        try {
            var keys = new LinkedHashMap<String, OpsScope>();
            int lineNo = 0;
            for (String line : Files.readAllLines(Path.of(path))) {
                lineNo++;
                KeyLine parsed;
                try {
                    parsed = parseLine(line, lineNo == 1, false);
                } catch (LineProblem p) {
                    throw new IllegalStateException(p.formatHint ? invalid(path, lineNo, p.getMessage())
                        : "Invalid ops authorized keys file " + path + ", line " + lineNo + ": " + p.getMessage());
                }
                if (parsed == null) continue; // blank or comment-only: free text
                String key = parsed.key();
                OpsScope scope = parsed.scope();
                OpsScope existing = keys.get(key);
                if (existing != null) {
                    scope = scope.min(existing);
                    Log.warn("ops authorized keys " + path + " line " + lineNo + ": key "
                        + fingerprint(key) + " is listed more than once; keeping the lower scope ("
                        + scope.wire() + ")");
                }
                keys.put(key, scope);
            }
            if (logKeys) {
                for (var e : keys.entrySet()) {
                    Log.info("ops.key", Map.of("kid", fingerprint(e.getKey()), "scope", e.getValue().wire()));
                }
            }
            return keys;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load authorized keys from: " + path, e);
        }
    }

    /**
     * One key line of an authorized-keys file: the public key (any legacy {@code ed25519:}
     * prefix removed), its scope, and its label words joined by single spaces ({@code ""} when
     * there are none). {@code scope} is null only from a lenient parse of a line without one.
     */
    record KeyLine(String key, OpsScope scope, String label) {}

    /** Why a key line is refused; {@code formatHint}: the message gets the format summary appended. */
    static final class LineProblem extends RuntimeException {
        final boolean formatHint;

        LineProblem(String message, boolean formatHint) {
            super(message, null, false, false);
            this.formatHint = formatHint;
        }
    }

    /**
     * Parse one line of an authorized-keys file: the single implementation of the grammar
     * {@link #loadAuthorizedKeys(String)} documents, shared with {@code brace ops keypair}.
     * Returns null for a blank or comment-only line. {@code firstLine}: a leading byte-order mark
     * is dropped (Windows editors write one, and it would otherwise stick to the first key).
     *
     * <p>Strict ({@code lenient} false, the server): every rule applies and a violation throws
     * {@link LineProblem}. Lenient (the CLI matching an existing entry by label to replace it):
     * nothing is refused, so a line the server would now reject, above all one with no scope
     * token written before 0.1.11, still yields its label and can be replaced; any token starting
     * {@code scope:} is taken as the scope, never as label text, and {@code scope} is null when
     * the line has none or it is not a valid one. A rotation that missed an old entry for its
     * label would leave that key trusted, so the lenient reading must not be stricter than the
     * server's about where the label is.
     */
    static KeyLine parseLine(String line, boolean firstLine, boolean lenient) {
        if (firstLine && line.startsWith("﻿")) line = line.substring(1);
        String content = stripComment(line);
        if (content.isBlank()) return null;
        if (!lenient) {
            String confusing = confusingCharacter(line);
            if (confusing != null) throw new LineProblem(confusing, true);
            String comment = line.substring(content.length()).toLowerCase(java.util.Locale.ROOT);
            if (comment.matches(".*scope\\s*[:=].*")) {
                throw new LineProblem("the comment contains a scope, which is ignored there; "
                    + "put scope:read or scope:control before the '#'", true);
            }
        }
        String[] tokens = content.strip().split("\\s+");
        String key = tokens[0];
        // Tolerate a legacy "ed25519:" type prefix written by older `brace ops keypair`.
        // Base64 never contains ':', so a leading "algo:" is unambiguously a prefix — strip
        // it so a key file written by a buggy CLI still matches the raw base64 the client sends.
        if (key.startsWith("ed25519:")) key = key.substring("ed25519:".length());
        if (!lenient) {
            String keyProblem = keyProblem(tokens);
            if (keyProblem != null) throw new LineProblem(keyProblem, false);
        }
        OpsScope scope = null;
        boolean scopeSeen = false;
        var label = new java.util.ArrayList<String>();
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if (lenient) {
                if (!token.startsWith("scope:")) {
                    label.add(token);
                } else if (!scopeSeen) {
                    scope = OpsScope.parseOrNull(token.substring("scope:".length()));
                    scopeSeen = true;
                }
                continue;
            }
            if (token.indexOf(':') < 0 && token.indexOf('=') < 0) { // label text
                label.add(token);
                continue;
            }
            OpsScope parsed = token.startsWith("scope:")
                ? OpsScope.parseOrNull(token.substring("scope:".length())) : null;
            if (parsed == null) {
                throw new LineProblem("a token with ':' or '=' that is not scope:read or scope:control", true);
            }
            if (scope != null) throw new LineProblem("more than one scope token", true);
            scope = parsed;
        }
        if (scope == null && !lenient) {
            throw new LineProblem("the line has no scope. Every key line "
                + "must say scope:read (status, logs and errors only) or scope:control (everything). "
                + "A line without one used to mean control: add scope:control to keep that, or "
                + "scope:read if the key was meant to be read-only", true);
        }
        return new KeyLine(key, scope, String.join(" ", label));
    }

    private static String invalid(String path, int lineNo, String what) {
        return "Invalid ops authorized keys file " + path + ", line " + lineNo + ": " + what
            + ". Format, one of:  KEY scope:read [label words...] [# comment]  or  "
            + "KEY scope:control [label words...] [# comment]. Write scope: in lowercase with no space "
            + "after the colon, exactly once.";
    }

    /** Characters that read as {@code :} or {@code =} but are not; a token written with one is not what it looks like. */
    private static final String LOOK_ALIKES = "：﹕꞉∶ː׃︓։܃܄⁚"
        + "＝═⁼₌꞊﹦";

    /**
     * Why a key line cannot be trusted to mean what it appears to say, or null: it contains
     * whitespace other than ASCII space and tab (a no-break or ideographic space joins or splits
     * tokens invisibly), a byte-order mark, a control character, or a look-alike of {@code :} or
     * {@code =}. Also used by {@code brace ops keypair} to refuse a label the server would.
     */
    static String confusingCharacter(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') continue;
            String what = null;
            if (c == '﻿') {
                what = "a byte-order mark (only allowed at the very start of the file)";
            } else if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                what = "a whitespace character other than space or tab";
            } else if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) {
                what = "an invisible control or format character";
            } else if (LOOK_ALIKES.indexOf(c) >= 0) {
                what = "a look-alike of ':' or '='";
            }
            if (what != null) {
                return "column " + (i + 1) + " is " + what + " (" + String.format("U+%04X", (int) c)
                    + "); retype the line with plain ASCII spaces and ':'";
            }
        }
        return null;
    }

    /** The raw length of an X.509-encoded Ed25519 public key: a 12-byte header and the 32-byte key. */
    private static final int ED25519_X509_LENGTH = 44;

    /** Why the key at the start of a line can never match a client's key, or null. */
    private static String keyProblem(String[] tokens) {
        String key = tokens[0];
        String hint = " Use the public key `brace ops keypair` prints (it also writes this file).";
        if (key.startsWith("ssh-") || key.startsWith("ecdsa-") || key.startsWith("AAAAC3NzaC1lZDI1NTE5")
                || key.startsWith("AAAAB3NzaC1yc2E")) {
            return "this is an OpenSSH public key line. Ops keys are not SSH keys: the server expects the "
                + "base64 key from `brace ops keypair`, then scope:read or scope:control." + hint;
        }
        if (key.startsWith("ed25519:")) key = key.substring("ed25519:".length());
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException e) {
            return "the first token is not a base64 public key." + hint;
        }
        if (decoded.length != ED25519_X509_LENGTH) {
            return "the first token decodes to " + decoded.length + " bytes; an Ed25519 public key is "
                + ED25519_X509_LENGTH + " (the key looks truncated or has extra characters)." + hint;
        }
        // L1 verification: the decoder accepts more than one spelling of the same bytes (missing
        // padding, non-zero unused bits in the last character). The server matches keys as text,
        // so a second spelling was a second entry with its own scope, defeating "a key listed
        // twice keeps the lower scope". Only the canonical spelling is accepted.
        if (!Base64.getEncoder().encodeToString(decoded).equals(key)) {
            return "the first token is not the key's canonical base64 spelling (missing '=' padding, or "
                + "a last character that decodes to the same bytes as another); write the key exactly as "
                + "printed, so one key cannot be listed twice under different scopes." + hint;
        }
        try {
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            return "the first token is not an Ed25519 public key." + hint;
        }
        return null;
    }

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
