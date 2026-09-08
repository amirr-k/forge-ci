package dev.cnba.cli;

import dev.cnba.cache.HttpRemoteArtifactClient;
import dev.cnba.cache.RemoteArtifactClient;
import java.net.URI;

/**
 * Local/remote precedence policy for this phase: remote mode is opt-in, purely via the {@code
 * CNBA_CONTROL_PLANE_URL} environment variable. Unset (the default), {@code cnba plan}/{@code
 * cnba run} are pure local mode — no network call, no infrastructure required. Set, every cache
 * lookup still checks local first and only falls back to the remote store on a local miss; every
 * fresh store still writes local first and then best-effort mirrors to remote. Nothing about local
 * mode's zero-infrastructure guarantee changes either way.
 */
final class RemoteCacheConfig {

    private RemoteCacheConfig() {}

    static RemoteArtifactClient fromEnvironment() {
        String url = System.getenv("CNBA_CONTROL_PLANE_URL");
        if (url == null || url.isBlank()) {
            return null;
        }
        return new HttpRemoteArtifactClient(URI.create(url.endsWith("/") ? url : url + "/"));
    }
}
