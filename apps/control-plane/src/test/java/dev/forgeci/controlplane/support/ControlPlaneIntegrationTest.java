package dev.forgeci.controlplane.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base for tests that need a full Spring context wired to the shared MySQL Testcontainer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
public abstract class ControlPlaneIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MySqlTestContainer.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", MySqlTestContainer.INSTANCE::getUsername);
        registry.add("spring.datasource.password", MySqlTestContainer.INSTANCE::getPassword);

        registry.add("forge.artifacts.s3.endpoint-override", MinioTestContainer.INSTANCE::getS3URL);
        registry.add("forge.artifacts.s3.access-key", MinioTestContainer.INSTANCE::getUserName);
        registry.add("forge.artifacts.s3.secret-key", MinioTestContainer.INSTANCE::getPassword);
        registry.add("forge.artifacts.s3.bucket", () -> "forgeci-artifacts-test");

        registry.add("spring.kafka.bootstrap-servers", KafkaTestContainer.INSTANCE::getBootstrapServers);

        registry.add("spring.data.redis.host", RedisTestContainer::host);
        registry.add("spring.data.redis.port", RedisTestContainer::port);

        // tightened so failure-recovery tests observe lease expiry, retry promotion, and Redis
        // reconciliation in seconds instead of the production defaults' minutes — other tests only
        // poll with generously bounded loops, so this doesn't affect them.
        registry.add("forge.scheduler.lease-grace-seconds", () -> "2");
        registry.add("forge.scheduler.lease-sweep-interval-ms", () -> "500");
        registry.add("forge.scheduler.retry-sweep-interval-ms", () -> "500");
        registry.add("forge.worker.heartbeat-interval-ms", () -> "1000");
        registry.add("forge.redis.reconcile-interval-ms", () -> "2000");
    }
}
