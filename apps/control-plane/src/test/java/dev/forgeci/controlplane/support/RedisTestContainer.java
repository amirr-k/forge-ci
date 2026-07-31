package dev.forgeci.controlplane.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** One Redis container shared by every test that needs it, started once per JVM. */
public final class RedisTestContainer {

    private static final int REDIS_PORT = 6379;

    public static final GenericContainer<?> INSTANCE =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    static {
        INSTANCE.start();
    }

    private RedisTestContainer() {}

    public static String host() {
        return INSTANCE.getHost();
    }

    public static Integer port() {
        return INSTANCE.getMappedPort(REDIS_PORT);
    }
}
