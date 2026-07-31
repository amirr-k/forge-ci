package dev.forgeci.cli;

import dev.forgeci.cache.HttpRemoteArtifactClient;
import dev.forgeci.cache.RemoteArtifactClient;
import java.net.URI;

/**
 * Local/remote precedence policy for this phase: remote mode is opt-in, purely via the
 * {@code FORGE_CONTROL_PLANE_URL} environment variable. Unset (the default), {@code forge
 * plan}/{@code forge run} are exactly phase 1/2 behavior — no network call, no infrastructure
 * required. Set, every cache lookup still checks local first and only falls back to the remote
 * store on a local miss; every fresh store still writes local first and then best-effort mirrors
 * to remote. Nothing about local mode's zero-infrastructure guarantee changes either way.
 */
final class RemoteCacheConfig {

    private RemoteCacheConfig() {}

    static RemoteArtifactClient fromEnvironment() {
        String url = System.getenv("FORGE_CONTROL_PLANE_URL");
        if (url == null || url.isBlank()) {
            return null;
        }
        return new HttpRemoteArtifactClient(URI.create(url.endsWith("/") ? url : url + "/"));
    }
}
