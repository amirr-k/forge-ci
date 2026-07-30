package dev.forgeci.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Maps a verified cache key to the artifact it resolves to. A manifest existing on disk is not by
 * itself proof of a hit — {@link ArtifactStore#load} still has to verify the object it points at.
 */
final class CacheManifestStore {

    private final Path directory;

    CacheManifestStore(Path directory) {
        this.directory = directory;
    }

    void save(String cacheKeyValue, String digest, long size) {
        Properties properties = new Properties();
        properties.setProperty("digest", digest);
        properties.setProperty("size", Long.toString(size));
        Path file = pathFor(cacheKeyValue);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Optional<CacheManifest> load(String cacheKeyValue) {
        Path file = pathFor(cacheKeyValue);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            return Optional.empty();
        }
        String digest = properties.getProperty("digest");
        String size = properties.getProperty("size");
        if (digest == null || size == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CacheManifest(digest, Long.parseLong(size)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Path pathFor(String cacheKeyValue) {
        return directory.resolve(cacheKeyValue + ".manifest");
    }
}
