package dev.forgeci.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Local content-addressed storage for archive bytes: content lands at a path derived from its own
 * digest, written to a temp file first and moved into place only after the digest is known — no
 * caller ever observes a partially written object at its final path.
 */
final class ArtifactStore {

    private final Path objectsDirectory;

    ArtifactStore(Path objectsDirectory) {
        this.objectsDirectory = objectsDirectory;
    }

    /**
     * Writes {@code content}, returning its digest. Idempotent: storing identical bytes twice is a
     * no-op.
     */
    String store(byte[] content) {
        String digest = Digests.sha256(content);
        Path destination = objectPath(digest);
        if (Files.exists(destination)) {
            return digest;
        }
        try {
            Files.createDirectories(destination.getParent());
            Path temp = Files.createTempFile(destination.getParent(), "tmp-", ".part");
            try {
                Files.write(temp, content);
                Files.move(
                        temp,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return digest;
    }

    /**
     * Reads the object stored at {@code digest} and verifies its content still matches both the
     * digest and {@code expectedSize} before returning it — a cache hit is never reported merely
     * because an object-store key exists.
     */
    byte[] load(String digest, long expectedSize) {
        Path source = objectPath(digest);
        byte[] content;
        try {
            content = Files.readAllBytes(source);
        } catch (IOException e) {
            throw new CorruptArtifactException(
                    "cached artifact " + digest + " is missing or unreadable: " + e);
        }
        if (content.length != expectedSize) {
            throw new CorruptArtifactException(
                    "cached artifact "
                            + digest
                            + " has size "
                            + content.length
                            + ", expected "
                            + expectedSize);
        }
        String actualDigest = Digests.sha256(content);
        if (!actualDigest.equals(digest)) {
            throw new CorruptArtifactException(
                    "cached artifact at "
                            + source
                            + " does not match its digest "
                            + digest
                            + " (got "
                            + actualDigest
                            + ")");
        }
        return content;
    }

    private Path objectPath(String digest) {
        return objectsDirectory.resolve(digest.substring(0, 2)).resolve(digest);
    }
}
