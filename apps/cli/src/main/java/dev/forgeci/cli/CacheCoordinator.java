package dev.forgeci.cli;

import dev.forgeci.cache.CacheKey;
import dev.forgeci.cache.CacheKeyCalculator;
import dev.forgeci.cache.Digests;
import dev.forgeci.cache.TaskCache;
import dev.forgeci.cache.ToolchainFingerprint;
import dev.forgeci.core.graph.TaskGraph;
import dev.forgeci.core.model.TaskDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves cache decisions for one command invocation: computes each task's cache key from the
 * current workspace state, checks it against the local cache, and tracks the resulting artifact
 * digest so dependents further down the same run see a real "dependency artifact changed" signal
 * instead of having to recompute their whole upstream subtree.
 *
 * <p>A task not touched by this run (outside the selected set) still needs a digest to feed its
 * dependents' keys — {@link #digestFor} resolves that lazily from the last time it was recorded,
 * without re-hashing its inputs, since nothing selected this run could have changed it.
 */
final class CacheCoordinator {

    private final Path projectDirectory;
    private final TaskGraph graph;
    private final TaskCache cache;
    private final String toolchain;
    private final Map<String, String> resolvedDigests = new ConcurrentHashMap<>();

    CacheCoordinator(ProjectWorkspace workspace) {
        this.projectDirectory = workspace.directory();
        this.graph = workspace.graph();
        this.cache = new TaskCache(projectDirectory);
        this.toolchain = ToolchainFingerprint.current();
    }

    record Decision(CacheKey key, boolean hit, TaskCache.CacheHit cacheHit, String reason) {}

    Decision decide(String taskName) {
        TaskDefinition task = graph.task(taskName);
        Map<String, String> environment = selectEnvironment(task.environment());
        Map<String, String> dependencyDigests = new TreeMap<>();
        for (String dependency : task.dependsOn()) {
            dependencyDigests.put(dependency, digestFor(dependency));
        }

        CacheKey key = CacheKeyCalculator.compute(projectDirectory, task, environment, dependencyDigests, toolchain);
        Optional<CacheKey> previous = cache.lastKey(taskName);
        Optional<TaskCache.CacheHit> hit = task.cacheable() ? cache.lookup(key) : Optional.empty();
        String reason = TaskCache.explainReason(previous, key, hit.isPresent());
        cache.recordKey(taskName, key);
        hit.ifPresent(h -> resolvedDigests.put(taskName, h.digest()));
        return new Decision(key, hit.isPresent(), hit.orElse(null), reason);
    }

    void restore(TaskCache.CacheHit hit) {
        cache.restore(hit);
    }

    TaskCache.CacheHit store(CacheKey key, List<String> outputGlobs) {
        return cache.store(key, outputGlobs);
    }

    /** Records the artifact digest a task actually produced this run, or {@code null} when it produced none. */
    void recordExecuted(String taskName, TaskCache.CacheHit stored) {
        resolvedDigests.put(taskName, stored != null ? stored.digest() : freshPlaceholder());
    }

    /** Records that a selected task will need to run but hasn't yet (a {@code forge plan} preview). */
    void recordPending(String taskName) {
        resolvedDigests.put(taskName, freshPlaceholder());
    }

    private String digestFor(String taskName) {
        return resolvedDigests.computeIfAbsent(taskName, this::lastKnownDigest);
    }

    /** Not selected this run, so nothing could have changed it — reuse what it last resolved to. */
    private String lastKnownDigest(String taskName) {
        return cache.lastKey(taskName).flatMap(cache::manifestDigest).orElse(Digests.EMPTY);
    }

    /** A digest nothing will ever match, so anything depending on a not-yet-known result also misses. */
    private static String freshPlaceholder() {
        return Digests.sha256(UUID.randomUUID().toString());
    }

    private static Map<String, String> selectEnvironment(List<String> allowlist) {
        Map<String, String> selected = new TreeMap<>();
        for (String name : allowlist) {
            String value = System.getenv(name);
            if (value != null) {
                selected.put(name, value);
            }
        }
        return selected;
    }
}
