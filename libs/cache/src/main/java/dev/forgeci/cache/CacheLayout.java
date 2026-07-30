package dev.forgeci.cache;

import java.nio.file.Path;

/** Where local mode keeps its cache on disk, rooted under the project directory. */
final class CacheLayout {

    private final Path root;

    CacheLayout(Path projectDirectory) {
        this.root = projectDirectory.resolve(".forge").resolve("cache");
    }

    Path objects() {
        return root.resolve("objects");
    }

    Path manifests() {
        return root.resolve("manifests");
    }

    Path keys() {
        return root.resolve("keys");
    }
}
