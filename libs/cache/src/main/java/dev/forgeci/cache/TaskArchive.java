package dev.forgeci.cache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A task's declared outputs as one deterministic archive: files in sorted path order, with no
 * timestamps and only the executable bit kept from filesystem metadata — so hashing the same
 * output twice always yields the same digest. Deliberately not a real tar file: a hand-rolled
 * length-prefixed format is trivial to keep byte-for-byte reproducible and needs no dependency.
 */
public final class TaskArchive {

    private static final byte[] MAGIC = "FORGE-ARCHIVE-1\n".getBytes(StandardCharsets.UTF_8);

    private TaskArchive() {}

    /** Builds an archive from every file under {@code projectDirectory} matching {@code outputGlobs}. */
    public static byte[] write(Path projectDirectory, List<String> outputGlobs) {
        List<String> paths = ProjectFiles.matching(projectDirectory, outputGlobs);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(buffer)) {
            data.write(MAGIC);
            data.writeInt(paths.size());
            for (String path : paths) {
                Path file = projectDirectory.resolve(path);
                byte[] content = Files.readAllBytes(file);
                data.writeUTF(path);
                data.writeBoolean(Files.isExecutable(file));
                data.writeInt(content.length);
                data.write(content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    /**
     * Extracts every entry into {@code outputRoot}, rejecting any entry whose path would resolve
     * outside it. An entry is written before the next is read, so a rejected entry midway through
     * still leaves any already-extracted files in place — callers that need atomicity should extract
     * into a fresh directory and then move it into place.
     */
    public static void extract(byte[] archive, Path outputRoot) {
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(archive))) {
            byte[] magic = new byte[MAGIC.length];
            data.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new CorruptArtifactException("archive header is invalid — corrupt or truncated artifact");
            }
            int count = data.readInt();
            if (count < 0) {
                throw new CorruptArtifactException("archive declares a negative entry count");
            }
            Path root = outputRoot.normalize();
            for (int i = 0; i < count; i++) {
                String path = data.readUTF();
                boolean executable = data.readBoolean();
                int length = data.readInt();
                if (length < 0) {
                    throw new CorruptArtifactException("archive entry '" + path + "' declares a negative length");
                }
                byte[] content = new byte[length];
                data.readFully(content);

                Path target = safeResolve(root, path);
                Files.createDirectories(target.getParent());
                Files.write(target, content);
                if (executable) {
                    target.toFile().setExecutable(true);
                }
            }
        } catch (EOFException e) {
            throw new CorruptArtifactException("archive ended before its declared entries were fully read");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Rejects an entry path that would extract outside {@code root}, however it tries to get there. */
    private static Path safeResolve(Path root, String entryPath) {
        if (entryPath.isEmpty() || entryPath.startsWith("/") || entryPath.startsWith("\\")) {
            throw new PathTraversalException("archive entry has an unsafe absolute path: " + entryPath);
        }
        Path resolved = root.resolve(entryPath).normalize();
        if (!resolved.startsWith(root)) {
            throw new PathTraversalException("archive entry escapes the output directory: " + entryPath);
        }
        return resolved;
    }
}
