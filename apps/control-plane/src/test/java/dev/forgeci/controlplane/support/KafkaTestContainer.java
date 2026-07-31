package dev.forgeci.controlplane.support;

import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Kafka broker (KRaft, no ZooKeeper) shared by every test that needs it, started once per JVM.
 */
public final class KafkaTestContainer {

    public static final KafkaContainer INSTANCE =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    static {
        INSTANCE.start();
    }

    private KafkaTestContainer() {}
}
