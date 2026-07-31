package dev.forgeci.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskArchiveTest {

    /**
     * Mirrors {@code TaskArchive}'s private header — a malicious/corrupt object still has to start
     * here.
     */
    private static final byte[] MAGIC = "FORGE-ARCHIVE-1\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripsFilesInSortedOrderWithNoTimestamps(
            @TempDir Path source, @TempDir Path destination) throws IOException {
        Files.createDirectories(source.resolve("build/nested"));
        Files.writeString(source.resolve("build/b.txt"), "b\n");
        Files.writeString(source.resolve("build/nested/a.txt"), "a\n");

        byte[] archive = TaskArchive.write(source, List.of("build/**"));
        TaskArchive.extract(archive, destination);

        assertEquals("b\n", Files.readString(destination.resolve("build/b.txt")));
        assertEquals("a\n", Files.readString(destination.resolve("build/nested/a.txt")));
    }

    @Test
    void hashingTheSameOutputTwiceProducesTheSameDigest(@TempDir Path source) throws IOException {
        Files.createDirectories(source.resolve("build"));
        Files.writeString(source.resolve("build/out.txt"), "content\n");

        byte[] first = TaskArchive.write(source, List.of("build/**"));
        byte[] second = TaskArchive.write(source, List.of("build/**"));

        assertArrayEquals(first, second);
    }

    @Test
    void anExtractionPathTraversalAttemptIsRejected(@TempDir Path destination) throws IOException {
        byte[] malicious =
                archiveWithSingleEntry("../evil.txt", "pwned".getBytes(StandardCharsets.UTF_8));

        assertThrows(
                PathTraversalException.class, () -> TaskArchive.extract(malicious, destination));
        assertFalse(Files.exists(destination.resolveSibling("evil.txt")));
    }

    @Test
    void anAbsolutePathEntryIsRejected(@TempDir Path destination) throws IOException {
        byte[] malicious =
                archiveWithSingleEntry("/etc/evil.txt", "pwned".getBytes(StandardCharsets.UTF_8));

        assertThrows(
                PathTraversalException.class, () -> TaskArchive.extract(malicious, destination));
    }

    @Test
    void aTruncatedArchiveIsRejectedAsCorrupt(@TempDir Path destination) throws IOException {
        byte[] truncated = new byte[] {MAGIC[0], MAGIC[1]};

        assertThrows(
                CorruptArtifactException.class, () -> TaskArchive.extract(truncated, destination));
    }

    private static byte[] archiveWithSingleEntry(String path, byte[] content) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(buffer)) {
            data.write(MAGIC);
            data.writeInt(1);
            data.writeUTF(path);
            data.writeBoolean(false);
            data.writeInt(content.length);
            data.write(content);
        }
        return buffer.toByteArray();
    }
}
