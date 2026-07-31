package dev.forgeci.controlplane.support;

import org.testcontainers.containers.MinIOContainer;

/**
 * One MinIO container shared by every test that needs an S3-compatible store, started once per JVM.
 */
public final class MinioTestContainer {

    public static final MinIOContainer INSTANCE =
            new MinIOContainer("minio/minio:latest")
                    .withUserName("forgeci")
                    .withPassword("forgeci-artifact-store");

    static {
        INSTANCE.start();
    }

    private MinioTestContainer() {}
}
