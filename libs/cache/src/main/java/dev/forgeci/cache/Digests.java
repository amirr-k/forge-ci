package dev.forgeci.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 content hashing, the digest algorithm every cache-key contributor is built from. */
public final class Digests {

    /**
     * The digest of zero bytes — the stand-in for "no output" (an empty archive, an empty list).
     */
    public static final String EMPTY = sha256(new byte[0]);

    private Digests() {}

    public static String sha256(byte[] content) {
        return HexFormat.of().formatHex(newDigest().digest(content));
    }

    public static String sha256(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sha256(String content) {
        return sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }
}
