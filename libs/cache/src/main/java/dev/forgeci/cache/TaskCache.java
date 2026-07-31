package dev.forgeci.cache;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The local deterministic cache, as seen by the CLI: compute a task's key, look up whether it's
 * already verified, restore a hit into the workspace, or store a fresh result. Every write path
 * (archive → digest → object store → manifest) follows the content-addressed commit protocol in
 * {@code architecture.md#content-addressed-artifacts}; nothing here is exposed as a hit until its
 * bytes have been re-verified against the digest that named them.
 */
public final class TaskCache {

    private final Path projectDirectory;
    private final ArtifactStore artifacts;
    private final CacheManifestStore manifests;
    private final CacheKeyRecordStore records;
    private final RemoteArtifactClient remote;
    private final long remoteProjectId;

    public TaskCache(Path projectDirectory) {
        this(projectDirectory, null, -1);
    }

    /**
     * Local mode's cache, plus a remote fallback: a local miss checks the remote store before
     * declaring the task un-cached, and a fresh local store also uploads so another workspace
     * pointed at the same control plane can reuse it. Local lookups and stores always happen
     * first and always succeed on their own — {@code remote} being unreachable degrades to
     * exactly phase 1/2 behavior, it never fails the build.
     */
    public TaskCache(Path projectDirectory, RemoteArtifactClient remote, long remoteProjectId) {
        this.projectDirectory = projectDirectory;
        CacheLayout layout = new CacheLayout(projectDirectory);
        this.artifacts = new ArtifactStore(layout.objects());
        this.manifests = new CacheManifestStore(layout.manifests());
        this.records = new CacheKeyRecordStore(layout.keys());
        this.remote = remote;
        this.remoteProjectId = remoteProjectId;
    }

    /** A verified artifact ready to be restored into the workspace. */
    public record CacheHit(String digest, long size, byte[] archive) {}

    /**
     * A cache hit requires both a manifest for the key and a stored object that still matches the
     * digest and size that manifest recorded — a manifest existing on its own is never enough.
     * Checks the local cache first; only on a local miss, and only when a remote store is
     * configured, does it ask the remote store — local mode's zero-infrastructure behavior is
     * unchanged whenever no remote is configured or it cannot be reached.
     */
    public Optional<CacheHit> lookup(CacheKey key) {
        Optional<CacheHit> local = localLookup(key);
        if (local.isPresent() || remote == null) {
            return local;
        }
        return remoteLookup(key);
    }

    private Optional<CacheHit> localLookup(CacheKey key) {
        return manifests.load(key.value())
                .flatMap(
                        manifest -> {
                            try {
                                byte[] archive = artifacts.load(manifest.digest(), manifest.size());
                                return Optional.of(new CacheHit(manifest.digest(), manifest.size(), archive));
                            } catch (CorruptArtifactException e) {
                                return Optional.empty();
                            }
                        });
    }

    /** A remote hit is also written into the local cache, so the next lookup for this key is a local hit. */
    private Optional<CacheHit> remoteLookup(CacheKey key) {
        try {
            return remote.lookup(remoteProjectId, key.value())
                    .map(
                            bytes -> {
                                String digest = artifacts.store(bytes);
                                manifests.save(key.value(), digest, bytes.length);
                                return new CacheHit(digest, bytes.length, bytes);
                            });
        } catch (RemoteCacheUnavailableException | CorruptArtifactException e) {
            return Optional.empty();
        }
    }

    /** Extracts a hit's archive into the project directory, rejecting any path-traversal attempt. */
    public void restore(CacheHit hit) {
        TaskArchive.extract(hit.archive(), projectDirectory);
    }

    /**
     * Archives the task's declared outputs, commits them to the local object store, records the
     * manifest, and — when a remote store is configured — best-effort uploads the same bytes so
     * another workspace can reuse them. A remote upload failure never fails this call: local mode
     * must keep working with zero infrastructure regardless of remote reachability.
     */
    public CacheHit store(CacheKey key, List<String> outputGlobs) {
        byte[] archive = TaskArchive.write(projectDirectory, outputGlobs);
        String digest = artifacts.store(archive);
        manifests.save(key.value(), digest, archive.length);
        if (remote != null) {
            try {
                remote.upload(remoteProjectId, key.value(), archive);
            } catch (RemoteCacheUnavailableException e) {
                // remote cache is best-effort; the local store above already succeeded
            }
        }
        return new CacheHit(digest, archive.length, archive);
    }

    /** The digest a verified manifest for {@code key} points at, without loading or verifying the object. */
    public Optional<String> manifestDigest(CacheKey key) {
        return manifests.load(key.value()).map(CacheManifest::digest);
    }

    /** Remembers {@code key} as the most recently computed key for {@code taskName}. */
    public void recordKey(String taskName, CacheKey key) {
        records.save(taskName, key);
    }

    public Optional<CacheKey> lastKey(String taskName) {
        return records.load(taskName);
    }

    /** A human explanation of why a task hit or missed, for {@code forge plan} and {@code forge explain}. */
    public static String explainReason(Optional<CacheKey> previous, CacheKey current, boolean hit) {
        if (hit) {
            return "inputs unchanged since the last cached run";
        }
        if (previous.isEmpty()) {
            return "no cache entry for this task yet";
        }
        CacheKey before = previous.get();
        if (before.schemaVersion() != current.schemaVersion()) {
            return "cache-key schema version changed";
        }
        if (!before.taskDefinitionDigest().equals(current.taskDefinitionDigest())) {
            return "task definition, command, or environment changed";
        }
        if (!before.toolchain().equals(current.toolchain())) {
            return "toolchain changed from " + before.toolchain() + " to " + current.toolchain();
        }
        if (!before.dependencyArtifactsDigest().equals(current.dependencyArtifactsDigest())) {
            String changedDependency = firstDifferingKey(before.dependencyDigests(), current.dependencyDigests());
            return changedDependency != null
                    ? changedDependency + " output changed"
                    : "a dependency's output changed";
        }
        if (!before.sourceInputsDigest().equals(current.sourceInputsDigest())) {
            return sourceInputReason(before.sourceInputDigests(), current.sourceInputDigests());
        }
        return "cache key matches a prior run, but no verified artifact was found for it";
    }

    private static String sourceInputReason(Map<String, String> before, Map<String, String> current) {
        for (String path : new TreeMap<>(current).keySet()) {
            if (!before.containsKey(path)) {
                return "source input " + path + " added";
            }
            if (!before.get(path).equals(current.get(path))) {
                return "source input " + path + " changed";
            }
        }
        for (String path : new TreeMap<>(before).keySet()) {
            if (!current.containsKey(path)) {
                return "source input " + path + " removed";
            }
        }
        return "source inputs changed";
    }

    private static String firstDifferingKey(Map<String, String> before, Map<String, String> current) {
        for (String name : new TreeMap<>(current).keySet()) {
            if (!before.containsKey(name) || !before.get(name).equals(current.get(name))) {
                return name;
            }
        }
        for (String name : before.keySet()) {
            if (!current.containsKey(name)) {
                return name;
            }
        }
        return null;
    }
}
