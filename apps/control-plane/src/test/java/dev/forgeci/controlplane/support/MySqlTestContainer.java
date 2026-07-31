package dev.forgeci.controlplane.support;

import org.testcontainers.containers.MySQLContainer;

/** One MySQL container shared by every test that needs it, started once per JVM. */
public final class MySqlTestContainer {

    public static final MySQLContainer<?> INSTANCE =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("forgeci")
                    .withUsername("forgeci")
                    .withPassword("forgeci");

    static {
        INSTANCE.start();
    }

    private MySqlTestContainer() {}
}
